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

/**
 * Receives BOOT_COMPLETED broadcast and initializes ElderCare scheduling.
 *
 * As a system app with directBootAware=true, this also receives
 * LOCKED_BOOT_COMPLETED for devices with file-based encryption.
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
        // TODO: Implement:
        // 1. Sync schedules from Firebase
        // 2. Set up AlarmManager for next scheduled check-in
        // 3. Register Firestore listener for schedule changes
        Log.i(TAG, "Scheduling initialized");
    }
}
