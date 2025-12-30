/*
 * Copyright 2024 ElderCare AI
 *
 * Setup Activity - Initial device setup and configuration
 */
package com.eldercare.app.ui;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

/**
 * Minimal setup activity for initial device configuration.
 *
 * This activity allows:
 * - Device pairing with caregiver's mobile app
 * - Wi-Fi configuration
 * - Audio testing
 * - Status display
 *
 * In production, this may be hidden from launcher and only
 * accessible via special intent or BLE pairing flow.
 */
public class SetupActivity extends Activity {

    private static final String TAG = "ElderCareSetup";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i(TAG, "SetupActivity created");

        // TODO: Implement setup UI:
        // 1. Device status display
        // 2. Wi-Fi configuration
        // 3. Pairing with caregiver app (BLE or QR code)
        // 4. Audio test (mic/speaker check)
        // 5. Schedule preview
    }
}
