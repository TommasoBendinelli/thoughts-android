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

# JNI constructs these via GetMethodID, so members must survive R8 too
-keep class com.shockwave.** { *; }


# Restore some Source file names and restore approximate line numbers in the stack traces,
# otherwise the stack traces are pretty useless
-keepattributes SourceFile,LineNumberTable

# ACRA loads Plugins using reflection
-keep class * implements org.acra.plugins.Plugin {*;}

# ACRA uses enum fields in json
-keep enum org.acra.** {*;}

# autodsl accesses constructors using reflection
-keepclassmembers class * implements org.acra.config.Configuration { <init>(...); }

# ACRA creates a proxy for this interface
-keep interface org.acra.ErrorReporter

-dontwarn com.faendir.kotlin.autodsl.DslInspect
-dontwarn com.faendir.kotlin.autodsl.DslMandatory

# Keep class names and attributes needed by Gson for reflection.
-keepattributes Signature
-keepattributes *Annotation*

# Gson specific classes
-keep class com.google.gson.stream.** { *; }

# Application classes that will be serialized/deserialized over Gson
-keep class com.gitlab.mudlej.MjPdfReader.pdf.SearchResult { *; }
-keep class android.graphics.Bitmap { *; }
-keep class java.io.File { *; }

# Prevent Gson from being obfuscated
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ---------------------------------------------------------------------------------------------
# Thoughts release build (R8). Debug builds are not minified; these rules only shape `release`.
# ---------------------------------------------------------------------------------------------

# Gson (de)serializes many Thoughts and Thoughts SDK classes by reflection, mostly without
# @SerializedName: their field names are the JSON keys exchanged with the server and stored in the
# on-device caches/outbox. Renaming or removing a field would silently corrupt that data.
-keepclassmembers class com.gitlab.mudlej.MjPdfReader.** {
    <fields>;
    <init>(...);
}
-keep class org.tbend.thoughts.sdk.** { *; }
# Gson writes and reads enum constants by name.
-keepclassmembers enum * { *; }

# zstd-jni: the native library looks Java classes, fields and methods up by name.
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**

# Optional TLS providers that OkHttp probes for at runtime.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
