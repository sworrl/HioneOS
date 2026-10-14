# Hione Music — release R8/ProGuard rules.
#
# Media3/ExoPlayer, Compose, Kotlin coroutines, and androidx.security (Tink) all ship their own
# consumer-rules.pro bundled inside their AARs, auto-applied by AGP — no manual keep rules needed
# for those. This file only handles what's specific to THIS app: readable stack traces in release
# crash logs (obfuscated code is still debuggable via adb logcat with these two lines), and a
# couple of defensive keeps for the one place this app touches reflection-adjacent APIs.

# Keep source file + line numbers so a release-build crash in adb logcat still points at a real
# file:line instead of just an obfuscated class name — R8 renames the class but these two lines
# keep the mapping from the stack trace's perspective readable without needing to unshrink a
# mapping.txt file for routine debugging.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions

# Track/ArtistGroup/AlbumGroup/Alarm etc. are plain Kotlin data classes accessed directly by field
# — never reflectively (de)serialized (Alarm.kt/LastFm.kt hand-roll their org.json parsing) — so
# no keep rule is needed for them; R8 is free to rename/inline freely. Left unkept intentionally.

# AndroidManifest-declared components (Activities/Services/Receivers) are automatically kept by
# AGP's own manifest-derived keep rules — no need to duplicate that here either.
