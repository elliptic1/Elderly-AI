/*
 * Copyright 2024 ElderCare AI
 *
 * Boot Receiver - Starts ElderCare on device boot
 */
package com.eldercare.app.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.eldercare.app.schedule.ScheduleManager;

/**
 * Receives BOOT_COMPLETED broadcast and initializes ElderCare scheduling.
 *
 * As a system app with directBootAware=true, this also receives
 * LOCKED_BOOT_COMPLETED for devices with file-based encryption.
 *
 * On boot, this receiver:
 * 1. Creates default schedules if none exist
 * 2. Reschedules all alarms
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "ElderCareBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "Boot receiver triggered: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {

            // Initialize schedule sync and alarms
            initializeScheduling(context);
        }
    }

    private void initializeScheduling(Context context) {
        Log.i(TAG, "Initializing ElderCare scheduling");

        try {
            ScheduleManager scheduleManager = new ScheduleManager(context);

            // Create default schedules if none exist
            scheduleManager.initializeDefaultSchedules();

            // Reschedule all alarms
            scheduleManager.rescheduleAllAlarms();

            Log.i(TAG, "Scheduling initialized successfully");

            // Start remote config service to sync from cloud
            startRemoteConfigService(context);

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize scheduling", e);
        }
    }

    private void startRemoteConfigService(Context context) {
        try {
            Intent serviceIntent = new Intent(context, 
                com.eldercare.app.config.RemoteConfigService.class);
            context.startService(serviceIntent);
            Log.i(TAG, "Remote config service started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start remote config service", e);
        }
    }
}
