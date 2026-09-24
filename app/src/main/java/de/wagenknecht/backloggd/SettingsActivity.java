package de.wagenknecht.backloggd;

import android.content.Intent;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.Menu;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import de.wagenknecht.backloggd.util.SystemBars;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemBars.blendIn(this);
        setContentView(R.layout.activity_settings);

        // On a recreation the fragment manager restores the fragment itself.
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings_content, new SettingsFragment())
                    .commit();
        }

        // The theme has no action bar of its own; the toolbar takes its place and shows the
        // activity label with a back arrow.
        setSupportActionBar(findViewById(R.id.toolbar));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        BottomNavigationView bottomNav = findViewById(R.id.bottomNav);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settings_root), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            bottomNav.setPadding(0, 0, 0, systemBars.bottom);
            return insets;
        });

        // The settings are none of the tabs. BottomNavigationView checks the first item by default,
        // which marked Home as active and turned a tap on it into a reselect that did nothing.
        Menu menu = bottomNav.getMenu();
        int group = menu.getItem(0).getGroupId();
        menu.setGroupCheckable(group, true, false);
        for (int i = 0; i < menu.size(); i++) {
            menu.getItem(i).setChecked(false);
        }
        menu.setGroupCheckable(group, true, true);

        bottomNav.setOnItemSelectedListener(item -> {
            bottomNav.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            String action = null;
            int id = item.getItemId();
            if (id == R.id.nav_home) action = "home";
            else if (id == R.id.nav_log_game) action = "log_game";
            else if (id == R.id.nav_search) action = "search";
            else if (id == R.id.nav_profile) action = "profile";
            else if (id == R.id.nav_more) action = "more";

            if (action == null) return false;

            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra("postLaunchAction", action);
            startActivity(intent);
            finish();
            return false;
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
