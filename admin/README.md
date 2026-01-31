# ElderCare Admin Tools

Remote configuration management for ElderCare devices.

## Overview

The ElderCare system supports **remote configuration updates** from Firebase Firestore. This allows you to:
- Update OpenAI API keys without physical access
- Add/modify check-in schedules remotely
- Adjust device settings from the cloud
- Monitor device status

## How It Works

1. **Device polls Firestore** every 15 minutes for config updates
2. **On boot**, device immediately syncs config
3. **Config changes** are applied automatically
4. **Device reports status** back to Firestore

### Architecture

```
Firestore Structure:
  devices/
    {deviceId}/
      config/
        current         ← Active config (device polls this)
      config-history/
        {timestamp}     ← Config change history
      status/
        current         ← Device reports status here
      sessions/
        {sessionId}     ← Check-in session logs
```

## Setup

### 1. Install Dependencies

```bash
cd admin/
npm install
```

### 2. Get Firebase Service Account Key

1. Go to Firebase Console → Project Settings → Service Accounts
2. Click "Generate New Private Key"
3. Save as `admin/service-account.json`

**Or** set environment variable:
```bash
export FIREBASE_SERVICE_ACCOUNT=/path/to/service-account.json
```

### 3. Get Device ID

The device ID is the Android ID (or device serial). To find it:

**Option A: From device (ADB)**
```bash
adb shell settings get secure android_id
```

**Option B: From logs**
```bash
adb logcat | grep "device:"
# Look for: "Fetching config for device: abc123def456"
```

## Usage

### Update API Key

```bash
node push-config.js <deviceId> --api-key sk-proj-YOUR_KEY_HERE
```

Example:
```bash
node push-config.js abc123def456 --api-key sk-proj-xyz789...
```

The device will pick up the new key within 15 minutes (or immediately on reboot).

### Add Check-in Schedule

```bash
node push-config.js <deviceId> --schedule HH:MM --prompt "Custom prompt"
```

Example:
```bash
# Morning check-in at 9:00 AM
node push-config.js abc123def456 --schedule 09:00 --prompt "Good morning! How are you feeling today?"

# Evening check-in at 6:00 PM
node push-config.js abc123def456 --schedule 18:00 --prompt "Evening check-in"
```

### Push Full Config (JSON)

Create a config file:

**device-config.json:**
```json
{
  "apiKey": "sk-proj-...",
  "schedules": [
    {
      "hour": 9,
      "minute": 0,
      "enabled": true,
      "prompt": "Good morning! How are you feeling today?"
    },
    {
      "hour": 13,
      "minute": 0,
      "enabled": true,
      "prompt": "Afternoon check-in"
    },
    {
      "hour": 18,
      "minute": 0,
      "enabled": true,
      "prompt": "Evening check-in. How was your day?"
    }
  ],
  "settings": {
    "maxSessionDuration": 180,
    "silenceTimeout": 20
  }
}
```

Push it:
```bash
node push-config.js abc123def456 --config device-config.json
```

## Monitoring

### Device Status

The push script automatically shows device status after updating:

```
Device Status:
  Status: online
  Message: Config synced successfully
  Last Update: 2024-01-31T17:45:23Z
```

### Manual Status Check

```bash
# TODO: Create get-status.js script
node get-status.js <deviceId>
```

### View Config History

Config changes are logged to `devices/{deviceId}/config-history/`.

## Security

### Firestore Rules

Set these rules in Firebase Console → Firestore → Rules:

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    // Devices can read their own config
    match /devices/{deviceId}/config/{document=**} {
      allow read: if request.auth != null && request.auth.uid == deviceId;
      allow write: if false; // Only admin SDK can write
    }
    
    // Devices can write their own status
    match /devices/{deviceId}/status/{document=**} {
      allow write: if request.auth != null && request.auth.uid == deviceId;
      allow read: if false; // Only admin SDK can read
    }
    
    // Admin-only access to everything else
    match /{document=**} {
      allow read, write: if false;
    }
  }
}
```

### Device Authentication

The current implementation uses **unauthenticated REST API** calls (simple for AOSP system app).

For production, implement one of:
1. **Firebase Auth with device credentials**
2. **Mutual TLS** with device certificates
3. **API key per device** (simpler)

## Troubleshooting

### Device not syncing config

**Check device logs:**
```bash
adb logcat | grep RemoteConfig
```

Look for:
- `Fetching config for device: ...`
- `Config fetched successfully`
- `Config applied successfully`

**Common issues:**
- Network connectivity (device needs internet)
- Firestore project ID mismatch (check `RemoteConfigService.java`)
- Invalid config format in Firestore

### Force immediate sync

Reboot the device:
```bash
adb reboot
```

Or manually start the service:
```bash
adb shell am startservice com.eldercare.app/.config.RemoteConfigService
```

### View Firestore data

```bash
# Install Firebase CLI
npm install -g firebase-tools

# Login
firebase login

# List projects
firebase projects:list

# Access Firestore
firebase firestore:get /devices/{deviceId}/config/current
```

## Development

### Testing Locally

1. Create test device config in Firestore:
   ```bash
   node push-config.js test-device --api-key sk-test-123
   ```

2. Flash device with test build

3. Check logs:
   ```bash
   adb logcat -s RemoteConfigService:I ElderCareBootReceiver:I
   ```

### Adding New Config Fields

1. **Update Firestore document** in `push-config.js`
2. **Parse field** in `RemoteConfigService.applyConfig()`
3. **Apply setting** via appropriate manager class
4. **Test** with test device

## Next Steps

- [ ] Add device authentication (Firebase Auth)
- [ ] Create web dashboard for config management
- [ ] Add bulk device updates
- [ ] Implement config rollback
- [ ] Add config validation/schema
- [ ] Create monitoring dashboard

## Support

For issues or questions:
- Check device logs: `adb logcat | grep ElderCare`
- Review Firestore data in Firebase Console
- Check network connectivity
