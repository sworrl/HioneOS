package android.service.trust;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PersistableBundle;
import java.util.List;

/**
 * Compile-only stub of the @SystemApi android.service.trust.TrustAgentService (Android 14).
 * Never packaged: hioneos-systemui uses stubs/trust-agent-stubs.jar as compileOnly, so at run
 * time the framework class is used. Signatures match the A14 framework.jar on the M500.
 * Rebuild the jar with stubs/build.sh.
 */
public class TrustAgentService extends Service {
    public static final String SERVICE_INTERFACE = "android.service.trust.TrustAgentService";
    public static final String TRUST_AGENT_META_DATA = "android.service.trust.trustagent";
    public static final int FLAG_GRANT_TRUST_INITIATED_BY_USER = 1;
    public static final int FLAG_GRANT_TRUST_DISMISS_KEYGUARD = 2;
    public static final int FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE = 4;
    public static final int FLAG_GRANT_TRUST_DISPLAY_MESSAGE = 8;

    public void onCreate() { throw new RuntimeException("Stub!"); }
    public void onUnlockAttempt(boolean successful) { throw new RuntimeException("Stub!"); }
    public void onUserMayRequestUnlock() { throw new RuntimeException("Stub!"); }
    public void onUserRequestedUnlock(boolean dismissKeyguard) { throw new RuntimeException("Stub!"); }
    public void onTrustTimeout() { throw new RuntimeException("Stub!"); }
    public void onDeviceLocked() { throw new RuntimeException("Stub!"); }
    public void onDeviceUnlocked() { throw new RuntimeException("Stub!"); }
    public void onDeviceUnlockLockout(long timeoutMs) { throw new RuntimeException("Stub!"); }
    public boolean onConfigure(List<PersistableBundle> options) { throw new RuntimeException("Stub!"); }
    public final void grantTrust(CharSequence message, long durationMs, int flags) { throw new RuntimeException("Stub!"); }
    public final void revokeTrust() { throw new RuntimeException("Stub!"); }
    public final void setManagingTrust(boolean managingTrust) { throw new RuntimeException("Stub!"); }
    public final void lockUser() { throw new RuntimeException("Stub!"); }
    public final IBinder onBind(Intent intent) { throw new RuntimeException("Stub!"); }
}
