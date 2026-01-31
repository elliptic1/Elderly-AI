/*
 * Copyright 2024 ElderCare AI
 *
 * ElderCare Application - Main application class
 *
 * Initializes scheduling, notifications, and other app-wide components.
 */
package com.eldercare.app;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.util.Log;

import com.eldercare.app.schedule.ScheduleManager;

/**
 * Main application class for ElderCare.
 *
 * Responsibilities:
 * - Initialize notification channels
 * - Set up default schedules
 * - Initialize scheduling system
 */
public class ElderCareApplication extends Application {

    private static final String TAG = "ElderCareApp";

    // Notification channel IDs
    public static final String CHANNEL_SERVICE = "eldercare_service";
    public static final String CHANNEL_ALERTS = "eldercare_alerts";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "ElderCare Application starting...");

        initializeNotificationChannels();
        initializeScheduling();

        Log.i(TAG, "ElderCare Application initialized");
    }

    private void initializeNotificationChannels() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            Log.e(TAG, "NotificationManager not available");
            return;
        }

        // Foreground service channel (low importance, for ongoing check-ins)
        NotificationChannel serviceChannel = new NotificationChannel(
            CHANNEL_SERVICE,
            "Check-in Active",
            NotificationManager.IMPORTANCE_LOW
        );
        serviceChannel.setDescription("Shown during voice check-in sessions");
        serviceChannel.setShowBadge(false);
        manager.createNotificationChannel(serviceChannel);

        // Alerts channel (high importance, for emergencies and missed check-ins)
        NotificationChannel alertsChannel = new NotificationChannel(
            CHANNEL_ALERTS,
            "Alerts",
            NotificationManager.IMPORTANCE_HIGH
        );
        alertsChannel.setDescription("Important alerts requiring attention");
        alertsChannel.setShowBadge(true);
        alertsChannel.enableVibration(true);
        manager.createNotificationChannel(alertsChannel);

        Log.i(TAG, "Notification channels created");
    }

    private void initializeScheduling() {
        try {
            ScheduleManager scheduleManager = new ScheduleManager(this);

            // Create default schedules if none exist
            scheduleManager.initializeDefaultSchedules();

            // Reschedule all alarms (in case app was updated or reinstalled)
            scheduleManager.rescheduleAllAlarms();

            Log.i(TAG, "Scheduling initialized");

        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize scheduling", e);
        }
    }
}
