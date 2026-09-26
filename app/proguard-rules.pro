# Keep annotations for reflection
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations

# WebView JS bridge: VencordNative must keep its class name, method names,
# and parameter types exactly as-is because JavaScript calls them by name
# via VencordMobileNative.getString(...) etc.  The default
# proguard-android-optimize.txt keeps @JavascriptInterface methods,
# but aggressive R8 settings (-repackageclasses, -allowaccessmodification)
# can still break the bridge if the class itself is obfuscated or merged.
-keep @interface android.webkit.JavascriptInterface
-keepclassmembers class com.nin0dev.vendroid.webview.VencordNative {
    @android.webkit.JavascriptInterface <methods>;
}
# Also keep the class from being renamed/merged so the runtime type
# matches what addJavascriptInterface() registered.
-keep class com.nin0dev.vendroid.webview.VencordNative { *; }

# Inner bridge classes registered via addJavascriptInterface() from
# openLogs()/openQuickCss()/openFirewallEditor(). R8's aggressive passes
# can merge or rename these even though @JavascriptInterface keeps their
# method names, so keep the classes explicitly.
-keep class com.nin0dev.vendroid.webview.VencordNative$LogViewerBridge { *; }
-keep class com.nin0dev.vendroid.webview.VencordNative$QuickCssBridge { *; }
-keep class com.nin0dev.vendroid.webview.VencordNative$FirewallEditorBridge { *; }

# Gson: keep only the core Gson class + TypeToken (for reflection).
# Let R8 shrink all unused Gson adapters/internals. Only serialized
# model classes need to be kept (via @SerializedName or explicit rules).
-keep class com.google.gson.Gson { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Suppress warnings for javax.annotation (not on Android)
-dontwarn javax.annotation.**

# Keep source file names and line numbers for crash reports in debug
-keepattributes SourceFile,LineNumberTable

# Aggressive R8 optimization: enables inlining, class merging,
# access modification, and multiple optimization passes for a
# smaller DEX and faster class loading.
-repackageclasses ''
-allowaccessmodification
-optimizationpasses 5
-mergeinterfacesaggressively

# VDELog: in-app logging engine. Keep the class and all members; R8's
# aggressive passes (-optimizationpasses 5, -allowaccessmodification,
# -repackageclasses) can inline or merge it away if it looks unused.
# The HandlerThread and Handler fields must survive for file I/O.
-keep class com.nin0dev.vendroid.utils.VDELog { *; }
-keep class com.nin0dev.vendroid.utils.VDELog$Level { *; }
-keep class com.nin0dev.vendroid.utils.VDELog$LogEntry { *; }

# FirewallConfig: runtime-editable domain allowlist. Aggressive R8 passes can
# inline or merge a singleton that looks unused from static analysis; keep it
# and its Category enum explicitly.
-keep class com.nin0dev.vendroid.utils.FirewallConfig { *; }
-keep class com.nin0dev.vendroid.utils.FirewallConfig$Category { *; }
-keep class com.nin0dev.vendroid.utils.FirewallConfig$Category$Companion { *; }

# Remove ALL logging in release (including Log.w and Log.e which
# still allocate strings for their arguments even if not visible).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int wtf(...);
    public static int e(...);
}

# OkHttp 5.x + Okio (aggressive R8: -repackageclasses, -allowaccessmodification,
# -mergeinterfacesaggressively). The AAR bundles its own okhttp3.pro (mostly
# -dontwarn); these keeps are belt-and-suspenders so the synchronous
# Call/ConnectionPool subset survives shrinking.
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# OkHttp 5.x Android artifact loads the public-suffix DB from an asset reflectively.
-keep class okhttp3.internal.publicsuffix.** { *; }

# Platform TLS providers OkHttp probes at runtime (bundled rules also cover these).
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
