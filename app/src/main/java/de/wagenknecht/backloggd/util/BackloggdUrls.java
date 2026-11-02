package de.wagenknecht.backloggd.util;

import static de.wagenknecht.backloggd.ApiConstants.BACKLOGGD_URL;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Which pages count as Backloggd, which may load in the app, and where a user's pages are. */
public final class BackloggdUrls {

    private BackloggdUrls() {}

    public static boolean isBackloggdHost(@Nullable Uri uri) {
        String host = uri != null ? uri.getHost() : null;
        return "backloggd.com".equalsIgnoreCase(host) || "www.backloggd.com".equalsIgnoreCase(host);
    }

    /**
     * MainActivity is exported, so any app on the device can hand it a URL, either as a VIEW
     * intent aimed at it directly (which skips the intent filter) or as the urlToLoad extra. Only
     * Backloggd pages over https may load into the WebView that holds the login; another site or a
     * javascript: URL would run with the user's session. Backslashes and user info are refused
     * because Android and Chromium parse them differently, e.g. https://evil.com\@backloggd.com.
     */
    public static boolean isSafeToLoad(@Nullable String url) {
        if (url == null || url.indexOf('\\') >= 0) {
            return false;
        }
        Uri uri = Uri.parse(url);
        return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getUserInfo() == null
                && isBackloggdHost(uri);
    }

    /** The user's profile page. */
    @NonNull
    public static String profile(@NonNull String username) {
        return BACKLOGGD_URL + "/u/" + username;
    }

    /** One part of the user's profile, such as "wishlist" or "following". */
    @NonNull
    public static String profileSection(@NonNull String username, @NonNull String section) {
        return profile(username) + "/" + section + "/";
    }

    /** True for the profile itself and every page below it. */
    public static boolean isOnProfileOf(@Nullable String path, @Nullable String username) {
        if (path == null || username == null) {
            return false;
        }
        String profilePath = "/u/" + username;
        return path.equals(profilePath) || path.startsWith(profilePath + "/");
    }
}
