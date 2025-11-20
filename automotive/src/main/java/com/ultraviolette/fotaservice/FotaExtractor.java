package com.ultraviolette.fotaservice;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import android.util.Log;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

public class FotaExtractor {

    private static final String TAG = "FotaService.FotaExtractor";

    // Base path for extracted data
    private final String baseDir;

    public FotaExtractor(String baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * Extract a .tar file that may contain .zip modules.
     * <p>
     * • If {@code path} is a directory → first .tar file inside it is used.<br>
     * • If {@code path} points directly to a .tar file → it is used.<br>
     * • Only entries that end with ".zip" are extracted.
     *
     * @param path  directory that contains the .tar or the .tar file itself
     */
    public boolean extractTar(String path) {
        Log.e(TAG, "FotaExtractor extractTar path: " + path);

        File input = new File(path);

        /* ---------- 1. Resolve the .tar file ---------- */
        if (input.isDirectory()) {
            File[] tars = input.listFiles((dir, name) ->
                    name.toLowerCase().endsWith(".tar"));
            if (tars == null || tars.length == 0) {
                Log.e(TAG, "No .tar file found in directory: " + path);
                return false;
            }
            input = tars[0];                     // take the first .tar
            Log.i(TAG, "Found TAR file: " + input.getAbsolutePath());
        }

        if (!input.isFile() || !input.canRead()) {
            Log.e(TAG, "TAR file not readable or not a file: " + input.getAbsolutePath());
            return false;
        }

        /* ---------- 2. Open TAR (uses Apache Commons Compress) ---------- */
        try (TarArchiveInputStream tis = new TarArchiveInputStream(
                new BufferedInputStream(new FileInputStream(input)))) {

            TarArchiveEntry entry;
            while ((entry = tis.getNextTarEntry()) != null) {
                String entryName = entry.getName();
                Log.i(TAG, "Found entry: " + entryName);

                // ----- only process .zip modules -----
                if (entryName.toLowerCase().endsWith(".zip")) {
                    Log.i(TAG, "Zip file found: " + entryName);
                    // strip any leading path → keep only the file name
                    String zipFileName = new File(entryName).getName();
                    //String moduleName = getModuleName(zipFileName);
                    String moduleName = "AndroidFiles";

                    File moduleDir = new File(baseDir, moduleName);
                    if (!moduleDir.exists() && !moduleDir.mkdirs()) {
                        Log.e(TAG, "Failed to create module dir: " + moduleDir.getAbsolutePath());
                        continue;
                    }

                    File zipFile = new File(moduleDir, zipFileName);

                    // write the .zip from the tar stream
                    try (FileOutputStream fos = new FileOutputStream(zipFile)) {
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = tis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                    Log.i(TAG, "Saved ZIP: " + zipFile.getAbsolutePath());

                    // now unpack the inner .zip
                    extractZip(zipFile, moduleDir);
                }
                else {
                    Log.i(TAG, "Non-Zip file found: " + entryName);
                    String zipFileName = new File(entryName).getName();
                    //String moduleName = getModuleName(zipFileName);
                    String moduleName = "extractedFiles";

                    File moduleDir = new File(baseDir, moduleName);
                    if (!moduleDir.exists() && !moduleDir.mkdirs()) {
                        Log.e(TAG, "Failed to create module dir: " + moduleDir.getAbsolutePath());
                        continue;
                    }

                    File zipFile = new File(moduleDir, zipFileName);

                    // write the .zip from the tar stream
                    try (FileOutputStream fos = new FileOutputStream(zipFile)) {
                        byte[] buffer = new byte[4096];
                        int len;
                        while ((len = tis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                    Log.i(TAG, "Saved ZIP: " + zipFile.getAbsolutePath());
                    extractZip(zipFile, moduleDir);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract TAR", e);
        }
        return true;
    }

    private void extractZip(File zipFile, File destDir) {
        Log.e(TAG, "FotaExtractor extractZip zipfile : " + zipFile + " dest dir :" + destDir);

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File newFile = new File(destDir, entry.getName());

                if (entry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    new File(newFile.getParent()).mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        zis.transferTo(fos);
                    }
                    Log.i(TAG, "Extracted: " + newFile.getAbsolutePath());
                }
                zis.closeEntry();
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract ZIP: " + zipFile.getName(), e);
        }
    }

    private String getModuleName(String fileName) {
        if (fileName.toLowerCase().contains("mcu")) return "mcu";
        if (fileName.toLowerCase().contains("bms")) return "bms";
        if (fileName.toLowerCase().contains("obc")) return "obc";
        if (fileName.toLowerCase().contains("android")) return "android";
        return "misc";
    }
}

