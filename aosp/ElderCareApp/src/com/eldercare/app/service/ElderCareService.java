/*
 * Copyright 2024 ElderCare AI
 *
 * ElderCare Service - Main foreground service for voice check-ins
 *
 * This service:
 * - Runs as a foreground service during voice sessions
 * - Manages WebSocket connection to OpenAI Realtime API
 * - Handles audio capture and playback
 * - Coordinates UI updates via CheckInActivity
 */
package com.eldercare.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.eldercare.app.analysis.SessionAnalyzer;
import com.eldercare.app.audio.VoiceAudioManager;
import com.eldercare.app.realtime.OpenAIRealtimeClient;
import com.eldercare.app.ui.CheckInActivity;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Foreground service that manages voice check-in sessions.
 *
 * Lifecycle:
 * 1. Started by ScheduleReceiver when a check-in is due
 * 2. Launches CheckInActivity (fullscreen smiley)
 * 3. Connects to OpenAI Realtime API via WebSocket
 * 4. Captures audio, sends to API, plays responses
 * 5. Ends after timeout or conversation completion
 * 6. Logs session summary and determines follow-up action
 */
public class ElderCareService extends Service
    implements OpenAIRealtimeClient.Listener, VoiceAudioManager.AudioCallback {

    private static final String TAG = "ElderCareService";
    private static final String CHANNEL_ID = "eldercare_service";
    private static final int NOTIFICATION_ID = 1;

    // Intent extras
    public static final String EXTRA_SCHEDULE_ID = "schedule_id";
    public static final String EXTRA_PROMPT = "prompt";
    public static final String EXTRA_API_KEY = "api_key";

    // Session configuration
    private static final long MAX_SESSION_DURATION_MS = 3 * 60 * 1000; // 3 minutes
    private static final long SILENCE_TIMEOUT_MS = 20 * 1000; // 20 seconds

    private boolean isSessionActive = false;
    private String scheduleId;
    private String prompt;
    private String apiKey;

    private OpenAIRealtimeClient realtimeClient;
    private VoiceAudioManager audioManager;
    private Handler mainHandler;
    private CheckInActivity checkInActivity;

    // Session tracking
    private StringBuilder userTranscriptBuilder = new StringBuilder();
    private StringBuilder assistantTranscriptBuilder = new StringBuilder();
    private long sessionStartTime;
    private long lastAudioTime;

    // Timeout handlers
    private Runnable sessionTimeoutRunnable;
    private Runnable silenceTimeoutRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "ElderCareService created");
        mainHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "ElderCareService starting check-in session");

        if (isSessionActive) {
            Log.w(TAG, "Session already active, ignoring");
            return START_NOT_STICKY;
        }

        // Extract parameters
        if (intent != null) {
            scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID);
            prompt = intent.getStringExtra(EXTRA_PROMPT);
            apiKey = intent.getStringExtra(EXTRA_API_KEY);
        }

        // Get API key from system property if not provided
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = System.getProperty("eldercare.openai.api_key");
        }

        if (apiKey == null || apiKey.isEmpty()) {
            Log.e(TAG, "No API key provided");
            stopSelf();
            return START_NOT_STICKY;
        }

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification("Starting check-in..."));

        // Launch the UI
        CheckInActivity.launch(this, scheduleId, prompt);

        // Start voice session
        startVoiceSession();

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "ElderCareService destroyed");
        stopVoiceSession();
    }

    /**
     * Set the activity reference for UI updates.
     */
    public void setCheckInActivity(CheckInActivity activity) {
        this.checkInActivity = activity;
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

        Log.i(TAG, "Starting voice session");
        isSessionActive = true;
        sessionStartTime = System.currentTimeMillis();
        lastAudioTime = sessionStartTime;

        // Initialize audio manager
        audioManager = new VoiceAudioManager(this, this);
        audioManager.prepare();

        // Initialize OpenAI client
        realtimeClient = new OpenAIRealtimeClient(apiKey, this);
        realtimeClient.connect();

        // Set up session timeout
        sessionTimeoutRunnable = () -> {
            Log.i(TAG, "Session timeout reached");
            endSession(OpenAIRealtimeClient.SessionResult.Action.NONE, "Session timed out");
        };
        mainHandler.postDelayed(sessionTimeoutRunnable, MAX_SESSION_DURATION_MS);

        // Update notification
        updateNotification("Check-in in progress");
    }

    private void stopVoiceSession() {
        if (!isSessionActive) {
            return;
        }

        Log.i(TAG, "Stopping voice session");
        isSessionActive = false;

        // Cancel timeouts
        if (sessionTimeoutRunnable != null) {
            mainHandler.removeCallbacks(sessionTimeoutRunnable);
        }
        if (silenceTimeoutRunnable != null) {
            mainHandler.removeCallbacks(silenceTimeoutRunnable);
        }

        // Stop audio
        if (audioManager != null) {
            audioManager.stopRecording();
            audioManager.stopPlayback();
            audioManager.release();
            audioManager = null;
        }

        // Disconnect from API
        if (realtimeClient != null) {
            realtimeClient.shutdown();
            realtimeClient = null;
        }

        stopForeground(true);
    }

    private void updateNotification(String message) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, createNotification(message));
        }
    }

    private void updateUI(CheckInActivity.State state) {
        if (checkInActivity != null) {
            checkInActivity.updateState(state);
        }
    }

    private void resetSilenceTimeout() {
        lastAudioTime = System.currentTimeMillis();

        if (silenceTimeoutRunnable != null) {
            mainHandler.removeCallbacks(silenceTimeoutRunnable);
        }

        silenceTimeoutRunnable = () -> {
            Log.i(TAG, "Silence timeout reached");
            // Commit any remaining audio and let the AI respond
            if (realtimeClient != null) {
                realtimeClient.commitAudio();
            }
        };
        mainHandler.postDelayed(silenceTimeoutRunnable, SILENCE_TIMEOUT_MS);
    }

    private void endSession(OpenAIRealtimeClient.SessionResult.Action action, String summary) {
        Log.i(TAG, "Ending session, performing analysis...");

        // Perform quick local analysis first
        SessionAnalyzer.AnalysisResult quickResult = SessionAnalyzer.quickAnalysis(
            userTranscriptBuilder.toString()
        );

        // Use the quick analysis result
        finalizeSession(quickResult);

        // Also perform deeper analysis in background (for logging/learning)
        SessionAnalyzer analyzer = new SessionAnalyzer(apiKey);
        analyzer.analyze(
            userTranscriptBuilder.toString(),
            assistantTranscriptBuilder.toString(),
            new SessionAnalyzer.Callback() {
                @Override
                public void onAnalysisComplete(SessionAnalyzer.AnalysisResult result) {
                    Log.i(TAG, "Deep analysis complete: " + result.action +
                        " (urgency: " + result.urgencyLevel + ")");
                    Log.i(TAG, "Analysis summary: " + result.summary);
                    Log.i(TAG, "Analysis reasoning: " + result.reasoning);

                    // If deep analysis finds something urgent that quick analysis missed,
                    // still send the alert
                    if (result.action == SessionAnalyzer.AnalysisResult.Action.ALERT_CAREGIVER &&
                        quickResult.action != SessionAnalyzer.AnalysisResult.Action.ALERT_CAREGIVER) {
                        Log.w(TAG, "Deep analysis found issue missed by quick analysis!");
                        sendCaregiverAlert(result.summary, result.urgencyLevel);
                    }

                    analyzer.shutdown();
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "Deep analysis failed: " + error);
                    analyzer.shutdown();
                }
            }
        );
    }

    private void finalizeSession(SessionAnalyzer.AnalysisResult result) {
        Log.i(TAG, "Finalizing session with action: " + result.action);

        // Log session to file
        logSession(result);

        // Handle action
        switch (result.action) {
            case ALERT_CAREGIVER:
                sendCaregiverAlert(result.summary, result.urgencyLevel);
                break;
            case CONTINUE:
                // For now, just log. Later could extend the conversation.
                Log.i(TAG, "Person wanted to continue talking");
                break;
            case NONE:
            default:
                Log.i(TAG, "Session completed normally");
                break;
        }

        updateUI(CheckInActivity.State.COMPLETE);

        // Stop the service after a brief delay
        mainHandler.postDelayed(this::stopSelf, 3000);
    }

    private void sendCaregiverAlert(String summary, int urgencyLevel) {
        Log.w(TAG, "ALERT: Sending caregiver notification!");
        Log.w(TAG, "Urgency level: " + urgencyLevel);
        Log.w(TAG, "Summary: " + summary);

        updateNotification("Alert sent to caregiver");

        // TODO: Implement actual notification via Firebase Cloud Messaging
        // For now, just log the alert to a special file
        try {
            File alertDir = new File(getFilesDir(), "alerts");
            if (!alertDir.exists()) {
                alertDir.mkdirs();
            }

            String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                .format(new Date());
            File alertFile = new File(alertDir, "alert_" + timestamp + ".txt");

            try (FileWriter writer = new FileWriter(alertFile)) {
                writer.write("CAREGIVER ALERT\n");
                writer.write("===============\n\n");
                writer.write("Time: " + new Date() + "\n");
                writer.write("Urgency Level: " + urgencyLevel + "/5\n");
                writer.write("Summary: " + summary + "\n\n");
                writer.write("User said: " + userTranscriptBuilder.toString() + "\n");
            }

            Log.i(TAG, "Alert logged to: " + alertFile.getAbsolutePath());

        } catch (IOException e) {
            Log.e(TAG, "Failed to log alert", e);
        }
    }

    private void logSession(SessionAnalyzer.AnalysisResult result) {
        try {
            File logDir = new File(getFilesDir(), "sessions");
            if (!logDir.exists()) {
                logDir.mkdirs();
            }

            String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                .format(new Date());
            File logFile = new File(logDir, "session_" + timestamp + ".txt");

            try (FileWriter writer = new FileWriter(logFile)) {
                writer.write("Session Log\n");
                writer.write("============\n\n");
                writer.write("Time: " + new Date() + "\n");
                writer.write("Schedule ID: " + scheduleId + "\n");
                writer.write("Duration: " + (System.currentTimeMillis() - sessionStartTime) / 1000 + "s\n");
                writer.write("Action: " + result.action + "\n");
                writer.write("Urgency Level: " + result.urgencyLevel + "/5\n\n");

                writer.write("User Transcript:\n");
                writer.write("----------------\n");
                writer.write(userTranscriptBuilder.toString() + "\n\n");

                writer.write("Assistant Transcript:\n");
                writer.write("---------------------\n");
                writer.write(assistantTranscriptBuilder.toString() + "\n\n");

                writer.write("Summary:\n");
                writer.write("--------\n");
                writer.write(result.summary + "\n\n");

                writer.write("Reasoning:\n");
                writer.write("----------\n");
                writer.write(result.reasoning + "\n");
            }

            Log.i(TAG, "Session logged to: " + logFile.getAbsolutePath());

        } catch (IOException e) {
            Log.e(TAG, "Failed to log session", e);
        }
    }

    // ========== OpenAIRealtimeClient.Listener ==========

    @Override
    public void onConnected() {
        Log.i(TAG, "Connected to OpenAI Realtime API");
        updateUI(CheckInActivity.State.LISTENING);

        // Start audio capture and playback
        audioManager.startRecording();
        audioManager.startPlayback();

        resetSilenceTimeout();
    }

    @Override
    public void onAudioReceived(byte[] audioData) {
        if (audioManager != null) {
            audioManager.playAudio(audioData);
        }
    }

    @Override
    public void onSpeechStarted() {
        Log.d(TAG, "Assistant started speaking");
        updateUI(CheckInActivity.State.SPEAKING);

        // Pause recording while AI speaks to avoid feedback
        if (audioManager != null) {
            audioManager.pauseRecording();
        }
    }

    @Override
    public void onSpeechEnded() {
        Log.d(TAG, "Assistant finished speaking");
        updateUI(CheckInActivity.State.LISTENING);

        // Resume recording
        if (audioManager != null) {
            audioManager.resumeRecording();
        }

        resetSilenceTimeout();
    }

    @Override
    public void onUserTranscript(String transcript) {
        Log.i(TAG, "User said: " + transcript);
        userTranscriptBuilder.append(transcript).append(" ");

        // Check for concerning keywords
        String lowerTranscript = transcript.toLowerCase();
        if (containsConcerningKeywords(lowerTranscript)) {
            Log.w(TAG, "Concerning content detected in user speech");
            // Let the conversation continue, but flag for alert
        }
    }

    @Override
    public void onAssistantTranscript(String transcript) {
        assistantTranscriptBuilder.append(transcript);
    }

    @Override
    public void onSessionEnded(OpenAIRealtimeClient.SessionResult result) {
        endSession(result.action, result.summary);
    }

    @Override
    public void onError(String error) {
        Log.e(TAG, "API error: " + error);
        updateUI(CheckInActivity.State.ERROR);
        endSession(OpenAIRealtimeClient.SessionResult.Action.NONE, "Error: " + error);
    }

    // ========== VoiceAudioManager.AudioCallback ==========

    @Override
    public void onAudioCaptured(byte[] audioData) {
        if (realtimeClient != null) {
            realtimeClient.sendAudio(audioData);
            resetSilenceTimeout();
        }
    }

    // ========== Helpers ==========

    private boolean containsConcerningKeywords(String text) {
        String[] concerningWords = {
            "help", "fall", "fell", "hurt", "pain", "emergency",
            "can't get up", "chest", "dizzy", "bleeding", "ambulance",
            "911", "hospital", "doctor", "scared", "alone"
        };

        for (String word : concerningWords) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
