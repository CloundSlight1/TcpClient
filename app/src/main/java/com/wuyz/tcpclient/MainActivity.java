package com.wuyz.tcpclient;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity implements View.OnClickListener {

    private static final String TAG = "MainActivity";

    private static final char[] HEX_CHAR = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};
    private static final String DEFAULT_IP = "192.168.0.1:9999";

    private AutoCompleteTextView addressText;
    private TextView outputText;
    private Socket socket;
    private String name;
    private String sha1;
    private long length;
    private File downloadFile;
    private ProgressDialog progressDialog;
    private DownloadTask downloadTask;
    private SharedPreferences preferences;
    private Set<String> addresses;
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(com.wuyz.tcpclient.R.layout.activity_main);

        preferences = PreferenceManager.getDefaultSharedPreferences(this);

        addressText = (AutoCompleteTextView) findViewById(R.id.address);
        outputText = (TextView) findViewById(com.wuyz.tcpclient.R.id.output);
        findViewById(com.wuyz.tcpclient.R.id.get_file_info).setOnClickListener(this);
        findViewById(com.wuyz.tcpclient.R.id.download).setOnClickListener(this);

        addresses = preferences.getStringSet("addresses", new HashSet<String>(10));
        if (addresses.isEmpty())
            addresses.add(DEFAULT_IP);
        addressText.setText(preferences.getString("lastIp", DEFAULT_IP));
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line);
//        List<String> list = new ArrayList<>(addresses.size());
//        for (String address : addresses) {
//            list.add(address);
//        }
        adapter.addAll(addresses);
        addressText.setAdapter(adapter);
    }

    @Override
    public void onClick(View v) {
        final int id = v.getId();
        if (id == com.wuyz.tcpclient.R.id.get_file_info) {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo networkInfo = manager.getActiveNetworkInfo();
            if (networkInfo == null || !networkInfo.isConnected()) {
                Toast.makeText(this, "Please connect network", Toast.LENGTH_SHORT).show();
                return;
            }
            final String address = addressText.getText().toString();
            if (address.isEmpty()) {
                Toast.makeText(this, "Please input address", Toast.LENGTH_SHORT).show();
                return;
            }
            final String[] arr = address.split(":");
            if (arr.length != 2) {
                Toast.makeText(this, "ip and port error", Toast.LENGTH_SHORT).show();
                return;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    getFileInfo(arr[0].trim(), Integer.parseInt(arr[1].trim()));
                }
            }).start();
            return;
        }

        if (id == com.wuyz.tcpclient.R.id.download) {
            if (name == null || name.isEmpty() || length < 1) {
                Toast.makeText(this, "Please get file info first", Toast.LENGTH_SHORT).show();
                return;
            }
            ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo networkInfo = manager.getActiveNetworkInfo();
            if (networkInfo == null || !networkInfo.isConnected()) {
                Toast.makeText(this, "Please connect network", Toast.LENGTH_SHORT).show();
                return;
            }
            final String address = addressText.getText().toString();
            if (address.isEmpty()) {
                Toast.makeText(this, "Please input address", Toast.LENGTH_SHORT).show();
                return;
            }
            final String[] arr = address.split(":");
            if (arr.length != 2) {
                Toast.makeText(this, "ip and port error", Toast.LENGTH_SHORT).show();
                return;
            }
            downloadTask = new DownloadTask();
            downloadTask.execute(arr[0].trim(), arr[1].trim());
            return;
        }
    }

    @Override
    protected void onDestroy() {
        if (downloadTask != null) {
            downloadTask.cancel(true);
            downloadTask = null;
        }
        super.onDestroy();
    }

    private boolean readFileInfo(InputStream inputStream) throws IOException{
        name = null;
        sha1 = null;
        length = 0;
        byte[] buffer = new byte[1024];
        int n, m;
        Log2.d(TAG, "readFileInfo begin");
        output("readFileInfo begin\n");
        n = inputStream.read();
        if (n > 0) {
            m = inputStream.read(buffer, 0, n);
            if (m == n) {
                name = new String(buffer, 0, n);
                Log2.d(TAG, "name: %s", name);
                output(String.format("name: %s\n", name));
            } else
                return false;
        } else
            return false;

        n = inputStream.read();
        if (n > 0) {
            m = inputStream.read(buffer, 0, n);
            if (m == n) {
                length = bytes2Long(buffer);
                Log2.d(TAG, "length: %s(0x%X)", getLengthDesc(length), length);
                output(String.format("length: %s(0x%X)\n", getLengthDesc(length), length));
            } else
                return false;
        } else
            return false;

        n = inputStream.read();
        if (n > 0) {
            m = inputStream.read(buffer, 0, n);
            if (m == n) {
                sha1 = new String(buffer, 0, n);
                Log2.d(TAG, "sha1: %s", sha1);
                output(String.format("sha1: %s\n", sha1));
            } else
                return false;
        } else
            return false;
        Log2.d(TAG, "readFileInfo end");
        output(String.format("readFileInfo end\n"));
        return true;
    }

    private void getFileInfo(String address, int port) {
        try {
            if (socket == null) {
                socket = new Socket();
                socket.connect(new InetSocketAddress(InetAddress.getByName(address), port), 5000);
            }
            OutputStream outputStream = socket.getOutputStream();
            outputStream.write(0);
            InputStream inputStream = socket.getInputStream();
            readFileInfo(inputStream);
            inputStream.close();
            outputStream.close();

            String key = address + ":" + port;
            preferences.edit().putString("lastIp", key).apply();
            boolean isNew = addresses.add(key);
            if (isNew) {
                preferences.edit().putStringSet("addresses", addresses).apply();
                adapter.add(key);
            }
        } catch (IOException e) {
            Log2.e(TAG, e);
            output(e.getMessage() + "\n");
        } finally {
            if (socket != null && socket.isClosed()) {
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            socket = null;
        }
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void output(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                outputText.append(msg);
            }
        });
    }

    static String getLengthDesc(float length) {
        if (length < 1024)
            return length + "B";

        length /= 1024;
        if (length < 1024)
            return String.format(Locale.getDefault(), "%.1fK", length);

        length /= 1024;
        if (length < 1024)
            return String.format(Locale.getDefault(), "%.1fM", length);

        length /= 1024;
        return String.format(Locale.getDefault(), "%.1fG", length);
    }

    public static String getSHA1(InputStream input) {
        byte[] data = new byte[1024];
        int n;
        try {
            MessageDigest mdInst = MessageDigest.getInstance("SHA1");
            while ((n = input.read(data)) > 0) {
                mdInst.update(data, 0, n);
            }
            byte[] buffer = mdInst.digest();
            char str[] = new char[buffer.length << 1];
            for (int i = 0; i < buffer.length; i++) {
                byte b = buffer[i];
                str[2 * i] = HEX_CHAR[b >>> 4 & 0xf];
                str[2 * i + 1] = HEX_CHAR[b & 0xf];
            }
            return new String(str);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static byte[] long2Bytes(long num) {
        byte[] byteNum = new byte[8];
        for (int i = 0; i < 8; ++i) {
            int offset = 64 - (i + 1) * 8;
            byteNum[i] = (byte) ((num >> offset) & 0xff);
        }
        return byteNum;
    }

    public static long bytes2Long(byte[] byteNum) {
        long num = 0;
        for (int i = 0; i < 8; ++i) {
            num <<= 8;
            num |= (byteNum[i] & 0xff);
        }
        return num;
    }

    class DownloadTask extends AsyncTask<String, Long, Void> {

        @Override
        protected void onPreExecute() {
            if (progressDialog != null) {
                progressDialog.dismiss();
            }
            progressDialog = new ProgressDialog(MainActivity.this);
            progressDialog.setTitle("Downloading ...");
            progressDialog.setIndeterminate(false);
            progressDialog.setCancelable(false);
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setMax(100);
            progressDialog.show();
        }

        @Override
        protected Void doInBackground(String... params) {
            try {
                if (socket == null) {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(InetAddress.getByName(params[0]), Integer.parseInt(params[1])), 5000);
                }
                OutputStream outputStream = socket.getOutputStream();
                outputStream.write(1);
                InputStream inputStream = socket.getInputStream();
                getExternalFilesDir(null).mkdirs();
                downloadFile = new File(getExternalFilesDir(null), name);
                try (FileOutputStream fileOutputStream = new FileOutputStream(downloadFile)) {
                    byte[] buffer = new byte[1024];
                    int n;
                    output("download begin\n");
                    long total = 0;
                    while ((n = inputStream.read(buffer)) != -1) {
//                        Log2.d(TAG, "total = %d", total);
                        fileOutputStream.write(buffer, 0, n);
                        total += n;
                        publishProgress(total);
//                        try {
//                            Thread.sleep(1);
//                        } catch (InterruptedException e) {
//                            e.printStackTrace();
//                        }
                    }
                }
                inputStream.close();
                outputStream.close();
                output("download end\n");
            } catch (IOException e) {
                Log2.e(TAG, e);
                output(e.getMessage() + "\n");
            } finally {
                if (socket != null && socket.isClosed()) {
                    try {
                        socket.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                socket = null;
            }
            return null;
        }

        @Override
        protected void onProgressUpdate(Long... values) {
            if (progressDialog != null) {
                progressDialog.setProgress((int) (values[0] * 100 / length));
            }
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            if (progressDialog != null) {
                progressDialog.dismiss();
                progressDialog = null;
            }
            if (downloadFile == null || !downloadFile.isFile()) {
                toast("Download failed: file is not created");
                return;
            }
            output(downloadFile.getAbsolutePath());
            try (FileInputStream fileInputStream = new FileInputStream(downloadFile)) {
                String currentSHA1 = getSHA1(fileInputStream);
                Log2.d(TAG, "currentSHA1 %s", currentSHA1);
                if (currentSHA1 == null || !currentSHA1.equals(sha1)) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                    builder.setMessage("sha1 is not correct, install it anyway?");
                    builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            tryInstallFile();
                        }
                    });
                    builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            if (downloadFile.exists() && downloadFile.isFile()) {
                                downloadFile.delete();
                                downloadFile = null;
                            }
                        }
                    });
                    builder.show();
                    return;
                }
                toast("Download succeed!");
                tryInstallFile();
            } catch (Exception e) {
                Log2.e(TAG, e);
                toast(e.getMessage());
            }
        }
    }

    private void tryInstallFile() {
        if (downloadFile.getName().toLowerCase().endsWith(".apk")) {
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setData(Uri.fromFile(downloadFile));
            intent.putExtra(Intent.EXTRA_ALLOW_REPLACE, true);
            intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            startActivity(intent);
        }
    }
}
