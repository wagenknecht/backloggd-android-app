package de.wagenknecht.backloggd.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Runs on a device, since the safety check relies on how Android itself parses URLs. */
@RunWith(AndroidJUnit4.class)
public class BackloggdUrlsTest {

    @Test
    public void loadsBackloggdPagesOverHttps() {
        assertTrue(BackloggdUrls.isSafeToLoad("https://backloggd.com/games/hollow-knight/"));
        assertTrue(BackloggdUrls.isSafeToLoad("https://www.backloggd.com/u/Tysk/"));
        assertTrue(BackloggdUrls.isSafeToLoad("HTTPS://BACKLOGGD.COM/"));
    }

    @Test
    public void refusesEverythingElse() {
        assertFalse(BackloggdUrls.isSafeToLoad(null));
        assertFalse(BackloggdUrls.isSafeToLoad("http://backloggd.com/"));
        assertFalse(BackloggdUrls.isSafeToLoad("javascript:alert(document.cookie)"));
        assertFalse(BackloggdUrls.isSafeToLoad("file:///data/data/"));
        assertFalse(BackloggdUrls.isSafeToLoad("https://example.com/"));
        assertFalse(BackloggdUrls.isSafeToLoad("https://backloggd.com.example.com/"));
        assertFalse(BackloggdUrls.isSafeToLoad("https://notbackloggd.com/"));
    }

    @Test
    public void refusesUrlsThatAndroidAndChromiumReadDifferently() {
        assertFalse(BackloggdUrls.isSafeToLoad("https://example.com\\@backloggd.com/"));
        assertFalse(BackloggdUrls.isSafeToLoad("https://user@backloggd.com/"));
        assertFalse(BackloggdUrls.isSafeToLoad("https://backloggd.com@example.com/"));
    }

    @Test
    public void buildsProfileUrls() {
        assertEquals("https://backloggd.com/u/Tysk", BackloggdUrls.profile("Tysk"));
        assertEquals("https://backloggd.com/u/Tysk/following/", BackloggdUrls.profileSection("Tysk", "following"));
    }

    @Test
    public void knowsWhenAPageBelongsToTheProfile() {
        assertTrue(BackloggdUrls.isOnProfileOf("/u/Tysk", "Tysk"));
        assertTrue(BackloggdUrls.isOnProfileOf("/u/Tysk/wishlist/", "Tysk"));
        assertFalse(BackloggdUrls.isOnProfileOf("/u/Tyskar/", "Tysk"));
        assertFalse(BackloggdUrls.isOnProfileOf("/u/Someone/", "Tysk"));
        assertFalse(BackloggdUrls.isOnProfileOf("/u/Tysk", null));
    }
}
