# Library consumer rules cover CameraX, Media3 and Compose. Nothing in this app is reached by
# reflection, so no keeps are needed beyond the defaults. Keep line numbers so a crash report from
# the device still points at real source lines.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
