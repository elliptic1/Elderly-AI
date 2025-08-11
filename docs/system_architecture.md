# System overview

* **HW**: Raspberry Pi 4 (2–4GB is fine), USB mic (Snowball iCE), powered speakers.
* **OS**: Raspberry Pi OS Lite (64-bit).
* **Audio stack**: ALSA + PipeWire (or PulseAudio). Use **PortAudio** via `pyaudio` (Python) or **GStreamer** if you want VAD/AGC later.
* **Lang/runtime**: Python 3.11.
* **Process manager**: `systemd`.
* **BLE**: BlueZ + `bleak` (Python) exposing a custom GATT for onboarding.
* **Network**: DHCP + `wpa_supplicant`.
* **Scheduling**: APScheduler (cron/interval) running in the main agent.
* **Cloud**: Firebase (Auth + Firestore). Optional: Cloud Functions for device provisioning & schedule writes.
* **LLM voice**: OpenAI Realtime API (WebSocket). Use PCM 16-bit, 16kHz mono.

---

# Boot flow (cold start → ready)

1. **System boots** → `device-agent.service` (systemd) starts after network.target but does not require Wi-Fi (it can start in offline mode).
2. **Audio init**

   * Detect/default ALSA devices; persist selected `hw:` IDs in a small TOML config.
   * Run a quick loopback test (record 1 sec → play back). Log failures.
3. **Identity & keys**

   * Generate device keypair on first boot: `ed25519` stored in `/var/lib/eldercare/device.key`.
   * Create a **device\_id** = hash(pubkey). Persist.
4. **BLE state**

   * If **no Wi-Fi creds** or **can’t reach Firebase**, start **BLE onboarding** (GATT):

     * Service `0xFFF0` “Onboarding”

       * Char `0xFFF1` `ssid` (write)
       * Char `0xFFF2` `psk` (write no resp)
       * Char `0xFFF3` `timezone` (write, Olson ID)
       * Char `0xFFF4` `claim_token` (write; from your mobile app)
       * Char `0xFFF5` `status` (notify: {idle, applying\_wifi, online, error:…})
   * On write of creds → write `/etc/wpa_supplicant/wpa_supplicant.conf`, `wpa_cli reconfigure`, probe connectivity, then close BLE advertising.
5. **Claim with backend**

   * Mobile app signs in (Firebase Auth), gets **claim\_token** from your backend.
   * Device posts `{device_id, pubkey, claim_token}` to a Cloud Function (HTTPS) which validates and writes:

     * `devices/{device_id}` doc: `{owner_uid, tz, audio_prefs, created_at, device_pubkey_fpr, status:"online"}`
     * Sets minimal custom token for device (service-account exchange) or writes a short-lived **device\_access\_token** in `devices/{device_id}/runtime`.
   * Device uses that token to **sign into Firebase** (REST) and establish Firestore session.
6. **Schedule sync**

   * Subscribe to `devices/{device_id}/schedules/*` (or a single `devices/{device_id}` doc with an array).
   * Normalize to local tz (IANA). Build local jobs in APScheduler.
   * Persist local cache in `/var/lib/eldercare/schedules.json` for offline operation.

---

# Normal runtime

* **Main loop** (single process, cooperative):

  * Firestore listener → updates schedules atomically.
  * APScheduler triggers → start voice session job.
  * Health pings every N minutes (`devices/{device_id}.status.last_seen`).
  * Local watchdog: if Firestore idle or time skew detected, resync NTP (`systemd-timesyncd`).

---

# Voice session (on schedule)

1. **Prompt build**

   * Static system prompt + per-user profile (name, preferred greeting) pulled from `profiles/{owner_uid}`.
   * Example: “You are a gentle daily check-in companion for {displayName}. Start by asking if they’re okay…”
2. **Realtime connection**

   * Open WebSocket to OpenAI Realtime endpoint.
   * Send **input\_audio.stream** frames from the mic (16kHz, 16-bit PCM chunks \~20ms).
   * Receive TTS audio frames; stream to speaker in real time (jitter buffer \~200ms).
3. **Session policy**

   * Max session length (e.g., 3 min) or silence timeout (e.g., 20 s).
   * If negative sentiment / “help” detected → raise **alert event** `devices/{device_id}/events` with transcript and confidence; optionally trigger push to caregiver app.
4. **Persistence**

   * Store compact transcript in Firestore (or GCS if long): `{started_at, duration_s, summary, disposition: ok|no_response|needs_attention}`.
5. **Privacy**

   * No raw audio is stored by default; only transcript + derived signals. Gate with a per-device policy flag if you ever need audio retention.

---

# Mobile onboarding app (BLE + cloud)

* **BLE pairing UX**

  * Scan → “ElderCare-XXXX” → connect → write characteristics: `ssid`, `psk`, `timezone`, get `status` notifications.
  * Show spinner until status `online`.
* **Claim**

  * After BLE connected, call backend to mint `claim_token`, write it via BLE.
  * On device `online`, app shows success and device name.
* **Schedule editor**

  * CRUD to `devices/{device_id}/schedules`: docs like

    ```
    {
      id: auto,
      cron: "0 9 * * *",        // 9:00 daily
      daysOfWeek: [1,2,3,4,5],  // optional override
      enabled: true,
      promptVariant: "daily_checkin_v1"
    }
    ```
  * App writes tz separately; device applies tz to cron.

---

# Data model (Firestore)

```
/devices/{device_id}
  owner_uid: string
  tz: "America/New_York"
  audio: { input_device: "hw:1,0", output_device: "hw:0,0", gain_db: 0 }
  status: { last_seen: ts, ip: "...", version: "1.2.3" }

/devices/{device_id}/schedules/{schedule_id}
  cron: "0 9 * * *"
  enabled: true
  promptVariant: "daily_checkin_v1"

/devices/{device_id}/events/{event_id}
  type: "checkin_summary" | "alert"
  started_at: ts
  duration_s: number
  transcript_summary: string
  disposition: "ok" | "no_response" | "needs_attention"

/profiles/{owner_uid}
  displayName: string
  preferredName: string
  emergencyContacts: [{name, phone}]
```

---

# Security model

* **Transport**: BLE pairing uses LE Secure Connections; require a short pairing code printed on the device or spoken once (“Your code is 4-7-1-2”).
* **Provisioning**: Device only accepts `claim_token` valid for 10 minutes (signed by backend).
* **Auth to Firebase**: Device uses a **custom token** minted by your backend with a limited set of Firestore rules:

  * Device can read/write only `devices/{device_id}/…`.
  * Caregiver app (end-user) uses normal Firebase Auth; rules allow them to manage schedules for devices they own.
* **Secret storage**: Wi-Fi PSK written with root perms, `0600`. Device keys in `/var/lib/eldercare` `0700`.

---

# Autostart & services

**systemd unit** (example):

```
# /etc/systemd/system/device-agent.service
[Unit]
Description=ElderCare Device Agent
After=network-online.target sound.target bluetooth.target
Wants=network-online.target

[Service]
User=eldercare
Group=audio
Environment=PYTHONUNBUFFERED=1
WorkingDirectory=/opt/eldercare
ExecStart=/usr/bin/python3 -m device_agent.main
Restart=always
RestartSec=2
LimitNOFILE=8192

[Install]
WantedBy=multi-user.target
```

* Separate optional service `ble-onboard.service` that **only** runs if agent reports “needs\_onboarding”; otherwise stays inactive.

---

# BLE onboarding service (sketch)

* Python + `bleak` peripheral mode (or use **BlueZ + dbus** directly).
* Expose GATT, accept writes, validate lengths, debounce multi-writes.
* When creds received:

  * Write `wpa_supplicant.conf` atomically.
  * `wpa_cli reconfigure`; wait for `wpa_state=COMPLETED`.
  * Attempt Firebase claim; send `status=online|error:<msg>` notification.

---

# Scheduler details

* Use **APScheduler** with `BackgroundScheduler` and **cron triggers** (tz aware).
* On every schedule change, replace the job. Persist jobs to a small SQLite or JSON file to survive reboots.
* Guardrail: **one session at a time** (file lock).

---

# OpenAI Realtime (Python outline)

* WebSocket client with:

  * Producer: mic → VAD (optional) → chunk → send.
  * Consumer: recv audio chunks → jitter buffer → ALSA write.
* Heartbeats every 15s; auto-reconnect with backoff.
* Configurable voices; input/output sample rates fixed at 16 kHz mono PCM.
* Graceful stop on scheduler cancel or silence timeout.

---

# Updates & observability

* **Updates**: package the agent as a `.deb` or run in Docker; use **Watchtower** if Docker, or roll a simple self-update command that fetches from GCS and restarts the service.
* **Logs**: Python `structlog` to journald + optional Firestore ring buffer (last 200 lines) for remote diagnostics.
* **Metrics**: push basic counters to Firestore (success/fail, avg latency, avg session length).

---

# Failure modes (and handling)

* **No internet**: keep schedules local; attempt session, if fails → mark `no_network` event.
* **Mic/speaker missing**: downgrade to TTS only (play chime + LED) and log `audio_error`.
* **Time skew**: resync via `timedatectl set-ntp true` and `systemd-timesyncd`.
* **Token expired**: refresh via backend (short call with device key signature).

---

# Minimal build checklist

1. Flash OS; create `eldercare` user; enable SSH.
2. Install deps: `python3-venv`, `bluez`, `libasound2-dev`, `portaudio19-dev`.
3. `pip install` core libs: `bleak`, `pyaudio`, `websockets`, `apscheduler`, `structlog`, `google-cloud-firestore` (or REST).
4. Drop systemd unit; `systemctl enable --now device-agent`.
5. Verify:

   * Offline boot → BLE advertising → app writes creds → device online.
   * Firestore schedule write → job appears on device (log shows).
   * At trigger time → session starts, audio flows, transcript saved.

