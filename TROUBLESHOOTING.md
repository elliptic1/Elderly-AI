# ElderCare Troubleshooting Guide

Comprehensive guide for diagnosing and fixing common issues.

## Table of Contents

- [Build Issues](#build-issues)
- [Device Issues](#device-issues)
- [Check-In Issues](#check-in-issues)
- [Remote Config Issues](#remote-config-issues)
- [OTA Update Issues](#ota-update-issues)
- [Audio Issues](#audio-issues)
- [Network Issues](#network-issues)
- [Firebase Issues](#firebase-issues)

## Build Issues

### AOSP Build Fails

**Symptom:** `m -j$(nproc)` fails with errors

**Common Causes:**

1. **Insufficient disk space**
   ```bash
   df -h ~/aosp-panther
   # Need 300GB+ free
   ```
   
   **Fix:** Free up space or use larger partition

2. **Insufficient RAM**
   ```bash
   free -h
   # Need 16GB+ RAM
   ```
   
   **Fix:** Reduce parallel jobs: `m -j4` instead of `-j$(nproc)`

3. **Missing dependencies**
   ```bash
   # Reinstall build essentials
   sudo apt install -y \
     git-core gnupg flex bison build-essential zip curl \
     zlib1g-dev gcc-multilib g++-multilib libc6-dev-i386
   ```

4. **Wrong Java version**
   ```bash
   java -version
   # Should be OpenJDK 11
   ```
   
   **Fix:**
   ```bash
   sudo apt install openjdk-11-jdk
   sudo update-alternatives --config java
   # Select OpenJDK 11
   ```

5. **Repo sync incomplete**
   ```bash
   cd ~/aosp-panther
   repo sync -c -j$(nproc)
   ```

### Build Succeeds But Flash Fails

**Symptom:** `fastboot flashall` fails

**Diagnostics:**
```bash
# Check build output exists
ls -lh ~/aosp-panther/out/target/product/panther/*.img

# Should see:
# boot.img, system.img, vendor.img, etc.
```

**Common Causes:**

1. **Bootloader locked**
   ```bash
   fastboot flashing unlock
   ```

2. **Wrong device**
   ```bash
   fastboot getvar product
   # Should show: panther (Pixel 7)
   ```

3. **USB connection issues**
   ```bash
   # Try different USB port
   # Try different cable
   # Enable USB debugging on device
   ```

4. **Fastboot version mismatch**
   ```bash
   fastboot --version
   # Should be 34.0.0+
   ```
   
   **Fix:**
   ```bash
   sudo apt install android-sdk-platform-tools
   ```

## Device Issues

### Device Won't Boot

**Symptom:** Black screen, bootloop, or stuck at Google logo

**Diagnostics:**
```bash
# Check fastboot connection
adb reboot bootloader
fastboot devices

# Check boot slot
fastboot getvar current-slot
fastboot getvar slot-count
```

**Fix Attempts:**

1. **Switch A/B slots**
   ```bash
   fastboot set_active other
   fastboot reboot
   ```

2. **Wipe cache**
   ```bash
   fastboot erase cache
   fastboot reboot
   ```

3. **Factory reset**
   ```bash
   fastboot -w
   fastboot reboot
   ```

4. **Reflash system**
   ```bash
   cd ~/aosp-panther
   fastboot flashall -w
   ```

5. **Flash stock Android (last resort)**
   - Download factory image from [Google](https://developers.google.com/android/images#panther)
   - Extract and run `flash-all.sh`

### Device Stuck at Smiley Face

**Symptom:** CheckInActivity shows but no interaction possible

**Diagnostics:**
```bash
# Check logs
adb logcat -s ElderCareApp:I

# Check if services are running
adb shell dumpsys activity services | grep eldercare
```

**Common Causes:**

1. **No schedules configured**
   ```bash
   # Check schedules
   adb shell "su; cat /data/data/com.eldercare.app/shared_prefs/SchedulePrefs.xml"
   ```
   
   **Fix:** Push config with schedules

2. **No API key**
   ```bash
   # Check API key exists
   adb shell "su; cat /data/data/com.eldercare.app/shared_prefs/SchedulePrefs.xml | grep api_key"
   ```
   
   **Fix:** Push config with API key

3. **Service crashed**
   ```bash
   # Restart services
   adb shell am stopservice com.eldercare.app/.service.ElderCareService
   adb shell am startservice com.eldercare.app/.service.ElderCareService
   ```

### Can't Access Setup Activity

**Symptom:** Want to configure device locally but setup is hidden

**Fix:**
```bash
# Launch setup activity manually
adb shell am start -n com.eldercare.app/.ui.SetupActivity
```

## Check-In Issues

### Check-In Doesn't Trigger

**Symptom:** Device doesn't start check-in at scheduled time

**Diagnostics:**
```bash
# Check alarms are scheduled
adb shell dumpsys alarm | grep eldercare

# Check schedule receiver is enabled
adb shell dumpsys package com.eldercare.app | grep receiver

# Check logs around scheduled time
adb logcat -s ScheduleReceiver:I ElderCareService:I
```

**Common Causes:**

1. **Schedule not configured**
   
   **Fix:**
   ```bash
   node push-config.js <deviceId> --schedule 09:00 --prompt "Morning check-in"
   ```

2. **Schedule disabled**
   
   **Diagnostics:**
   ```bash
   adb shell "su; cat /data/data/com.eldercare.app/shared_prefs/SchedulePrefs.xml"
   ```
   
   **Fix:** Re-push schedule with enabled=true

3. **Alarm permissions revoked**
   
   **Fix:**
   ```bash
   adb shell pm grant com.eldercare.app android.permission.SCHEDULE_EXACT_ALARM
   ```

4. **Device sleeping (battery saver)**
   
   **Fix:** Keep device plugged in, disable battery optimization

### Can't Connect to OpenAI

**Symptom:** Check-in starts but no audio response

**Diagnostics:**
```bash
adb logcat -s OpenAIRealtime:I ElderCareService:I

# Look for connection errors
```

**Common Causes:**

1. **Invalid API key**
   
   **Symptoms in logs:**
   ```
   OpenAIRealtime: Handshake failed: 401
   ```
   
   **Fix:** Update API key
   ```bash
   node push-config.js <deviceId> --api-key sk-proj-VALID_KEY
   ```

2. **No network connection**
   
   **Diagnostics:**
   ```bash
   adb shell ping -c 3 api.openai.com
   ```
   
   **Fix:** Connect device to WiFi/ethernet

3. **OpenAI API down**
   
   **Check:** https://status.openai.com
   
   **Fix:** Wait for service to restore

4. **SSL/TLS error**
   
   **Symptoms in logs:**
   ```
   OpenAIRealtime: Failed to connect: SSL handshake failed
   ```
   
   **Fix:** Check device time/date is correct
   ```bash
   adb shell date
   # Should be current UTC time
   ```

### Session Ends Immediately

**Symptom:** Check-in starts then stops after <5 seconds

**Diagnostics:**
```bash
adb logcat -s ElderCareService:I OpenAIRealtime:I VoiceAudioManager:I
```

**Common Causes:**

1. **Silence timeout too short**
   
   **Fix:** Increase timeout in config (TODO: make configurable)

2. **Audio capture failure**
   
   **Fix:** See [Audio Issues](#audio-issues)

3. **Session timeout triggered**
   
   **Check logs for:** "Session timeout reached"

## Remote Config Issues

### Config Not Syncing

**Symptom:** Changes to Firestore not applied to device

**Diagnostics:**
```bash
# Check service is running
adb shell dumpsys activity services | grep RemoteConfig

# Check logs
adb logcat -s RemoteConfigService:I

# Force sync
adb shell am startservice com.eldercare.app/.config.RemoteConfigService
```

**Common Causes:**

1. **Wrong Firebase project ID**
   
   **Check:**
   ```bash
   adb logcat -s RemoteConfigService:I | grep "project"
   ```
   
   **Fix:** Update PROJECT_ID in RemoteConfigService.java, rebuild

2. **Network issues**
   
   **Diagnostics:**
   ```bash
   adb shell ping -c 3 firestore.googleapis.com
   ```

3. **Firestore document doesn't exist**
   
   **Check Firebase Console:**
   - Firestore → devices → {deviceId} → config → current
   
   **Fix:** Push config from admin tool

4. **Device ID mismatch**
   
   **Get device ID:**
   ```bash
   adb shell settings get secure android_id
   ```
   
   **Check it matches Firestore document ID**

### Config Applied But Not Working

**Symptom:** Config sync logs show success but API key still invalid

**Diagnostics:**
```bash
# Check SharedPreferences
adb shell "su; cat /data/data/com.eldercare.app/shared_prefs/SchedulePrefs.xml"

# Look for api_key value
```

**Common Causes:**

1. **App needs restart**
   
   **Fix:**
   ```bash
   adb shell am force-stop com.eldercare.app
   # App will restart automatically
   ```

2. **Alarms need rescheduling**
   
   **Fix:**
   ```bash
   adb reboot
   # BootReceiver will reschedule on boot
   ```

## OTA Update Issues

### OTA Not Detected

**Symptom:** Device doesn't download available update

**Diagnostics:**
```bash
# Check service is running
adb shell dumpsys activity services | grep OTAUpdate

# Force update check
adb shell am startservice com.eldercare.app/.update.OTAUpdateService

# Watch logs
adb logcat -s OTAUpdateService:I
```

**Common Causes:**

1. **Update already installed**
   
   **Check:**
   ```bash
   adb shell getprop ro.build.id
   # Should match buildId in Firestore updates/latest
   ```

2. **Firestore updates/latest doesn't exist**
   
   **Check Firebase Console**
   
   **Fix:**
   ```bash
   node push-ota.js --file eldercare-ota-v1.0.1.zip --upload
   ```

3. **Network issues**
   
   **Test:**
   ```bash
   adb shell ping -c 3 firestore.googleapis.com
   ```

### OTA Download Fails

**Symptom:** Update detected but download fails

**Diagnostics:**
```bash
adb logcat -s OTAUpdateService:I | grep -i download
```

**Common Causes:**

1. **Insufficient storage**
   
   **Check:**
   ```bash
   adb shell df -h /data
   # Need ~1GB free for OTA package
   ```
   
   **Fix:** Clear old packages
   ```bash
   adb shell "su; rm -rf /data/eldercare/updates/*.zip"
   ```

2. **Package URL inaccessible**
   
   **Test:**
   ```bash
   curl -I "URL_FROM_FIRESTORE"
   # Should return 200 OK
   ```
   
   **Fix:** Check Firebase Storage permissions, re-upload package

3. **Network timeout**
   
   **Fix:** Wait and retry, package will be re-downloaded

### OTA Checksum Mismatch

**Symptom:** Download completes but verification fails

**Diagnostics:**
```bash
adb logcat -s OTAUpdateService:I | grep -i checksum
```

**Symptoms in logs:**
```
OTAUpdateService: Checksum mismatch!
OTAUpdateService:   Expected: a1b2c3...
OTAUpdateService:   Actual: d4e5f6...
```

**Causes:**

1. **Corrupted download**
   
   **Fix:** Delete and retry
   ```bash
   adb shell "su; rm /data/eldercare/updates/*.zip"
   adb shell am startservice com.eldercare.app/.update.OTAUpdateService
   ```

2. **Wrong SHA-256 in Firestore**
   
   **Fix:** Recalculate checksum and update Firestore
   ```bash
   sha256sum eldercare-ota-v1.0.1.zip
   # Update Firestore updates/latest with correct SHA-256
   ```

3. **Package modified after upload**
   
   **Fix:** Re-upload package
   ```bash
   node push-ota.js --file eldercare-ota-v1.0.1.zip --upload
   ```

### OTA Apply Fails

**Symptom:** Download succeeds but update_engine fails

**Diagnostics:**
```bash
adb logcat -s OTAUpdateService:I update_engine:I
adb shell dumpsys update_engine
```

**Common error codes:**

- **Error 1:** Download error (shouldn't happen, already downloaded)
- **Error 7:** Verification failed (package signature invalid)
- **Error 10:** Payload mismatch (wrong device/build)

**Fixes:**

1. **Check package is for correct device**
   - Must be built for `panther` (Pixel 7)

2. **Check package signed with correct keys**
   - Development: Uses testkey
   - Production: Must use release keys

3. **Check build fingerprint matches**
   - OTA must be newer than current build

### Device Won't Boot After OTA

**Symptom:** Device reboots but doesn't start after OTA

**Automatic Rollback:**

Android A/B system will automatically rollback after 3 failed boot attempts.

**Manual Rollback:**
```bash
# Boot to bootloader
# (Power + Volume Down)

# Switch to other slot
fastboot set_active other
fastboot reboot
```

**If rollback doesn't work:**
```bash
# Reflash last known good build
cd ~/aosp-panther/out/target/product/panther
fastboot flashall
```

## Audio Issues

### No Audio Output

**Symptom:** Check-in runs but can't hear AI responses

**Diagnostics:**
```bash
# Check audio routing
adb shell dumpsys audio

# Check volume levels
adb shell "su; tinymix"

# Look for 'RX' (receiver/speaker) controls
```

**Fixes:**

1. **Volume too low**
   ```bash
   # Max out media volume
   adb shell media volume --stream 3 --set 15
   ```

2. **Wrong audio route**
   ```bash
   # Force speaker
   adb shell "su; tinymix 'RX1 MIX1 INP1' 'RX1'"
   ```

3. **Audio hardware failure**
   ```bash
   # Test with beep
   adb shell "su; tinyplay /system/media/audio/notifications/Orion.ogg"
   ```
   
   If no sound → hardware issue

### No Audio Input

**Symptom:** AI speaks but doesn't hear user

**Diagnostics:**
```bash
# Check mic permissions
adb shell dumpsys package com.eldercare.app | grep RECORD_AUDIO

# Test mic capture
adb shell "su; tinycap /sdcard/test.wav"
# Speak for 5 seconds, then Ctrl+C
adb pull /sdcard/test.wav
# Listen to test.wav on computer
```

**Fixes:**

1. **Missing mic permission**
   ```bash
   adb shell pm grant com.eldercare.app android.permission.RECORD_AUDIO
   ```

2. **Mic muted in hardware**
   
   Check physical mic isn't covered/blocked

3. **Wrong mic selected**
   ```bash
   # Check audio manager routing
   adb shell dumpsys audio | grep -A 20 "Mic"
   ```

### Echo / Feedback

**Symptom:** AI hears itself speaking

**Causes:**
- Speaker volume too loud
- Mic too sensitive
- AEC (Acoustic Echo Cancellation) not working

**Fixes:**

1. **Lower speaker volume**
   ```bash
   adb shell media volume --stream 3 --set 10
   ```

2. **Enable AEC in audio manager (requires code change)**

3. **Physical separation of mic/speaker** (hardware solution)

## Network Issues

### No Internet Connection

**Diagnostics:**
```bash
# Test connectivity
adb shell ping -c 3 8.8.8.8  # Google DNS
adb shell ping -c 3 api.openai.com
adb shell ping -c 3 firestore.googleapis.com
```

**Fixes:**

1. **Connect to WiFi**
   ```bash
   # List available networks
   adb shell "su; cmd wifi list-networks"
   
   # Connect to network
   adb shell "su; cmd wifi connect-network 'SSID' open"
   # or with password:
   adb shell "su; cmd wifi connect-network 'SSID' wpa2 'PASSWORD'"
   ```

2. **Check WiFi enabled**
   ```bash
   adb shell "su; svc wifi enable"
   ```

3. **Check airplane mode off**
   ```bash
   adb shell settings get global airplane_mode_on
   # Should be 0
   
   # If 1, disable:
   adb shell settings put global airplane_mode_on 0
   ```

### SSL/TLS Errors

**Symptom:** HTTPS connections fail

**Diagnostics:**
```bash
adb logcat | grep -i "ssl\|tls"
```

**Common Causes:**

1. **Wrong system time**
   ```bash
   adb shell date
   # Should match current UTC
   
   # Fix:
   adb shell "su; date -s '2024-01-31 18:00:00'"
   ```

2. **Missing CA certificates**
   
   **Fix:** Reflash system (includes updated certs)

## Firebase Issues

### Firestore Connection Fails

**Diagnostics:**
```bash
adb logcat -s RemoteConfigService:I OTAUpdateService:I | grep -i firestore
```

**Common Causes:**

1. **Wrong project ID**
   
   **Check logs for:**
   ```
   Failed to fetch config: HTTP 404
   ```
   
   **Fix:** Update PROJECT_ID in Java files, rebuild

2. **Firestore security rules too restrictive**
   
   **Check Firebase Console → Firestore → Rules**
   
   Make sure devices can read their config:
   ```javascript
   match /devices/{deviceId}/config/{doc} {
     allow read: if true;
   }
   ```

3. **Network issues**
   
   See [Network Issues](#network-issues)

### Firebase Storage Access Denied

**Symptom:** Can't download OTA packages

**Diagnostics:**
```bash
# Test URL directly
curl -I "https://storage.googleapis.com/YOUR_BUCKET/ota-updates/package.zip"

# Should return 200, not 403
```

**Fixes:**

1. **File not public**
   
   **Fix:**
   ```bash
   # Re-upload with public flag
   node push-ota.js --file package.zip --upload
   ```

2. **Storage rules too restrictive**
   
   **Check Firebase Console → Storage → Rules**
   
   ```javascript
   match /ota-updates/{fileName} {
     allow read: if true;  // Public read
   }
   ```

## Getting Help

If issues persist after trying these solutions:

1. **Check logs thoroughly**
   ```bash
   adb logcat > eldercare-full-log.txt
   # Review for errors
   ```

2. **Check system state**
   ```bash
   adb bugreport eldercare-bugreport.zip
   # Comprehensive system state
   ```

3. **File an issue** with:
   - Device model and build ID
   - Steps to reproduce
   - Relevant log excerpts
   - What you've tried already

4. **Community support:**
   - GitHub Discussions
   - Discord server
   - Stack Overflow (tag: eldercare-ai)

---

**Last Updated:** 2024-01-31  
**See also:** [SETUP.md](SETUP.md), [ARCHITECTURE.md](ARCHITECTURE.md)
