package de.wagenknecht.backloggd.worker;

import static de.wagenknecht.backloggd.ApiConstants.BACKLOGGD_URL;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.util.Log;
import android.webkit.CookieManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.preference.PreferenceManager;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.jsoup.Connection;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import de.wagenknecht.backloggd.R;
import de.wagenknecht.backloggd.util.BackloggdRequest;
import de.wagenknecht.backloggd.util.ImageDownloader;
import de.wagenknecht.backloggd.util.PushNotifications;
import de.wagenknecht.backloggd.util.UsernameHelper;

public class WishlistCheckerWorker extends Worker {

    private static final String TAG = "WishlistCheckerWorker";
    private static final String PREF_REMINDER_HOUR = "daily_notification_hour";
    private static final String PREF_REMINDER_MINUTE = "daily_notification_minute";
    public static final String WORK_NAME = "WishlistChecker";
    /** Holds one column per game on the wishlist page. */
    private static final String GAMES_CONTAINER_ID = "user_games";
    /** The site shows 40 games a page; this covers wishlists of up to 400 games this year. */
    private static final int MAX_PAGES = 10;
    /** Offline at the reminder time, the check waits for a connection instead of failing. */
    private static final Constraints NEEDS_NETWORK = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build();

    public WishlistCheckerWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            return checkWishlist();
        } catch (RuntimeException e) {
            // The daily run schedules its own successor, so an unexpected failure would end the
            // chain and silently stop all further reminders. Keep it going.
            Log.e(TAG, "Unexpected failure while checking the wishlist.", e);
            scheduleNextWorker(getApplicationContext());
            return Result.failure();
        }
    }

    private Result checkWishlist() {
        Context context = getApplicationContext();
        Log.d(TAG, "Worker started. Checking wishlist for games releasing today.");

        // Without a way to show the reminder, reading the wishlist and its covers is wasted.
        if (!PushNotifications.canPost(context, PushNotifications.Channel.WISHLIST)) {
            Log.d(TAG, "Wishlist reminders are switched off. Trying again tomorrow.");
            scheduleNextWorker(context);
            return Result.success();
        }

        String cookies = CookieManager.getInstance().getCookie(BACKLOGGD_URL);
        if (cookies == null || cookies.isEmpty()) {
            Log.w(TAG, "Could not get cookies. User is probably not logged in. Trying again tomorrow.");
            scheduleNextWorker(context);
            return Result.success();
        }

        String username = UsernameHelper.getCached(context);
        if (username == null) {
            Log.d(TAG, "Username not cached, fetching it from the settings page.");
            username = UsernameHelper.fetchSync(context);
        }
        if (username == null) {
            Log.w(TAG, "Could not determine the username. Retrying in 30 minutes.");
            scheduleRetry(context, 30, TimeUnit.MINUTES);
            return Result.failure();
        }

        int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        String wishlistUrl = BACKLOGGD_URL + "/u/" + username + "/games/wishlist/by/release/type:wishlist;release_year:" + currentYear + "/";

        try {
            // Sorted latest release first, so today's games sit behind all later ones this year,
            // possibly several pages in.
            List<WishlistGame> games = new ArrayList<>();
            String pageUrl = wishlistUrl;
            int pages = 0;
            while (pageUrl != null && pages < MAX_PAGES) {
                Connection.Response wishlistResponse = BackloggdRequest
                        .forUrl(context, pageUrl, cookies)
                        .execute();
                int statusCode = wishlistResponse.statusCode();
                if (statusCode != 200) {
                    Log.w(TAG, "Wishlist request returned status " + statusCode + ". Body excerpt: "
                            + wishlistResponse.body().substring(0, Math.min(500, wishlistResponse.body().length())));
                    return Result.retry();
                }
                Document doc = wishlistResponse.parse();
                if (pages == 0 && doc.getElementById(GAMES_CONTAINER_ID) == null) {
                    // Without the container this is not the page we expect, e.g. after a redesign.
                    Log.w(TAG, "Wishlist page has no #" + GAMES_CONTAINER_ID + "; its markup may have changed. Title: "
                            + doc.title());
                }
                games.addAll(parseWishlist(doc));
                pageUrl = nextPageUrl(doc);
                pages++;
            }
            Log.d(TAG, "Read " + games.size() + " games from " + pages + " wishlist page(s).");

            if (games.isEmpty()) {
                Log.d(TAG, "No games found on the wishlist page for the current year.");
            } else {
                for (WishlistGame game : games) {
                    String gameTitle = game.title;
                    String releaseDateStr = game.releaseDate;
                    String imageUrl = game.imageUrl;

                    if (isReleasedToday(releaseDateStr)) {
                        SharedPreferences releasePrefs = context.getSharedPreferences("wishlist_release_dates", Context.MODE_PRIVATE);
                        String storedDate = releasePrefs.getString(gameTitle, "");

                        if (!releaseDateStr.equals(storedDate)) {
                            Log.d(TAG, "Found game releasing today: " + gameTitle);
                            Bitmap gameCoverBitmap = ImageDownloader.downloadDownsampled(imageUrl);
                            boolean shown = PushNotifications.post(context, PushNotifications.Channel.WISHLIST,
                                    gameTitle.hashCode(),
                                    context.getString(R.string.wishlist_notification_title),
                                    context.getString(R.string.wishlist_notification_message, gameTitle),
                                    gameCoverBitmap, null);
                            // Only remembered once shown, so allowing notifications later today still brings it.
                            if (shown) {
                                releasePrefs.edit().putString(gameTitle, releaseDateStr).apply();
                            }
                        } else {
                            Log.d(TAG, "Already notified for today's release of " + gameTitle);
                        }
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to connect to Backloggd and parse wishlist.", e);
            return Result.retry();
        }

        scheduleNextWorker(context);
        return Result.success();
    }

    /** One game on the wishlist page. */
    @VisibleForTesting
    static final class WishlistGame {
        final String title;
        /** As the site shows it: "Sep 24, 2026", or only "2026" / "2026 Q3" without an exact day. */
        final String releaseDate;
        final String imageUrl;

        WishlistGame(String title, String releaseDate, String imageUrl) {
            this.title = title;
            this.releaseDate = releaseDate;
            this.imageUrl = imageUrl;
        }
    }

    /**
     * Reads the games off the wishlist page. Each game is a column in #user_games holding a cover
     * card, the title and a plain paragraph with the release date. The column also carries a
     * hidden "more" menu, whose texts are skipped.
     */
    @VisibleForTesting
    static List<WishlistGame> parseWishlist(Document doc) {
        List<WishlistGame> games = new ArrayList<>();
        for (Element column : doc.select("#" + GAMES_CONTAINER_ID + " .col-cus-user-games")) {
            Element cover = column.selectFirst("img.card-img");
            String title = column.select(".game-text-centered").text();
            if (title.isEmpty() && cover != null) {
                title = cover.attr("alt");
            }

            String releaseDate = "";
            for (Element paragraph : column.select("p")) {
                if (paragraph.closest("[id^=more-button-container]") == null) {
                    releaseDate = paragraph.text();
                    break;
                }
            }

            if (!title.isEmpty() && !releaseDate.isEmpty()) {
                games.add(new WishlistGame(title, releaseDate, cover != null ? cover.attr("src") : ""));
            }
        }
        return games;
    }

    /**
     * The next page of the list, or null on the last one, where "Next" has no link. Only
     * Backloggd pages qualify, since the request carries the login cookies.
     */
    @VisibleForTesting
    @Nullable
    static String nextPageUrl(Document doc) {
        Element next = doc.selectFirst("nav.pagy a[aria-label=Next][href]");
        if (next == null) {
            return null;
        }
        String url = next.absUrl("href");
        return url.startsWith(BACKLOGGD_URL + "/") ? url : null;
    }

    private boolean isReleasedToday(String releaseDateStr) {
        SimpleDateFormat formatWithComma = new SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH);
        SimpleDateFormat formatWithoutComma = new SimpleDateFormat("MMM dd yyyy", Locale.ENGLISH);
        Date releaseDate;

        try {
            releaseDate = formatWithComma.parse(releaseDateStr);
        } catch (ParseException e) {
            try {
                releaseDate = formatWithoutComma.parse(releaseDateStr);
            } catch (ParseException e2) {
                // Normal for games with only a year or quarter ("2026", "2026 Q3") so far.
                Log.d(TAG, "No exact release date: " + releaseDateStr);
                return false;
            }
        }

        Calendar today = Calendar.getInstance();
        Calendar releaseCal = Calendar.getInstance();
        releaseCal.setTime(releaseDate);

        return today.get(Calendar.YEAR) == releaseCal.get(Calendar.YEAR) &&
                today.get(Calendar.MONTH) == releaseCal.get(Calendar.MONTH) &&
                today.get(Calendar.DAY_OF_MONTH) == releaseCal.get(Calendar.DAY_OF_MONTH);
    }

    /** Hour of the daily reminder, 9 unless the user picked another time. */
    public static int getReminderHour(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getInt(PREF_REMINDER_HOUR, 9);
    }

    public static int getReminderMinute(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getInt(PREF_REMINDER_MINUTE, 0);
    }

    /** Stores a new reminder time and moves the next check to it. */
    public static void setReminderTime(Context context, int hour, int minute) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putInt(PREF_REMINDER_HOUR, hour)
                .putInt(PREF_REMINDER_MINUTE, minute)
                .apply();
        scheduleNextWorker(context);
    }

    /**
     * Makes sure the daily check is scheduled, leaving one that is already waiting alone. The app
     * calls this on every start; replacing there would drop the retry of a run that failed this
     * morning and skip today's reminders.
     */
    public static void ensureScheduled(Context context) {
        scheduleNextWorker(context, ExistingWorkPolicy.KEEP);
    }

    /** Schedules the next daily check, replacing whatever is waiting, e.g. after a time change. */
    public static void scheduleNextWorker(Context context) {
        scheduleNextWorker(context, ExistingWorkPolicy.REPLACE);
    }

    private static void scheduleNextWorker(Context context, ExistingWorkPolicy policy) {
        int hour = getReminderHour(context);
        int minute = getReminderMinute(context);

        Calendar nextRun = Calendar.getInstance();
        nextRun.set(Calendar.HOUR_OF_DAY, hour);
        nextRun.set(Calendar.MINUTE, minute);
        nextRun.set(Calendar.SECOND, 0);

        if (nextRun.before(Calendar.getInstance())) {
            nextRun.add(Calendar.DAY_OF_YEAR, 1);
        }

        long delay = nextRun.getTimeInMillis() - System.currentTimeMillis();

        OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(WishlistCheckerWorker.class)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(NEEDS_NETWORK)
                .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                policy,
                workRequest);

        Log.d(TAG, "Wishlist checker worker scheduled to run at " + nextRun.getTime()
                + (policy == ExistingWorkPolicy.KEEP ? " unless a run is already waiting" : ""));
    }

    private static void scheduleRetry(Context context, long delay, TimeUnit unit) {
        OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(WishlistCheckerWorker.class)
                .setInitialDelay(delay, unit)
                .setConstraints(NEEDS_NETWORK)
                .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE, // Replace any existing retry
                workRequest);

        Log.d(TAG, "Wishlist checker worker retry scheduled in " + delay + " " + unit.toString().toLowerCase());
    }
}
