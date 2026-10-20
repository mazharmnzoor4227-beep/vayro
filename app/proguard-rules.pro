# Kodex release R8 rules.
# v3.2.46 (security audit H2): release shrinking/obfuscation is ENABLED
# (build.gradle.kts sets isMinifyEnabled + isShrinkResources). Before this
# version the shipped DEX carried full original class/method names. The
# rules below protect only the surfaces that locate code by name at
# runtime (JNI, framework reflection, serialization); everything else in
# com.vayro.app is free to be renamed. The mapping file is never
# published — it stays in the CI workspace only.

# --- JNI -----------------------------------------------------------------
# libpocketspawn.so resolves Java_com_vayro_app_runtime_NativeSpawn_*
# symbols by name; renaming this object or its native methods breaks the
# guest runtime spawn path outright.
-keep class com.vayro.app.runtime.NativeSpawn { *; }
# Any other class with native methods: keep the member names.
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Framework reflection -------------------------------------------------
# Compose screens obtain ViewModels via viewModel(), which instantiates
# them reflectively by constructor. Keep ViewModel constructors callable.
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class * extends androidx.lifecycle.AndroidViewModel {
    <init>(...);
}
# Parcelable: the CREATOR field is read reflectively by the framework.
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
# Enums: valueOf/values are called reflectively by serializers/framework.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- java.io.Serializable (standard rules) --------------------------------
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# --- kotlinx-serialization -------------------------------------------------
# The app itself parses JSON with org.json, but libraries on the
# classpath (Firebase encoders, credentials) ship @Serializable types
# whose serializers are located reflectively. Standard documented rules.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
}
-keepclasseswithmembers @kotlinx.serialization.Serializable class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class **$$serializer {
    *;
}

# --- zstd-jni ---------------------------------------------------------------
# zstd-jni's native library looks up its own Java classes/methods by
# name (JNI). Keep the (small) library intact rather than risk a rename.
-keep class com.github.luben.zstd.** { *; }

# --- OkHttp / Okio ------------------------------------------------------------
# OkHttp ships its own consumer rules; these only silence platform notes
# for optional APIs it probes reflectively.
-dontwarn okhttp3.internal.platform.**
-dontnote okhttp3.**
-dontwarn okio.**

# Firebase Auth / Crashlytics / FCM, androidx.credentials, security-crypto
# and kotlinx-coroutines all ship consumer keep rules in their AARs and
# are kept through those; manifest-declared components (Application,
# activities, services, receivers) are AGP entry points and are handled
# by the generated manifest rules.
