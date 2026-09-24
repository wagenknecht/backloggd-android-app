package de.wagenknecht.backloggd.worker;

import static de.wagenknecht.backloggd.ApiConstants.BACKLOGGD_URL;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;
import android.webkit.CookieManager;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.preference.PreferenceManager;
import androidx.work.ExistingWorkPolicy;
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

import de.wagenknecht.backloggd.MainActivity;
import de.wagenknecht.backloggd.R;
import de.wagenknecht.backloggd.util.BackloggdRequest;
import de.wagenknecht.backloggd.util.ImageDownloader;
import de.wagenknecht.backloggd.util.UsernameHelper;

public class WishlistCheckerWorker extends Worker {

    private static final String TAG = "WishlistCheckerWorker";
    private static final String CHANNEL_ID = "BACKLOGGD_WISHLIST_RELEASE";
    public static final String WORK_NAME = "WishlistChecker";
    /** Holds one column per game on the wishlist page. */
    private static final String GAMES_CONTAINER_ID = "user_games";

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
        String wishlistUrl = BACKLOGGD_URL + "/u/" + username + "/wishlist/release/type:wishlist;release_year:" + currentYear;

        try {
            Connection.Response wishlistResponse = BackloggdRequest
                    .forUrl(context, wishlistUrl, cookies)
                    .execute();
            int statusCode = wishlistResponse.statusCode();
            if (statusCode != 200) {
                Log.w(TAG, "Wishlist request returned status " + statusCode + ". Body excerpt: "
                        + wishlistResponse.body().substring(0, Math.min(500, wishlistResponse.body().length())));
                return Result.retry();
            }
            Document doc = wishlistResponse.parse();
            if (doc.getElementById(GAMES_CONTAINER_ID) == null) {
                // Without the container this is not the page we expect, e.g. after a redesign.
                Log.w(TAG, "Wishlist page has no #" + GAMES_CONTAINER_ID + "; its markup may have changed. Title: "
                        + doc.title());
            }
            List<WishlistGame> games = parseWishlist(doc);

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
                            showPushNotification(
                                    context.getString(R.string.wishlist_notification_title),
                                    context.getString(R.string.wishlist_notification_message, gameTitle),
                                    gameTitle,
                                    gameCoverBitmap);
                            releasePrefs.edit().putString(gameTitle, releaseDateStr).apply();
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

    private void showPushNotification(String title, String contentText, String gameTitle, Bitmap gameCover) {
        Context context = getApplicationContext();
        createNotificationChannel(context);

        int notificationId = gameTitle.hashCode();
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, notificationId, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_notification)
                .setColor(ContextCompat.getColor(context, R.color.back_pink))
                .setContentTitle(title)
                .setContentText(contentText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        if (gameCover != null) {
            builder.setLargeIcon(gameCover);
        }

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot send notification. Permission not granted.");
            return;
        }
        notificationManager.notify(notificationId, builder.build());
    }

    private void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = context.getString(R.string.wishlist_channel_name);
            String description = context.getString(R.string.wishlist_channel_description);
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);

            NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    public static void scheduleNextWorker(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int hour = prefs.getInt("daily_notification_hour", 9);
        int minute = prefs.getInt("daily_notification_minute", 0);

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
                .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest);

        Log.d(TAG, "Wishlist checker worker scheduled to run at " + nextRun.getTime());
    }

    private static void scheduleRetry(Context context, long delay, TimeUnit unit) {
        OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(WishlistCheckerWorker.class)
                .setInitialDelay(delay, unit)
                .build();

        WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE, // Replace any existing retry
                workRequest);

        Log.d(TAG, "Wishlist checker worker retry scheduled in " + delay + " " + unit.toString().toLowerCase());
    }
}
