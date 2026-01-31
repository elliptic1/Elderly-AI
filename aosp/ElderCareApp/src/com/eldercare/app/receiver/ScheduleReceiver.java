/*
 * Copyright 2024 ElderCare AI
 *
 * Schedule Receiver - Triggers voice check-ins at scheduled times
 */
package com.eldercare.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

import com.eldercare.app.schedule.ScheduleManager;
import com.eldercare.app.service.ElderCareService;

/**
 * Receives alarms from AlarmManager and starts voice check-in sessions.
 *
 * This receiver:
 * 1. Acquires a wake lock to ensure the device stays awake
 * 2. Starts the ElderCareService with schedule parameters
 * 3. Schedules the next occurrence of this check-in
 */
public class ScheduleReceiver extends BroadcastReceiver {

    private static final String TAG = "ElderCareSchedule";

    public static final String ACTION_CHECK_IN = "com.eldercare.app.ACTION_CHECK_IN";
    public static final String EXTRA_SCHEDULE_ID = "schedule_id";
    public static final String EXTRA_PROMPT = "prompt";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_CHECK_IN.equals(intent.getAction())) {
            return;
        }

        String scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID);
        String prompt = intent.getStringExtra(EXTRA_PROMPT);

        Log.i(TAG, "Check-in triggered for schedule: " + scheduleId);

        // Acquire wake lock to keep device awake during service start
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "ElderCare:CheckIn"
        );
        wakeLock.acquire(60000); // 1 minute max

        try {
            // Get API key from ScheduleManager
            ScheduleManager scheduleManager = new ScheduleManager(context);
            String apiKey = scheduleManager.getApiKey();

            if (apiKey == null || apiKey.isEmpty()) {
                Log.e(TAG, "No API key configured, skipping check-in");
                return;
            }

            // Start the ElderCare service to conduct the check-in
            Intent serviceIntent = new Intent(context, ElderCareService.class);
            serviceIntent.putExtra(ElderCareService.EXTRA_SCHEDULE_ID, scheduleId);
            serviceIntent.putExtra(ElderCareService.EXTRA_PROMPT, prompt);
            serviceIntent.putExtra(ElderCareService.EXTRA_API_KEY, apiKey);

            context.startForegroundService(serviceIntent);

            // Schedule the next occurrence
            scheduleManager.rescheduleAllAlarms();

        } finally {
            wakeLock.release();
        }
    }
}
