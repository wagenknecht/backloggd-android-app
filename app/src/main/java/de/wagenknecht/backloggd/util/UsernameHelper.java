package de.wagenknecht.backloggd.util;

import static de.wagenknecht.backloggd.ApiConstants.BACKLOGGD_URL;
import static de.wagenknecht.backloggd.ApiConstants.SETTINGS_URL;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.preference.PreferenceManager;

import org.jsoup.Connection;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;

public final class UsernameHelper {

    private static final String TAG = "UsernameHelper";
    public static final String PREF_KEY = "backloggd_username";

    public interface Callback {
        @MainThread
        void onResult(@Nullable String username);
    }

    private UsernameHelper() {}

    @Nullable
    public static String getCached(@NonNull Context context) {
        String u = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_KEY, null);
        return (u == null || u.isEmpty()) ? null : u;
    }

    public static void clearCached(@NonNull Context context) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove(PREF_KEY).apply();
    }

    public static void setCached(@NonNull Context context, @NonNull String username) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putString(PREF_KEY, username).apply();
    }

    /**
     * The username from a WebView evaluateJavascript result, which is a JSON string such as
     * "\"Tysk\"", or "null" when the page has none. Usernames never need escaping, so anything
     * that does is not one.
     */
    @Nullable
    public static String fromJsResult(@Nullable String result) {
        if (result == null || result.length() < 3
                || result.charAt(0) != '"' || result.charAt(result.length() - 1) != '"') {
            return null;
        }
        String username = result.substring(1, result.length() - 1);
        return username.indexOf('\\') >= 0 ? null : username;
    }

    @WorkerThread
    @Nullable
    public static String fetchSync(@NonNull Context context) {
        String cookies = CookieManager.getInstance().getCookie(BACKLOGGD_URL);
        if (cookies == null || cookies.isEmpty()) {
            Log.d(TAG, "No cookies, user not logged in.");
            return null;
        }
        try {
            Connection.Response response = BackloggdRequest
                    .forUrl(context, SETTINGS_URL, cookies)
                    .execute();
            if (response.statusCode() != 200) {
                Log.w(TAG, "Settings page returned status " + response.statusCode());
                return null;
            }
            Document doc = response.parse();
            Element usernameInput = doc.selectFirst("input#user_username");
            if (usernameInput == null) {
                Log.w(TAG, "Username input not found on settings page.");
                return null;
            }
            String username = usernameInput.val();
            if (username == null || username.isEmpty()) return null;

            setCached(context, username);
            Log.d(TAG, "Fetched and saved username: " + username);
            return username;
        } catch (IOException e) {
            Log.e(TAG, "Failed to fetch settings page.", e);
            return null;
        }
    }

    public static void fetchAsync(@NonNull Context context, @NonNull Callback callback) {
        Context appContext = context.getApplicationContext();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String username = fetchSync(appContext);
            mainHandler.post(() -> callback.onResult(username));
        }).start();
    }
}
