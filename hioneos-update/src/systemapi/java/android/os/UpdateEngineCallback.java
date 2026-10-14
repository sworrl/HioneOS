package android.os;

/** Compile-time stub of the framework's android.os.UpdateEngineCallback (@SystemApi). */
public abstract class UpdateEngineCallback {
    public UpdateEngineCallback() { }
    public abstract void onStatusUpdate(int status, float percent);
    public abstract void onPayloadApplicationComplete(int errorCode);
}
