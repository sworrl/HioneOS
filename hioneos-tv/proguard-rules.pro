# Keep the cast client; it is reached only from Compose state.
-keep class com.hione.tv.** { *; }
-keep class com.hione.player.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
