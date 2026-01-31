/*
 * Copyright 2024 ElderCare AI
 *
 * ScheduleManager - Manages check-in schedules using AlarmManager
 */
package com.eldercare.app.schedule;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.eldercare.app.receiver.ScheduleReceiver;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Manages scheduled check-ins using Android's AlarmManager.
 *
 * For now, schedules are stored locally in SharedPreferences.
 * Later, they will sync from Firebase.
 */
public class ScheduleManager {

    private static final String TAG = "ScheduleManager";
    private static final String PREFS_NAME = "eldercare_schedules";
    private static final String KEY_SCHEDULES = "schedules";
    private static final String KEY_API_KEY = "openai_api_key";

    private final Context context;
    private final AlarmManager alarmManager;
    private final SharedPreferences prefs;

    /**
     * Represents a scheduled check-in.
     */
    public static class Schedule {
        public final String id;
        public final int hourOfDay;   // 0-23
        public final int minute;      // 0-59
        public final boolean[] daysOfWeek; // Sunday=0 through Saturday=6
        public final boolean enabled;
        public final String prompt;   // Optional custom prompt

        public Schedule(String id, int hourOfDay, int minute,
                       boolean[] daysOfWeek, boolean enabled, String prompt) {
            this.id = id;
            this.hourOfDay = hourOfDay;
            this.minute = minute;
            this.daysOfWeek = daysOfWeek != null ? daysOfWeek : new boolean[]{true, true, true, true, true, true, true};
            this.enabled = enabled;
            this.prompt = prompt;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("hourOfDay", hourOfDay);
            json.put("minute", minute);
            json.put("enabled", enabled);
            json.put("prompt", prompt);

            JSONArray days = new JSONArray();
            for (boolean day : daysOfWeek) {
                days.put(day);
            }
            json.put("daysOfWeek", days);

            return json;
        }

        public static Schedule fromJson(JSONObject json) throws JSONException {
            String id = json.getString("id");
            int hourOfDay = json.getInt("hourOfDay");
            int minute = json.getInt("minute");
            boolean enabled = json.optBoolean("enabled", true);
            String prompt = json.optString("prompt", null);

            boolean[] daysOfWeek = new boolean[7];
            JSONArray days = json.optJSONArray("daysOfWeek");
            if (days != null) {
                for (int i = 0; i < Math.min(7, days.length()); i++) {
                    daysOfWeek[i] = days.getBoolean(i);
                }
            } else {
                // Default to all days
                for (int i = 0; i < 7; i++) {
                    daysOfWeek[i] = true;
                }
            }

            return new Schedule(id, hourOfDay, minute, daysOfWeek, enabled, prompt);
        }
    }

    public ScheduleManager(Context context) {
        this.context = context.getApplicationContext();
        this.alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Initialize default schedules if none exist.
     * Creates check-ins at 9 AM, 1 PM, and 6 PM.
     */
    public void initializeDefaultSchedules() {
        List<Schedule> existing = getSchedules();
        if (!existing.isEmpty()) {
            Log.i(TAG, "Schedules already exist, skipping defaults");
            return;
        }

        Log.i(TAG, "Creating default schedules");

        // Morning check-in at 9 AM
        addSchedule(new Schedule(
            "default_morning",
            9, 0,
            null, true,
            "Good morning! How are you feeling today?"
        ));

        // Afternoon check-in at 1 PM
        addSchedule(new Schedule(
            "default_afternoon",
            13, 0,
            null, true,
            "Hi there! Just checking in. How's your day going?"
        ));

        // Evening check-in at 6 PM
        addSchedule(new Schedule(
            "default_evening",
            18, 0,
            null, true,
            "Good evening! How was your day?"
        ));
    }

    /**
     * Add or update a schedule.
     */
    public void addSchedule(Schedule schedule) {
        List<Schedule> schedules = getSchedules();

        // Remove existing schedule with same ID
        schedules.removeIf(s -> s.id.equals(schedule.id));

        // Add new schedule
        schedules.add(schedule);

        // Save
        saveSchedules(schedules);

        // Set alarm if enabled
        if (schedule.enabled) {
            setAlarm(schedule);
        }

        Log.i(TAG, "Added schedule: " + schedule.id + " at " +
            schedule.hourOfDay + ":" + String.format("%02d", schedule.minute));
    }

    /**
     * Remove a schedule.
     */
    public void removeSchedule(String scheduleId) {
        List<Schedule> schedules = getSchedules();
        schedules.removeIf(s -> s.id.equals(scheduleId));
        saveSchedules(schedules);
        cancelAlarm(scheduleId);

        Log.i(TAG, "Removed schedule: " + scheduleId);
    }

    /**
     * Get all schedules.
     */
    public List<Schedule> getSchedules() {
        List<Schedule> schedules = new ArrayList<>();

        String json = prefs.getString(KEY_SCHEDULES, "[]");
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                schedules.add(Schedule.fromJson(array.getJSONObject(i)));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse schedules", e);
        }

        return schedules;
    }

    private void saveSchedules(List<Schedule> schedules) {
        try {
            JSONArray array = new JSONArray();
            for (Schedule schedule : schedules) {
                array.put(schedule.toJson());
            }
            prefs.edit().putString(KEY_SCHEDULES, array.toString()).apply();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to save schedules", e);
        }
    }

    /**
     * Set the OpenAI API key.
     */
    public void setApiKey(String apiKey) {
        prefs.edit().putString(KEY_API_KEY, apiKey).apply();
    }

    /**
     * Get the OpenAI API key.
     */
    public String getApiKey() {
        return prefs.getString(KEY_API_KEY, null);
    }

    /**
     * Reschedule all enabled alarms.
     * Call this on boot or when schedules change.
     */
    public void rescheduleAllAlarms() {
        Log.i(TAG, "Rescheduling all alarms");

        for (Schedule schedule : getSchedules()) {
            if (schedule.enabled) {
                setAlarm(schedule);
            } else {
                cancelAlarm(schedule.id);
            }
        }
    }

    /**
     * Set an alarm for a schedule.
     */
    private void setAlarm(Schedule schedule) {
        long triggerTime = calculateNextTriggerTime(schedule);

        Intent intent = new Intent(context, ScheduleReceiver.class);
        intent.setAction(ScheduleReceiver.ACTION_CHECK_IN);
        intent.putExtra(ScheduleReceiver.EXTRA_SCHEDULE_ID, schedule.id);
        intent.putExtra(ScheduleReceiver.EXTRA_PROMPT, schedule.prompt);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context,
            schedule.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Use setExactAndAllowWhileIdle for precise timing even in doze mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                );
            } else {
                // Fall back to inexact alarm
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                );
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            );
        }

        Log.i(TAG, "Alarm set for " + schedule.id + " at " + new java.util.Date(triggerTime));
    }

    /**
     * Cancel an alarm for a schedule.
     */
    private void cancelAlarm(String scheduleId) {
        Intent intent = new Intent(context, ScheduleReceiver.class);
        intent.setAction(ScheduleReceiver.ACTION_CHECK_IN);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context,
            scheduleId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        alarmManager.cancel(pendingIntent);
        Log.i(TAG, "Alarm cancelled for " + scheduleId);
    }

    /**
     * Calculate the next trigger time for a schedule.
     */
    private long calculateNextTriggerTime(Schedule schedule) {
        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();

        target.set(Calendar.HOUR_OF_DAY, schedule.hourOfDay);
        target.set(Calendar.MINUTE, schedule.minute);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);

        // If time has passed today, start from tomorrow
        if (target.before(now)) {
            target.add(Calendar.DAY_OF_YEAR, 1);
        }

        // Find the next enabled day
        for (int i = 0; i < 7; i++) {
            int dayOfWeek = target.get(Calendar.DAY_OF_WEEK) - 1; // Convert to 0-indexed (Sunday=0)
            if (schedule.daysOfWeek[dayOfWeek]) {
                return target.getTimeInMillis();
            }
            target.add(Calendar.DAY_OF_YEAR, 1);
        }

        // Fallback: just use tomorrow
        target = Calendar.getInstance();
        target.set(Calendar.HOUR_OF_DAY, schedule.hourOfDay);
        target.set(Calendar.MINUTE, schedule.minute);
        target.set(Calendar.SECOND, 0);
        target.add(Calendar.DAY_OF_YEAR, 1);

        return target.getTimeInMillis();
    }

    /**
     * Trigger a check-in immediately (for testing).
     */
    public void triggerCheckInNow(String scheduleId) {
        Schedule schedule = null;
        for (Schedule s : getSchedules()) {
            if (s.id.equals(scheduleId)) {
                schedule = s;
                break;
            }
        }

        if (schedule == null) {
            Log.e(TAG, "Schedule not found: " + scheduleId);
            return;
        }

        Log.i(TAG, "Triggering immediate check-in for: " + scheduleId);

        Intent intent = new Intent(context, ScheduleReceiver.class);
        intent.setAction(ScheduleReceiver.ACTION_CHECK_IN);
        intent.putExtra(ScheduleReceiver.EXTRA_SCHEDULE_ID, schedule.id);
        intent.putExtra(ScheduleReceiver.EXTRA_PROMPT, schedule.prompt);

        context.sendBroadcast(intent);
    }
}
