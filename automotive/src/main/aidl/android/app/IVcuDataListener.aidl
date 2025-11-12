package android.app;

/**
 * Interface for listening to VCU data.
 * Implemented by system services to receive VCU updates.
 * @hide
 */
interface IVcuDataListener {
    void onVcuDataReceived(in byte[] data);
}