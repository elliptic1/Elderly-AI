# Remote Configuration System

## Overview

The ElderCare device now supports **remote configuration updates** from Firebase Firestore, eliminating the need for physical access to update settings.

## What Was Built

### 1. Device-Side Components

**RemoteConfigService** (`aosp/ElderCareApp/src/com/eldercare/app/config/RemoteConfigService.java`)
- Background service that polls Firestore every 15 minutes
- Fetches device config from `devices/{deviceId}/config/current`
- Applies updates automatically (API key, schedules, settings)
- Reports device status back to Firestore
- Uses direct Firestore REST API (no SDK needed in AOSP)

**Boot Integration** (`aosp/ElderCareApp/src/com/eldercare/app/receiver/BootReceiver.java`)
- Starts RemoteConfigService on device boot
- Ensures config sync happens immediately after restart

**Manifest Updates** (`aosp/ElderCareApp/AndroidManifest.xml`)
- Declared RemoteConfigService
- Already had necessary network permissions

### 2. Admin Tools

**push-config.js** (`admin/push-config.js`)
- CLI tool to push config updates to Firestore
- Supports updating API key, schedules, and full config files
- Shows device status after pushing updates
- Maintains config version history

**Package Configuration** (`admin/package.json`)
- Firebase Admin SDK dependency
- Simple scripts for common tasks

**Setup Script** (`admin/setup.sh`)
- One-command setup for admin tools
- Checks for dependencies and service account
- Auto-detects connected device ID via ADB

### 3. Documentation

**Admin README** (`admin/README.md`)
- Complete guide to remote config system
- Usage examples for common tasks
- Troubleshooting guide
- Security considerations

**Example Config** (`admin/example-config.json`)
- Template for device configuration
- Shows all supported fields

**Project README** (`README.md`)
- Added remote config section
- Quick start guide

## How It Works

### Architecture

```
┌─────────────────┐
│  Admin (You)    │
│  push-config.js │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│   Firestore     │
│   devices/      │
│   {deviceId}/   │
│     config/     │
│       current   │
└────────┬────────┘
         │
         ▼ (polls every 15min)
┌─────────────────┐
│ ElderCare Device│
│ RemoteConfig    │
│ Service         │
└────────┬────────┘
         │
         ▼ (applies)
┌─────────────────┐
│ ScheduleManager │
│ (API key, etc)  │
└─────────────────┘
```

### Config Flow

1. **You push config** via `push-config.js`
   - Writes to `devices/{deviceId}/config/current`
   - Increments version number
   - Saves history entry

2. **Device polls Firestore**
   - Every 15 minutes (while running)
   - On boot (immediate sync)
   - On network reconnect

3. **Device applies updates**
   - Compares to local config
   - Updates changed settings
   - Reports status back

### Supported Config Fields

```json
{
  "apiKey": "sk-proj-...",           // OpenAI API key
  "schedules": [                     // Check-in schedules
    {
      "hour": 9,
      "minute": 0,
      "enabled": true,
      "prompt": "Morning check-in"
    }
  ],
  "settings": {                      // Device settings
    "maxSessionDuration": 180,
    "silenceTimeout": 20
  },
  "version": 5,                      // Auto-incremented
  "updatedAt": "2024-01-31T..."      // Auto timestamp
}
```

## Usage Examples

### Update API Key

```bash
node push-config.js abc123 --api-key sk-proj-new-key-here
```

### Add Schedule

```bash
node push-config.js abc123 --schedule 09:00 --prompt "Morning check-in"
```

### Full Config

```bash
# Create config file
cat > my-device-config.json << EOF
{
  "apiKey": "sk-proj-...",
  "schedules": [
    {"hour": 9, "minute": 0, "enabled": true, "prompt": "Morning"},
    {"hour": 18, "minute": 0, "enabled": true, "prompt": "Evening"}
  ]
}
EOF

# Push it
node push-config.js abc123 --config my-device-config.json
```

## Device Identification

Each device is identified by its **Android ID** (or device serial as fallback).

Get device ID:
```bash
adb shell settings get secure android_id
```

Or check device logs:
```bash
adb logcat | grep "Fetching config for device:"
```

## Security Notes

### Current Implementation
- Uses **unauthenticated REST API** for simplicity
- Relies on obscure device IDs
- Good for testing, **not production**

### Production Recommendations
1. **Add device authentication**
   - Firebase Auth with device credentials
   - Or API key per device
   - Or mutual TLS with certificates

2. **Firestore Security Rules**
   ```javascript
   // Devices can only read their own config
   match /devices/{deviceId}/config/{doc} {
     allow read: if request.auth.uid == deviceId;
   }
   ```

3. **Encrypt sensitive fields**
   - Encrypt API keys in transit/storage
   - Use Firebase App Check

## Next Steps

### Immediate
- [ ] Test on real device
- [ ] Set up Firebase project
- [ ] Generate service account key
- [ ] Push first config

### Future Enhancements
- [ ] Add device authentication
- [ ] Web dashboard for multi-device management
- [ ] Real-time config push (vs polling)
- [ ] Config validation/rollback
- [ ] Bulk device operations
- [ ] Monitoring/alerting dashboard

## Troubleshooting

### Device not syncing

**Check logs:**
```bash
adb logcat -s RemoteConfigService:I
```

**Common issues:**
- No internet connection
- Firebase project ID mismatch
- Firestore permissions
- Invalid config format

**Force sync:**
```bash
adb reboot
# or
adb shell am startservice com.eldercare.app/.config.RemoteConfigService
```

### Push fails

**Verify:**
- Service account key is valid
- Firebase project ID matches
- Device ID exists in Firestore
- Network connectivity

## Files Changed

```
aosp/ElderCareApp/
  src/com/eldercare/app/
    config/
      RemoteConfigService.java        [NEW]
    receiver/
      BootReceiver.java               [MODIFIED]
  AndroidManifest.xml                 [MODIFIED]

admin/
  push-config.js                      [NEW]
  package.json                        [NEW]
  setup.sh                            [NEW]
  README.md                           [NEW]
  example-config.json                 [NEW]
  .gitignore                          [NEW]

README.md                             [MODIFIED]
REMOTE_CONFIG.md                      [NEW - this file]
```

## Testing Checklist

- [ ] Build AOSP with new RemoteConfigService
- [ ] Flash to Pixel 7
- [ ] Set up Firebase project
- [ ] Install admin tools
- [ ] Get device ID
- [ ] Push test config
- [ ] Verify device fetches config (check logs)
- [ ] Verify API key is updated
- [ ] Trigger test check-in
- [ ] Verify device reports status back

---

Built: 2024-01-31
Status: Ready for testing
