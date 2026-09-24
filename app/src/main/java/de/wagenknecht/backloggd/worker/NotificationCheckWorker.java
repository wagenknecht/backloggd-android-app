package de.wagenknecht.backloggd.worker;

import static de.wagenknecht.backloggd.ApiConstants.NOTIFICATION_URL;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;
import android.webkit.CookieManager;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.preference.PreferenceManager;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.jsoup.Connection;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import de.wagenknecht.backloggd.MainActivity;
import de.wagenknecht.backloggd.R;
import de.wagenknecht.backloggd.util.BackloggdRequest;
import de.wagenknecht.backloggd.util.ImageDownloader;

public class NotificationCheckWorker extends Worker {

    private static final String TAG = "NotificationCheckWorker";
    private static final String CHANNEL_ID = "BACKLOGGD_NOTIFICATIONS";
    public static final String WORK_NAME = "NotificationCheck";
    private static final String PREF_INTERVAL = "notification_interval";
    private static final String DEFAULT_INTERVAL_MINUTES = "15";
    /** Interval value the settings screen uses for "Never". */
    private static final long INTERVAL_NEVER = -1;

    public NotificationCheckWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    /** Schedules the periodic check using the interval currently stored in the preferences. */
    public static void schedule(@NonNull Context context) {
        String stored = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_INTERVAL, DEFAULT_INTERVAL_MINUTES);
        schedule(context, Long.parseLong(stored));
    }

    /**
     * Schedules the periodic check, or cancels it when the user picked "Never". Takes the interval
     * explicitly because the settings screen has to act on the new value before it is persisted.
     */
    public static void schedule(@NonNull Context context, long intervalMinutes) {
        if (intervalMinutes == INTERVAL_NEVER) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
            Log.d(TAG, "Notification worker cancelled by user setting.");
            return;
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(NotificationCheckWorker.class, intervalMinutes, TimeUnit.MINUTES)
                        .setConstraints(constraints)
                        .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request);

        Log.d(TAG, "Notification worker scheduled for every " + intervalMinutes + " minutes.");
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "Worker started. Checking for notifications.");

        String cookies = CookieManager.getInstance().getCookie(NOTIFICATION_URL);
        if (cookies == null || cookies.isEmpty()) {
            Log.w(TAG, "Could not get cookies. User is probably not logged in. Aborting.");
            return Result.success();
        }

        try {
            Connection.Response response = BackloggdRequest
                    .forUrl(getApplicationContext(), NOTIFICATION_URL, cookies)
                    .execute();
            int statusCode = response.statusCode();
            if (statusCode != 200) {
                Log.w(TAG, "Notifications request returned status " + statusCode + ". Body excerpt: "
                        + response.body().substring(0, Math.min(500, response.body().length())));
                return Result.retry();
            }
            Document doc = response.parse();

            if (doc.select(".notification").isEmpty()) {
                // Not even read notifications: likely not the page we expect, e.g. after a redesign.
                Log.w(TAG, "Notifications page has no .notification elements; its markup may have changed. Title: "
                        + doc.title());
            }
            List<UnreadNotification> unreadNotifications = parseUnread(doc);

            if (!unreadNotifications.isEmpty()) {
                Log.i(TAG, unreadNotifications.size() + " unread notifications found!");
                for (UnreadNotification notification : unreadNotifications) {
                    String title = getApplicationContext().getString(notification.titleRes);

                    Bitmap image = null;
                    if (!notification.imageUrl.isEmpty()) {
                        image = ImageDownloader.downloadDownsampled(notification.imageUrl);
                    }

                    showPushNotification(title, notification.text, notification.text.hashCode(), image);
                }
            } else {
                Log.d(TAG, "No unread notifications.");
            }

            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch or parse notifications page.", e);
            return Result.failure();
        }
    }

    /** One unread entry on the notifications page. */
    @VisibleForTesting
    static final class UnreadNotification {
        /** E.g. "LordLica followed you". */
        final String text;
        /** The other user's avatar; empty when there is none. */
        final String imageUrl;
        @StringRes
        final int titleRes;

        UnreadNotification(String text, String imageUrl, @StringRes int titleRes) {
            this.text = text;
            this.imageUrl = imageUrl;
            this.titleRes = titleRes;
        }
    }

    /**
     * Reads the unread entries off the notifications page. Each entry is a .notification row;
     * unread ones carry an extra "unread" class, which also lights up their indicator dot.
     */
    @VisibleForTesting
    static List<UnreadNotification> parseUnread(Document doc) {
        List<UnreadNotification> notifications = new ArrayList<>();
        for (Element notification : doc.select(".notification.unread")) {
            String text = notification.select(".notification-body p").text();
            if (text.isEmpty()) {
                continue;
            }
            notifications.add(new UnreadNotification(
                    text,
                    notification.select(".avatar img").attr("src"),
                    titleFor(notification.select(".notification-icon i").attr("class"))));
        }
        return notifications;
    }

    /** Picks the notification title from the entry's Font Awesome icon. */
    @VisibleForTesting
    @StringRes
    static int titleFor(String iconClass) {
        List<String> classes = Arrays.asList(iconClass.trim().split("\\s+"));
        if (classes.contains("fa-star")) {
            return R.string.notification_title_badge;
        }
        // Font Awesome 6 renamed fa-user-friends to fa-user-group; the site now uses the new name.
        if (classes.contains("fa-user-group") || classes.contains("fa-user-friends")) {
            return R.string.notification_title_follower;
        }
        if (classes.contains("fa-heart")) {
            return R.string.notification_title_like;
        }
        if (classes.contains("fa-message") || classes.contains("fa-comment")) {
            return R.string.notification_title_comment;
        }
        return R.string.notification_title_default;
    }

    private void showPushNotification(String title, String contentText, int notificationId, Bitmap image) {
        Context context = getApplicationContext();
        createNotificationChannel(context);

        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra("urlToLoad", NOTIFICATION_URL);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_notification)
                .setColor(ContextCompat.getColor(context, R.color.back_pink))
                .setContentTitle(title)
                .setContentText(contentText)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);

        if (image != null) {
            builder.setLargeIcon(image);
        } else {
            builder.setStyle(new NotificationCompat.BigTextStyle().bigText(contentText));
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
            CharSequence name = context.getString(R.string.notification_channel_name);
            String description = context.getString(R.string.notification_channel_description);
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);

            NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
}