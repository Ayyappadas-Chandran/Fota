package com.ultraviolette.fotaservice.utils;

import android.content.Context;
import android.util.Log;

import com.ultraviolette.fotaservice.cluster.UpdateConfig;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Utility class for working with json update configurations.
 */
public final class UpdateConfigs {

    public static final String UPDATE_CONFIGS_ROOT = "configs/";
    public String TAG = "FotaService.UpdateConfigs";

    /**
     * @param configs update configs
     * @return list of names
     */
    public static String[] configsToNames(List<UpdateConfig> configs) {
        return configs.stream().map(UpdateConfig::getName).toArray(String[]::new);
    }

    /**
     * @param context app context
     * @return configs root directory
     */
    //TODO : We can given the Config path here from here only it will take the configs/fota-config.json file.
    public static String getConfigsRoot(Context context) {
        return Paths
                .get(context.getFilesDir().toString(), UPDATE_CONFIGS_ROOT)
                .toString();
    }
    //TODO : Write now written for one json file. If multiple files are there we need to handle it.
    /**
     * @param context application context
     * @return list of configs from directory {@link UpdateConfigs#getConfigsRoot}
     */
    /*public static List<UpdateConfig> getUpdateConfigs(Context context) {
        File root = new File(getConfigsRoot(context));
        ArrayList<UpdateConfig> configs = new ArrayList<>();

        if (!root.exists()) {
            return configs;
        }
        for (final File f : root.listFiles()) {
            if (!f.isDirectory() && f.getName().endsWith(".json")) {
                try {
                    String json = new String(Files.readAllBytes(f.toPath()),
                            StandardCharsets.UTF_8);
                    configs.add(UpdateConfig.fromJson(json));
                } catch (Exception e) {
                    Log.e("UpdateConfigs", "Can't read/parse config file " + f.getName(), e);
                    throw new RuntimeException(
                            "Can't read/parse config file " + f.getName(), e);
                }
            }
        }
        return configs;
    }*/

    public static UpdateConfig getUpdateConfig(String path) {
        //data/vendor/uv_fota/fota/AndroidFiles
        File configFile = new File(path, "fota_update.json");
        ensureConfigDirectory();
        if (!configFile.exists()) {
            Log.w("UpdateConfigs", "Config file not found: " + configFile.getAbsolutePath());
            return null;
        }

        try {
            //String json = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);
            String json = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
            UpdateConfig config = UpdateConfig.fromJson(json);
            Log.i("UpdateConfigs", "Loaded config: " + configFile.getName());
            return config;

        } catch (IOException e) {
            Log.e("UpdateConfigs", "Failed to read config file: " + configFile.getName(), e);
            return null;
        } catch (Exception e) {
            Log.e("UpdateConfigs", "Failed to parse JSON in: " + configFile.getName(), e);
            return null;
        }
    }

    private static void ensureConfigDirectory() {
        File dir = new File("/data/local/tmp/fota/");
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            Log.i("FotaService.UpdateConfigs", "Created config directory: " + created);
            dir.setReadable(true, false);
            dir.setWritable(true, false);
            dir.setExecutable(true, false);
        }
        else {
            Log.e("FotaService.UpdateConfigs", "inside ensureConfigDirectory.Directory exists");
        }
    }

    /**
     * @param filename searches by given filename
     * @param config searches in {@link UpdateConfig#getAbConfig()}
     * @return offset and size of {@code filename} in the package zip file
     *         stored as {@link UpdateConfig.PackageFile}.
     */
    public static Optional<UpdateConfig.PackageFile> getPropertyFile(
            final String filename,
            UpdateConfig config) {
        return Arrays
                .stream(config.getAbConfig().getPropertyFiles())
                .filter(file -> filename.equals(file.getFilename()))
                .findFirst();
    }

    private UpdateConfigs() {}
}
