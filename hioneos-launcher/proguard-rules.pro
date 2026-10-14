# ============================================================================
# HioneOS Production Optimization, Minification & Obfuscation ProGuard Rules
# ============================================================================

# Aggressive Multi-Pass Optimization
-optimizationpasses 5
-allowaccessmodification
-repackageclasses 'com.hione.internal.obf'

# Preserve Entrypoints & Activity Contracts
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference

# AndroidX Jetpack Compose Rules
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.material3.** { *; }
-keep class androidx.compose.animation.** { *; }
-keep class androidx.compose.foundation.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
    @androidx.compose.runtime.ReadOnlyComposable *;
}

# Kotlin Coroutines & Reflection
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Direct Audio HAL & Hardware I2C / ALSA JNI reflection
-keep class com.hione.launcher.CirrusLogicManager { *; }
-keep class com.hione.settings.hardware.** { *; }
-keep class com.hione.player.audio.** { *; }
-keep class * implements android.os.IInterface { *; }

# Obfuscate Attributes while preserving Stack Traces
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# Strip Non-Critical Debug Logs in Production
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# Tink / EncryptedSharedPreferences (arcobocconotto secure store) references
# compile-only errorprone + javax annotations that aren't on the runtime
# classpath — tell R8 to ignore them (they're not needed at runtime).
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.api.client.http.**
-keep class com.google.crypto.tink.** { *; }

# Auto-generated R8 keep/dontwarn rules for Tink transitive deps:
# Please add these rules to your existing keep rules in order to suppress warnings.
# This is generated automatically by the Android Gradle plugin.
-dontwarn org.joda.time.Instant