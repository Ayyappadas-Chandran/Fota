package com.ultraviolette.fotaservice;

import android.app.DownloadManager;
import android.app.IOtaCompleteListener;
import android.app.IUdpService;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import com.ultraviolette.fotaservice.cluster.UpdateConfig;
import com.ultraviolette.fotaservice.cluster.UpdateManager;
import com.ultraviolette.fotaservice.cluster.UpdaterState;
import com.ultraviolette.fotaservice.utils.ConfigManager;
import com.ultraviolette.fotaservice.utils.FileDownloader;
import com.ultraviolette.fotaservice.utils.UpdateConfigs;
import com.ultraviolette.fotaservice.utils.UpdateEngineErrorCodes;
import com.ultraviolette.fotaservice.utils.UpdateEngineStatuses;


import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import android.os.UpdateEngine;

public class FotaService extends Service {

    private static final String TAG = "FotaService";
    private ConfigManager mConfigManager;
    //private UpdateEngine mUpdateEngine;
    private final PrepareUpdate mPrepareUpdate = new PrepareUpdate();
    private IUdpService mUdpService;
    private IFotaS3Events s3Service; // Binder to S3Service
    private final UpdateManager mUpdateManager =
            new UpdateManager(new UpdateEngine(), new Handler());
    private UpdateConfig mConfigs;
    private static final String UDP_SERVICE_NAME = "udp";
    private String DOWNLOAD_PATH = "data/vendor/uv_fota";

    // Keeps track ofsetLastUpdateStatus registered listeners from CloudObserverService
    private final RemoteCallbackList<IFotaCallback> listeners = new RemoteCallbackList<>();
    private final RemoteCallbackList<IFotaS3Callback> s3Listener = new RemoteCallbackList<>();

    // AIDL binder to expose to MQTTService
    private final IFotaMqttEvents.Stub binder = new IFotaMqttEvents.Stub() {

        @Override
        public void fotaUpdateAvailable(String payloadUrl) {
            Log.e(TAG, "Received OTA data from MQTTService: " + " / " + payloadUrl);
            //TODO : For testing this need to be called only after success message recieved from S3Service.
            String downloadDir = "data/local/tmp/cloud_download";
            // Internally notify all registered listeners
            try {
                Log.e(TAG, "S3Service fotaDownloadRequest ");
                s3Service.fotaDownloadRequest("Requestcall for fota download");
                Log.e(TAG, "S3Service fotaDownloadRequest called");

            } catch (RemoteException e) {
                Log.e(TAG, "S3Service RemoteException");
                throw new RuntimeException(e);
            }
            //downloadOtaUpdate(payloadUrl, downloadDir);

        }
    };

    private final ServiceConnection s3Connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            s3Service = IFotaS3Events.Stub.asInterface(service);
            Log.e(TAG, "Bound to S3Service");

            if (s3Service != null) {
                try {
                    s3Service.registerCallback(s3Callback);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            s3Service = null;
            Log.e(TAG, "S3Service disconnected");
        }
    };

    private final IFotaS3Callback s3Callback = new IFotaS3Callback.Stub() {

        @Override
        public void onOtaAvailable(String payloadUrl) throws RemoteException {
            Log.e(TAG, "Received OTA data from s3Callback: " + " / " + payloadUrl);

/*            mConfigManager.setLastUpdateStatus("downloading");
            mConfigManager.setCurrentVersion("1.0.0");
            mConfigManager.saveConfig();*/

            //If s3 is downloading then we need to look for path and file)

            //String downloadDir = "data/local/tmp/cloud_download/fota/";
            String downloadDir = "/data/vendor/uv_fota/fota";
            downloadOtaUpdate(payloadUrl, downloadDir);
        }
    };


    @Override
    public void onCreate() {
        super.onCreate();
        //mConfigManager = new ConfigManager();
        //mUpdateEngine = new UpdateEngine();
        Log.i(TAG, "FotaService created and config loaded");
        bindToUpdateEngine();
        bindToS3Service();
        updateEngineCallbackListners();
        connectToHelloService();
        vcuAckCallbackListners();

    }

    @Override
    public void onDestroy() {
        // Unregister all callbacks
        if (mUpdateManager != null) {
            mUpdateManager.setOnEngineStatusUpdateCallback(null);
            mUpdateManager.setOnProgressUpdateCallback(null);
            mUpdateManager.setOnEngineCompleteCallback(null);

            // Unbind before destroying
            mUpdateManager.unbind();
            unbindService(s3Connection);

        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.e(TAG, "FotaService bound");
        return binder;
    }


    private void bindToUpdateEngine() {
        /*boolean bound = mUpdateEngine.bind(new UpdateEngineCallback() {
            @Override
            public void onStatusUpdate(int status, float percent) {
                Log.i(TAG, "FOTA progress: status=" + status + ", progress=" + (percent * 100) + "%");
            }

            @Override
            public void onPayloadApplicationComplete(int errorCode) {
                Log.i(TAG, "FOTA completed with code=" + errorCode);
            }
        });

        Log.i(TAG, "UpdateEngine bound: " + bound);*/
        mUpdateManager.bind();
    }

    private void bindToS3Service() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(
                "com.example.database",
                "com.example.database.service.DatabaseBackgroundService"
        ));
        boolean bound = bindService(intent, s3Connection, BIND_AUTO_CREATE);
        Log.e(TAG, "Attempting to bind to S3Service, result: " + bound);
    }

    private void connectToHelloService() {
        Log.d(TAG, "Attempting to connect to IHelloService...");
        new Thread(() -> {
            int maxRetries = 10;
            for (int attempt = 0; attempt < maxRetries && mUdpService == null; attempt++) {
                try {
                    Class<?> smClass = Class.forName("android.os.ServiceManager");
                    Method getServiceMethod = smClass.getMethod("getService", String.class);
                    IBinder binder = (IBinder) getServiceMethod.invoke(null, UDP_SERVICE_NAME);
                    if (binder != null) {
                        mUdpService = IUdpService.Stub.asInterface(binder);
                        Log.i(TAG, "Connected to IHelloService on attempt " + (attempt + 1));
                        //updateNotification("Connected to Hello Service");
                        return;
                    }
                    Log.w(TAG, "IHelloService not found. Retrying... (" + (attempt + 1) + "/" + maxRetries + ")");
                    Thread.sleep(1000);
                } catch (Exception e) {
                    Log.e(TAG, "Exception while connecting to IHelloService", e);
                    break;
                }
            }
            if (mUdpService == null) {
                Log.e(TAG, "Failed to connect to IHelloService after " + maxRetries + " retries.");
                //updateNotification("Hello Service Failed");
            }
        }).start();
    }

    private final IOtaCompleteListener fotaUdpCallback = new IOtaCompleteListener.Stub() {

        @Override
        public void onOtaCompleted(int success)
        {
            Log.e(TAG, String.format("fotaUdpCallback onOtaCompleted " + success));
        }
    };
    public void updateEngineCallbackListners() {
        this.mUpdateManager.setOnStateChangeCallback(this::onUpdaterStateChange);
        this.mUpdateManager.setOnEngineStatusUpdateCallback(this::onEngineStatusUpdate);
        this.mUpdateManager.setOnEngineCompleteCallback(this::onEnginePayloadApplicationComplete);
        this.mUpdateManager.setOnProgressUpdateCallback(this::onProgressUpdate);
    }

    public void vcuAckCallbackListners()
    {

    }

    // Optional: send OTA update to all registered listeners
    public void notifyClientsDownloadComplete(/*String downloadedPath*/) {
        Log.e(TAG, String.format("notifyClientsDownloadComplete"));
        int n = listeners.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                listeners.getBroadcastItem(i).onStatusChanged(1);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        listeners.finishBroadcast();
    }

    public void downloadOtaUpdate(String payloadUrl, String destPath) {
        //TODO : Downloading the files need to happen here instead of downloading inside UpdateManager.
        //TODO : Because not just Android updates, vcu updates also need to download.
        //boolean downloadStatus;
        Log.i(TAG, "downloadOtaUpdate DestPath " + destPath);
        boolean downloadStatus = payloadUrl.equals("SUCCESS");
        //TODO : Download is taken care by S3
        /*FileDownloader downloader = new FileDownloader(
                payloadUrl,
                0,
                0,
                Paths.get(destPath, "fota-update.tar").toFile());
        try {
            downloadStatus = downloader.download();
        } catch (IOException e) {
            Log.e(TAG, String.format("Downloading OTA Update failed"));
            throw new RuntimeException(e);
        }*/
        //TODO : Added the extraction logic and direct call to applyLoad for local downloaded fota file.
        FotaExtractor extractor = new FotaExtractor(destPath);
        boolean extractStatus = extractor.extractTar(destPath);
        Log.e(TAG, String.format("extractStatus : " + extractStatus));
        //mUpdateManager.applyLocalPayload(destPath, );
        //TODO : For checking purpose only
        downloadStatus = true;
        if (downloadStatus)
        {
            //TODO : To hmi or other modules
            //notifyClientsDownloadComplete();
            try {
                Log.e(TAG, String.format("Sending the status to Udp Service that download of OTA completed"));
                mUdpService.startOta(fotaUdpCallback);
            } catch (RemoteException e) {
                Log.e(TAG, String.format("Sending the status to Udp Service failed"));

                throw new RuntimeException(e);
            }
        }
        mConfigs = UpdateConfigs.getUpdateConfig(destPath);

        try {
            mUpdateManager.applyUpdate(this, mConfigs);
        } catch (UpdaterState.InvalidTransitionException e) {
            throw new RuntimeException(e);
        }


        /*new Thread(() -> {
            try (InputStream in = new URL(payloadUrl).openStream()) {
                Files.copy(in, Paths.get(destPath), StandardCopyOption.REPLACE_EXISTING);
                Log.i(TAG, "FOTA package downloaded: " + destPath);
                mConfigManager.setLastUpdateStatus("download_complete");
                mConfigManager.saveConfig();

                //Extract the downloaded OTA package.
                String extractBaseDir = getApplicationContext().getFilesDir().getAbsolutePath() + "/fota/";
                Log.i(TAG, "Extracting OTA content to: " + extractBaseDir);

                FotaExtractor extractor = new FotaExtractor(extractBaseDir);
                extractor.extractTar(destPath);

                //
                notifyClientsDownloadComplete(destPath);
            } catch (Exception e) {
                mConfigManager.setLastUpdateStatus("failed");
                mConfigManager.saveConfig();
                Log.e(TAG, "Download failed", e);
            }
        }).start();*/

        /*FileDownloader downloader = new FileDownloader(
                mConfigs.getUrl(),
                metadataPackageFile.get().getOffset(),
                metadataPackageFile.get().getSize(),
                metadataPath.toFile());
        downloader.download();*/
    }




    /**
     * Invoked when SystemUpdaterSample app state changes.
     * Value of {@code state} will be one of the
     * values from {@link UpdaterState}.
     */
    private void onUpdaterStateChange(int state) {
        Log.i(TAG, "UpdaterStateChange state="
                + UpdaterState.getStateText(state)
                + "/" + state);

        //TODO : API call to HMI related to Update Engine Status

            if (state == UpdaterState.IDLE) {
                //setUiUpdaterState(state);
            } else if (state == UpdaterState.RUNNING) {
                //setUiUpdaterState(state);
            } else if (state == UpdaterState.PAUSED) {
                //setUiUpdaterState(state);
            }
    }

    /**
     * Invoked when {@link UpdateEngine} status changes. Value of {@code status} will
     * be one of the values from {@link UpdateEngine.UpdateStatusConstants}.
     */
    private void onEngineStatusUpdate(int status) {
        Log.i(TAG, "StatusUpdate - status="
                + UpdateEngineStatuses.getStatusText(status)
                + "/" + status);
        //TODO : API call to HMI related to Update Engine Status
        //sendUiEngineStatus(status);
    }

    /**
     * Invoked when the payload has been applied, whether successfully or
     * unsuccessfully. The value of {@code errorCode} will be one of the
     * values from {@link UpdateEngine.ErrorCodeConstants}.
     */
    private void onEnginePayloadApplicationComplete(int errorCode) {
        final String completionState = UpdateEngineErrorCodes.isUpdateSucceeded(errorCode)
                ? "SUCCESS"
                : "FAILURE";
        Log.i(TAG,
                "PayloadApplicationCompleted - errorCode="
                        + UpdateEngineErrorCodes.getCodeName(errorCode) + "/" + errorCode
                        + " " + completionState);
        //TODO : API call to HMI related to Payload Apply
        //sendUiEngineErrorCode(errorCode);

    }

    /**
     * Invoked when update progress changes.
     */
    private void onProgressUpdate(double progress) {
        //TODO : API call to HMI related to Progress
        //sendProgress((int) (100 * progress));
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // If needed, you can start foreground here, otherwise bound service will run when CloudObserver binds
        return START_STICKY;
    }
}