package com.ultraviolette.fotaservice.utils;

import android.util.Log;

import org.json.JSONObject;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class ConfigManager {
    private static final String TAG = "FotaConfigManager";
    //private static final String CONFIG_PATH = "/data/system/fota_config.json";
    //private static final String CONFIG_DIR = "/data/vendor/udp_socket/";
    private static final String CONFIG_DIR = "/data/vendor/uv_fota/";
    private static final String CONFIG_PATH = CONFIG_DIR + "fota_config.json";

    private JSONObject configJson;

    public ConfigManager() {
        loadConfig();
    }

    private void loadConfig() {
        ensureConfigDirectory();
        File file = new File(CONFIG_PATH);
        if (!file.exists()) {
            Log.e(TAG, "Config file not found. Creating default config.");
            createDefaultConfig();
            saveConfig();
            return;
        }
        {
            Log.e(TAG, "Config File exists");
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            configJson = new JSONObject(sb.toString());
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to load config. Using defaults.", e);
            createDefaultConfig();
        }
    }

    private void ensureConfigDirectory() {
        File dir = new File(CONFIG_DIR);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            Log.i(TAG, "Created config directory: " + created);
            dir.setReadable(true, false);
            dir.setWritable(true, false);
            dir.setExecutable(true, false);
        }
        {
            Log.e(TAG, "inside ensureConfigDirectory.Directory exists");
        }
    }

    private void createDefaultConfig() {
        configJson = new JSONObject();
        try {
            //configJson.put("download_dir", "/data/vendor/udp_socket/");
            configJson.put("download_dir", "/data/vendor/uv_fota/");
            configJson.put("current_version", "v1.0.0");
            configJson.put("last_update_status", "idle");
            configJson.put("last_payload_url", "");
            configJson.put("retry_count", 3);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating default config", e);
        }
    }

    public synchronized void saveConfig() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(CONFIG_PATH))) {
            writer.write(configJson.toString(4)); // formatted JSON
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to save config", e);
        }
    }

    // ===== Getter / Setter methods =====

    public synchronized String getDownloadDir() {
        //return configJson.optString("download_dir", "/data/local/tmp/fota/");
        return configJson.optString("download_dir", "/data/vendor/udp_socket/");
    }

    public synchronized void setDownloadDir(String dir) {
        try {
            configJson.put("download_dir", dir);
            saveConfig();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to set download dir", e);
        }
    }

    public synchronized void setCurrentVersion(String version) {
        try {
            configJson.put("current_version", version);
            saveConfig();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to set current version", e);
        }
    }

    public synchronized String getCurrentVersion() {
        return configJson.optString("current_version", "v1.0.0");
    }

    public synchronized void setLastUpdateStatus(String status) {
        try {
            configJson.put("last_update_status", status);
            saveConfig();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to set update status", e);
        }
    }

    public synchronized String getLastUpdateStatus() {
        return configJson.optString("last_update_status", "idle");
    }
}
