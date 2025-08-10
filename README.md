# ElderCare Voice Companion

ElderCare Voice Companion is a Raspberry Pi–powered, AI-driven check-in system designed for remote caregivers.
It schedules voice conversations with the person being cared for, runs them via the OpenAI Realtime API, captures transcripts, generates summaries, and stores them in Firebase so caregivers can see how their loved one is doing — from anywhere in the world.

---

## Features

* **Hands-Free Check-Ins**
  Device auto-starts on schedule, greets the person, and conducts a natural conversation.
* **Realtime AI Conversation**
  Uses OpenAI Realtime Voice API for low-latency, bidirectional speech.
* **BLE Onboarding**
  First-time setup via secure Bluetooth pairing from the mobile app — Wi-Fi credentials and account association happen without any cables.
* **Cloud-Backed Schedules & Settings**
  All schedules, prompts, and preferences stored in Firebase and sync instantly to the device.
* **Session Summaries & History**
  Summaries of each check-in are generated automatically and visible in the caregiver’s app, with optional full transcripts.
* **Multi-Device Support**
  Multiple devices can be linked to the same caregiver account for coverage in different rooms or locations.

---

## System Architecture

### Components

1. **Raspberry Pi Device Agent**

   * BLE onboarding service (BlueZ + `bleak`)
   * Wi-Fi credential apply via `wpa_supplicant`
   * Firebase listener for schedules/settings
   * APScheduler for local schedule execution
   * Realtime API client for voice sessions
   * Transcript buffering and summary generation
   * Session data upload to Firebase
2. **Firebase Backend**

   * Auth (caregiver + device custom tokens)
   * Firestore for schedules, devices, sessions, transcripts, and profiles
   * Cloud Functions for provisioning, summary generation, and notifications
3. **Mobile App** (iOS & Android)

   * BLE provisioning UI
   * Account sign-in & device claiming
   * Schedule management
   * Session history & summaries
   * Transcript view (optional)
4. **OpenAI Realtime API**

   * Low-latency streaming speech recognition and synthesis
   * Custom system prompts for friendly check-ins

---

## First-Time Setup (Onboarding)

1. **Plug In Device**
   On boot, if no Wi-Fi connection is detected, the Pi enters BLE pairing mode and advertises as `ElderCare-XXXX`.
2. **Pair via Mobile App**
   Caregiver uses the app to find and connect to the device over BLE.
3. **Send Credentials**
   App writes SSID, password, timezone, and a one-time `claim_token` from Firebase to the device.
4. **Device Claims Account**
   Device calls backend with its ID and claim token → receives a Firebase custom token → signs in and updates Firestore.
5. **Ready State**
   BLE shuts down, device syncs schedules and waits for the first check-in.

---

## Scheduled Check-In Flow

1. **Scheduler Fires** (local APScheduler job from Firestore schedule)
2. **Session Start**

   * Create `/sessions/{session_id}` in Firestore
   * Connect to OpenAI Realtime API with conversation prompt
3. **Conversation**

   * Stream mic audio to OpenAI
   * Play TTS responses through speakers
   * Capture live transcript chunks to `/sessionTranscripts/{session_id}`
4. **Session End**

   * Mark transcript `complete=true`
   * Summarize (either on-device or Cloud Function)
   * Update session doc with `summary`, `metrics`, `disposition`
5. **Caregiver Notification** (optional)

   * If `disposition=="needs_attention"` or `no_response`, send FCM push

---

## Data Model (Firestore)

```
/devices/{device_id}
  owner_uid: string
  home_id: string
  tz: string
  status: { last_seen: ts }
  audio: { input: string, output: string, gain_db: number }

/devices/{device_id}/schedules/{schedule_id}
  cron: string
  enabled: bool
  promptVariant: string

/sessions/{session_id}
  device_id: string
  home_id: string
  started_at: ts
  ended_at: ts
  status: string
  disposition: string
  metrics: { duration_s: number, user_speech_s: number }
  summary: { text: string, confidence: number }
  transcript_ref: string|null

/sessionTranscripts/{session_id}
  chunks: [ { t: number, who: "user"|"ai", text: string } ]
  complete: bool
```

---

## Security

* **BLE**: LE Secure Connections + one-time pairing code
* **Wi-Fi Credentials**: written securely to `wpa_supplicant.conf` with `0600` permissions
* **Auth**: Devices authenticate to Firebase via backend-issued custom tokens scoped to their own `device_id`
* **Firestore Rules**:

  * Caregivers can read/write devices they own
  * Devices can only read/write their own subtrees

---

## Development

### Device Agent Requirements

* Raspberry Pi OS Lite (64-bit)
* Python 3.11+
* Dependencies:

  ```
  pip install bleak pyaudio websockets apscheduler google-cloud-firestore structlog
  sudo apt install bluez libasound2-dev portaudio19-dev
  ```
* Systemd unit for autostart

### Firebase Setup

* Enable Auth, Firestore, Cloud Functions
* Create service account for backend provisioning
* Configure Firestore rules

### Mobile App

* Requires BLE central support + Firebase Auth SDK
* Platform channels for BLE → Flutter (or native)

---

## Roadmap

* Multi-room via Wi-Fi hub/satellite mesh
* Cloud-side summarization for consistent caregiver experience
* Wake-word detection for spontaneous interaction
* Caregiver push-to-talk sessions

---

## License

MIT License – See LICENSE file for details.

---

Do you want me to go ahead and also create **an architecture diagram** for this README so it’s visually clear how the device, app, backend, and OpenAI Realtime API fit together? That would make it much easier for devs to grasp the flow at a glance.
