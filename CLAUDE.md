# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ElderCare Voice Companion is a Raspberry Pi-powered AI check-in system for remote caregivers. The system schedules voice conversations with elderly users, conducts them via OpenAI's Realtime API, and provides summaries to caregivers via a mobile app.

## Architecture

Three main components:

1. **Device Agent** (`device/`) - Python 3.11 application running on Raspberry Pi
   - BLE onboarding via `bleak` for initial Wi-Fi setup
   - Firestore listener for schedule synchronization
   - APScheduler for triggering voice sessions
   - OpenAI Realtime API (WebSocket) for voice conversations at 16kHz PCM mono
   - Entry point: `device/src/main.py`

2. **Cloud Functions** (`functions/`) - Firebase Cloud Functions (Node.js 18)
   - Device provisioning and claim token validation
   - Schedule management

3. **Mobile App** (`mobile-android/`) - Android app for caregivers (under development)
   - BLE pairing with devices
   - Schedule CRUD operations
   - Session summary viewing

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
