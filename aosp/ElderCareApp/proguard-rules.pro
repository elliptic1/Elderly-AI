# ElderCare ProGuard Rules

# Keep application class
-keep class com.eldercare.app.ElderCareApplication { *; }

# Keep all receivers (needed for manifest-registered receivers)
-keep class com.eldercare.app.receiver.** { *; }

# Keep service
-keep class com.eldercare.app.service.** { *; }

# Firebase (if using Firebase SDK)
-keep class com.google.firebase.** { *; }

# OkHttp/WebSocket (for OpenAI Realtime API)
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
