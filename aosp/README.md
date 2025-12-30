# ElderCare AOSP Integration

This directory contains the AOSP (Android Open Source Project) components for the ElderCare Voice Companion device.

## Overview

The ElderCare device is a custom Android phone running a modified AOSP build with a pre-installed system app that conducts AI-powered voice check-ins with elderly users.

## Directory Structure

```
aosp/
├── Android.bp              # Top-level build file
├── AndroidProducts.mk      # Product registration
├── vendorsetup.sh          # Lunch combo registration
├── ElderCareApp/           # System app source
│   ├── Android.bp          # App build file
│   ├── AndroidManifest.xml # App manifest
│   ├── src/                # Java source code
│   ├── res/                # Resources
│   └── proguard-rules.pro  # ProGuard configuration
├── config/
│   └── eldercare-permissions.xml  # Pre-granted permissions
├── device-overlay/
│   └── panther/            # Panther-specific overlays
│       └── res/values/config.xml
└── products/
    └── eldercare_panther.mk  # Product makefile for Panther
```

## Integration with AOSP

This repo is synced into the AOSP tree via local manifest. The local manifest is stored at:

```
aosp-panther/.repo/local_manifests/elderly-ai.xml
```

This syncs the Elderly-AI repo to `vendor/elderly-ai/` within the AOSP tree.

## Building

### Prerequisites

1. Full AOSP source tree (aosp-panther)
2. Panther device binaries

### Build Steps

```bash
# Navigate to AOSP root
cd /path/to/aosp-panther

# Sync including Elderly-AI
repo sync

# Set up environment
source build/envsetup.sh

# Select ElderCare product
lunch eldercare_panther-userdebug

# Build
m
```

### Building Just the App

```bash
# From AOSP root, after lunch:
m ElderCareApp
```

## System App Details

### ElderCareApp

The main system app with the following components:

- **ElderCareService**: Foreground service that conducts voice check-ins
- **BootReceiver**: Starts scheduling on device boot
- **ScheduleReceiver**: Triggers check-ins at scheduled times
- **SetupActivity**: Initial device configuration UI

### Permissions

As a privileged system app signed with the platform key, ElderCareApp has:

- `RECORD_AUDIO` - Microphone access for voice conversations
- `MODIFY_AUDIO_SETTINGS` - Audio routing control
- `FOREGROUND_SERVICE_MICROPHONE` - Foreground service with mic
- `SCHEDULE_EXACT_ALARM` - Precise scheduling for check-ins
- `RECEIVE_BOOT_COMPLETED` - Auto-start on boot

### Audio Configuration

Matches the OpenAI Realtime API requirements:
- Sample Rate: 16000 Hz
- Channels: Mono (1)
- Bit Depth: 16-bit PCM

### Session Limits

- Maximum duration: 3 minutes
- Silence timeout: 20 seconds

## Development Workflow

### Making Changes to ElderCareApp

1. Edit files in `Elderly-AI/aosp/ElderCareApp/`
2. Commit and push to GitHub
3. In AOSP tree: `repo sync vendor/elderly-ai`
4. Rebuild: `m ElderCareApp`

### Testing on Device

```bash
# Install updated app
adb install -r $OUT/system/priv-app/ElderCareApp/ElderCareApp.apk

# Or for full system image
adb reboot bootloader
fastboot flashall
```

## Related Components

- **device/** - Raspberry Pi agent (legacy, being replaced)
- **functions/** - Firebase Cloud Functions
- **mobile-android/** - Caregiver mobile app

## Configuration

Device-specific configuration is stored in Firebase Firestore:

- `/devices/{device_id}` - Device settings
- `/devices/{device_id}/schedules/{schedule_id}` - Check-in schedules
- `/devices/{device_id}/events/{event_id}` - Session summaries
