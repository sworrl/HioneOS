# ============================================================================
# HioneOS SystemUI Production Optimization & Obfuscation ProGuard Rules
# ============================================================================

-optimizationpasses 5
-allowaccessmodification
-repackageclasses 'com.hione.systemui.internal.obf'

-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver

-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.material3.** { *; }
-keep class androidx.compose.animation.** { *; }
-keep class androidx.compose.foundation.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
    @androidx.compose.runtime.ReadOnlyComposable *;
}

-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# Trust agent: its callbacks override the @SystemApi TrustAgentService, which R8 only sees as a
# compile-only stub. Keep the class and its members so no override is dropped or renamed.
-keep class com.hione.systemui.trust.** { *; }
-dontwarn android.service.trust.**
