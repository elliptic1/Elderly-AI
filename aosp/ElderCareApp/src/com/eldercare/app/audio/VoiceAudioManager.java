/*
 * Copyright 2024 ElderCare AI
 *
 * VoiceAudioManager - Handles audio capture and playback for voice sessions
 */
package com.eldercare.app.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages audio capture from microphone and playback through speaker.
 *
 * Audio format matches OpenAI Realtime API requirements:
 * - 16-bit PCM
 * - 16kHz sample rate
 * - Mono channel
 */
public class VoiceAudioManager {

    private static final String TAG = "VoiceAudioManager";

    // Audio configuration matching OpenAI Realtime API
    public static final int SAMPLE_RATE = 16000;
    public static final int CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO;
    public static final int CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO;
    public static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    // Buffer size for recording (100ms chunks)
    private static final int RECORD_BUFFER_MS = 100;

    private final Context context;
    private final AudioCallback callback;

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private AudioManager systemAudioManager;

    private HandlerThread recordThread;
    private Handler recordHandler;

    private AtomicBoolean isRecording = new AtomicBoolean(false);
    private AtomicBoolean isPlaying = new AtomicBoolean(false);

    private int minRecordBufferSize;
    private int minPlayBufferSize;
    private int originalVolume;

    /**
     * Callback for audio events.
     */
    public interface AudioCallback {
        /** Called when audio data is captured from mic */
        void onAudioCaptured(byte[] audioData);

        /** Called when an error occurs */
        void onError(String error);
    }

    public VoiceAudioManager(Context context, AudioCallback callback) {
        this.context = context;
        this.callback = callback;
        this.systemAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        calculateBufferSizes();
    }

    private void calculateBufferSizes() {
        // Recording buffer size
        minRecordBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG_IN,
            AUDIO_FORMAT
        );

        // Use at least 100ms worth of buffer
        int recordBytesPerMs = SAMPLE_RATE * 2 / 1000; // 16-bit mono = 2 bytes per sample
        int targetRecordBuffer = recordBytesPerMs * RECORD_BUFFER_MS;
        minRecordBufferSize = Math.max(minRecordBufferSize, targetRecordBuffer);

        // Playback buffer size
        minPlayBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG_OUT,
            AUDIO_FORMAT
        );

        Log.i(TAG, "Record buffer: " + minRecordBufferSize + ", Play buffer: " + minPlayBufferSize);
    }

    /**
     * Prepare audio system for voice session.
     * Sets volume to max and prepares audio streams.
     */
    public void prepare() {
        Log.i(TAG, "Preparing audio system");

        // Save original volume and set to maximum
        originalVolume = systemAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int maxVolume = systemAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        systemAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0);

        // Request audio focus
        systemAudioManager.requestAudioFocus(
            null,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        );

        // Enable speakerphone for louder output
        systemAudioManager.setSpeakerphoneOn(true);

        // Initialize AudioRecord
        try {
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                minRecordBufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new RuntimeException("AudioRecord failed to initialize");
            }

            Log.i(TAG, "AudioRecord initialized");

        } catch (SecurityException e) {
            Log.e(TAG, "Microphone permission denied", e);
            callback.onError("Microphone permission denied");
            return;
        }

        // Initialize AudioTrack
        audioTrack = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_CONFIG_OUT)
                .setEncoding(AUDIO_FORMAT)
                .build())
            .setBufferSizeInBytes(minPlayBufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build();

        Log.i(TAG, "AudioTrack initialized");

        // Start recording thread
        recordThread = new HandlerThread("AudioRecord");
        recordThread.start();
        recordHandler = new Handler(recordThread.getLooper());

        Log.i(TAG, "Audio system prepared");
    }

    /**
     * Start capturing audio from the microphone.
     */
    public void startRecording() {
        if (audioRecord == null || isRecording.get()) {
            return;
        }

        Log.i(TAG, "Starting audio recording");
        isRecording.set(true);

        audioRecord.startRecording();

        // Start capture loop on background thread
        recordHandler.post(this::captureLoop);
    }

    private void captureLoop() {
        byte[] buffer = new byte[minRecordBufferSize];

        while (isRecording.get()) {
            int bytesRead = audioRecord.read(buffer, 0, buffer.length);

            if (bytesRead > 0) {
                // Create a copy for the callback
                byte[] audioData = new byte[bytesRead];
                System.arraycopy(buffer, 0, audioData, 0, bytesRead);
                callback.onAudioCaptured(audioData);
            } else if (bytesRead < 0) {
                Log.e(TAG, "AudioRecord read error: " + bytesRead);
                callback.onError("Recording error: " + bytesRead);
                break;
            }
        }

        Log.i(TAG, "Capture loop ended");
    }

    /**
     * Stop capturing audio.
     */
    public void stopRecording() {
        if (!isRecording.get()) {
            return;
        }

        Log.i(TAG, "Stopping audio recording");
        isRecording.set(false);

        if (audioRecord != null) {
            audioRecord.stop();
        }
    }

    /**
     * Start audio playback.
     */
    public void startPlayback() {
        if (audioTrack == null || isPlaying.get()) {
            return;
        }

        Log.i(TAG, "Starting audio playback");
        isPlaying.set(true);
        audioTrack.play();
    }

    /**
     * Play audio data through the speaker.
     */
    public void playAudio(byte[] audioData) {
        if (audioTrack == null || !isPlaying.get()) {
            return;
        }

        audioTrack.write(audioData, 0, audioData.length);
    }

    /**
     * Stop audio playback.
     */
    public void stopPlayback() {
        if (!isPlaying.get()) {
            return;
        }

        Log.i(TAG, "Stopping audio playback");
        isPlaying.set(false);

        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.flush();
        }
    }

    /**
     * Pause recording temporarily (e.g., while AI is speaking).
     */
    public void pauseRecording() {
        if (isRecording.get() && audioRecord != null) {
            audioRecord.stop();
        }
    }

    /**
     * Resume recording after pause.
     */
    public void resumeRecording() {
        if (isRecording.get() && audioRecord != null) {
            audioRecord.startRecording();
            recordHandler.post(this::captureLoop);
        }
    }

    /**
     * Release all audio resources.
     */
    public void release() {
        Log.i(TAG, "Releasing audio resources");

        isRecording.set(false);
        isPlaying.set(false);

        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }

        if (audioTrack != null) {
            audioTrack.stop();
            audioTrack.release();
            audioTrack = null;
        }

        if (recordThread != null) {
            recordThread.quitSafely();
            recordThread = null;
        }

        // Restore original volume
        if (systemAudioManager != null) {
            systemAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0);
            systemAudioManager.setSpeakerphoneOn(false);
            systemAudioManager.abandonAudioFocus(null);
        }

        Log.i(TAG, "Audio resources released");
    }

    /**
     * Check if currently recording.
     */
    public boolean isRecording() {
        return isRecording.get();
    }

    /**
     * Check if currently playing.
     */
    public boolean isPlaying() {
        return isPlaying.get();
    }
}
