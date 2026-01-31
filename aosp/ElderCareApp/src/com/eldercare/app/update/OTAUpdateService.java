/*
 * Copyright 2024 ElderCare AI
 *
 * OTA Update Service - Manages system software updates
 */
package com.eldercare.app.update;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.UpdateEngine;
import android.os.UpdateEngineCallback;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Service that checks for and applies OTA system updates.
 *
 * Features:
 * - Checks Firestore for available updates
 * - Downloads OTA packages
 * - Verifies package integrity (SHA-256)
 * - Applies update using Android update_engine
 * - Reboots to new system partition
 *
 * Pixel 7 uses A/B (seamless) updates:
 * - Current slot: A or B
 * - Update applies to inactive slot
 * - Reboot switches to new slot
 * - Can rollback if update fails
 */
public class OTAUpdateService extends Service {

    private static final String TAG = "OTAUpdateService";

    // Firestore REST API endpoint
    private static final String FIRESTORE_BASE_URL = 
        "https://firestore.googleapis.com/v1/projects/%s/databases/(default)/documents";
    
    // TODO: Replace with your Firebase project ID
    private static final String PROJECT_ID = "eldercare-ai-project";
    
    // Check for updates every 6 hours
    private static final long UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000;
    
    // Download location
    private static final String DOWNLOAD_DIR = "/data/eldercare/updates";

    private HandlerThread workerThread;
    private Handler workerHandler;
    private Handler mainHandler;
    
    private UpdateEngine updateEngine;
    private String deviceId;
    private boolean isRunning = false;
    private boolean isDownloading = false;
    private boolean isApplyingUpdate = false;

    private final Runnable updateCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRunning) {
                checkForUpdates();
                workerHandler.postDelayed(this, UPDATE_CHECK_INTERVAL_MS);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "OTAUpdateService created");

        deviceId = getDeviceId();
        updateEngine = new UpdateEngine();

        workerThread = new HandlerThread("OTAUpdate");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
        mainHandler = new Handler(getMainLooper());

        // Create download directory
        File downloadDir = new File(DOWNLOAD_DIR);
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "OTAUpdateService started");

        if (!isRunning) {
            isRunning = true;
            
            // Check immediately on start
            workerHandler.post(() -> checkForUpdates());
            
            // Schedule periodic checks
            workerHandler.postDelayed(updateCheckRunnable, UPDATE_CHECK_INTERVAL_MS);
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
        Log.i(TAG, "OTAUpdateService destroyed");
        
        isRunning = false;
        workerHandler.removeCallbacks(updateCheckRunnable);
        workerThread.quitSafely();
        
        if (updateEngine != null) {
            updateEngine.unbind();
        }
    }

    /**
     * Get unique device identifier.
     */
    private String getDeviceId() {
        String androidId = android.provider.Settings.Secure.getString(
            getContentResolver(),
            android.provider.Settings.Secure.ANDROID_ID
        );
        
        if (androidId != null && !androidId.isEmpty()) {
            return androidId;
        }
        
        return android.os.Build.SERIAL;
    }

    /**
     * Get current system build info.
     */
    private String getCurrentBuildId() {
        return SystemProperties.get("ro.build.id", Build.ID);
    }

    private String getCurrentBuildFingerprint() {
        return Build.FINGERPRINT;
    }

    /**
     * Check Firestore for available updates.
     */
    private void checkForUpdates() {
        try {
            String currentBuildId = getCurrentBuildId();
            Log.i(TAG, "Checking for updates (current: " + currentBuildId + ")");

            // Build Firestore document path
            String docPath = "updates/latest";
            String urlString = String.format(FIRESTORE_BASE_URL, PROJECT_ID) + "/" + docPath;

            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            
            if (responseCode == 404) {
                Log.d(TAG, "No update info available");
                return;
            }
            
            if (responseCode != 200) {
                Log.e(TAG, "Failed to check for updates: HTTP " + responseCode);
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

            // Parse update info
            JSONObject doc = new JSONObject(response.toString());
            JSONObject fields = doc.getJSONObject("fields");

            String buildId = getStringValue(fields, "buildId");
            String buildFingerprint = getStringValue(fields, "buildFingerprint");
            String packageUrl = getStringValue(fields, "packageUrl");
            String sha256 = getStringValue(fields, "sha256");
            long packageSize = getLongValue(fields, "packageSize");
            String releaseNotes = getStringValue(fields, "releaseNotes");

            Log.i(TAG, "Latest available: " + buildId);

            // Check if update is needed
            if (buildId.equals(currentBuildId)) {
                Log.i(TAG, "Already on latest version");
                return;
            }

            Log.i(TAG, "Update available!");
            Log.i(TAG, "  Current: " + currentBuildId);
            Log.i(TAG, "  New: " + buildId);
            Log.i(TAG, "  Size: " + (packageSize / 1024 / 1024) + " MB");
            Log.i(TAG, "  Notes: " + releaseNotes);

            // Download and apply update
            downloadAndApplyUpdate(buildId, packageUrl, sha256);

        } catch (Exception e) {
            Log.e(TAG, "Error checking for updates", e);
        }
    }

    /**
     * Download OTA package and apply it.
     */
    private void downloadAndApplyUpdate(String buildId, String packageUrl, String expectedSha256) {
        if (isDownloading || isApplyingUpdate) {
            Log.w(TAG, "Update already in progress");
            return;
        }

        workerHandler.post(() -> {
            try {
                isDownloading = true;
                
                Log.i(TAG, "Downloading update package...");
                File packageFile = new File(DOWNLOAD_DIR, "update_" + buildId + ".zip");

                // Download
                URL url = new URL(packageUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(30000);

                InputStream input = conn.getInputStream();
                FileOutputStream output = new FileOutputStream(packageFile);

                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytes = 0;
                long lastLogBytes = 0;

                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                    
                    // Log progress every 10 MB
                    if (totalBytes - lastLogBytes > 10 * 1024 * 1024) {
                        Log.i(TAG, "Downloaded: " + (totalBytes / 1024 / 1024) + " MB");
                        lastLogBytes = totalBytes;
                    }
                }

                output.close();
                input.close();

                Log.i(TAG, "Download complete: " + (totalBytes / 1024 / 1024) + " MB");

                isDownloading = false;

                // Verify checksum
                if (!verifyChecksum(packageFile, expectedSha256)) {
                    Log.e(TAG, "Checksum verification failed!");
                    packageFile.delete();
                    return;
                }

                Log.i(TAG, "Checksum verified");

                // Apply update
                applyUpdate(packageFile);

            } catch (Exception e) {
                Log.e(TAG, "Error downloading update", e);
                isDownloading = false;
            }
        });
    }

    /**
     * Verify package SHA-256 checksum.
     */
    private boolean verifyChecksum(File file, String expectedSha256) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            
            InputStream input = new java.io.FileInputStream(file);
            byte[] buffer = new byte[8192];
            int bytesRead;
            
            while ((bytesRead = input.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            
            input.close();

            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            String actualSha256 = hexString.toString();
            boolean matches = actualSha256.equalsIgnoreCase(expectedSha256);

            if (!matches) {
                Log.e(TAG, "Checksum mismatch!");
                Log.e(TAG, "  Expected: " + expectedSha256);
                Log.e(TAG, "  Actual: " + actualSha256);
            }

            return matches;

        } catch (Exception e) {
            Log.e(TAG, "Error verifying checksum", e);
            return false;
        }
    }

    /**
     * Apply OTA update using update_engine.
     */
    private void applyUpdate(File packageFile) {
        if (isApplyingUpdate) {
            Log.w(TAG, "Update already being applied");
            return;
        }

        mainHandler.post(() -> {
            try {
                isApplyingUpdate = true;
                
                Log.i(TAG, "Applying OTA update...");

                // Bind to update engine callback
                updateEngine.bind(new UpdateEngineCallback() {
                    @Override
                    public void onStatusUpdate(int status, float percent) {
                        handleStatusUpdate(status, percent);
                    }

                    @Override
                    public void onPayloadApplicationComplete(int errorCode) {
                        handleUpdateComplete(errorCode, packageFile);
                    }
                });

                // Parse payload metadata
                String packagePath = packageFile.getAbsolutePath();
                
                // For A/B updates, we need to extract metadata from the package
                // and provide offset/size for each partition
                
                // This is a simplified approach - real implementation needs to:
                // 1. Unzip and read payload_properties.txt
                // 2. Extract partition offsets from payload.bin
                // 3. Pass metadata to update_engine
                
                Log.i(TAG, "Triggering update_engine with package: " + packagePath);
                
                // Apply update
                // Note: This is a simplified call. Real implementation needs payload metadata.
                String[] headerKeyValuePairs = new String[0]; // TODO: Extract from package
                updateEngine.applyPayload(
                    "file://" + packagePath,
                    0, // payload offset in file
                    packageFile.length(), // payload size
                    headerKeyValuePairs
                );

                Log.i(TAG, "Update engine started");

            } catch (Exception e) {
                Log.e(TAG, "Error applying update", e);
                isApplyingUpdate = false;
            }
        });
    }

    /**
     * Handle update_engine status updates.
     */
    private void handleStatusUpdate(int status, float percent) {
        String statusName = getUpdateStatusName(status);
        Log.i(TAG, "Update status: " + statusName + " (" + (int)(percent * 100) + "%)");
        
        // Status codes from UpdateEngine:
        // IDLE = 0
        // CHECKING_FOR_UPDATE = 1
        // UPDATE_AVAILABLE = 2
        // DOWNLOADING = 3
        // VERIFYING = 4
        // FINALIZING = 5
        // UPDATED_NEED_REBOOT = 6
        // REPORTING_ERROR_EVENT = 7
        // ATTEMPTING_ROLLBACK = 8
        // DISABLED = 9
    }

    /**
     * Handle update completion.
     */
    private void handleUpdateComplete(int errorCode, File packageFile) {
        isApplyingUpdate = false;

        if (errorCode == UpdateEngine.ErrorCodeConstants.SUCCESS) {
            Log.i(TAG, "Update applied successfully!");
            Log.i(TAG, "Preparing to reboot...");

            // Clean up package file
            packageFile.delete();

            // Reboot to apply update (switch to new slot)
            rebootDevice();

        } else {
            Log.e(TAG, "Update failed with error code: " + errorCode);
            // Keep package for retry
        }
    }

    /**
     * Reboot the device to complete update.
     */
    private void rebootDevice() {
        try {
            Log.i(TAG, "Rebooting in 5 seconds...");
            
            // Give time for logs to flush
            Thread.sleep(5000);

            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                pm.reboot("OTA Update");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error rebooting", e);
        }
    }

    /**
     * Get human-readable status name.
     */
    private String getUpdateStatusName(int status) {
        switch (status) {
            case UpdateEngine.UpdateStatusConstants.IDLE: return "IDLE";
            case UpdateEngine.UpdateStatusConstants.CHECKING_FOR_UPDATE: return "CHECKING";
            case UpdateEngine.UpdateStatusConstants.UPDATE_AVAILABLE: return "AVAILABLE";
            case UpdateEngine.UpdateStatusConstants.DOWNLOADING: return "DOWNLOADING";
            case UpdateEngine.UpdateStatusConstants.VERIFYING: return "VERIFYING";
            case UpdateEngine.UpdateStatusConstants.FINALIZING: return "FINALIZING";
            case UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT: return "NEED_REBOOT";
            case UpdateEngine.UpdateStatusConstants.REPORTING_ERROR_EVENT: return "ERROR";
            case UpdateEngine.UpdateStatusConstants.ATTEMPTING_ROLLBACK: return "ROLLBACK";
            case UpdateEngine.UpdateStatusConstants.DISABLED: return "DISABLED";
            default: return "UNKNOWN(" + status + ")";
        }
    }

    // Helper methods for parsing Firestore fields

    private String getStringValue(JSONObject fields, String key) {
        try {
            return fields.getJSONObject(key).getString("stringValue");
        } catch (Exception e) {
            return null;
        }
    }

    private int getIntValue(JSONObject fields, String key) {
        try {
            return fields.getJSONObject(key).getInt("integerValue");
        } catch (Exception e) {
            return 0;
        }
    }

    private long getLongValue(JSONObject fields, String key) {
        try {
            return fields.getJSONObject(key).getLong("integerValue");
        } catch (Exception e) {
            return 0;
        }
    }
}
