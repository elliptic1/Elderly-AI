/*
 * Copyright 2024 ElderCare AI
 *
 * Remote Config Service - Syncs device configuration from Firebase
 */
package com.eldercare.app.config;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import com.eldercare.app.schedule.ScheduleManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Background service that polls Firestore for device configuration updates.
 *
 * Syncs:
 * - OpenAI API key
 * - Check-in schedules
 * - Device settings
 *
 * Polling strategy:
 * - Every 15 minutes while running
 * - On boot via BootReceiver
 * - On network reconnect
 */
public class RemoteConfigService extends Service {

    private static final String TAG = "RemoteConfigService";

    // Firestore REST API endpoint
    private static final String FIRESTORE_BASE_URL = 
        "https://firestore.googleapis.com/v1/projects/%s/databases/(default)/documents";
    
    // TODO: Replace with your Firebase project ID
    private static final String PROJECT_ID = "eldercare-ai-project";
    
    // Polling interval (15 minutes)
    private static final long POLL_INTERVAL_MS = 15 * 60 * 1000;

    private HandlerThread workerThread;
    private Handler workerHandler;
    private Handler mainHandler;
    
    private ScheduleManager scheduleManager;
    private String deviceId;
    private boolean isRunning = false;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRunning) {
                fetchAndApplyConfig();
                workerHandler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "RemoteConfigService created");

        scheduleManager = new ScheduleManager(this);
        deviceId = getDeviceId();

        workerThread = new HandlerThread("RemoteConfig");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
        mainHandler = new Handler(getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "RemoteConfigService started");

        if (!isRunning) {
            isRunning = true;
            
            // Initial sync immediately
            workerHandler.post(() -> fetchAndApplyConfig());
            
            // Schedule periodic polling
            workerHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "RemoteConfigService destroyed");
        
        isRunning = false;
        workerHandler.removeCallbacks(pollRunnable);
        workerThread.quitSafely();
    }

    /**
     * Get unique device identifier.
     * Uses Android ID or device serial.
     */
    private String getDeviceId() {
        String androidId = android.provider.Settings.Secure.getString(
            getContentResolver(),
            android.provider.Settings.Secure.ANDROID_ID
        );
        
        if (androidId != null && !androidId.isEmpty()) {
            return androidId;
        }
        
        // Fallback to device serial
        return android.os.Build.SERIAL;
    }

    /**
     * Fetch device config from Firestore and apply updates.
     */
    private void fetchAndApplyConfig() {
        try {
            Log.i(TAG, "Fetching config for device: " + deviceId);

            // Build Firestore document path
            String docPath = String.format("devices/%s/config/current", deviceId);
            String urlString = String.format(FIRESTORE_BASE_URL, PROJECT_ID) + "/" + docPath;

            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            
            if (responseCode == 404) {
                Log.w(TAG, "Device config not found in Firestore");
                // This is normal for first-time setup
                return;
            }
            
            if (responseCode != 200) {
                Log.e(TAG, "Failed to fetch config: HTTP " + responseCode);
                return;
            }

            // Read response
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)
            );
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            // Parse Firestore document
            JSONObject doc = new JSONObject(response.toString());
            JSONObject fields = doc.getJSONObject("fields");

            Log.i(TAG, "Config fetched successfully");
            applyConfig(fields);

        } catch (Exception e) {
            Log.e(TAG, "Error fetching config", e);
        }
    }

    /**
     * Apply configuration from Firestore document fields.
     */
    private void applyConfig(JSONObject fields) {
        try {
            // Extract API key
            if (fields.has("apiKey")) {
                String apiKey = getStringValue(fields, "apiKey");
                if (apiKey != null && !apiKey.isEmpty()) {
                    String currentKey = scheduleManager.getApiKey();
                    if (!apiKey.equals(currentKey)) {
                        Log.i(TAG, "Updating API key");
                        scheduleManager.setApiKey(apiKey);
                    }
                }
            }

            // Extract schedules
            if (fields.has("schedules")) {
                JSONArray schedules = fields.getJSONObject("schedules")
                    .getJSONArray("arrayValue")
                    .getJSONObject(0)
                    .getJSONArray("values");
                
                Log.i(TAG, "Processing " + schedules.length() + " schedule(s)");
                // TODO: Implement schedule sync
                // For now, just log what we would do
                for (int i = 0; i < schedules.length(); i++) {
                    JSONObject schedule = schedules.getJSONObject(i)
                        .getJSONObject("mapValue")
                        .getJSONObject("fields");
                    
                    int hour = getIntValue(schedule, "hour");
                    int minute = getIntValue(schedule, "minute");
                    boolean enabled = getBoolValue(schedule, "enabled");
                    String prompt = getStringValue(schedule, "prompt");
                    
                    Log.i(TAG, String.format("Schedule: %02d:%02d enabled=%b prompt=%s",
                        hour, minute, enabled, prompt));
                }
            }

            // Extract config version for tracking
            if (fields.has("version")) {
                int version = getIntValue(fields, "version");
                Log.i(TAG, "Config version: " + version);
            }

            // Extract last update timestamp
            if (fields.has("updatedAt")) {
                String updatedAt = getStringValue(fields, "updatedAt");
                Log.i(TAG, "Config last updated: " + updatedAt);
            }

            Log.i(TAG, "Config applied successfully");

        } catch (Exception e) {
            Log.e(TAG, "Error applying config", e);
        }
    }

    /**
     * Helper: Extract string value from Firestore field.
     */
    private String getStringValue(JSONObject fields, String key) {
        try {
            return fields.getJSONObject(key).getString("stringValue");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Helper: Extract integer value from Firestore field.
     */
    private int getIntValue(JSONObject fields, String key) {
        try {
            return fields.getJSONObject(key).getInt("integerValue");
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Helper: Extract boolean value from Firestore field.
     */
    private boolean getBoolValue(JSONObject fields, String key) {
        try {
            return fields.getJSONObject(key).getBoolean("booleanValue");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Report device status back to Firestore.
     * Call this after applying config or on significant events.
     */
    public void reportStatus(String status, String message) {
        workerHandler.post(() -> {
            try {
                Log.i(TAG, "Reporting status: " + status);

                String docPath = String.format("devices/%s/status/current", deviceId);
                String urlString = String.format(FIRESTORE_BASE_URL, PROJECT_ID) + "/" + docPath;

                // Build Firestore document
                JSONObject doc = new JSONObject();
                JSONObject fields = new JSONObject();
                
                fields.put("status", new JSONObject()
                    .put("stringValue", status));
                fields.put("message", new JSONObject()
                    .put("stringValue", message));
                fields.put("timestamp", new JSONObject()
                    .put("timestampValue", 
                        new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
                            .format(new java.util.Date())));
                fields.put("deviceId", new JSONObject()
                    .put("stringValue", deviceId));
                
                doc.put("fields", fields);

                // Send PATCH request
                URL url = new URL(urlString + "?updateMask.fieldPaths=status&updateMask.fieldPaths=message&updateMask.fieldPaths=timestamp");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PATCH");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                OutputStream os = conn.getOutputStream();
                os.write(doc.toString().getBytes(StandardCharsets.UTF_8));
                os.close();

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    Log.i(TAG, "Status reported successfully");
                } else {
                    Log.w(TAG, "Status report failed: HTTP " + responseCode);
                }

            } catch (Exception e) {
                Log.e(TAG, "Error reporting status", e);
            }
        });
    }
}
