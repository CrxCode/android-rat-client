package com.safe.myapp;

import android.content.Context;
import android.location.Location;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.util.JsonReader;
import android.util.Log;

import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;

import cz.msebera.android.httpclient.Header;

public class SafeCommunications {

    private Context context;
    private DataOutputStream out;
    private SafeLogger logger;
    private String simpleID;
    public boolean sending;

    public SafeCommunications(Context context, SafeLogger logger, DataOutputStream out, String simpleID) {
        this.context = context;
        this.logger = logger;
        this.out = out;
        this.simpleID = simpleID;
        sending = false;
    }

    private String getWifiConnection(){
        WifiManager wifiMgr = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo wifiInfo = wifiMgr.getConnectionInfo();
        String ssid = wifiInfo.getSSID();
        if (ssid.equals("<unknown ssid>")) {
            ssid += " probably on mobile data";
        } else {
            ssid += " state " + wifiInfo.getSupplicantState();
        }
        return ssid;
    }

    public void handShake() {
        String ssid = getWifiConnection();
        // the server expects a handshake with simple strings delimited by \r\n
        StringBuilder sb = new StringBuilder();
        sb.append(simpleID);
        sb.append("\r\n");
        sb.append(SafeService.VERSION);
        sb.append("\r\n");
        sb.append(context.getApplicationContext().getPackageName());
        sb.append("\r\n");
        sb.append(ssid);
        sb.append("\r\n");
        sb.append(SafeService.isbAudioStarted());
        sb.append("\r\n");
        sb.append(SafeService.isbLocationStarted());
        sb.append("\r\n");
        String handshake = sb.toString();

        try {
            synchronized (out) {
                logger.write("Handshaking");
                // notify the server we are going to send a handshake
                out.writeInt(SafeService.HANDSHAKE);
                // notify of size
                out.writeInt(handshake.getBytes().length);
                // send handshake data
                out.write(handshake.getBytes(), 0, handshake.getBytes().length);
                out.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
            logger.write(Log.getStackTraceString(e));
        }
    }

    public void say(String message) {
        if (out != null) {
            sending = true;
            synchronized (out) { // wait for any other uploads or messages
                try {
                    // notify server we are sending a message
                    out.writeInt(SafeService.MESSAGE);
                    // notify server of the size of the message
                    out.writeInt(message.getBytes().length);

                    // convert message to bytes and send
                    byte[] messageBytes = message.getBytes();
                    out.write(messageBytes, 0, messageBytes.length);
                    out.flush();
                    logger.write("Sent message " + message);
                } catch (IOException e) {
                    logger.write("IOException, could not send message: " + message);
                }
            }
            sending = false;
        }
    }

    public void download(String... fileLocs) {
        // list of files to be uploaded to server
        ArrayList<File> files = new ArrayList<>();
        try {
            for (String fileLoc : fileLocs) {
                // create a file object from each string
                File file = new File(Environment.getExternalStorageDirectory(), fileLoc);
                // check if the file exists
                if (file.exists()) {
                    // if it exists and is a file add the file to our list
                    if (file.isFile()) {
                        files.add(file);
                        say("sending " + file + " " + file.length());
                        // if it is a directory add all files in it to our list
                    } else if (file.isDirectory()) {
                        say(file + " is a directory, sending all files within it, skipping folders");
                        for (File f : file.listFiles()) {
                            if (f.isFile()) {
                                files.add(f);
                                say("sending " + f + " " + f.length());
                            }
                        }
                    } else {
                        say(fileLoc + " is not a valid file");
                    }
                } else {
                    say(fileLoc + " does not exist");
                }
            }
            // upload files to server
            upload(files.toArray(new File[files.size()]));
        } catch (NullPointerException e) {
            say("w00ps");
        }
    }

    public void upload(File... files) {
        for (final File file : files) {
            say(file.getName() + " Checking");
            RequestParams fileCheck = new RequestParams();
            fileCheck.put("fileName", file.getName());
            fileCheck.put("clientId", simpleID);
            fileCheck.put("fileSize", file.length());
            SafeRestClient.syncGet("/acceptFile/" + simpleID + "/", fileCheck, new AsyncHttpResponseHandler() {

                @Override
                public void onSuccess(int statusCode, cz.msebera.android.httpclient.Header[] headers, byte[] responseBody) {

                    RequestParams params = new RequestParams();
                    say(file.getName() + " Does not exist. Uploading...");
                    try {
                        params.put("tehAwesomeFile", file);
                        params.put("clientId", simpleID);
                    } catch(FileNotFoundException e) {
                        say("Could not find file: " + file.getName());
                    }

                    SafeRestClient.syncPost("/postFile/" + simpleID + "/", params, new AsyncHttpResponseHandler() {

                        @Override
                        public void onSuccess(int statusCode, cz.msebera.android.httpclient.Header[] headers, byte[] responseBody) {
                            try {
                                say(new String(responseBody, "UTF-8") + " Received");
                            } catch (UnsupportedEncodingException e) {
                                e.printStackTrace();
                            }
                        }
                        @Override
                        public void onFailure(int statusCode, cz.msebera.android.httpclient.Header[] headers, byte[] responseBody, Throwable error) {
                            try {
                                if(responseBody != null) {
                                    say(new String(responseBody, "UTF-8"));
                                }
                            } catch (UnsupportedEncodingException e) {
                                e.printStackTrace();
                            }
                        }
                    });
                }

                @Override
                public void onFailure(int statusCode, cz.msebera.android.httpclient.Header[] headers, byte[] responseBody, Throwable error) {
                    // The server did not accept the file
                    try {
                        say(new String(responseBody, "UTF-8"));
                    } catch (UnsupportedEncodingException e) {
                        e.printStackTrace();
                    }
                }
            });
        }
        say("Upload completed");
    }

    public void setOut(DataOutputStream out) {
        this.out = out;
    }
}
