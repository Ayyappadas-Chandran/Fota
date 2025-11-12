package android.app;
import android.app.IVcuDataListener;
import android.app.IOtaCompleteListener;
/**
* @hide
*/
interface IUdpService {
    /**
     * A simple method that calls down to the native layer and returns a greeting.
     */
    String sayHello(String name);
    void sendDataToVCU(in byte[] data);
    void startOta(IOtaCompleteListener listener);

    void registerVcuDataListener(IVcuDataListener listener);
    void unregisterVcuDataListener(IVcuDataListener listener);

    void unregisterOtaListener(IOtaCompleteListener listener);
}