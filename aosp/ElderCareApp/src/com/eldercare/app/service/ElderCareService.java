/*
 * Copyright 2024 ElderCare AI
 *
 * ElderCare Service - Main foreground service for voice check-ins
 *
 * This service:
 * - Runs as a foreground service during voice sessions
 * - Manages WebSocket connection to OpenAI Realtime API
 * - Handles audio capture and playback
 * - Syncs schedules from Firebase
 */
package com.eldercare.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Foreground service that manages voice check-in sessions.
 *
 * Lifecycle:
 * 1. Started by ScheduleReceiver when a check-in is due
 * 2. Connects to OpenAI Realtime API via WebSocket
 * 3. Captures audio, sends to API, plays responses
 * 4. Ends after timeout or conversation completion
 * 5. Reports summary to Firebase
 */
public class ElderCareService extends Service {

    private static final String TAG = "ElderCareService";
    private static final String CHANNEL_ID = "eldercare_service";
    private static final int NOTIFICATION_ID = 1;

    // Session configuration (matches Raspberry Pi implementation)
    private static final int MAX_SESSION_DURATION_MS = 3 * 60 * 1000; // 3 minutes
    private static final int SILENCE_TIMEOUT_MS = 20 * 1000; // 20 seconds

    // Audio configuration: 16-bit 16kHz mono PCM (matches OpenAI Realtime API)
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNELS = 1;
    private static final int BITS_PER_SAMPLE = 16;

    private boolean isSessionActive = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "ElderCareService created");
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "ElderCareService starting check-in session");

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification("Starting check-in..."));

        // Start voice session
        startVoiceSession();

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "ElderCareService destroyed");
        stopVoiceSession();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "ElderCare Check-in",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Active during voice check-in sessions");

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification(String message) {
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ElderCare")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build();
    }

    private void startVoiceSession() {
        if (isSessionActive) {
            Log.w(TAG, "Session already active, ignoring start request");
            return;
        }

        isSessionActive = true;

        // TODO: Implement voice session:
        // 1. Initialize AudioRecord for microphone input
        // 2. Initialize AudioTrack for speaker output
        // 3. Connect to OpenAI Realtime API via WebSocket
        // 4. Start audio capture/playback loop
        // 5. Handle conversation flow
        // 6. Respect timeouts (MAX_SESSION_DURATION_MS, SILENCE_TIMEOUT_MS)
    }

    private void stopVoiceSession() {
        if (!isSessionActive) {
            return;
        }

        isSessionActive = false;

        // TODO: Cleanup:
        // 1. Close WebSocket connection
        // 2. Stop audio capture/playback
        // 3. Generate session summary
        // 4. Upload summary to Firebase
        // 5. Stop foreground service
    }
}
