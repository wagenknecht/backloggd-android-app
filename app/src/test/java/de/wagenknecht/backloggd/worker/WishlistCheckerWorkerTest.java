package de.wagenknecht.backloggd.worker;

import static org.junit.Assert.assertEquals;
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
}
