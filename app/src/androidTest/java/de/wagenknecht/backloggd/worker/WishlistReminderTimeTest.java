package de.wagenknecht.backloggd.worker;

import static org.junit.Assert.assertEquals;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class WishlistReminderTimeTest {

    private Context context;
    private int hourBefore;
    private int minuteBefore;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        hourBefore = WishlistCheckerWorker.getReminderHour(context);
        minuteBefore = WishlistCheckerWorker.getReminderMinute(context);
    }

    @After
    public void tearDown() {
        WishlistCheckerWorker.setReminderTime(context, hourBefore, minuteBefore);
    }

    @Test
    public void storesTheTimeAndMovesTheNextCheckToIt() throws Exception {
        Calendar inTwoHours = Calendar.getInstance();
        inTwoHours.add(Calendar.HOUR_OF_DAY, 2);
        int hour = inTwoHours.get(Calendar.HOUR_OF_DAY);
        int minute = inTwoHours.get(Calendar.MINUTE);

        WishlistCheckerWorker.setReminderTime(context, hour, minute);

        assertEquals(hour, WishlistCheckerWorker.getReminderHour(context));
        assertEquals(minute, WishlistCheckerWorker.getReminderMinute(context));

        List<WorkInfo> work = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WishlistCheckerWorker.WORK_NAME).get();
        WorkInfo waiting = null;
        for (WorkInfo info : work) {
            if (info.getState() == WorkInfo.State.ENQUEUED) {
                waiting = info;
            }
        }
        Calendar due = Calendar.getInstance();
        due.setTimeInMillis(waiting.getNextScheduleTimeMillis());
        assertEquals(hour, due.get(Calendar.HOUR_OF_DAY));
        assertEquals(minute, due.get(Calendar.MINUTE));
    }
}
