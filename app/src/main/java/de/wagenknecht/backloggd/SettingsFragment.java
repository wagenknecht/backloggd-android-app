package de.wagenknecht.backloggd;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import java.util.Locale;

import de.wagenknecht.backloggd.BuildConfig;
import de.wagenknecht.backloggd.worker.NotificationCheckWorker;
import de.wagenknecht.backloggd.worker.WishlistCheckerWorker;

public class SettingsFragment extends PreferenceFragmentCompat {

    private static final String TIME_PICKER_TAG = "reminder_time_picker";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);

        Preference dailyNotificationTime = findPreference("daily_notification_time");
        if (dailyNotificationTime != null) {
            updateDailyNotificationTimeSummary(dailyNotificationTime);

            // A picker that survived a rotation lost its listener along with the old fragment.
            Fragment openPicker = getChildFragmentManager().findFragmentByTag(TIME_PICKER_TAG);
            if (openPicker instanceof MaterialTimePicker) {
                listenForReminderTime((MaterialTimePicker) openPicker, dailyNotificationTime);
            }

            dailyNotificationTime.setOnPreferenceClickListener(preference -> {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
                MaterialTimePicker picker = new MaterialTimePicker.Builder()
                        .setTimeFormat(TimeFormat.CLOCK_24H)
                        .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                        .setHour(prefs.getInt("daily_notification_hour", 9))
                        .setMinute(prefs.getInt("daily_notification_minute", 0))
                        .setTitleText(preference.getTitle())
                        .build();
                listenForReminderTime(picker, preference);
                picker.show(getChildFragmentManager(), TIME_PICKER_TAG);
                return true;
            });
        }

        ListPreference notificationInterval = findPreference("notification_interval");
        if (notificationInterval != null) {
            notificationInterval.setOnPreferenceChangeListener((preference, newValue) -> {
                NotificationCheckWorker.schedule(requireContext(), Long.parseLong((String) newValue));
                return true;
            });
        }

        Preference developerLink = findPreference("developer_link");
        if (developerLink != null) {
            developerLink.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), MainActivity.class);
                intent.putExtra("urlToLoad", "https://backloggd.com/u/Tysk/");
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                requireActivity().finish();
                return true;
            });
        }

        setupDebugCategory();
    }

    private void setupDebugCategory() {
        PreferenceCategory debugCategory = findPreference("debug_category");
        if (debugCategory == null) {
            return;
        }
        if (!BuildConfig.DEBUG) {
            getPreferenceScreen().removePreference(debugCategory);
            return;
        }

        Preference runNotif = findPreference("debug_run_notification_worker");
        if (runNotif != null) {
            runNotif.setOnPreferenceClickListener(p -> {
                WorkManager.getInstance(requireContext()).enqueue(
                        new OneTimeWorkRequest.Builder(NotificationCheckWorker.class).build());
                Toast.makeText(requireContext(), "NotificationCheckWorker enqueued", Toast.LENGTH_SHORT).show();
                return true;
            });
        }

        Preference runWishlist = findPreference("debug_run_wishlist_worker");
        if (runWishlist != null) {
            runWishlist.setOnPreferenceClickListener(p -> {
                WorkManager.getInstance(requireContext()).enqueue(
                        new OneTimeWorkRequest.Builder(WishlistCheckerWorker.class).build());
                Toast.makeText(requireContext(), "WishlistCheckerWorker enqueued", Toast.LENGTH_SHORT).show();
                return true;
            });
        }
    }

    private void listenForReminderTime(MaterialTimePicker picker, Preference preference) {
        picker.addOnPositiveButtonClickListener(v -> {
            PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                    .putInt("daily_notification_hour", picker.getHour())
                    .putInt("daily_notification_minute", picker.getMinute())
                    .apply();
            updateDailyNotificationTimeSummary(preference);
            WishlistCheckerWorker.scheduleNextWorker(requireContext());
        });
    }

    private void updateDailyNotificationTimeSummary(Preference preference) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        int hour = prefs.getInt("daily_notification_hour", 9);
        int minute = prefs.getInt("daily_notification_minute", 0);
        preference.setSummary(String.format(Locale.getDefault(), "%02d:%02d", hour, minute));
    }

}
