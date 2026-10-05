# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# disable obfuscation
-dontobfuscate

# Keep JNI interface
-keep class org.fcitx.fcitx5.android.core.* { *; }
-keep class org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase {
    public <init>(...);
}

# sherpa-onnx 1.13.8 exposes its Kotlin wrapper classes to native JNI. Native
# code finds these classes by their source names (FindClass), constructs result
# objects through their constructors (NewObject, e.g. OfflineRecognizerResult and
# OnlineRecognizerResult), and reads configuration fields by source name (e.g.
# maxActivePaths). The AAR ships no consumer rules (proguard.txt is empty), so
# release shrinking removes members only referenced from native code. The earlier
# fields-only keep was insufficient: R8 removed the JNI-constructed
# OfflineRecognizerResult(...) constructor, causing NoSuchMethodError on device.
# Keep the complete JNI-facing wrapper surface (classes + all members).
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Keep dependency magic
-keep class ** extends org.mechdancer.dependency.Component {
    int hashCode();
    boolean equals(java.lang.Object);
}

# remove kotlin null checks
-processkotlinnullchecks remove

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
