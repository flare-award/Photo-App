# Serendip — R8 rules for the release build.
#
# AGP 9 runs R8 in strict full mode with `proguard-android-optimize.txt`.
# Library rules (Room, CameraX, Coil, Kotlin coroutines, DataStore, Compose)
# are shipped as consumer rules inside the AARs; nothing here duplicates them.
#
# The app itself uses no reflection: Room access goes through KSP-generated
# code, DataStore stores primitives, enums are matched by name through plain
# Kotlin `entries`, and manifest components are kept by AGP automatically.

# Readable stack traces in crash reports / logcat (Play de-obfuscates with mapping.txt).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Debug-level technical logs are already guarded by BuildConfig.DEBUG (see
# system/AppLog.kt); this lets R8 drop the call sites and their string
# concatenations from release code entirely. Warnings/errors stay in logcat.
-assumenosideeffects class com.flareaward.serendip.system.AppLog {
    public void d(java.lang.String);
}
