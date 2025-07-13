# Android permissions for scheduled voice chat

To automatically check in on a user and start a voice chat the application needs
a variety of permissions:

| Permission | Purpose |
|------------|---------|
| `RECORD_AUDIO` | Capture the user's voice so it can be transcribed and sent to the AI. |
| `INTERNET` | Reach the OpenAI API to initiate voice chat or send transcriptions. |
| `RECEIVE_BOOT_COMPLETED` | Re-schedule alarms or background workers when the device restarts. |
| `WAKE_LOCK` | Temporarily wake and keep the device active when a scheduled check in fires. |
| `SCHEDULE_EXACT_ALARM` | Set exact alarms on Android 12+ so the device can wake at a specific time. |
| `FOREGROUND_SERVICE` | Run a foreground service that listens in the background without being killed. |
| `BIND_ACCESSIBILITY_SERVICE` | Provide accessibility features allowing the app to interact even if the user cannot reach the phone. |
| `POST_NOTIFICATIONS` | Notify the user about scheduled calls on newer Android versions. |

Some manufacturers also require `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` so the
service is not killed, though this is requested at runtime rather than declared
in the manifest.

These permissions are reflected in the `AndroidManifest.xml` inside the `app`
module.
