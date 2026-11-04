package de.wagenknecht.backloggd.util;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import de.wagenknecht.backloggd.R;

/**
 * The look the user picked in the app settings: the site's own colours, or one of the app's
 * shadcn/ui-based themes. It covers the app's own views and, through the stylesheets under
 * assets/web/, the Backloggd pages.
 */
public final class AppTheme {

    public static final String PREF_KEY = "app_theme";
    /** Values of the preference, as in arrays.xml. */
    public static final String BACKLOGGD = "backloggd";
    public static final String OLED = "oled";
    public static final String OLED_BACKLOGGD = "oled_backloggd";
    public static final String GREEN = "green";

    private AppTheme() {}

    @NonNull
    public static String get(@NonNull Context context) {
        return known(PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_KEY, BACKLOGGD));
    }

    /** The stored value, or the site's own look for one this version does not know. */
    @NonNull
    static String known(@Nullable String theme) {
        if (OLED.equals(theme) || OLED_BACKLOGGD.equals(theme) || GREEN.equals(theme)) {
            return theme;
        }
        return BACKLOGGD;
    }

    /**
     * Applies the picked theme on top of the one from the manifest. Call before the activity
     * inflates any view, and return what it gives to compare later with {@link #get}.
     */
    @NonNull
    public static String apply(@NonNull Activity activity) {
        String theme = get(activity);
        switch (theme) {
            case OLED:
                activity.setTheme(R.style.Theme_Backloggd_Oled);
                break;
            case OLED_BACKLOGGD:
                activity.setTheme(R.style.Theme_Backloggd_Oled_Backloggd);
                break;
            case GREEN:
                activity.setTheme(R.style.Theme_Backloggd_Green);
                break;
        }
        return theme;
    }

    /** The stylesheets under assets/ that restyle Backloggd pages for a theme, in order. */
    @NonNull
    static String[] webStylesheets(@NonNull String theme) {
        switch (theme) {
            case OLED:
                return new String[]{
                        "web/theme-oled.css", "web/theme-oled-mono.css", "web/theme-shadcn.css"};
            case OLED_BACKLOGGD:
                return new String[]{
                        "web/theme-oled.css", "web/theme-oled-backloggd.css", "web/theme-shadcn.css"};
            case GREEN:
                return new String[]{"web/theme-green.css", "web/theme-shadcn.css"};
            default:
                return new String[0];
        }
    }

    @NonNull
    public static String[] webStylesheets(@NonNull Context context) {
        return webStylesheets(get(context));
    }
}
