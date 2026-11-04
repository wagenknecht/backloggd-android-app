package de.wagenknecht.backloggd.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AppThemeTest {

    @Test
    public void keepsTheKnownThemes() {
        assertEquals(AppTheme.OLED, AppTheme.known("oled"));
        assertEquals(AppTheme.OLED_BACKLOGGD, AppTheme.known("oled_backloggd"));
        assertEquals(AppTheme.GREEN, AppTheme.known("green"));
        assertEquals(AppTheme.BACKLOGGD, AppTheme.known("backloggd"));
    }

    @Test
    public void fallsBackToTheSiteForAnUnknownTheme() {
        // E.g. one a newer version stored before a downgrade, or nothing stored yet.
        assertEquals(AppTheme.BACKLOGGD, AppTheme.known("sepia"));
        assertEquals(AppTheme.BACKLOGGD, AppTheme.known(null));
    }

    @Test
    public void theSiteNeedsNoStylesheets() {
        assertEquals(0, AppTheme.webStylesheets(AppTheme.BACKLOGGD).length);
    }

    @Test
    public void theSharedRulesComeAfterTheThemesColours() {
        assertArrayEquals(
                new String[]{"web/theme-oled.css", "web/theme-oled-mono.css", "web/theme-shadcn.css"},
                AppTheme.webStylesheets(AppTheme.OLED));
        assertArrayEquals(
                new String[]{"web/theme-oled.css", "web/theme-oled-backloggd.css", "web/theme-shadcn.css"},
                AppTheme.webStylesheets(AppTheme.OLED_BACKLOGGD));
        assertArrayEquals(new String[]{"web/theme-green.css", "web/theme-shadcn.css"},
                AppTheme.webStylesheets(AppTheme.GREEN));
    }
}
