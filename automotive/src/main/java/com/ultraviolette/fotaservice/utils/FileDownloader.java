package com.ultraviolette.fotaservice.utils;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;


public final class FileDownloader {

    private String mUrl;
    private long mOffset;
    private long mSize;
    private File mDestination;

    public FileDownloader(String url, long offset, long size, File destination) {
        this.mUrl = url;
        this.mOffset = offset;
        this.mSize = size;
        this.mDestination = destination;
    }

    /**
     * Downloads the file with given offset and size.
     * @throws IOException when can't download the file
     */
    //TODO : Right now not needed offset and size. Later may need.
    /*public void download() throws IOException {
        Log.d("FileDownloader", "downloading " + mDestination.getName()
                + " from " + mUrl
                + " to " + mDestination.getAbsolutePath());

        URL url = new URL(mUrl);
        URLConnection connection = url.openConnection();
        connection.connect();

        // download the file
        try (InputStream input = connection.getInputStream()) {
            try (OutputStream output = new FileOutputStream(mDestination)) {
                long skipped = input.skip(mOffset);
                if (skipped != mOffset) {
                    throw new IOException("Can't download file "
                            + mUrl
                            + " with given offset "
                            + mOffset);
                }
                byte[] data = new byte[4096];
                long total = 0;
                while (total < mSize) {
                    int needToRead = (int) Math.min(4096, mSize - total);
                    int count = input.read(data, 0, needToRead);
                    if (count <= 0) {
                        break;
                    }
                    output.write(data, 0, count);
                    total += count;
                }
                if (total != mSize) {
                    throw new IOException("Can't download file "
                            + mUrl
                            + " with given size "
                            + mSize);
                }
            }
        }
    }*/

    public boolean download() throws IOException {
        boolean downloadStatus = false;
        Log.d("FileDownloader", "Downloading from " + mUrl + " to " + mDestination.getAbsolutePath());

        URL url = new URL(mUrl);
        URLConnection connection = url.openConnection();
        connection.connect();

        try (InputStream input = connection.getInputStream();
             OutputStream output = new FileOutputStream(mDestination)) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            long total = 0;

            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
                total += bytesRead;
            }

            Log.e("FileDownloader", "Download complete. Total bytes: " + total);
            downloadStatus = true;
        } catch (IOException e) {
            downloadStatus = true;
            Log.e("FileDownloader", "Download failed: " + e.getMessage(), e);
            throw e;
        }
        return downloadStatus;
    }


}
