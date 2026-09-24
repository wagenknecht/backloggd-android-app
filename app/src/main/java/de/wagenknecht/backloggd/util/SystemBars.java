package de.wagenknecht.backloggd.util;

import android.graphics.Color;
import android.os.Build;

import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.annotation.NonNull;

/**
 * Lets the status and navigation bars blend into the app instead of looking like separate OS
 * chrome: the app draws behind them, and they keep light icons on the dark background.
 */
public final class SystemBars {

    private SystemBars() {}

    public static void blendIn(@NonNull ComponentActivity activity) {
        // dark() means light icons, whatever the system's own dark mode says.
        EdgeToEdge.enable(activity,
                SystemBarStyle.dark(Color.TRANSPARENT),
                SystemBarStyle.dark(Color.TRANSPARENT));

        // Without this, button navigation gets a translucent scrim over the bottom nav.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.getWindow().setNavigationBarContrastEnforced(false);
        }
    }
}
