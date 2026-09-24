package de.wagenknecht.backloggd.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.jsoup.Jsoup;
import org.junit.Test;

import java.util.List;

import de.wagenknecht.backloggd.R;

public class NotificationCheckWorkerTest {

    /** One entry as the notifications page renders it; unread ones get the extra class. */
    private static String entry(boolean unread, String icon, String user, String action) {
        return "<div class=\"row notification py-2 " + (unread ? "unread" : "") + "\">"
                + "<div class=\"col-auto mb-auto mt-1 pr-0\"><div class=\"notification-icon\">"
                + "<i class=\"fa-solid " + icon + "\"></i><span class=\"notification-indicator\"></span></div></div>"
                + "<div class=\"col\"><div class=\"row\">"
                + "<div class=\"col-auto pr-0\"><a href=\"/u/" + user + "/\"><div class=\"avatar\"><img src=\"https://example.com/" + user + ".jpg\"></div></a></div>"
                + "<div class=\"col pl-2 notification-body\"><p class=\"mt-1\"><a href=\"/u/" + user + "/\">" + user + "</a> " + action + "</p></div>"
                + "</div></div>"
                + "<div class=\"col-12 col-md-auto mb-auto mt-1\"><p class=\"subtitle-text mb-0 text-right\">5 days ago</p></div>"
                + "</div>";
    }

    private static List<NotificationCheckWorker.UnreadNotification> parse(String... entries) {
        return NotificationCheckWorker.parseUnread(Jsoup.parse("<html><body>" + String.join("", entries) + "</body></html>"));
    }

    @Test
    public void readsTextAndAvatarOfUnreadEntries() {
        List<NotificationCheckWorker.UnreadNotification> unread = parse(
                entry(true, "fa-user-group", "LordLica", "followed you"));

        assertEquals(1, unread.size());
        // The "5 days ago" paragraph sits outside the body and must not end up in the text.
        assertEquals("LordLica followed you", unread.get(0).text);
        assertEquals("https://example.com/LordLica.jpg", unread.get(0).imageUrl);
    }

    @Test
    public void ignoresReadEntries() {
        assertTrue(parse(entry(false, "fa-heart", "Someone", "liked your review")).isEmpty());
        assertEquals(1, parse(
                entry(false, "fa-heart", "Someone", "liked your review"),
                entry(true, "fa-heart", "Other", "liked your list")).size());
    }

    @Test
    public void titleFollowsTheIcon() {
        assertEquals(R.string.notification_title_follower, NotificationCheckWorker.titleFor("fa-solid fa-user-group"));
        assertEquals(R.string.notification_title_follower, NotificationCheckWorker.titleFor("fas fa-user-friends"));
        assertEquals(R.string.notification_title_like, NotificationCheckWorker.titleFor("fa-solid fa-heart"));
        assertEquals(R.string.notification_title_comment, NotificationCheckWorker.titleFor("fa-solid fa-message"));
        assertEquals(R.string.notification_title_badge, NotificationCheckWorker.titleFor("fa-solid fa-star"));
    }

    @Test
    public void unknownOrMissingIconFallsBackToDefault() {
        assertEquals(R.string.notification_title_default, NotificationCheckWorker.titleFor(""));
        assertEquals(R.string.notification_title_default, NotificationCheckWorker.titleFor("fa-solid fa-rocket"));
        // Matches whole class names only, so fa-heart-crack is not a like.
        assertEquals(R.string.notification_title_default, NotificationCheckWorker.titleFor("fa-solid fa-heart-crack"));
    }
}
