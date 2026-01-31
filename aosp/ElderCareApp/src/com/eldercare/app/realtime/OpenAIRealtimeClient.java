/*
 * Copyright 2024 ElderCare AI
 *
 * OpenAI Realtime API Client - WebSocket client for voice streaming
 */
package com.eldercare.app.realtime;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Client for OpenAI's Realtime Voice API.
 *
 * Handles:
 * - WebSocket connection management
 * - Sending audio input (16kHz mono PCM)
 * - Receiving audio output
 * - Session configuration
 * - Response handling and classification
 *
 * Uses raw SSL socket with WebSocket protocol implementation since
 * Android doesn't have java.net.http.WebSocket.
 */
public class OpenAIRealtimeClient {

    private static final String TAG = "OpenAIRealtime";
    private static final String HOST = "api.openai.com";
    private static final int PORT = 443;
    private static final String PATH = "/v1/realtime?model=gpt-4o-realtime-preview";

    // System prompt for eldercare check-in
    private static final String ELDERCARE_SYSTEM_PROMPT =
        "You are a caring and friendly companion checking in on an elderly person. " +
        "Your goal is to have a brief, warm conversation to see how they're doing. " +
        "Start by greeting them warmly and asking how they are today. " +
        "Listen carefully to their response. " +
        "If they mention any concerning symptoms, falls, emergencies, or requests for help, " +
        "acknowledge their concern with empathy and let them know help will be notified. " +
        "Keep the conversation brief (under 2 minutes) but caring. " +
        "End by wishing them well.";

    private final String apiKey;
    private final Listener listener;
    private final Handler mainHandler;
    private final HandlerThread workerThread;
    private final Handler workerHandler;

    private SSLSocket socket;
    private OutputStream outputStream;
    private InputStream inputStream;
    private AtomicBoolean isConnected = new AtomicBoolean(false);
    private AtomicBoolean isRunning = new AtomicBoolean(false);

    /**
     * Callback interface for realtime events.
     */
    public interface Listener {
        void onConnected();
        void onAudioReceived(byte[] audioData);
        void onSpeechStarted();
        void onSpeechEnded();
        void onUserTranscript(String transcript);
        void onAssistantTranscript(String transcript);
        void onSessionEnded(SessionResult result);
        void onError(String error);
    }

    /**
     * Result of a voice session, used to determine follow-up action.
     */
    public static class SessionResult {
        public enum Action {
            NONE,
            CONTINUE,
            ALERT_CAREGIVER
        }

        public final Action action;
        public final String summary;
        public final String transcript;

        public SessionResult(Action action, String summary, String transcript) {
            this.action = action;
            this.summary = summary;
            this.transcript = transcript;
        }
    }

    public OpenAIRealtimeClient(String apiKey, Listener listener) {
        this.apiKey = apiKey;
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());

        workerThread = new HandlerThread("OpenAIRealtime");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
    }

    /**
     * Connect to the OpenAI Realtime API.
     */
    public void connect() {
        workerHandler.post(() -> {
            try {
                Log.i(TAG, "Connecting to OpenAI Realtime API...");
                isRunning.set(true);

                // Create SSL socket
                SSLContext sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, null, new SecureRandom());
                SSLSocketFactory factory = sslContext.getSocketFactory();

                socket = (SSLSocket) factory.createSocket(HOST, PORT);
                socket.startHandshake();

                outputStream = socket.getOutputStream();
                inputStream = socket.getInputStream();

                // Perform WebSocket handshake
                if (!performHandshake()) {
                    throw new IOException("WebSocket handshake failed");
                }

                isConnected.set(true);
                Log.i(TAG, "WebSocket connected");

                // Configure session
                configureSession();

                // Notify listener
                mainHandler.post(() -> listener.onConnected());

                // Start reading responses
                readLoop();

            } catch (Exception e) {
                Log.e(TAG, "Failed to connect", e);
                mainHandler.post(() -> listener.onError("Connection failed: " + e.getMessage()));
            }
        });
    }

    private boolean performHandshake() throws IOException {
        // Generate WebSocket key
        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        String wsKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP);

        // Build HTTP upgrade request
        StringBuilder request = new StringBuilder();
        request.append("GET ").append(PATH).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(HOST).append("\r\n");
        request.append("Upgrade: websocket\r\n");
        request.append("Connection: Upgrade\r\n");
        request.append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n");
        request.append("Sec-WebSocket-Version: 13\r\n");
        request.append("Authorization: Bearer ").append(apiKey).append("\r\n");
        request.append("OpenAI-Beta: realtime=v1\r\n");
        request.append("\r\n");

        outputStream.write(request.toString().getBytes(StandardCharsets.UTF_8));
        outputStream.flush();

        // Read response
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        String statusLine = reader.readLine();

        if (statusLine == null || !statusLine.contains("101")) {
            Log.e(TAG, "Handshake failed: " + statusLine);
            // Read remaining headers for debugging
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                Log.d(TAG, "Response header: " + line);
            }
            return false;
        }

        // Read remaining headers
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            Log.d(TAG, "Header: " + line);
        }

        return true;
    }

    private void readLoop() {
        try {
            while (isRunning.get() && isConnected.get()) {
                String message = readWebSocketFrame();
                if (message != null) {
                    handleMessage(message);
                }
            }
        } catch (Exception e) {
            if (isRunning.get()) {
                Log.e(TAG, "Read error", e);
                mainHandler.post(() -> listener.onError("Connection lost: " + e.getMessage()));
            }
        }
    }

    private String readWebSocketFrame() throws IOException {
        // Read first byte (FIN + opcode)
        int b1 = inputStream.read();
        if (b1 == -1) return null;

        int opcode = b1 & 0x0F;

        // Read second byte (mask + length)
        int b2 = inputStream.read();
        if (b2 == -1) return null;

        long payloadLength = b2 & 0x7F;

        if (payloadLength == 126) {
            payloadLength = ((inputStream.read() & 0xFF) << 8) |
                           (inputStream.read() & 0xFF);
        } else if (payloadLength == 127) {
            payloadLength = 0;
            for (int i = 0; i < 8; i++) {
                payloadLength = (payloadLength << 8) | (inputStream.read() & 0xFF);
            }
        }

        // Read payload
        byte[] payload = new byte[(int) payloadLength];
        int totalRead = 0;
        while (totalRead < payloadLength) {
            int read = inputStream.read(payload, totalRead, (int) payloadLength - totalRead);
            if (read == -1) break;
            totalRead += read;
        }

        if (opcode == 0x01) { // Text frame
            return new String(payload, StandardCharsets.UTF_8);
        } else if (opcode == 0x08) { // Close frame
            isConnected.set(false);
            return null;
        }

        return null;
    }

    private void sendWebSocketFrame(String message) throws IOException {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        int length = payload.length;

        // Generate mask key
        byte[] maskKey = new byte[4];
        new SecureRandom().nextBytes(maskKey);

        // Build frame
        byte[] frame;
        int offset;

        if (length < 126) {
            frame = new byte[2 + 4 + length];
            frame[0] = (byte) 0x81; // FIN + text opcode
            frame[1] = (byte) (0x80 | length); // Mask + length
            offset = 2;
        } else if (length < 65536) {
            frame = new byte[4 + 4 + length];
            frame[0] = (byte) 0x81;
            frame[1] = (byte) (0x80 | 126);
            frame[2] = (byte) ((length >> 8) & 0xFF);
            frame[3] = (byte) (length & 0xFF);
            offset = 4;
        } else {
            frame = new byte[10 + 4 + length];
            frame[0] = (byte) 0x81;
            frame[1] = (byte) (0x80 | 127);
            for (int i = 0; i < 8; i++) {
                frame[2 + i] = (byte) ((length >> (56 - i * 8)) & 0xFF);
            }
            offset = 10;
        }

        // Add mask key
        System.arraycopy(maskKey, 0, frame, offset, 4);
        offset += 4;

        // Add masked payload
        for (int i = 0; i < length; i++) {
            frame[offset + i] = (byte) (payload[i] ^ maskKey[i % 4]);
        }

        synchronized (outputStream) {
            outputStream.write(frame);
            outputStream.flush();
        }
    }

    /**
     * Send audio data to the API.
     */
    public void sendAudio(byte[] audioData) {
        if (!isConnected.get()) return;

        workerHandler.post(() -> {
            try {
                String base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP);

                JSONObject msg = new JSONObject();
                msg.put("type", "input_audio_buffer.append");
                msg.put("audio", base64Audio);
                sendWebSocketFrame(msg.toString());

            } catch (Exception e) {
                Log.e(TAG, "Failed to send audio", e);
            }
        });
    }

    /**
     * Commit audio buffer and request response.
     */
    public void commitAudio() {
        if (!isConnected.get()) return;

        workerHandler.post(() -> {
            try {
                JSONObject commitMsg = new JSONObject();
                commitMsg.put("type", "input_audio_buffer.commit");
                sendWebSocketFrame(commitMsg.toString());

                JSONObject responseMsg = new JSONObject();
                responseMsg.put("type", "response.create");
                sendWebSocketFrame(responseMsg.toString());

            } catch (Exception e) {
                Log.e(TAG, "Failed to commit audio", e);
            }
        });
    }

    private void configureSession() {
        try {
            JSONObject config = new JSONObject();
            config.put("type", "session.update");

            JSONObject session = new JSONObject();
            session.put("modalities", new JSONArray().put("text").put("audio"));
            session.put("instructions", ELDERCARE_SYSTEM_PROMPT);
            session.put("voice", "alloy");
            session.put("input_audio_format", "pcm16");
            session.put("output_audio_format", "pcm16");
            session.put("turn_detection", new JSONObject()
                .put("type", "server_vad")
                .put("threshold", 0.5)
                .put("prefix_padding_ms", 300)
                .put("silence_duration_ms", 500));

            config.put("session", session);
            sendWebSocketFrame(config.toString());

            Log.i(TAG, "Session configured");

            // Trigger initial greeting
            triggerInitialGreeting();

        } catch (Exception e) {
            Log.e(TAG, "Failed to configure session", e);
        }
    }

    private void triggerInitialGreeting() {
        try {
            JSONObject contextMsg = new JSONObject();
            contextMsg.put("type", "conversation.item.create");

            JSONObject item = new JSONObject();
            item.put("type", "message");
            item.put("role", "user");

            JSONArray content = new JSONArray();
            JSONObject textContent = new JSONObject();
            textContent.put("type", "input_text");
            textContent.put("text", "Please start the check-in by greeting me warmly.");
            content.put(textContent);

            item.put("content", content);
            contextMsg.put("item", item);

            sendWebSocketFrame(contextMsg.toString());

            JSONObject responseMsg = new JSONObject();
            responseMsg.put("type", "response.create");
            sendWebSocketFrame(responseMsg.toString());

            Log.i(TAG, "Initial greeting triggered");

        } catch (Exception e) {
            Log.e(TAG, "Failed to trigger greeting", e);
        }
    }

    public void disconnect() {
        isRunning.set(false);
        isConnected.set(false);

        workerHandler.post(() -> {
            try {
                if (socket != null) {
                    socket.close();
                    socket = null;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error closing socket", e);
            }
        });
    }

    public void shutdown() {
        disconnect();
        workerThread.quitSafely();
    }

    private void handleMessage(String message) {
        try {
            JSONObject event = new JSONObject(message);
            String type = event.optString("type", "");

            switch (type) {
                case "session.created":
                    Log.i(TAG, "Session created");
                    break;

                case "session.updated":
                    Log.i(TAG, "Session updated");
                    break;

                case "response.audio.delta":
                    String audioBase64 = event.optString("delta", "");
                    if (!audioBase64.isEmpty()) {
                        byte[] audioData = Base64.decode(audioBase64, Base64.DEFAULT);
                        mainHandler.post(() -> listener.onAudioReceived(audioData));
                    }
                    break;

                case "response.audio_transcript.delta":
                    String assistantText = event.optString("delta", "");
                    if (!assistantText.isEmpty()) {
                        mainHandler.post(() -> listener.onAssistantTranscript(assistantText));
                    }
                    break;

                case "conversation.item.input_audio_transcription.completed":
                    String userTranscript = event.optString("transcript", "");
                    mainHandler.post(() -> listener.onUserTranscript(userTranscript));
                    break;

                case "response.audio.started":
                    mainHandler.post(() -> listener.onSpeechStarted());
                    break;

                case "response.audio.done":
                    mainHandler.post(() -> listener.onSpeechEnded());
                    break;

                case "response.done":
                    Log.i(TAG, "Response complete");
                    break;

                case "error":
                    JSONObject error = event.optJSONObject("error");
                    String errorMsg = error != null ? error.optString("message", "Unknown error") : "Unknown error";
                    Log.e(TAG, "API error: " + errorMsg);
                    mainHandler.post(() -> listener.onError(errorMsg));
                    break;

                default:
                    Log.d(TAG, "Event: " + type);
                    break;
            }

        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse message", e);
        }
    }
}
