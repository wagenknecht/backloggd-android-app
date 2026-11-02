package de.wagenknecht.backloggd.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import androidx.core.app.NotificationCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PushNotificationsTest {

    private static final int ID = 4711;

    private Context context;
    private NotificationManager manager;

    @Before
    public void setUp() {
        // The permission only exists from Android 13 on; before, granting it throws.
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU);
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        manager = context.getSystemService(NotificationManager.class);
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .grantRuntimePermission(context.getPackageName(), Manifest.permission.POST_NOTIFICATIONS);
    }

    @After
    public void tearDown() {
        if (manager != null) {
            manager.cancel(ID);
        }
    }

    @Test
    public void postsOnTheGivenChannelWithTitleAndExpandableText() {
        assertTrue(PushNotifications.canPost(context, PushNotifications.Channel.WISHLIST));
        assertTrue(PushNotifications.post(context, PushNotifications.Channel.WISHLIST, ID,
                "Releasing Today!", "Some Game releases today.", null, null));

        Notification notification = find(ID);
        assertEquals("BACKLOGGD_WISHLIST_RELEASE", notification.getChannelId());
        assertEquals("Releasing Today!", notification.extras.getString(NotificationCompat.EXTRA_TITLE));
        assertEquals("Some Game releases today.",
                notification.extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT).toString());
        assertNotNull(notification.contentIntent);
    }

    @Test
    public void createsBothChannelsWithTheirNames() {
        PushNotifications.canPost(context, PushNotifications.Channel.BACKLOGGD);
        PushNotifications.canPost(context, PushNotifications.Channel.WISHLIST);

        assertEquals("Backloggd Notifications",
                manager.getNotificationChannel("BACKLOGGD_NOTIFICATIONS").getName().toString());
        assertEquals("Wishlist Game Releases",
                manager.getNotificationChannel("BACKLOGGD_WISHLIST_RELEASE").getName().toString());
    }

    private Notification find(int id) {
        for (StatusBarNotification active : manager.getActiveNotifications()) {
            if (active.getId() == id) {
                return active.getNotification();
            }
        }
        throw new AssertionError("Notification " + id + " is not showing");
    }
}
