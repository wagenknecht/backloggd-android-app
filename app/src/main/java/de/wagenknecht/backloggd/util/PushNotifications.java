package de.wagenknecht.backloggd.util;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import de.wagenknecht.backloggd.MainActivity;
import de.wagenknecht.backloggd.R;

/** Shows the app's notifications, which all look alike and open the app when tapped. */
public final class PushNotifications {

    /** The app's notification channels; users can switch each off on its own. */
    public enum Channel {
        BACKLOGGD("BACKLOGGD_NOTIFICATIONS",
                R.string.notification_channel_name, R.string.notification_channel_description),
        WISHLIST("BACKLOGGD_WISHLIST_RELEASE",
                R.string.wishlist_channel_name, R.string.wishlist_channel_description);

        final String id;
        @StringRes
        final int name;
        @StringRes
        final int description;

        Channel(String id, @StringRes int name, @StringRes int description) {
            this.id = id;
            this.name = name;
            this.description = description;
        }
    }

    private PushNotifications() {}

    /** False when the permission is missing, or the app or this channel is switched off. */
    public static boolean canPost(@NonNull Context context, @NonNull Channel channel) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                || !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // The channel only exists once created, and a new one is never switched off.
            createChannel(context, channel);
            NotificationChannel created = context.getSystemService(NotificationManager.class)
                    .getNotificationChannel(channel.id);
            return created != null && created.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    /**
     * Shows a notification, replacing an earlier one with the same id. Tapping it opens the app,
     * on the given Backloggd page if there is one.
     *
     * @return false when it could not be shown because notifications are not allowed
     */
    public static boolean post(@NonNull Context context, @NonNull Channel channel, int id,
                               @NonNull String title, @NonNull String text,
                               @Nullable Bitmap largeIcon, @Nullable String urlToOpen) {
        if (!canPost(context, channel)) {
            return false;
        }

        Intent intent = new Intent(context, MainActivity.class);
        if (urlToOpen != null) {
            intent.putExtra("urlToLoad", urlToOpen);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Long texts, such as a comment, expand instead of being cut off after one line.
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channel.id)
                .setSmallIcon(R.mipmap.ic_notification)
                .setColor(ContextCompat.getColor(context, R.color.back_pink))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true);
        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon);
        }

        // canPost checked the permission; lint cannot see that across the call.
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build());
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Creates the channel, or updates its name and description; the user's settings stay. */
    private static void createChannel(Context context, Channel channel) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel notificationChannel = new NotificationChannel(channel.id,
                    context.getString(channel.name), NotificationManager.IMPORTANCE_DEFAULT);
            notificationChannel.setDescription(context.getString(channel.description));
            context.getSystemService(NotificationManager.class).createNotificationChannel(notificationChannel);
        }
    }
}
