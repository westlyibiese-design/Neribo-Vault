# Neribo Vault: R8 / ProGuard rules for the release build.
#
# The release build cannot be tested by the AI that wrote these rules, so they are
# deliberately conservative: when in doubt, keep more. A larger APK is better than a crash.
# Run the release smoke test in docs/RELEASE.md before publishing anything.

# ---------------------------------------------------------------------------------------
# Crash traces. Keep file names and line numbers so a stack trace can be read after R8.
# ---------------------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Attributes that reflection-based libraries (Room, WorkManager, Kotlin) rely on.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# ---------------------------------------------------------------------------------------
# Room. Entities, DAOs and the database are found and filled through generated code and
# reflection, and their column names must match the schema already on the phone.
# ---------------------------------------------------------------------------------------
-keep class com.westly.neribovault.data.local.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------------------------------
# Optional cloud sync. Rows are turned into and out of JSON by column name, so the whole
# package (API, engine, tables, worker, auth) is kept as it is.
# ---------------------------------------------------------------------------------------
-keep class com.westly.neribovault.data.cloud.** { *; }

# ---------------------------------------------------------------------------------------
# WorkManager. Workers are created by class name, through their (Context, WorkerParameters)
# constructor. This covers the reminder workers and the sync worker.
# ---------------------------------------------------------------------------------------
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class * extends androidx.work.CoroutineWorker { *; }
-dontwarn androidx.work.**

# ---------------------------------------------------------------------------------------
# Encrypted storage (androidx.security:security-crypto and the Tink library behind it).
# Tink reads protobuf message fields by reflection.
# ---------------------------------------------------------------------------------------
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.crypto.tink.**

# ---------------------------------------------------------------------------------------
# Biometric prompt.
# ---------------------------------------------------------------------------------------
-keep class androidx.biometric.** { *; }

# ---------------------------------------------------------------------------------------
# Google sign-in through Credential Manager. Providers are loaded by class name.
# ---------------------------------------------------------------------------------------
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-dontwarn com.google.android.gms.**

# ---------------------------------------------------------------------------------------
# OkHttp and Okio (network calls for the optional sync).
# ---------------------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------------------------------
# Coil (image loading for photos and document thumbnails).
# ---------------------------------------------------------------------------------------
-dontwarn coil.**

# ---------------------------------------------------------------------------------------
# Kotlin metadata and coroutines.
# ---------------------------------------------------------------------------------------
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**

# Enums are stored by name and read back with valueOf(), so keep values() and valueOf().
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------------------------------------------------------------------------------------
# The generated BuildConfig (cloud project values and the debug flag).
# ---------------------------------------------------------------------------------------
-keep class com.westly.neribovault.BuildConfig { *; }

# The application class and the activity are named in the manifest.
-keep class com.westly.neribovault.NeriboApp { *; }
-keep class com.westly.neribovault.MainActivity { *; }
