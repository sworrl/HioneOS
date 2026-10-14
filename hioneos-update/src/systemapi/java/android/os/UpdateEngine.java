package android.os;

/**
 * Compile-time stub of the framework's android.os.UpdateEngine (@SystemApi, so it is not in the
 * public android.jar). Signatures match /system/framework/framework.jar on the M500 (dexdump,
 * 2026-10-10). This class is compileOnly: it never ends up in the APK, the real one comes from
 * the boot classpath. Hione Update may call it because it is a platform-signed system app.
 */
public class UpdateEngine {
    public UpdateEngine() { throw new RuntimeException("stub"); }
    public boolean bind(UpdateEngineCallback callback, Handler handler) { throw new RuntimeException("stub"); }
    public boolean bind(UpdateEngineCallback callback) { throw new RuntimeException("stub"); }
    public boolean unbind() { throw new RuntimeException("stub"); }
    public void applyPayload(String url, long offset, long size, String[] headerKeyValuePairs) { throw new RuntimeException("stub"); }
    public void cancel() { throw new RuntimeException("stub"); }
    public void suspend() { throw new RuntimeException("stub"); }
    public void resume() { throw new RuntimeException("stub"); }
    public void resetStatus() { throw new RuntimeException("stub"); }
    public int cleanupAppliedPayload() { throw new RuntimeException("stub"); }
    public boolean verifyPayloadMetadata(String metadataFilename) { throw new RuntimeException("stub"); }
}
