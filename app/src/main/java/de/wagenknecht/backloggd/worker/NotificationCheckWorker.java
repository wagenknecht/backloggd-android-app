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

import java.util.concurrent.TimeUnit;

import org.jsoup.Connection;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

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

            Elements unreadNotifications = doc.select(".notification.unread");

            if (!unreadNotifications.isEmpty()) {
                Log.i(TAG, unreadNotifications.size() + " unread notifications found!");
                for (Element notification : unreadNotifications) {
                    String notificationText = notification.select(".notification-body p").text();
                    String imageUrl = notification.select(".avatar img").attr("src");
                    String iconClass = notification.select(".notification-icon i").attr("class");
                    int titleRes;
                    if (iconClass.contains("fa-star")) {
                        titleRes = R.string.notification_title_badge;
                    } else if (iconClass.contains("fa-user-friends")) {
                        titleRes = R.string.notification_title_follower;
                    } else if (iconClass.contains("fa-heart")) {
                        titleRes = R.string.notification_title_like;
                    } else {
                        titleRes = R.string.notification_title_default;
                    }
                    String title = getApplicationContext().getString(titleRes);

                    Bitmap image = null;
                    if (imageUrl != null && !imageUrl.isEmpty()) {
                        image = ImageDownloader.downloadDownsampled(imageUrl);
                    }

                    if (!notificationText.isEmpty()) {
                        showPushNotification(title, notificationText, notificationText.hashCode(), image);
                    }
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