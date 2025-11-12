package com.ultraviolette.fotaservice;

import static com.ultraviolette.fotaservice.utils.PackageFiles.COMPATIBILITY_ZIP_FILE_NAME;
import static com.ultraviolette.fotaservice.utils.PackageFiles.OTA_PACKAGE_DIR;
import static com.ultraviolette.fotaservice.utils.PackageFiles.PAYLOAD_BINARY_FILE_NAME;
import static com.ultraviolette.fotaservice.utils.PackageFiles.PAYLOAD_PROPERTIES_FILE_NAME;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.RecoverySystem;
import android.os.UpdateEngine;
import android.util.Log;

import com.google.common.collect.ImmutableSet;
import com.ultraviolette.fotaservice.cluster.UpdateConfig;
import com.ultraviolette.fotaservice.utils.FileDownloader;
import com.ultraviolette.fotaservice.utils.PackageFiles;
import com.ultraviolette.fotaservice.utils.PayloadSpecs;
import com.ultraviolette.fotaservice.utils.UpdateConfigs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Optional;

public class PrepareUpdate {
    private static final String TAG = "PrepareUpdate";
    public static final int RESULT_CODE_SUCCESS = 0;
    public static final int RESULT_CODE_ERROR = 1;

    private static final ImmutableSet<String> PRE_STREAMING_FILES_SET =
            ImmutableSet.of(
                    PackageFiles.CARE_MAP_FILE_NAME,
                    PackageFiles.COMPATIBILITY_ZIP_FILE_NAME,
                    PackageFiles.METADATA_FILE_NAME,
                    PackageFiles.PAYLOAD_PROPERTIES_FILE_NAME
            );

    private final PayloadSpecs mPayloadSpecs = new PayloadSpecs();
    private final UpdateEngine mUpdateEngine = new UpdateEngine();
    public PayloadSpec execute(UpdateConfig config)
            throws IOException, PreparationFailedException {

        Log.e(TAG, "PrepareUpdate execute.");
        if (config.getAbConfig().getVerifyPayloadMetadata()) {
            Log.i(TAG, "Verifying payload metadata with UpdateEngine.");
            if (!verifyPayloadMetadata(config)) {
                throw new PreparationFailedException("Payload metadata is not compatible");
            }
        }
        TODO :
        /*if (config.getInstallType() == UpdateConfig.AB_INSTALL_TYPE_NON_STREAMING) {
            Log.e(TAG, "PrepareUpdate Install type AB_INSTALL_TYPE_NON_STREAMING.");
            return mPayloadSpecs.forNonStreaming(config.getUpdatePackageFile());
        }*/

        downloadPreStreamingFiles(config, OTA_PACKAGE_DIR);

        Optional<UpdateConfig.PackageFile> payloadBinary =
                UpdateConfigs.getPropertyFile(PAYLOAD_BINARY_FILE_NAME, config);

        if (!payloadBinary.isPresent()) {
            throw new PreparationFailedException(
                    "Failed to find " + PAYLOAD_BINARY_FILE_NAME + " in config");
        }

        if (!UpdateConfigs.getPropertyFile(PAYLOAD_PROPERTIES_FILE_NAME, config).isPresent()
                || !Paths.get(OTA_PACKAGE_DIR, PAYLOAD_PROPERTIES_FILE_NAME).toFile().exists()) {
            throw new IOException(PAYLOAD_PROPERTIES_FILE_NAME + " not found");
        }

        //File compatibilityFile = Paths.get(OTA_PACKAGE_DIR, COMPATIBILITY_ZIP_FILE_NAME).toFile();
        //TODO : VerifyPackageCompatability
        /*if (compatibilityFile.isFile()) {
            Log.i(TAG, "Verifying OTA package for compatibility with the device");
            if (!verifyPackageCompatibility(compatibilityFile)) {
                throw new PreparationFailedException(
                        "OTA package is not compatible with this device");
            }
        }*/

        return mPayloadSpecs.forStreaming(config.getUrl(),
                payloadBinary.get().getOffset(),
                payloadBinary.get().getSize(),
                Paths.get(OTA_PACKAGE_DIR, PAYLOAD_PROPERTIES_FILE_NAME).toFile());
    }

    /**
     * Downloads only payload_metadata.bin and verifies with
     * {@link UpdateEngine#verifyPayloadMetadata}.
     * Returns {@code true} if the payload is verified or the result is unknown because of
     * exception from UpdateEngine.
     * By downloading only small portion of the package, it allows to verify if UpdateEngine
     * will install the update.
     */
    private boolean verifyPayloadMetadata(UpdateConfig config) {
        Optional<UpdateConfig.PackageFile> metadataPackageFile =
                Arrays.stream(config.getAbConfig().getPropertyFiles())
                        .filter(p -> p.getFilename().equals(
                                PackageFiles.PAYLOAD_METADATA_FILE_NAME))
                        .findFirst();
        if (!metadataPackageFile.isPresent()) {
            Log.w(TAG, String.format("ab_config.property_files doesn't contain %s",
                    PackageFiles.PAYLOAD_METADATA_FILE_NAME));
            return true;
        }
        Path metadataPath = Paths.get(OTA_PACKAGE_DIR, PackageFiles.PAYLOAD_METADATA_FILE_NAME);
        try {
            Files.deleteIfExists(metadataPath);
            FileDownloader d = new FileDownloader(
                    config.getUrl(),
                    metadataPackageFile.get().getOffset(),
                    metadataPackageFile.get().getSize(),
                    metadataPath.toFile());
            d.download();
        } catch (IOException e) {
            Log.w(TAG, String.format("Downloading %s from %s failed",
                    PackageFiles.PAYLOAD_METADATA_FILE_NAME,
                    config.getUrl()), e);
            return true;
        }
        //TODO : Commented now just to avoid the dependency with UpdateEngine. Need it after.
        /*try {
            return mUpdateEngine.verifyPayloadMetadata(metadataPath.toAbsolutePath().toString());
        } catch (Exception e) {
            Log.w(TAG, "UpdateEngine#verifyPayloadMetadata failed", e);
            return true;
        }*/
        return true; //TODO : dummy value
    }

    /**
     * Downloads files defined in {@link UpdateConfig#getAbConfig()}
     * and exists in {@code PRE_STREAMING_FILES_SET}, and put them
     * in directory {@code dir}.
     *
     * @throws IOException when can't download a file
     */
    private void downloadPreStreamingFiles(UpdateConfig config, String dir)
            throws IOException {
        Log.d(TAG, "Deleting existing files from " + dir);
        //TODO : Right now deleting the file if it is existing in the directory.
        for (String file : PRE_STREAMING_FILES_SET) {
            Files.deleteIfExists(Paths.get(OTA_PACKAGE_DIR, file));
        }
        Log.d(TAG, "Downloading files to " + dir);
        for (UpdateConfig.PackageFile file : config.getAbConfig().getPropertyFiles()) {
            if (PRE_STREAMING_FILES_SET.contains(file.getFilename())) {
                Log.d(TAG, "Downloading file " + file.getFilename());
                FileDownloader downloader = new FileDownloader(
                        config.getUrl(),
                        file.getOffset(),
                        file.getSize(),
                        Paths.get(dir, file.getFilename()).toFile());
                downloader.download();
            }
        }
    }

    //TODO : Need to check whether verifyPackageCompatibility is needed or not.
    /*/**
     * @param file physical location of {@link PackageFiles#COMPATIBILITY_ZIP_FILE_NAME}
     * @return true if OTA package is compatible with this device
     */
    /*private boolean verifyPackageCompatibility(File file) {
        try {
            return RecoverySystem.verifyPackageCompatibility(file);
        } catch (IOException e) {
            Log.e(TAG, "Failed to verify package compatibility", e);
            return false;
        }
    }*/
    //Latest API in RecoverySystem
    /*private void verifyPackageCompatibility(File file) {
        try {
             RecoverySystem.verifyPackage(new File("/data/vendor/uv_fota/update.zip"),
                    null,
                    null
            );
        } catch (IOException e) {
            Log.e(TAG, "Failed to verify package compatibility", e);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException(e);
        }
    }*/


    private static class PreparationFailedException extends Exception {
        PreparationFailedException(String message) {
            super(message);
        }
    }

    public interface Callback {
        void onSuccess(PayloadSpec spec);
        void onError(String message);
    }

    public void prepare(UpdateConfig config, Callback callback) {
        new Thread(() -> {
            try {
                PayloadSpec spec = execute(config);
                new Handler(Looper.getMainLooper()).post(() -> callback.onSuccess(spec));
            } catch (IOException e) {
                postError(callback, "Download failed: " + e.getMessage());
            } catch (PreparationFailedException e) {
                postError(callback, "Incompatible: " + e.getMessage());
            } catch (Exception e) {
                postError(callback, "Error: " + e.getMessage());
            }
        }).start();
    }

    private void postError(Callback callback, String msg) {
        new Handler(Looper.getMainLooper()).post(() -> callback.onError(msg));
    }
}

