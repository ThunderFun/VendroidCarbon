# Keep annotations for reflection
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations

# WebView JS bridge: JavaScript invokes VencordNative members by name via
# VencordMobileNative.getString(...). R8 cannot see those call sites, so keep
# the class and its @JavascriptInterface methods.
-keep @interface android.webkit.JavascriptInterface
-keepclassmembers class io.github.thunderfun.vendroid.webview.VencordNative {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class io.github.thunderfun.vendroid.webview.VencordNative { *; }

# Inner bridges registered by openLogs(), openQuickCss(), and
# openFirewallEditor(). They are only reached from JavaScript, so keep them
# from being merged.
-keep class io.github.thunderfun.vendroid.webview.VencordNative$LogViewerBridge { *; }
-keep class io.github.thunderfun.vendroid.webview.VencordNative$QuickCssBridge { *; }
-keep class io.github.thunderfun.vendroid.webview.VencordNative$FirewallEditorBridge { *; }

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

# AGP writes the mapping to build/outputs/mapping/<variant>/mapping.txt.
# Do not add -printmapping: the last variant to build overwrites a fixed path.

# Keep shrinking and optimization; obfuscation stays off so class and member
# names remain readable in stack traces.
-dontobfuscate
-optimizationpasses 5
-mergeinterfacesaggressively

# VDELog: in-app logging engine. Keep it and its members so R8 does not
# inline methods or merge the class away. The HandlerThread and Handler
# fields must survive for file I/O.
-keep class io.github.thunderfun.vendroid.utils.VDELog { *; }
-keep class io.github.thunderfun.vendroid.utils.VDELog$Level { *; }
-keep class io.github.thunderfun.vendroid.utils.VDELog$LogEntry { *; }

# FirewallConfig: runtime-editable domain allowlist. Aggressive R8 passes can
# inline or merge a singleton that looks unused from static analysis; keep it
# and its Category enum explicitly.
-keep class io.github.thunderfun.vendroid.utils.FirewallConfig { *; }
-keep class io.github.thunderfun.vendroid.utils.FirewallConfig$Category { *; }
-keep class io.github.thunderfun.vendroid.utils.FirewallConfig$Category$Companion { *; }

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

# OkHttp 5.x + Okio. The AAR's okhttp3.pro is mostly -dontwarn, so these
# keeps protect the synchronous Call/ConnectionPool subset from shrinking.
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# OkHttp 5.x Android artifact loads the public-suffix DB from an asset reflectively.
-keep class okhttp3.internal.publicsuffix.** { *; }

# Platform TLS providers OkHttp probes at runtime (bundled rules also cover these).
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
