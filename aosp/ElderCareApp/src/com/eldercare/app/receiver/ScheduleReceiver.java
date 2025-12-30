/*
 * Copyright 2024 ElderCare AI
 *
 * Schedule Receiver - Triggers voice check-ins at scheduled times
 */
package com.eldercare.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.eldercare.app.service.ElderCareService;

/**
 * Receives alarms from AlarmManager and starts voice check-in sessions.
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

        // Start the ElderCare service to conduct the check-in
        Intent serviceIntent = new Intent(context, ElderCareService.class);
        serviceIntent.putExtra(EXTRA_SCHEDULE_ID, scheduleId);
        serviceIntent.putExtra(EXTRA_PROMPT, prompt);

        context.startForegroundService(serviceIntent);

        // Schedule the next occurrence
        scheduleNext(context, scheduleId);
    }

    private void scheduleNext(Context context, String scheduleId) {
        // TODO: Calculate next occurrence based on cron expression
        // and set AlarmManager alarm
    }
}
