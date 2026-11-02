package de.wagenknecht.backloggd.util;

import android.content.Context;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * The JavaScript and CSS the app runs on Backloggd pages, kept as readable files under
 * assets/web/ instead of string literals.
 */
public final class PageScripts {

    /** Adds app.css to the page, once per page. */
    public final String injectStyle;
    public final String search;
    public final String logGame;
    public final String watchDialogs;
    /** Evaluates to the saved username on the settings page, or null. */
    public final String readUsername;

    private PageScripts(Context context) throws IOException {
        String css = read(context, "web/app.css");
        injectStyle = "(function(){"
                + "if(document.getElementById('app-injected-style'))return;"
                + "var s=document.createElement('style');"
                + "s.id='app-injected-style';"
                + "s.textContent=" + JSONObject.quote(css) + ";"
                + "(document.head||document.documentElement).appendChild(s);"
                + "})();";
        search = read(context, "web/search.js");
        logGame = read(context, "web/log-game.js");
        watchDialogs = read(context, "web/watch-dialogs.js");
        readUsername = read(context, "web/read-username.js");
    }

    /** Reads all scripts; they are a few kilobytes and part of the APK, so this is quick. */
    @NonNull
    public static PageScripts load(@NonNull Context context) {
        try {
            return new PageScripts(context);
        } catch (IOException e) {
            // The files ship inside the APK; missing ones are a build mistake, not a runtime state.
            throw new IllegalStateException("Page scripts are missing from the APK.", e);
        }
    }

    private static String read(Context context, String path) throws IOException {
        StringBuilder text = new StringBuilder();
        try (InputStream in = context.getAssets().open(path);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int n;
            while ((n = reader.read(buffer)) > 0) {
                text.append(buffer, 0, n);
            }
        }
        return text.toString();
    }
}
