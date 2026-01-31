/*
 * Copyright 2024 ElderCare AI
 *
 * Setup Activity - Device configuration and testing
 */
package com.eldercare.app.ui;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.eldercare.app.R;
import com.eldercare.app.schedule.ScheduleManager;

import java.util.List;

/**
 * Setup activity for configuring the ElderCare device.
 *
 * Allows:
 * - Setting the OpenAI API key
 * - Viewing configured schedules
 * - Testing a check-in manually
 */
public class SetupActivity extends Activity {

    private static final String TAG = "ElderCareSetup";

    private ScheduleManager scheduleManager;

    private EditText apiKeyInput;
    private Button saveApiKeyButton;
    private Button testCheckInButton;
    private TextView schedulesText;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        Log.i(TAG, "SetupActivity created");

        scheduleManager = new ScheduleManager(this);

        initializeViews();
        loadCurrentSettings();
    }

    private void initializeViews() {
        apiKeyInput = findViewById(R.id.api_key_input);
        saveApiKeyButton = findViewById(R.id.save_api_key_button);
        testCheckInButton = findViewById(R.id.test_checkin_button);
        schedulesText = findViewById(R.id.schedules_text);
        statusText = findViewById(R.id.status_text);

        saveApiKeyButton.setOnClickListener(v -> saveApiKey());
        testCheckInButton.setOnClickListener(v -> testCheckIn());

        // Enable save button only when there's text
        apiKeyInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                saveApiKeyButton.setEnabled(s.length() > 0);
            }
        });
    }

    private void loadCurrentSettings() {
        // Load API key
        String apiKey = scheduleManager.getApiKey();
        if (apiKey != null && !apiKey.isEmpty()) {
            // Show masked version
            apiKeyInput.setHint("API key configured (sk-...****)");
            statusText.setText("Status: Ready");
            testCheckInButton.setEnabled(true);
        } else {
            apiKeyInput.setHint("Enter your OpenAI API key");
            statusText.setText("Status: API key required");
            testCheckInButton.setEnabled(false);
        }

        // Load schedules
        loadSchedules();
    }

    private void loadSchedules() {
        List<ScheduleManager.Schedule> schedules = scheduleManager.getSchedules();

        if (schedules.isEmpty()) {
            schedulesText.setText("No schedules configured");
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("Configured Schedules:\n\n");

            for (ScheduleManager.Schedule schedule : schedules) {
                sb.append("• ");
                sb.append(String.format("%02d:%02d", schedule.hourOfDay, schedule.minute));
                sb.append(" - ");
                sb.append(schedule.enabled ? "Enabled" : "Disabled");
                sb.append("\n");
                if (schedule.prompt != null && !schedule.prompt.isEmpty()) {
                    sb.append("  \"").append(schedule.prompt).append("\"\n");
                }
                sb.append("\n");
            }

            schedulesText.setText(sb.toString());
        }
    }

    private void saveApiKey() {
        String apiKey = apiKeyInput.getText().toString().trim();

        if (apiKey.isEmpty()) {
            Toast.makeText(this, "Please enter an API key", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!apiKey.startsWith("sk-")) {
            Toast.makeText(this, "API key should start with 'sk-'", Toast.LENGTH_SHORT).show();
            return;
        }

        scheduleManager.setApiKey(apiKey);
        apiKeyInput.setText("");
        apiKeyInput.setHint("API key configured (sk-...****)");
        statusText.setText("Status: Ready");
        testCheckInButton.setEnabled(true);

        Toast.makeText(this, "API key saved", Toast.LENGTH_SHORT).show();
        Log.i(TAG, "API key saved");
    }

    private void testCheckIn() {
        String apiKey = scheduleManager.getApiKey();

        if (apiKey == null || apiKey.isEmpty()) {
            Toast.makeText(this, "Please configure API key first", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.i(TAG, "Starting test check-in");
        statusText.setText("Status: Starting test check-in...");

        // Trigger immediate check-in
        List<ScheduleManager.Schedule> schedules = scheduleManager.getSchedules();
        if (!schedules.isEmpty()) {
            scheduleManager.triggerCheckInNow(schedules.get(0).id);
            Toast.makeText(this, "Check-in started!", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "No schedules configured", Toast.LENGTH_SHORT).show();
        }
    }
}
