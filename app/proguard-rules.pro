# Rules on top of proguard-android-optimize.txt, which already keeps the WebView bridge's
# @JavascriptInterface methods.

# Release builds drop verbose, debug and info logging: it names the user and the pages they
# visit. Warnings and errors stay, they are what a bug report needs.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Jsoup ships with an optional re2j regex backend. We don't bundle re2j,
# so silence the warnings — jsoup falls back to java.util.regex at runtime.
-dontwarn com.google.re2j.**