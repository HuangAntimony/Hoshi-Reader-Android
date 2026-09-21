# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# JNI looks up these bridge classes and constructors by their JVM names.
-keep class de.manhhao.hoshi.** { *; }

# Keep JavaScript interfaces exposed to WebView content.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# UniFFI's generated Kotlin/JNA bindings are part of the native ABI boundary.
-keep class uniffi.hoshiepub.** { *; }

# JNA's own native dispatcher looks up internal classes and fields such as
# com.sun.jna.Pointer.peer by their original JVM names.
-keep class com.sun.jna.** { *; }

# JNA also ships desktop AWT integration classes that are unused on Android.
-dontwarn java.awt.**

# sherpa-onnx JNI resolves config fields, result constructors, and methods by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# FFmpeg transcription JNI entry points use the bridge class name.
-keep class moe.antimony.hoshi.features.sasayaki.transcription.NativeSasayakiAudio { *; }
