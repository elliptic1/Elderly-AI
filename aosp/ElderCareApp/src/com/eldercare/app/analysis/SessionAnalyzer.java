/*
 * Copyright 2024 ElderCare AI
 *
 * SessionAnalyzer - Analyzes conversation transcripts to determine follow-up action
 */
package com.eldercare.app.analysis;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Analyzes conversation transcripts to determine what follow-up action to take.
 *
 * Uses OpenAI's chat API to classify the conversation outcome:
 * - NONE: Everything is fine, no action needed
 * - CONTINUE: The person seems to want to keep talking
 * - ALERT_CAREGIVER: Something concerning was mentioned
 */
public class SessionAnalyzer {

    private static final String TAG = "SessionAnalyzer";
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";

    private final String apiKey;
    private final Handler handler;
    private final HandlerThread handlerThread;

    /**
     * Result of the analysis.
     */
    public static class AnalysisResult {
        public enum Action {
            NONE,           // Everything okay
            CONTINUE,       // Keep talking
            ALERT_CAREGIVER // Send notification
        }

        public final Action action;
        public final String summary;
        public final String reasoning;
        public final int urgencyLevel; // 1-5, 5 being most urgent

        public AnalysisResult(Action action, String summary, String reasoning, int urgencyLevel) {
            this.action = action;
            this.summary = summary;
            this.reasoning = reasoning;
            this.urgencyLevel = urgencyLevel;
        }
    }

    public interface Callback {
        void onAnalysisComplete(AnalysisResult result);
        void onError(String error);
    }

    public SessionAnalyzer(String apiKey) {
        this.apiKey = apiKey;

        handlerThread = new HandlerThread("SessionAnalyzer");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    /**
     * Analyze a conversation transcript.
     *
     * @param userTranscript What the elderly person said
     * @param assistantTranscript What the AI assistant said
     * @param callback Callback for results
     */
    public void analyze(String userTranscript, String assistantTranscript, Callback callback) {
        handler.post(() -> {
            try {
                AnalysisResult result = performAnalysis(userTranscript, assistantTranscript);
                callback.onAnalysisComplete(result);
            } catch (Exception e) {
                Log.e(TAG, "Analysis failed", e);
                callback.onError(e.getMessage());
            }
        });
    }

    private AnalysisResult performAnalysis(String userTranscript, String assistantTranscript)
            throws IOException, JSONException {

        String prompt = buildAnalysisPrompt(userTranscript, assistantTranscript);

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", "gpt-4o-mini");
        requestBody.put("max_tokens", 500);
        requestBody.put("temperature", 0.3);

        JSONArray messages = new JSONArray();

        JSONObject systemMessage = new JSONObject();
        systemMessage.put("role", "system");
        systemMessage.put("content",
            "You are an AI assistant that analyzes conversations between an elderly person " +
            "and a check-in companion. Your job is to determine if any follow-up action is needed. " +
            "Respond ONLY with a JSON object containing: action (NONE, CONTINUE, or ALERT_CAREGIVER), " +
            "summary (1-2 sentence summary), reasoning (why you chose this action), " +
            "and urgencyLevel (1-5, where 5 is emergency).");
        messages.put(systemMessage);

        JSONObject userMessage = new JSONObject();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.put(userMessage);

        requestBody.put("messages", messages);

        // Make API request
        URL url = new URL(OPENAI_API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.toString().getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new IOException("API request failed with code: " + responseCode);
        }

        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }

        return parseAnalysisResponse(response.toString());
    }

    private String buildAnalysisPrompt(String userTranscript, String assistantTranscript) {
        StringBuilder sb = new StringBuilder();
        sb.append("Please analyze this check-in conversation with an elderly person:\n\n");

        sb.append("ASSISTANT (AI Check-in companion): ");
        sb.append(assistantTranscript.isEmpty() ? "[No response recorded]" : assistantTranscript);
        sb.append("\n\n");

        sb.append("ELDERLY PERSON: ");
        sb.append(userTranscript.isEmpty() ? "[No response recorded]" : userTranscript);
        sb.append("\n\n");

        sb.append("Based on this conversation, determine:\n");
        sb.append("1. Is the person okay or is there cause for concern?\n");
        sb.append("2. Did they mention any health issues, falls, pain, or emergencies?\n");
        sb.append("3. Do they seem lonely and want to continue talking?\n");
        sb.append("4. Should their caregiver be notified?\n\n");

        sb.append("Respond with a JSON object only.");

        return sb.toString();
    }

    private AnalysisResult parseAnalysisResponse(String apiResponse) throws JSONException {
        JSONObject response = new JSONObject(apiResponse);
        JSONArray choices = response.getJSONArray("choices");

        if (choices.length() == 0) {
            return new AnalysisResult(
                AnalysisResult.Action.NONE,
                "Unable to analyze conversation",
                "No response from analysis",
                1
            );
        }

        String content = choices.getJSONObject(0)
            .getJSONObject("message")
            .getString("content");

        // Parse the JSON response from the model
        JSONObject analysis;
        try {
            // Handle case where response might have markdown code blocks
            content = content.trim();
            if (content.startsWith("```json")) {
                content = content.substring(7);
            }
            if (content.startsWith("```")) {
                content = content.substring(3);
            }
            if (content.endsWith("```")) {
                content = content.substring(0, content.length() - 3);
            }
            content = content.trim();

            analysis = new JSONObject(content);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse analysis JSON: " + content, e);
            return new AnalysisResult(
                AnalysisResult.Action.NONE,
                "Check-in completed",
                "Analysis parsing failed",
                1
            );
        }

        String actionStr = analysis.optString("action", "NONE").toUpperCase();
        AnalysisResult.Action action;
        switch (actionStr) {
            case "ALERT_CAREGIVER":
                action = AnalysisResult.Action.ALERT_CAREGIVER;
                break;
            case "CONTINUE":
                action = AnalysisResult.Action.CONTINUE;
                break;
            default:
                action = AnalysisResult.Action.NONE;
                break;
        }

        String summary = analysis.optString("summary", "Check-in completed");
        String reasoning = analysis.optString("reasoning", "");
        int urgencyLevel = analysis.optInt("urgencyLevel", 1);

        // Clamp urgency level
        urgencyLevel = Math.max(1, Math.min(5, urgencyLevel));

        return new AnalysisResult(action, summary, reasoning, urgencyLevel);
    }

    /**
     * Quick local analysis based on keywords.
     * Used as a fallback if API analysis fails.
     */
    public static AnalysisResult quickAnalysis(String userTranscript) {
        if (userTranscript == null || userTranscript.isEmpty()) {
            return new AnalysisResult(
                AnalysisResult.Action.NONE,
                "No response from person",
                "Person did not respond during check-in",
                2 // Slightly concerning
            );
        }

        String lowerTranscript = userTranscript.toLowerCase();

        // Check for emergency keywords
        String[] emergencyKeywords = {
            "help me", "can't get up", "fell down", "chest pain", "can't breathe",
            "call 911", "ambulance", "emergency", "bleeding", "heart"
        };

        for (String keyword : emergencyKeywords) {
            if (lowerTranscript.contains(keyword)) {
                return new AnalysisResult(
                    AnalysisResult.Action.ALERT_CAREGIVER,
                    "Possible emergency mentioned: " + keyword,
                    "Detected emergency keyword in transcript",
                    5
                );
            }
        }

        // Check for concerning keywords
        String[] concerningKeywords = {
            "hurt", "pain", "dizzy", "fell", "fall", "sick", "scared",
            "alone", "lonely", "sad", "help", "doctor", "hospital"
        };

        for (String keyword : concerningKeywords) {
            if (lowerTranscript.contains(keyword)) {
                return new AnalysisResult(
                    AnalysisResult.Action.ALERT_CAREGIVER,
                    "Concerning content mentioned",
                    "Detected concerning keyword: " + keyword,
                    3
                );
            }
        }

        // Check for positive responses
        String[] positiveKeywords = {
            "good", "fine", "great", "okay", "well", "thanks", "thank you",
            "wonderful", "nice", "lovely", "happy"
        };

        for (String keyword : positiveKeywords) {
            if (lowerTranscript.contains(keyword)) {
                return new AnalysisResult(
                    AnalysisResult.Action.NONE,
                    "Person seems to be doing well",
                    "Detected positive response",
                    1
                );
            }
        }

        // Default: inconclusive
        return new AnalysisResult(
            AnalysisResult.Action.NONE,
            "Check-in completed normally",
            "No concerning keywords detected",
            1
        );
    }

    public void shutdown() {
        handlerThread.quitSafely();
    }
}
