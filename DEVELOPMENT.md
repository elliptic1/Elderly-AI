# Development Guide

Guide for developers working on the ElderCare system.

## Development Environment

### Required Tools

- **AOSP Build Environment** - Ubuntu 20.04+, 16GB RAM, 300GB disk
- **Android Studio** - For Java/Android development (optional but helpful)
- **ADB/Fastboot** - Android SDK Platform Tools
- **Git** - Version control
- **Node.js 18+** - For admin tools
- **Firebase CLI** - For cloud functions

### Recommended Tools

- **VSCode** - With Java/Android extensions
- **LogCat** - For viewing device logs
- **Wireshark** - For debugging network issues
- **Android Emulator** - For quick UI testing (limited - won't run OpenAI WebSocket)

## Code Organization

### AOSP App Structure

```
aosp/ElderCareApp/
├── src/com/eldercare/app/
│   ├── service/
│   │   └── ElderCareService.java        # Main check-in service
│   ├── config/
│   │   └── RemoteConfigService.java     # Remote config sync
│   ├── update/
│   │   └── OTAUpdateService.java        # OTA update manager
│   ├── realtime/
│   │   └── OpenAIRealtimeClient.java    # WebSocket client for OpenAI
│   ├── audio/
│   │   └── VoiceAudioManager.java       # Audio capture/playback
│   ├── analysis/
│   │   └── SessionAnalyzer.java         # Session analysis
│   ├── schedule/
│   │   └── ScheduleManager.java         # Check-in scheduling
│   ├── receiver/
│   │   ├── BootReceiver.java            # Boot-time initialization
│   │   └── ScheduleReceiver.java        # Alarm handling
│   ├── ui/
│   │   ├── CheckInActivity.java         # Main UI (smiley)
│   │   ├── SmileyView.java              # Animated smiley face
│   │   └── SetupActivity.java           # Configuration UI
│   └── ElderCareApplication.java        # App initialization
├── res/
│   ├── layout/
│   │   ├── activity_checkin.xml         # Check-in UI layout
│   │   └── activity_setup.xml           # Setup UI layout
│   ├── values/
│   │   ├── strings.xml                  # String resources
│   │   ├── colors.xml                   # Color palette
│   │   └── themes.xml                   # App theme
│   └── mipmap/                          # App icons
├── AndroidManifest.xml                  # App permissions, components
└── Android.bp                           # AOSP build config
```

### Key Components

**ElderCareService:**
- Foreground service during check-ins
- Manages OpenAI WebSocket connection
- Handles audio I/O
- Performs session analysis
- Triggers alerts

**OpenAIRealtimeClient:**
- WebSocket connection to OpenAI Realtime API
- Sends/receives audio chunks (16kHz PCM)
- Handles session events (speech start/end, transcription, etc.)
- Manages connection lifecycle

**VoiceAudioManager:**
- Audio recording from mic (16kHz mono PCM)
- Audio playback to speaker
- Uses AudioRecord/AudioTrack APIs
- Handles audio routing, echo cancellation

**RemoteConfigService:**
- Background service polling Firestore
- Fetches device config from `devices/{deviceId}/config/current`
- Applies updates to SharedPreferences
- Reports status to Firestore

**OTAUpdateService:**
- Background service checking for updates
- Downloads OTA packages from URL
- Verifies SHA-256 checksum
- Calls `update_engine` API to apply updates
- Handles reboot

## Development Workflow

### 1. Make Code Changes

```bash
# Edit source files
vim ~/workspace/Elderly-AI/aosp/ElderCareApp/src/com/eldercare/app/...

# Or use Android Studio
cd ~/workspace/Elderly-AI/aosp/ElderCareApp
studio .
```

### 2. Incremental Build

```bash
cd ~/aosp-panther
source build/envsetup.sh
lunch eldercare_panther-userdebug

# Build only the app (fast)
m ElderCareApp

# Output: out/target/product/panther/system/priv-app/ElderCareApp/ElderCareApp.apk
```

### 3. Install to Device

```bash
# Remount system partition as writable
adb root
adb remount

# Push new APK
adb push out/target/product/panther/system/priv-app/ElderCareApp/ElderCareApp.apk \
  /system/priv-app/ElderCareApp/

# Restart app
adb shell am force-stop com.eldercare.app

# App will restart automatically (persistent=true)
```

**Alternative (faster for testing):**
```bash
# Install as user app (loses system privileges but faster iteration)
adb install -r out/target/product/panther/system/priv-app/ElderCareApp/ElderCareApp.apk
```

### 4. View Logs

```bash
# All app logs
adb logcat -s ElderCareApp:I

# Specific component
adb logcat -s ElderCareService:I
adb logcat -s OpenAIRealtime:I
adb logcat -s VoiceAudioManager:I

# Filter by tag
adb logcat | grep "ElderCare"

# Save to file
adb logcat > dev-logs.txt
```

### 5. Debug

**LogCat debugging:**
```java
import android.util.Log;

private static final String TAG = "MyComponent";

Log.d(TAG, "Debug message: " + value);
Log.i(TAG, "Info message");
Log.w(TAG, "Warning message");
Log.e(TAG, "Error message", exception);
```

**Attach debugger (Android Studio):**
1. Run → Attach Debugger to Android Process
2. Select `com.eldercare.app`
3. Set breakpoints
4. Trigger check-in

**Remote debugging (network):**
```bash
# On device
adb tcpip 5555

# On dev machine
adb connect 192.168.1.100:5555
```

## Testing

### Unit Testing

Currently no unit tests (TODO). Framework would be:

```java
// test/java/com/eldercare/app/SessionAnalyzerTest.java
@RunWith(AndroidJUnit4.class)
public class SessionAnalyzerTest {
    @Test
    public void detectsConcerningContent() {
        SessionAnalyzer analyzer = new SessionAnalyzer();
        String transcript = "I fell and hurt my hip";
        
        assertTrue(analyzer.containsConcerningKeywords(transcript));
    }
}
```

### Integration Testing

**Manual test checklist:**
- [ ] Check-in triggers on schedule
- [ ] OpenAI connection successful
- [ ] Audio capture works (clear sound)
- [ ] Audio playback works (appropriate volume)
- [ ] Session transcripts saved
- [ ] Concerning content detected
- [ ] Alerts sent on concerns
- [ ] Remote config syncs
- [ ] OTA check/download/apply works
- [ ] Rollback works on bad OTA

**Test script:**
```bash
#!/bin/bash
# test-checkin.sh

# Trigger test check-in
adb shell am broadcast \
  -a com.eldercare.app.SCHEDULED_CHECKIN \
  -n com.eldercare.app/.receiver.ScheduleReceiver \
  --es schedule_id "test"

# Monitor logs
adb logcat -s ElderCareService:I OpenAIRealtime:I
```

### Load Testing

Test multiple check-ins in sequence:

```bash
# Stress test
for i in {1..10}; do
  echo "Check-in $i"
  adb shell am broadcast -a com.eldercare.app.SCHEDULED_CHECKIN ...
  sleep 300  # 5 min between check-ins
done
```

## Common Tasks

### Add New Config Field

1. **Update Firestore document schema**
   ```json
   {
     "newField": "value",
     // ... existing fields
   }
   ```

2. **Parse in RemoteConfigService.java**
   ```java
   if (fields.has("newField")) {
       String value = getStringValue(fields, "newField");
       // Apply setting
   }
   ```

3. **Store in SharedPreferences or apply directly**

4. **Update admin tool**
   ```javascript
   // push-config.js
   config.updates.newField = args[++i];
   ```

### Add New Service

1. **Create service class**
   ```java
   package com.eldercare.app.myfeature;
   
   public class MyService extends Service {
       @Override
       public int onStartCommand(Intent intent, int flags, int startId) {
           // Service logic
           return START_STICKY;
       }
   }
   ```

2. **Declare in AndroidManifest.xml**
   ```xml
   <service
       android:name=".myfeature.MyService"
       android:enabled="true"
       android:exported="false" />
   ```

3. **Start from BootReceiver**
   ```java
   Intent intent = new Intent(context, MyService.class);
   context.startService(intent);
   ```

### Add New UI Activity

1. **Create activity class**
   ```java
   public class MyActivity extends Activity {
       @Override
       protected void onCreate(Bundle savedInstanceState) {
           super.onCreate(savedInstanceState);
           setContentView(R.layout.activity_my);
       }
   }
   ```

2. **Create layout XML**
   ```xml
   <!-- res/layout/activity_my.xml -->
   <LinearLayout ...>
       <!-- UI elements -->
   </LinearLayout>
   ```

3. **Declare in AndroidManifest.xml**
   ```xml
   <activity
       android:name=".ui.MyActivity"
       android:exported="true"
       android:theme="@style/Theme.ElderCare" />
   ```

### Add Permission

1. **Declare in AndroidManifest.xml**
   ```xml
   <uses-permission android:name="android.permission.MY_PERMISSION" />
   ```

2. **Request at runtime (if dangerous permission)**
   ```java
   if (checkSelfPermission(MY_PERMISSION) != PERMISSION_GRANTED) {
       requestPermissions(new String[]{MY_PERMISSION}, REQUEST_CODE);
   }
   ```

### Modify System Prompt

Currently hardcoded in `OpenAIRealtimeClient.java`:

```java
private static final String SYSTEM_PROMPT = 
    "You are a friendly companion checking in on an elderly person...";
```

**To make configurable:**
1. Add `systemPrompt` field to config
2. Store in SharedPreferences
3. Pass to `OpenAIRealtimeClient` constructor
4. Use in session creation

## Code Style

### Java

Follow Android/Google Java style:
- 4-space indentation
- camelCase for variables/methods
- PascalCase for classes
- UPPER_SNAKE_CASE for constants
- Braces on same line

```java
public class MyClass {
    private static final String TAG = "MyClass";
    
    private int myVariable;
    
    public void myMethod(String param) {
        if (condition) {
            doSomething();
        } else {
            doSomethingElse();
        }
    }
}
```

### Comments

Use JavaDoc for public APIs:

```java
/**
 * Performs session analysis on transcript.
 *
 * @param transcript Full conversation transcript
 * @return Analysis result with urgency level
 */
public AnalysisResult analyze(String transcript) {
    // Implementation
}
```

### Error Handling

Always log errors:

```java
try {
    riskyOperation();
} catch (Exception e) {
    Log.e(TAG, "Failed to perform operation", e);
    // Recover gracefully or alert user
}
```

## Git Workflow

### Branch Strategy

- `main` - Production-ready code
- `develop` - Integration branch
- `feature/my-feature` - Feature branches
- `fix/bug-description` - Bug fix branches

### Commit Messages

Follow conventional commits:

```
type(scope): subject

body (optional)

footer (optional)
```

**Types:**
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation only
- `refactor`: Code refactoring
- `test`: Add/update tests
- `chore`: Build/tooling changes

**Examples:**
```
feat(audio): add echo cancellation

Implemented AEC using AudioEffect API to reduce feedback
during check-ins.

Closes #123
```

```
fix(ota): handle network timeout during download

Added retry logic with exponential backoff.
```

### Pull Request Process

1. Create feature branch from `develop`
2. Make changes, commit with good messages
3. Push to GitHub
4. Open PR to `develop`
5. Request review
6. Address feedback
7. Merge when approved

## Release Process

### Version Numbering

Semantic versioning: `MAJOR.MINOR.PATCH`

- **MAJOR:** Breaking changes
- **MINOR:** New features (backwards compatible)
- **PATCH:** Bug fixes

### Release Checklist

- [ ] Update version in `build.gradle` (TODO: add gradle)
- [ ] Update changelog
- [ ] Tag release: `git tag -a v1.0.1 -m "Release 1.0.1"`
- [ ] Build OTA package: `./build-ota.sh v1.0.1`
- [ ] Test on 1-3 devices (sideload)
- [ ] If good, publish OTA: `node push-ota.js --file ... --upload`
- [ ] Monitor rollout
- [ ] Document known issues
- [ ] Push tag: `git push origin v1.0.1`

## Resources

### Documentation

- **AOSP Docs:** https://source.android.com/docs
- **Android API Ref:** https://developer.android.com/reference
- **OpenAI Realtime API:** https://platform.openai.com/docs/guides/realtime
- **Firebase Docs:** https://firebase.google.com/docs

### Tools

- **Android Studio:** https://developer.android.com/studio
- **LogCat:** `adb logcat --help`
- **Fastboot:** `fastboot --help`
- **Firebase CLI:** https://firebase.google.com/docs/cli

### Community

- **AOSP Gerrit:** https://android-review.googlesource.com
- **Stack Overflow:** Tag `android-aosp`
- **XDA Forums:** https://forum.xda-developers.com

## Debugging Tips

### Can't See Logs

```bash
# Increase log buffer size
adb logcat -G 16M

# Clear old logs
adb logcat -c
```

### App Crashes on Startup

```bash
# Check crash logs
adb logcat -b crash

# Check permissions
adb shell dumpsys package com.eldercare.app | grep permission
```

### WebSocket Won't Connect

```bash
# Test connectivity
adb shell ping -c 3 api.openai.com

# Check SSL/TLS
adb shell date  # Verify time is correct

# Test with curl (from device)
adb shell "curl -I https://api.openai.com/v1/realtime"
```

### Audio Not Working

```bash
# Check audio routing
adb shell dumpsys audio

# Test speaker
adb shell "tinyplay /system/media/audio/notifications/Orion.ogg"

# Test mic
adb shell "tinycap /sdcard/test.wav"
# Speak, then Ctrl+C
adb pull /sdcard/test.wav
# Listen on computer
```

## Performance Profiling

### Memory Profiling

```bash
# Get memory usage
adb shell dumpsys meminfo com.eldercare.app

# Monitor over time
while true; do
  adb shell dumpsys meminfo com.eldercare.app | grep TOTAL
  sleep 5
done
```

### CPU Profiling

```bash
# Top CPU consumers
adb shell top -m 10

# CPU usage for app
adb shell top | grep eldercare
```

### Network Profiling

```bash
# Monitor network usage
adb shell dumpsys netstats detail

# Capture packets (requires root)
adb shell tcpdump -i any -w /sdcard/capture.pcap
# Later: adb pull /sdcard/capture.pcap
# Analyze with Wireshark
```

## Next Steps for Development

### High Priority

- [ ] Add unit tests
- [ ] Add integration tests
- [ ] Set up CI/CD (GitHub Actions)
- [ ] Implement device authentication
- [ ] Encrypt API keys (Android Keystore)

### Medium Priority

- [ ] Web dashboard for device management
- [ ] Caregiver mobile app
- [ ] Incremental OTA updates
- [ ] Session recording upload (optional)
- [ ] Multi-language support

### Low Priority

- [ ] Custom wake word
- [ ] Fall detection
- [ ] Video check-ins
- [ ] Raspberry Pi port

---

**Last Updated:** 2024-01-31  
**See also:** [ARCHITECTURE.md](ARCHITECTURE.md), [SETUP.md](SETUP.md)
