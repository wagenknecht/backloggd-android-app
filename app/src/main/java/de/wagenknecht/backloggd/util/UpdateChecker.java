package de.wagenknecht.backloggd.util;

import static de.wagenknecht.backloggd.ApiConstants.GITHUB_CHANGELOGS_API_URL;
import static de.wagenknecht.backloggd.ApiConstants.GITHUB_LATEST_RELEASE_API_URL;
import static de.wagenknecht.backloggd.ApiConstants.GITHUB_RELEASE_BY_TAG_API_URL;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares the installed version against the latest GitHub release. Every failure path stays
 * silent on purpose: a failed update check should never bother the user.
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final int TIMEOUT_MS = 15000;
    private static final String PREF_LAST_SEEN_VERSION = "last_seen_version";
    /** Written by the bundleChangelog Gradle task from fastlane/.../changelogs/<versionCode>.txt. */
    private static final String BUNDLED_CHANGELOG_ASSET = "changelog.txt";
    private static final Pattern CHANGELOG_FILE = Pattern.compile("(\\d+)\\.txt");

    /** A newer release than the installed one. */
    public static final class Release {
        /** Tag name, e.g. "2.1". */
        public final String version;
        /** Direct link to the APK asset, or null when the release has none. */
        @Nullable
        public final String apkUrl;
        /** Release notes reduced to plain text; empty when the release has none. */
        public final String notes;

        Release(String version, @Nullable String apkUrl, String notes) {
            this.version = version;
            this.apkUrl = apkUrl;
            this.notes = notes;
        }
    }

    public interface Callback {
        @MainThread
        void onUpdateAvailable(@NonNull Release release);
    }

    public interface NotesCallback {
        /** Only called when the installed version actually has release notes. */
        @MainThread
        void onReleaseNotes(@NonNull String version, @NonNull String notes);
    }

    private UpdateChecker() {}

    /**
     * True when the installed version is newer than the one seen at the last launch. False on a
     * fresh install, so a new user is not greeted with notes for a version they never ran.
     */
    public static boolean wasUpdatedSinceLastLaunch(@NonNull Context context) {
        PackageInfo info = getPackageInfo(context);
        if (info == null || info.versionName == null) {
            return false;
        }
        String lastSeen = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_LAST_SEEN_VERSION, null);
        return wasUpdated(info.versionName, lastSeen, info.firstInstallTime, info.lastUpdateTime);
    }

    /**
     * Versions up to 2.0 never recorded themselves, so an update from one of them arrives without
     * a last seen version. Android still tells it apart from a fresh install: only an update
     * moves the last update time past the first install time.
     */
    @VisibleForTesting
    static boolean wasUpdated(@NonNull String installed, @Nullable String lastSeen,
                              long firstInstallTime, long lastUpdateTime) {
        if (lastSeen == null) {
            return lastUpdateTime > firstInstallTime;
        }
        return isNewerVersion(installed, lastSeen);
    }

    /** Records the installed version, so the next update is recognised as one. */
    public static void rememberInstalledVersion(@NonNull Context context) {
        String installed = getInstalledVersion(context);
        if (installed == null) {
            return;
        }
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putString(PREF_LAST_SEEN_VERSION, installed).apply();
    }

    /**
     * Looks up the release notes for the version that is installed right now. The changelog
     * bundled into the APK wins; without one, the notes of the matching GitHub release are used.
     * Stays silent when neither exists — a locally built version has no matching tag on GitHub.
     */
    public static void fetchInstalledReleaseNotes(@NonNull Context context, @NonNull NotesCallback callback) {
        String installed = getInstalledVersion(context);
        if (installed == null) {
            return;
        }

        Context appContext = context.getApplicationContext();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String notes = readBundledChangelog(appContext);
            if (notes == null) {
                JSONObject releaseJson = fetchRelease(GITHUB_RELEASE_BY_TAG_API_URL + installed);
                if (releaseJson == null) {
                    return;
                }
                notes = toPlainText(releaseJson.optString("body", ""));
            }
            if (notes.isEmpty()) {
                Log.d(TAG, "Release " + installed + " has no notes to show.");
                return;
            }
            String shownNotes = notes;
            mainHandler.post(() -> callback.onReleaseNotes(installed, shownNotes));
        }).start();
    }

    /**
     * Looks for a newer release in the background. The callback runs on the main thread and only
     * fires when an update actually exists.
     */
    public static void checkAsync(@NonNull Context context, @NonNull Callback callback) {
        String installedVersion = getInstalledVersion(context);
        if (installedVersion == null) {
            return;
        }

        Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            JSONObject releaseJson = fetchLatestRelease();
            if (releaseJson == null) {
                return;
            }

            String latestVersion = releaseJson.optString("tag_name", "");
            if (latestVersion.isEmpty()) {
                Log.w(TAG, "Latest release has no tag_name, skipping update check.");
                return;
            }

            Log.d(TAG, "Latest release on GitHub: " + latestVersion);
            Log.d(TAG, "Installed app version: " + installedVersion);

            if (!isNewerVersion(latestVersion, installedVersion)) {
                return;
            }

            // Releases up to 2.0 predate the changelog files; their notes come from the release text.
            String changelog = fetchChangelogAt(latestVersion);
            String notes = changelog != null
                    ? changelog
                    : toPlainText(releaseJson.optString("body", ""));

            Release release = new Release(latestVersion, findApkAssetUrl(releaseJson), notes);
            mainHandler.post(() -> callback.onUpdateAvailable(release));
        }).start();
    }

    @Nullable
    private static String getInstalledVersion(@NonNull Context context) {
        PackageInfo info = getPackageInfo(context);
        return info != null ? info.versionName : null;
    }

    @Nullable
    private static PackageInfo getPackageInfo(@NonNull Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Could not read the installed version.", e);
            return null;
        }
    }

    @WorkerThread
    @Nullable
    private static JSONObject fetchLatestRelease() {
        return fetchRelease(GITHUB_LATEST_RELEASE_API_URL);
    }

    /** Fetches a GitHub release as JSON, or null if that fails for any reason. */
    @WorkerThread
    @Nullable
    private static JSONObject fetchRelease(String url) {
        String body = fetchText(url);
        if (body == null) {
            return null;
        }
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            Log.e(TAG, "Unreadable release JSON from " + url, e);
            return null;
        }
    }

    /**
     * Fetches the changelog that the given tag carries in the repo, i.e. the file with the
     * highest versionCode. Null when the tag has no changelog files or the lookup fails.
     */
    @WorkerThread
    @Nullable
    private static String fetchChangelogAt(@NonNull String tag) {
        String listing = fetchText(GITHUB_CHANGELOGS_API_URL + tag);
        if (listing == null) {
            return null;
        }

        Map<String, String> downloadUrls = new HashMap<>();
        try {
            JSONArray entries = new JSONArray(listing);
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                String downloadUrl = entry.optString("download_url", "");
                if (!downloadUrl.isEmpty()) {
                    downloadUrls.put(entry.optString("name", ""), downloadUrl);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Unreadable changelog listing for " + tag, e);
            return null;
        }

        String name = latestChangelogName(downloadUrls.keySet());
        if (name == null) {
            return null;
        }
        String changelog = fetchText(downloadUrls.get(name));
        if (changelog == null) {
            return null;
        }
        String notes = cleanChangelog(changelog);
        return notes.isEmpty() ? null : notes;
    }

    /** The changelog bundled into this APK, or null when the build had none. */
    @WorkerThread
    @Nullable
    private static String readBundledChangelog(@NonNull Context context) {
        try (InputStream in = context.getAssets().open(BUNDLED_CHANGELOG_ASSET)) {
            String notes = cleanChangelog(readAll(in));
            return notes.isEmpty() ? null : notes;
        } catch (FileNotFoundException e) {
            return null;
        } catch (IOException e) {
            Log.e(TAG, "Could not read the bundled changelog.", e);
            return null;
        }
    }

    /** Fetches a URL as text, or null if that fails for any reason. */
    @WorkerThread
    @Nullable
    private static String fetchText(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            // The GitHub API rejects requests without a User-Agent.
            connection.setRequestProperty("User-Agent", "Backloggd-Android-App");

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Request to " + url + " returned status " + status);
                return null;
            }

            try (InputStream in = connection.getInputStream()) {
                return readAll(in);
            }
        } catch (IOException e) {
            Log.e(TAG, "Request to " + url + " failed", e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line).append('\n');
            }
        }
        return text.toString();
    }

    /**
     * Picks the changelog of the newest version from a listing of file names, comparing the
     * versionCodes numerically so "10.txt" beats "9.txt". Other files are ignored.
     */
    @VisibleForTesting
    @Nullable
    static String latestChangelogName(@NonNull Collection<String> names) {
        String latest = null;
        long latestCode = -1;
        for (String name : names) {
            Matcher matcher = CHANGELOG_FILE.matcher(name);
            if (!matcher.matches()) {
                continue;
            }
            long code;
            try {
                code = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (code > latestCode) {
                latestCode = code;
                latest = name;
            }
        }
        return latest;
    }

    /** Changelogs are plain text already; they only need their line endings and length evened out. */
    @VisibleForTesting
    static String cleanChangelog(String changelog) {
        String text = changelog.replace("\r\n", "\n");
        text = BLANK_LINES.matcher(text).replaceAll("\n\n");
        return capLength(text.trim());
    }

    /** Direct download URL of the release's APK asset, or null when it has none. */
    @Nullable
    private static String findApkAssetUrl(@NonNull JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null || !asset.optString("name", "").endsWith(".apk")) {
                continue;
            }
            String downloadUrl = asset.optString("browser_download_url", "");
            if (!downloadUrl.isEmpty()) {
                return downloadUrl;
            }
        }
        return null;
    }

    /** A linked badge image, e.g. the shields.io download counters at the top of a release. */
    private static final Pattern LINKED_IMAGE = Pattern.compile("\\[!\\[[^\\]]*]\\([^)]*\\)]\\([^)]*\\)");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern HEADING = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s*");
    private static final Pattern LIST_MARKER = Pattern.compile("(?m)^\\s*[-*+]\\s+");
    private static final Pattern EMPHASIS = Pattern.compile("(\\*\\*?)(.+?)\\1");
    private static final Pattern BLANK_LINES = Pattern.compile("\n{3,}");

    private static final int MAX_NOTES_CHARS = 2000;

    /**
     * Reduces GitHub's Markdown release notes to something a dialog can show: badge images drop
     * out entirely, links keep only their label, and list markers become bullets. Deliberately
     * not a full Markdown parser — it handles the handful of constructs these notes actually use,
     * without pulling in a library.
     */
    @VisibleForTesting
    static String toPlainText(String markdown) {
        String text = markdown.replace("\r\n", "\n");
        text = LINKED_IMAGE.matcher(text).replaceAll("");
        text = IMAGE.matcher(text).replaceAll("");
        text = LINK.matcher(text).replaceAll("$1");
        text = HEADING.matcher(text).replaceAll("");
        text = LIST_MARKER.matcher(text).replaceAll("• ");
        text = EMPHASIS.matcher(text).replaceAll("$2");
        text = BLANK_LINES.matcher(text).replaceAll("\n\n");
        return capLength(text.trim());
    }

    private static String capLength(String text) {
        if (text.length() > MAX_NOTES_CHARS) {
            return text.substring(0, MAX_NOTES_CHARS).trim() + "…";
        }
        return text;
    }

    /**
     * Compares dot-separated version numbers, tolerating an optional "v" prefix and a differing
     * number of segments ("2.1" counts as newer than "2.0.3"). Only a strictly newer remote
     * version returns true, so locally built versions ahead of the last release stay quiet.
     */
    @VisibleForTesting
    static boolean isNewerVersion(@NonNull String remote, @NonNull String local) {
        String[] remoteParts = stripVersionPrefix(remote).split("\\.");
        String[] localParts = stripVersionPrefix(local).split("\\.");
        int segments = Math.max(remoteParts.length, localParts.length);
        for (int i = 0; i < segments; i++) {
            int remotePart = versionPart(remoteParts, i);
            int localPart = versionPart(localParts, i);
            if (remotePart != localPart) {
                return remotePart > localPart;
            }
        }
        return false;
    }

    private static String stripVersionPrefix(String version) {
        String trimmed = version.trim();
        if (trimmed.startsWith("v") || trimmed.startsWith("V")) {
            return trimmed.substring(1);
        }
        return trimmed;
    }

    /** Leading digits of the given segment, or 0 for a missing or non-numeric one. */
    private static int versionPart(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        String part = parts[index].trim();
        int end = 0;
        while (end < part.length() && Character.isDigit(part.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(part.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
