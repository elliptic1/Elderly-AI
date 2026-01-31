/*
 * Copyright 2024 ElderCare AI
 *
 * CheckInActivity - Main fullscreen UI shown during voice check-ins
 */
package com.eldercare.app.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.eldercare.app.R;

/**
 * Fullscreen activity displayed during voice check-in sessions.
 *
 * Shows a large friendly smiley face that:
 * - Pulses gently when listening
 * - Shows status text below
 * - Turns on screen and keeps it on during session
 *
 * This activity is launched by ElderCareService when a check-in is triggered.
 */
public class CheckInActivity extends Activity {

    private static final String TAG = "CheckInActivity";

    // Intent extras
    public static final String EXTRA_SCHEDULE_ID = "schedule_id";
    public static final String EXTRA_PROMPT = "prompt";

    private SmileyView smileyView;
    private TextView statusText;
    private View listeningIndicator;

    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i(TAG, "CheckInActivity created");

        // Wake up screen and keep it on
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );

        setContentView(R.layout.activity_checkin);

        smileyView = findViewById(R.id.smiley_view);
        statusText = findViewById(R.id.status_text);
        listeningIndicator = findViewById(R.id.listening_indicator);

        mainHandler = new Handler(Looper.getMainLooper());

        // Set initial state
        updateState(State.CONNECTING);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "CheckInActivity resumed");
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i(TAG, "CheckInActivity paused");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "CheckInActivity destroyed");
    }

    /**
     * Current state of the check-in session.
     */
    public enum State {
        CONNECTING,   // Connecting to OpenAI API
        LISTENING,    // Listening for user speech
        SPEAKING,     // AI is speaking
        COMPLETE,     // Check-in finished
        ERROR         // Connection or other error
    }

    /**
     * Update the UI based on current state.
     * Can be called from any thread.
     */
    public void updateState(State state) {
        mainHandler.post(() -> {
            switch (state) {
                case CONNECTING:
                    statusText.setText(R.string.status_connecting);
                    smileyView.setIdle();
                    listeningIndicator.setVisibility(View.GONE);
                    break;

                case LISTENING:
                    statusText.setText(R.string.status_listening);
                    smileyView.setListening(true);
                    listeningIndicator.setVisibility(View.VISIBLE);
                    break;

                case SPEAKING:
                    statusText.setText(R.string.status_speaking);
                    smileyView.setListening(false);
                    listeningIndicator.setVisibility(View.GONE);
                    break;

                case COMPLETE:
                    statusText.setText(R.string.status_complete);
                    smileyView.setIdle();
                    listeningIndicator.setVisibility(View.GONE);
                    // Close activity after brief delay
                    mainHandler.postDelayed(this::finish, 3000);
                    break;

                case ERROR:
                    statusText.setText(R.string.status_error);
                    smileyView.setError(true);
                    listeningIndicator.setVisibility(View.GONE);
                    // Close activity after showing error briefly
                    mainHandler.postDelayed(this::finish, 5000);
                    break;
            }
        });
    }

    /**
     * Launch this activity for a check-in session.
     */
    public static void launch(Context context, String scheduleId, String prompt) {
        Intent intent = new Intent(context, CheckInActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(EXTRA_SCHEDULE_ID, scheduleId);
        intent.putExtra(EXTRA_PROMPT, prompt);
        context.startActivity(intent);
    }
}
