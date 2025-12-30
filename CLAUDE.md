# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ElderCare Voice Companion is an AI-powered check-in system for remote caregivers. The system schedules voice conversations with elderly users, conducts them via OpenAI's Realtime API, and provides summaries to caregivers via a mobile app.

**Hardware Strategy**: The production device is a custom Android phone running a modified AOSP build with the ElderCare system app pre-installed. The Raspberry Pi implementation (`device/`) serves as a prototype/development platform.

## Architecture

Four main components:

1. **AOSP System App** (`aosp/`) - Android system app for production devices
   - Privileged system app with platform signing
   - Foreground service for voice check-ins
   - OpenAI Realtime API (WebSocket) for voice at 16kHz PCM mono
   - Firebase sync for schedules
   - Target device: Panther (Pixel 7 Pro) or similar
   - See `aosp/README.md` for build instructions

2. **Device Agent** (`device/`) - Python 3.11 application running on Raspberry Pi (prototype)
   - BLE onboarding via `bleak` for initial Wi-Fi setup
   - Firestore listener for schedule synchronization
   - APScheduler for triggering voice sessions
   - OpenAI Realtime API (WebSocket) for voice conversations at 16kHz PCM mono
   - Entry point: `device/src/main.py`

3. **Cloud Functions** (`functions/`) - Firebase Cloud Functions (Node.js 18)
   - Device provisioning and claim token validation
   - Schedule management

4. **Mobile App** (`mobile-android/`) - Android app for caregivers (under development)
   - BLE pairing with devices
   - Schedule CRUD operations
   - Session summary viewing

## AOSP Integration

The `aosp/` directory is synced into the AOSP tree via local manifest at:
```
aosp-panther/.repo/local_manifests/elderly-ai.xml
```

This syncs the Elderly-AI repo to `vendor/elderly-ai/` within the AOSP tree.

### Building the AOSP Image

```bash
cd /path/to/aosp-panther
repo sync
source build/envsetup.sh
lunch eldercare_panther-userdebug
m
```

### AOSP Directory Structure

```
aosp/
├── ElderCareApp/           # System app source
│   ├── Android.bp          # Build file
│   ├── AndroidManifest.xml # Manifest with system permissions
│   └── src/                # Java source
├── config/                 # System configuration
├── device-overlay/panther/ # Device-specific overlays
└── products/               # Product makefiles
```

## Common Commands

### Device (Python)

```bash
# Install system dependency (required for PyAudio)
sudo apt-get install portaudio19-dev

# Install Python dependencies
pip install -r device/requirements.txt

# Run tests
cd device && pytest

# Run voice chat demo
python device/src/voice_chat.py
```

### Cloud Functions (Node.js)

```bash
cd functions

# Install dependencies
npm install

# Run tests
npm test

# Start local emulator
npm run serve

# Deploy to Firebase
npm run deploy
```

## Code Style

Follow `.editorconfig`:
- Python/JS/TS/JSON: 2-space indent
- Java/Kotlin: 4-space indent
- UTF-8, LF line endings, final newline required

## Firestore Data Model

```
/devices/{device_id}
  owner_uid, tz, audio config, status

/devices/{device_id}/schedules/{schedule_id}
  cron expression, enabled flag, prompt variant

/devices/{device_id}/events/{event_id}
  session summaries and alerts

/profiles/{owner_uid}
  user display name, emergency contacts
```

## Key Technical Details

- Device runs as systemd service (`device-agent.service`)
- BLE GATT service at `0xFFF0` with characteristics for SSID, PSK, timezone, claim token, status
- Voice sessions: max 3 min or 20s silence timeout
- Audio: 16-bit 16kHz mono PCM via PortAudio
- Secrets stored in `/var/lib/eldercare/` with restricted permissions
