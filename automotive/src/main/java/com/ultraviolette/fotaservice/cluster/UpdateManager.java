package com.ultraviolette.fotaservice.cluster;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.os.UpdateEngine;
import android.os.UpdateEngineCallback;
import android.util.Log;

import com.ultraviolette.fotaservice.PayloadSpec;
import com.ultraviolette.fotaservice.PrepareUpdate;
import com.ultraviolette.fotaservice.utils.UpdateEngineErrorCodes;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.AtomicDouble;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

//import androidx.annotation.GuardedBy;

/**
 * Manages the update flow. It has its own state (in memory), separate from
 * {@link UpdateEngine}'s state. Asynchronously interacts with the {@link UpdateEngine}.
 */
public class UpdateManager {

    private static final String TAG = "FotaService.UpdateManager";

    /** HTTP Header: User-Agent; it will be sent to the server when streaming the payload. */
    static final String HTTP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.113 Safari/537.36";

    private final UpdateEngine mUpdateEngine;
    private PrepareUpdate mPrepareUpdate = new PrepareUpdate();
    private AtomicInteger mUpdateEngineStatus =
            new AtomicInteger(UpdateEngine.UpdateStatusConstants.IDLE);
    private AtomicInteger mEngineErrorCode = new AtomicInteger(UpdateEngineErrorCodes.UNKNOWN);
    private AtomicDouble mProgress = new AtomicDouble(0);
    private UpdaterState mUpdaterState = new UpdaterState(UpdaterState.IDLE);

    private AtomicBoolean mManualSwitchSlotRequired = new AtomicBoolean(true);

    /** Synchronize state with engine status only once when app binds to UpdateEngine. */
    private AtomicBoolean mStateSynchronized = new AtomicBoolean(false);

    //@GuardedBy("mLock")
    private UpdateData mLastUpdateData = null;

    //@GuardedBy("mLock")
    private IntConsumer mOnStateChangeCallback = null;
    //@GuardedBy("mLock")
    private IntConsumer mOnEngineStatusUpdateCallback = null;
    //@GuardedBy("mLock")
    private DoubleConsumer mOnProgressUpdateCallback = null;
    //@GuardedBy("mLock")
    private IntConsumer mOnEngineCompleteCallback = null;

    private final Object mLock = new Object();

    private final UpdateManager.UpdateEngineCallbackImpl
            mUpdateEngineCallback = new UpdateManager.UpdateEngineCallbackImpl();

    private final Handler mHandler;

    /**
     * @param updateEngine UpdateEngine instance.
     * @param handler      Handler for {@link } intent service.
     */
    public UpdateManager(UpdateEngine updateEngine, Handler handler) {
        this.mUpdateEngine = updateEngine;
        this.mHandler = handler;
    }

    /**
     * Binds to {@link UpdateEngine}. Invokes onStateChangeCallback if present.
     */
    public void bind() {
        getOnStateChangeCallback().ifPresent(callback -> callback.accept(mUpdaterState.get()));

        mStateSynchronized.set(false);
        boolean bindUpdateEngine = this.mUpdateEngine.bind(mUpdateEngineCallback);
        Log.d(TAG, "bindUpdateEngine : " + bindUpdateEngine);
    }

    /**
     * Unbinds from {@link UpdateEngine}.
     */
    public void unbind() {
        this.mUpdateEngine.unbind();
    }

    public int getUpdaterState() {
        return mUpdaterState.get();
    }

    /**
     * Returns true if manual switching slot is required. Value depends on
     * the update config {@code ab_config.force_switch_slot}.
     */
    public boolean isManualSwitchSlotRequired() {
        return mManualSwitchSlotRequired.get();
    }

    /**
     * Sets SystemUpdaterSample app state change callback. Value of {@code state} will be one
     * of the values from {@link UpdaterState}.
     *
     * @param onStateChangeCallback a callback with parameter {@code state}.
     */
    public void setOnStateChangeCallback(IntConsumer onStateChangeCallback) {
        synchronized (mLock) {
            this.mOnStateChangeCallback = onStateChangeCallback;
        }
    }

    private Optional<IntConsumer> getOnStateChangeCallback() {
        synchronized (mLock) {
            return mOnStateChangeCallback == null
                    ? Optional.empty()
                    : Optional.of(mOnStateChangeCallback);
        }
    }

    /**
     * Sets update engine status update callback. Value of {@code status} will
     * be one of the values from {@link UpdateEngine.UpdateStatusConstants}.
     *
     * @param onStatusUpdateCallback a callback with parameter {@code status}.
     */
    public void setOnEngineStatusUpdateCallback(IntConsumer onStatusUpdateCallback) {
        Log.e(TAG, "ssetOnEngineStatusUpdateCallback ");
        synchronized (mLock) {
            this.mOnEngineStatusUpdateCallback = onStatusUpdateCallback;
        }
    }

    private Optional<IntConsumer> getOnEngineStatusUpdateCallback() {
        Log.e(TAG, "getOnEngineStatusUpdateCallback ");
        synchronized (mLock) {
            return mOnEngineStatusUpdateCallback == null
                    ? Optional.empty()
                    : Optional.of(mOnEngineStatusUpdateCallback);
        }
    }

    /**
     * Sets update engine payload application complete callback. Value of {@code errorCode} will
     * be one of the values from {@link UpdateEngine.ErrorCodeConstants}.
     *
     * @param onComplete a callback with parameter {@code errorCode}.
     */
    public void setOnEngineCompleteCallback(IntConsumer onComplete) {
        Log.e(TAG, "setOnEngineCompleteCallback ");
        synchronized (mLock) {
            this.mOnEngineCompleteCallback = onComplete;
        }
    }

    private Optional<IntConsumer> getOnEngineCompleteCallback() {
        Log.e(TAG, "getOnEngineCompleteCallback ");
        synchronized (mLock) {
            return mOnEngineCompleteCallback == null
                    ? Optional.empty()
                    : Optional.of(mOnEngineCompleteCallback);
        }
    }

    /**
     * Sets progress update callback. Progress is a number from {@code 0.0} to {@code 1.0}.
     *
     * @param onProgressCallback a callback with parameter {@code progress}.
     */
    public void setOnProgressUpdateCallback(DoubleConsumer onProgressCallback) {
        Log.e(TAG, "setOnProgressUpdateCallback ");
        synchronized (mLock) {
            this.mOnProgressUpdateCallback = onProgressCallback;
        }
    }

    private Optional<DoubleConsumer> getOnProgressUpdateCallback() {
        Log.e(TAG, "setOnProgressUpdateCallback ");
        synchronized (mLock) {
            return mOnProgressUpdateCallback == null
                    ? Optional.empty()
                    : Optional.of(mOnProgressUpdateCallback);
        }
    }

    /**
     * Suspend running update.
     */
    public synchronized void suspend() throws UpdaterState.InvalidTransitionException {
        Log.d(TAG, "suspend invoked");
        setUpdaterState(UpdaterState.PAUSED);
        mUpdateEngine.cancel();
    }

    /**
     * Resume suspended update.
     */
    public synchronized void resume() throws UpdaterState.InvalidTransitionException {
        Log.d(TAG, "resume invoked");
        setUpdaterState(UpdaterState.RUNNING);
        updateEngineReApplyPayload();
    }

    /**
     * Updates {@link this.mState} and if state is changed,
     * it also notifies {@link this.mOnStateChangeCallback}.
     */
    private void setUpdaterState(int newUpdaterState)
            throws UpdaterState.InvalidTransitionException {
        Log.d(TAG, "setUpdaterState invoked newState=" + newUpdaterState);
        int previousState = mUpdaterState.get();
        mUpdaterState.set(newUpdaterState);
        if (previousState != newUpdaterState) {
            getOnStateChangeCallback().ifPresent(callback -> callback.accept(newUpdaterState));
        }
    }

    /**
     * Same as {@link this.setUpdaterState}. Logs the error if new state
     * cannot be set.
     */
    private void setUpdaterStateSilent(int newUpdaterState) {
        try {
            setUpdaterState(newUpdaterState);
        } catch (UpdaterState.InvalidTransitionException e) {
            // Most likely UpdateEngine status and UpdaterSample state got de-synchronized.
            // To make sample app simple, we don't handle it properly.
            Log.e(TAG, "Failed to set updater state", e);
        }
    }

    /**
     * Creates new UpdaterState, assigns it to {@link this.mUpdaterState},
     * and notifies callbacks.
     */
    private void initializeUpdateState(int state) {
        this.mUpdaterState = new UpdaterState(state);
        getOnStateChangeCallback().ifPresent(callback -> callback.accept(state));
    }

    /**
     * Requests update engine to stop any ongoing update. If an update has been applied,
     * leave it as is.
     */
    public synchronized void cancelRunningUpdate() throws UpdaterState.InvalidTransitionException {
        Log.d(TAG, "cancelRunningUpdate invoked");
        setUpdaterState(UpdaterState.IDLE);
        mUpdateEngine.cancel();
    }

    /**
     * Resets update engine to IDLE state. If an update has been applied it reverts it.
     */
    public synchronized void resetUpdate() throws UpdaterState.InvalidTransitionException {
        Log.d(TAG, "resetUpdate invoked");
        setUpdaterState(UpdaterState.IDLE);
        mUpdateEngine.resetStatus();
    }

    /**
     * Applies the given update.
     *
     * <p>UpdateEngine works asynchronously. This method doesn't wait until
     * end of the update.</p>
     */
    public synchronized void applyUpdate(Context context, UpdateConfig config)
            throws UpdaterState.InvalidTransitionException {
        mEngineErrorCode.set(UpdateEngineErrorCodes.UNKNOWN);
        setUpdaterState(UpdaterState.RUNNING);

        synchronized (mLock) {
            // Cleaning up previous update data.
            mLastUpdateData = null;
        }

        if (!config.getAbConfig().getForceSwitchSlot()) {
            mManualSwitchSlotRequired.set(true);
        } else {
            mManualSwitchSlotRequired.set(false);
        }
/*        Log.d(TAG, "Starting PrepareUpdateService");
        PrepareUpdateService.startService(context, config, mHandler, (code, payloadSpec) -> {
            if (code != PrepareUpdateService.RESULT_CODE_SUCCESS) {
                Log.e(TAG, "PrepareUpdateService failed, result code is " + code);
                setUpdaterStateSilent(UpdaterState.ERROR);
                return;
            }
            updateEngineApplyPayload(UpdateData.builder()
                    .setExtraProperties(prepareExtraProperties(config))
                    .setPayload(payloadSpec)
                    .build());
        });*/
        mPrepareUpdate.prepare(config, new PrepareUpdate.Callback() {
            @Override
            public void onSuccess(PayloadSpec spec) {
                Log.e(TAG, "OTA prepared! Starting streaming apply...");

                UpdateData updateData = UpdateData.builder()
                        .setExtraProperties(prepareExtraProperties(config))
                        .setPayload(spec)
                        .build();

                // This calls UpdateEngine.applyPayload()
                //updateEngineApplyPayload(updateData);
                //TODO Commented Streaming and applied Non-Streaming(Local) payload update
                updateEngineApplyLocalPayload(updateData);
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "OTA prepare failed: " + message);
                setUpdaterStateSilent(UpdaterState.ERROR);
            }
        });
    }

    private List<String> prepareExtraProperties(UpdateConfig config) {
        Log.e(TAG, "OTA prepared! Starting streaming apply...");
        List<String> extraProperties = new ArrayList<>();

        if (!config.getAbConfig().getForceSwitchSlot()) {
            // Disable switch slot on reboot, which is enabled by default.
            // User will enable it manually by clicking "Switch Slot" button on the screen.
            //extraProperties.add(UpdateEngineProperties.PROPERTY_DISABLE_SWITCH_SLOT_ON_REBOOT);
            Log.e(TAG, "UpdateEngine getForceSwitchSlot is false");
        }
        if (config.getInstallType() == UpdateConfig.AB_INSTALL_TYPE_STREAMING) {
            extraProperties.add("USER_AGENT=" + HTTP_USER_AGENT);
            config.getAbConfig()
                    .getAuthorization()
                    .ifPresent(s -> extraProperties.add("AUTHORIZATION=" + s));
        }
        return extraProperties;
    }

    /**
     * Applies given payload.
     *
     * <p>UpdateEngine works asynchronously. This method doesn't wait until
     * end of the update.</p>
     *
     * <p>It's possible that the update engine throws a generic error, such as upon seeing invalid
     * payload properties (which come from OTA packages), or failing to set up the network
     * with the given id.</p>
     */
    private void updateEngineApplyPayload(UpdateData update) {
        Log.d(TAG, "updateEngineApplyPayload invoked with url " + update.mPayload.getUrl());

        synchronized (mLock) {
            mLastUpdateData = update;
        }

        ArrayList<String> properties = new ArrayList<>(update.getPayload().getProperties());
        properties.addAll(update.getExtraProperties());

        //TODO : Removed the dependency with update engine. Later need to implement
        /*try {
            mUpdateEngine.applyPayload(
                    update.getPayload().getUrl(),
                    update.getPayload().getOffset(),
                    update.getPayload().getSize(),
                    properties.toArray(new String[0]));
        } catch (Exception e) {
            Log.e(TAG, "UpdateEngine failed to apply the update", e);
            setUpdaterStateSilent(UpdaterState.ERROR);
        }*/
        //TODO : IF full Download
        //mUpdateEngine.applyPayload(pathToDownloadedFIle, 0, totalFileSize, new String[]{});
    }
    //TODO : Written for Local Payload(Non-Streaming)
    private void updateEngineApplyLocalPayload(UpdateData update) {
        Log.d(TAG, "updateEngineApplyPayload invoked with url " + update.mPayload.getUrl());

        synchronized (mLock) {
            mLastUpdateData = update;
        }

        ArrayList<String> properties = new ArrayList<>(update.getPayload().getProperties());
        properties.addAll(update.getExtraProperties());

        //TODO : Removed the dependency with update engine. Later need to implement
        /*try {
            mUpdateEngine.applyPayload(
                    update.getPayload().getUrl(),
                    update.getPayload().getOffset(),
                    update.getPayload().getSize(),
                    properties.toArray(new String[0]));
        } catch (Exception e) {
            Log.e(TAG, "UpdateEngine failed to apply the update", e);
            setUpdaterStateSilent(UpdaterState.ERROR);
        }*/
        //TODO : IF full Download
        //mUpdateEngine.applyPayload(pathToDownloadedFIle, 0, totalFileSize, new String[]{});
    }
    //TODO : Taking it from the local rather than from Remote URL.

    public void applyLocalPayload(String localPayloadPath) {
        File payloadFile = new File(localPayloadPath + "/payload.bin");
        Log.e(TAG, "applyLocalPayload for file " + payloadFile);
        try {
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(
                    payloadFile,
                    ParcelFileDescriptor.MODE_READ_ONLY
            );

            List<String> headers = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(localPayloadPath + "/payload_properties.txt"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] kv = line.split("=", 2);
                    if (kv.length == 2) {
                        headers.add(kv[0]);
                        headers.add(kv[1]);
                        Log.e(TAG, "Values inside payload properties" + kv[0] + " = " + kv[1]);
                    }
                }
            }
            String[] headerKeyValuePairs = headers.toArray(new String[0]);
            Log.e(TAG, "headerKeyValuePairs0 " + headerKeyValuePairs[0]);
            Log.e(TAG, "headerKeyValuePairs1 " + headerKeyValuePairs[1]);
            Log.e(TAG, "headerKeyValuePairs2 " + headerKeyValuePairs[2]);


            // Get size if not known (headers may include FILE_SIZE)
            StatFs stat = new StatFs(localPayloadPath);
            AssetFileDescriptor assetFd = new AssetFileDescriptor(
                    pfd,           // ParcelFileDescriptor
                    0L,            // startOffset
                    payloadFile.length()  // length
            );

            //mUpdateEngine.applyPayload(assetFd, headerKeyValuePairs);
            Log.d(TAG, "Applying local payload from : " + localPayloadPath);
        } catch (Exception e) {
            Log.e(TAG, "Apply failed", e);
        }
    }

    /**
     * Re-applies {@link this.mLastUpdateData} to update_engine.
     */
    private void updateEngineReApplyPayload() {
        Log.d(TAG, "updateEngineReApplyPayload invoked");
        UpdateData lastUpdate;
        synchronized (mLock) {
            // mLastPayloadSpec might be empty in some cases.
            // But to make this sample app simple, we will not handle it.
            Preconditions.checkArgument(
                    mLastUpdateData != null,
                    "mLastUpdateData must be present.");
            lastUpdate = mLastUpdateData;
        }
        updateEngineApplyPayload(lastUpdate);
    }

    /**
     * Sets the new slot that has the updated partitions as the active slot,
     * which device will boot into next time.
     * This method is only supposed to be called after the payload is applied.
     *
     * Invoking {@link UpdateEngine#applyPayload} with the same payload url, offset, size
     * and payload metadata headers doesn't trigger new update. It can be used to just switch
     * active A/B slot.
     *
     * {@link UpdateEngine#applyPayload} might take several seconds to finish, and it will
     * invoke callbacks {@link this#onStatusUpdate} and {@link this#onPayloadApplicationComplete)}.
     */
/*    public synchronized void setSwitchSlotOnReboot() {
        Log.d(TAG, "setSwitchSlotOnReboot invoked");

        // When mManualSwitchSlotRequired set false, next time
        // onApplicationPayloadComplete is called,
        // it will set updater state to REBOOT_REQUIRED.
        mManualSwitchSlotRequired.set(false);

        UpdateData.Builder builder;
        synchronized (mLock) {
            // To make sample app simple, we don't handle it.
            Preconditions.checkArgument(
                    mLastUpdateData != null,
                    "mLastUpdateData must be present.");
            builder = mLastUpdateData.toBuilder();
        }
        // PROPERTY_SKIP_POST_INSTALL should be passed on to skip post-installation hooks.
        builder.setExtraProperties(
                Collections.singletonList(UpdateEngineProperties.PROPERTY_SKIP_POST_INSTALL));
        // UpdateEngine sets property SWITCH_SLOT_ON_REBOOT=1 by default.
        // HTTP headers are not required, UpdateEngine is not expected to stream payload.
        updateEngineApplyPayload(builder.build());
    }*/

    /**
     * Synchronize UpdaterState with UpdateEngine status.
     * Apply necessary UpdateEngine operation if status are out of sync.
     *
     * It's expected to be called once when sample app binds itself to UpdateEngine.
     */
    private void synchronizeUpdaterStateWithUpdateEngineStatus() {
        Log.d(TAG, "synchronizeUpdaterStateWithUpdateEngineStatus is invoked.");

        int state = mUpdaterState.get();
        int engineStatus = mUpdateEngineStatus.get();
        Log.d(TAG, "synchronizeUpdaterStateWithUpdateEngineStatus state : " + state);
        Log.d(TAG, "synchronizeUpdaterStateWithUpdateEngineStatus engineStatus : " + engineStatus);
        if (engineStatus == UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT) {
            // If update has been installed before running the sample app,
            // set state to REBOOT_REQUIRED.
            initializeUpdateState(UpdaterState.REBOOT_REQUIRED);
            return;
        }

        switch (state) {
            case UpdaterState.IDLE:
            case UpdaterState.ERROR:
            case UpdaterState.PAUSED:
            case UpdaterState.SLOT_SWITCH_REQUIRED:
                // It might happen when update is started not from the sample app.
                // To make the sample app simple, we won't handle this case.
                Preconditions.checkState(
                        engineStatus == UpdateEngine.UpdateStatusConstants.IDLE,
                        "When mUpdaterState is %s, mUpdateEngineStatus "
                                + "must be 0/IDLE, but it is %s",
                        state,
                        engineStatus);
                break;
            case UpdaterState.RUNNING:
                if (engineStatus == UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT
                        || engineStatus == UpdateEngine.UpdateStatusConstants.IDLE) {
                    Log.i(TAG, "ensureUpdateEngineStatusIsRunning - re-applying last payload");
                    // Re-apply latest update. It makes update_engine to invoke
                    // onPayloadApplicationComplete callback. The callback notifies
                    // if update was successful or not.
                    updateEngineReApplyPayload();
                }
                break;
            case UpdaterState.REBOOT_REQUIRED:
                // This might happen when update is installed by other means,
                // and sample app is not aware of it.
                // To make the sample app simple, we won't handle this case.
                Preconditions.checkState(
                        engineStatus == UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT,
                        "When mUpdaterState is %s, mUpdateEngineStatus "
                                + "must be 6/UPDATED_NEED_REBOOT, but it is %s",
                        state,
                        engineStatus);
                break;
            default:
                throw new IllegalStateException("This block should not be reached.");
        }
    }

    /**
     * Invoked by update_engine whenever update status or progress changes.
     * It's also guaranteed to be invoked when app binds to the update_engine, except
     * when update_engine fails to initialize (as defined in
     * system/update_engine/binder_service_android.cc in
     * function BinderUpdateEngineAndroidService::bind).
     *
     * @param status   one of {@link UpdateEngine.UpdateStatusConstants}.
     * @param progress a number from 0.0 to 1.0.
     */
    private void onStatusUpdate(int status, float progress) {
        Log.d(TAG, String.format(
                "onStatusUpdate invoked, status=%s, progress=%.2f",
                status,
                progress));

        int previousStatus = mUpdateEngineStatus.get();
        Log.e(TAG, "onStatusUpdate previousStatus : " + previousStatus);
        mUpdateEngineStatus.set(status);
        mProgress.set(progress);

        if (!mStateSynchronized.getAndSet(true)) {
            // We synchronize state with engine status once
            // only when sample app is bound to UpdateEngine.
            synchronizeUpdaterStateWithUpdateEngineStatus();
        }

        getOnProgressUpdateCallback().ifPresent(callback -> callback.accept(mProgress.get()));

        if (previousStatus != status) {
            Log.e(TAG, "onStatusUpdate from UpdateEngineCallback status is not same as previous");
            getOnEngineStatusUpdateCallback().ifPresent(callback -> callback.accept(status));
        }
    }

    private void onPayloadApplicationComplete(int errorCode) {
        Log.d(TAG, "onPayloadApplicationComplete invoked, errorCode=" + errorCode);
        mEngineErrorCode.set(errorCode);
        if (errorCode == UpdateEngine.ErrorCodeConstants.SUCCESS
                || errorCode == UpdateEngineErrorCodes.UPDATED_BUT_NOT_ACTIVE) {
            setUpdaterStateSilent(isManualSwitchSlotRequired()
                    ? UpdaterState.SLOT_SWITCH_REQUIRED
                    : UpdaterState.REBOOT_REQUIRED);
        } else if (errorCode != UpdateEngineErrorCodes.USER_CANCELLED) {
            setUpdaterStateSilent(UpdaterState.ERROR);
        }

        getOnEngineCompleteCallback()
                .ifPresent(callback -> callback.accept(errorCode));
    }

    /**
     * Helper class to delegate {@code update_engine} callback invocations to UpdateManager.
     */
    class UpdateEngineCallbackImpl extends UpdateEngineCallback {
        @Override
        public void onStatusUpdate(int status, float percent) {
            Log.e(TAG, "onStatusUpdate from UpdateEngineCallback status : " + status + " % : " + percent);
            UpdateManager.this.onStatusUpdate(status, percent);
        }

        @Override
        public void onPayloadApplicationComplete(int errorCode) {
            Log.e(TAG, "onPayloadApplicationComplete from UpdateEngineCallback errorcode : " + errorCode);

            UpdateManager.this.onPayloadApplicationComplete(errorCode);
        }
    }

    /**
     * Contains update data - PayloadSpec and extra properties list.
     *
     * <p>{@code mPayload} contains url, offset and size to {@code PAYLOAD_BINARY_FILE_NAME}.
     * {@code mExtraProperties} is a list of additional properties to pass to
     * {@link UpdateEngine#applyPayload}.</p>
     */
    private static class UpdateData {
        private final PayloadSpec mPayload;
        private final ImmutableList<String> mExtraProperties;

        public static Builder builder() {
            return new Builder();
        }

        UpdateData(Builder builder) {
            this.mPayload = builder.mPayload;
            this.mExtraProperties = ImmutableList.copyOf(builder.mExtraProperties);
        }

        public PayloadSpec getPayload() {
            return mPayload;
        }

        public ImmutableList<String> getExtraProperties() {
            return mExtraProperties;
        }

        public Builder toBuilder() {
            return builder()
                    .setPayload(mPayload)
                    .setExtraProperties(mExtraProperties);
        }

        static class Builder {
            private PayloadSpec mPayload;
            private List<String> mExtraProperties;

            public Builder setPayload(PayloadSpec payload) {
                this.mPayload = payload;
                return this;
            }

            public Builder setExtraProperties(List<String> extraProperties) {
                this.mExtraProperties = new ArrayList<>(extraProperties);
                return this;
            }

            public Builder addExtraProperty(String property) {
                if (this.mExtraProperties == null) {
                    this.mExtraProperties = new ArrayList<>();
                }
                this.mExtraProperties.add(property);
                return this;
            }

            public UpdateData build() {
                return new UpdateData(this);
            }
        }
    }

}

