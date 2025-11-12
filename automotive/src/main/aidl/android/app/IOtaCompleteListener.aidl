package android.app;

/**
* @hide
*/
oneway interface IOtaCompleteListener {
    void onOtaCompleted(int successMask);
}