package de.wagenknecht.backloggd.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.jsoup.Jsoup;
import org.junit.Test;

import java.util.List;

public class WishlistCheckerWorkerTest {

    /** One game column as the wishlist page renders it, trimmed to the parts that matter. */
    private static String column(String title, String date, String cover) {
        return "<div class=\"col col-cus-user-games px-1 mt-2\">"
                + "<div class=\"card mx-auto game-cover quick-access\" game_id=\"1\">"
                + "<a href=\"/games/x/\" class=\"cover-link\"></a>"
                + "<div class=\"overflow-wrapper\"><img class=\"card-img height\" src=\"" + cover + "\" alt=\"" + title + "\"></div>"
                + "<div class=\"game-text-centered\">" + title + "</div>"
                + "</div>"
                + "<div class=\"d-none\" id=\"more-button-container-1\"><div class=\"quick-access-bar-more\">"
                + "<a class=\"quick-journal\"><div class=\"row\"><div class=\"col\"><p>Edit log</p></div></div></a>"
                + "</div></div>"
                + "<div><p>" + date + "</p></div>"
                + "</div>";
    }

    private static String page(String... columns) {
        return "<html><head><title>Tysk's games | Backloggd</title></head><body>"
                + "<div id=\"game-lists\" class=\"row my-0 show-release\"><div class=\"col\"><div class=\"row mx-n1\">"
                + "<div id=\"user-games-container\" class=\"col\"><div id=\"user_games\" class=\"row justify-content-center\">"
                + String.join("", columns)
                + "</div></div></div></div></div></body></html>";
    }

    @Test
    public void readsTitleDateAndCoverOfEachGame() {
        List<WishlistCheckerWorker.WishlistGame> games = WishlistCheckerWorker.parseWishlist(Jsoup.parse(page(
                column("Control Resonant", "Sep 24, 2026", "https://images.igdb.com/c.jpg"),
                column("Grand Theft Auto VI", "Nov 19, 2026", "https://images.igdb.com/g.jpg"))));

        assertEquals(2, games.size());
        assertEquals("Control Resonant", games.get(0).title);
        assertEquals("Sep 24, 2026", games.get(0).releaseDate);
        assertEquals("https://images.igdb.com/c.jpg", games.get(0).imageUrl);
        assertEquals("Grand Theft Auto VI", games.get(1).title);
    }

    @Test
    public void skipsTheHiddenMoreMenu() {
        List<WishlistCheckerWorker.WishlistGame> games = WishlistCheckerWorker.parseWishlist(Jsoup.parse(page(
                column("Pragmata", "Apr 17, 2026", "p.jpg"))));

        // The menu's "Edit log" paragraph comes first in the column but is not a date.
        assertEquals("Apr 17, 2026", games.get(0).releaseDate);
    }

    @Test
    public void keepsYearAndQuarterOnlyDates() {
        List<WishlistCheckerWorker.WishlistGame> games = WishlistCheckerWorker.parseWishlist(Jsoup.parse(page(
                column("Moomin Midsummer Madness", "2026", "m.jpg"),
                column("Tiny Delivery", "2026 Q3", "t.jpg"))));

        assertEquals("2026", games.get(0).releaseDate);
        assertEquals("2026 Q3", games.get(1).releaseDate);
    }

    @Test
    public void emptyWhenTheGamesContainerIsMissing() {
        assertTrue(WishlistCheckerWorker.parseWishlist(Jsoup.parse(
                "<html><body><div id=\"user-games-library-container\"></div></body></html>")).isEmpty());
    }

    private static final String WISHLIST_URL =
            "https://backloggd.com/u/Tysk/games/wishlist/by/release/type:wishlist;release_year:2026/";

    /** The pagination below the games, as the site renders it; prev and next are either links or disabled. */
    private static String pagination(String nextLink) {
        return "<nav class=\"pagy nav\" aria-label=\"Pages\">"
                + "<a role=\"link\" aria-disabled=\"true\" aria-label=\"Previous\">‹&nbsp;Prev</a>"
                + "<a role=\"link\" aria-disabled=\"true\" aria-current=\"page\" class=\"current\">1</a>"
                + (nextLink != null
                        ? "<a href=\"" + nextLink + "\">2</a><a href=\"" + nextLink + "\" aria-label=\"Next\">Next&nbsp;›</a>"
                        : "<a role=\"link\" aria-disabled=\"true\" aria-label=\"Next\">Next&nbsp;›</a>")
                + "</nav>";
    }

    @Test
    public void followsTheNextLinkToTheFollowingPage() {
        String nextPage = WishlistCheckerWorker.nextPageUrl(Jsoup.parse(
                page(column("A", "Dec 10, 2026", "a.jpg")) + pagination(
                        "/u/Tysk/games/wishlist/by/release/type:wishlist;release_year:2026?page=2"),
                WISHLIST_URL));

        assertEquals("https://backloggd.com/u/Tysk/games/wishlist/by/release/type:wishlist;release_year:2026?page=2", nextPage);
    }

    @Test
    public void stopsOnTheLastPage() {
        assertNull(WishlistCheckerWorker.nextPageUrl(Jsoup.parse(
                page(column("A", "Dec 10, 2026", "a.jpg")) + pagination(null), WISHLIST_URL)));
    }

    @Test
    public void stopsWithoutPagination() {
        assertNull(WishlistCheckerWorker.nextPageUrl(Jsoup.parse(
                page(column("A", "Dec 10, 2026", "a.jpg")), WISHLIST_URL)));
    }

    private static final long HOUR = 60 * 60 * 1000L;
    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void movesADailyRunPlannedForAnotherClock() {
        // Planned for 9:00 before daylight saving time ended; 9:00 is now an hour later.
        assertTrue(WishlistCheckerWorker.isMisplacedDailyRun(true, 0, NOW + 5 * HOUR, NOW + 6 * HOUR, NOW));
    }

    @Test
    public void leavesADailyRunAtTheReminderTime() {
        assertFalse(WishlistCheckerWorker.isMisplacedDailyRun(true, 0, NOW + 5 * HOUR, NOW + 5 * HOUR, NOW));
        assertFalse("seconds apart", WishlistCheckerWorker.isMisplacedDailyRun(true, 0, NOW + 5 * HOUR + 30_000, NOW + 5 * HOUR, NOW));
    }

    @Test
    public void leavesRetriesAndOverdueRunsAlone() {
        // Each still has today's reminders to deliver; moving it would skip them.
        assertFalse("retry", WishlistCheckerWorker.isMisplacedDailyRun(false, 0, NOW + HOUR / 2, NOW + 20 * HOUR, NOW));
        assertFalse("backing off", WishlistCheckerWorker.isMisplacedDailyRun(true, 2, NOW + HOUR, NOW + 20 * HOUR, NOW));
        assertFalse("waiting for a connection", WishlistCheckerWorker.isMisplacedDailyRun(true, 0, NOW - HOUR, NOW + 23 * HOUR, NOW));
    }

    @Test
    public void neverFollowsALinkToAnotherSite() {
        // The request for the next page carries the login cookies.
        assertNull(WishlistCheckerWorker.nextPageUrl(Jsoup.parse(
                page(column("A", "Dec 10, 2026", "a.jpg")) + pagination("https://example.com/?page=2"),
                WISHLIST_URL)));
    }
}
