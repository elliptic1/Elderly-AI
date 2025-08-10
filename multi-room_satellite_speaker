Here’s the multi-room plan, straight to the point. TL;DR: don’t try to make “extra rooms” with a random Bluetooth speaker/mic—use additional Pi “satellites” over Wi-Fi and coordinate them. Bluetooth full-duplex voice on Linux is a mess (HFP mono, nasty stack glue, flaky). Wi-Fi + WebRTC (or RTP) is clean, scalable, and lets us arbitrate which room is “active”.

# Goals

* Two (or more) rooms can hear the check-in and reply.
* Only **one** OpenAI voice session runs at a time.
* The device closest to the speaker becomes **active talker**; others mute.
* Setup remains simple: same onboarding flow; user “adds another room”.

# Option A (RECOMMENDED): Multi-device over Wi-Fi (Hub & Satellites)

Each room is a Pi 4 with mic+speaker. One is **hub**, others are **satellites**. All are claimed to the same account and **grouped** into a “home”.

## Why not Bluetooth satellites?

* **HFP/HSP** for mic is low-bitrate, mono, requires BlueZ + oFono/ModemManager hacks on Pi; switching between A2DP (speaker) and HFP (mic) tears down the stream. Terrible latency/quality for far-field.
* **LE Audio** (LC3) is not mature on Pi; multi-stream + bidirectional still bleeding-edge.
* You’ll spend weeks fighting the stack for worse UX.

## High-level topology

* **Hub**: opens the single Realtime API session to OpenAI on schedule.
* **Satellites**: stream mic audio to the hub and play downstream audio from the hub.
* **Arbitration**: hub picks **active talker** based on VAD/SNR (and optional wake phrase), sends “mute mics” to the rest.
* **Failover**: if hub is offline, a satellite can auto-promote to hub (simple leader election).

## Data model (Firestore)

```
/homes/{home_id}
  owner_uid
  name
  settings: { tz, voice, volume_default }

/devices/{device_id}
  home_id
  role: "hub" | "satellite"
  room: "Living Room"
  status: { last_seen, ip, version }
  audio: { input:"hw:1,0", output:"hw:0,0", gain_db:0 }

/sessions/{home_id}
  lease_owner: device_id
  lease_expires_at: ts
  state: "idle" | "active"
  started_at: ts
  active_room: "Living Room"
```

## Session lease (single session guarantee)

* **Acquire (transaction)**:

  * If `state=="idle"` OR `lease_expires_at < now`, set:

    * `lease_owner=this_device_id`, `state="active"`, `lease_expires_at=now+90s`.
* **Heartbeat**: hub extends lease every 15s.
* **Release**: set `state="idle"` when done or on fatal error.
* Satellites **never** open OpenAI directly unless they win leader election.

## Audio transport (satellite ↔ hub)

* **Protocol**: WebRTC (libwebrtc via `aiortc`) or raw **RTP/Opus** over QUIC/UDP.

  * Uplink: satellite mic → hub (Opus 16 kHz mono).
  * Downlink: hub program mixes/forwards OpenAI TTS stream → satellites (Opus).
* **Why WebRTC?** Built-in jitter buffers, AEC/VAD hooks, NAT-friendly if you ever go cross-LAN (later).
* **Echo control**: PortAudio capture + AEC (SpeexDSP or WebRTC AEC3) at each node.

## Active talker arbitration

* Satellites send VAD stats in metadata every \~100 ms: `{rms, snr, vad(bool)}`.
* Hub selects the stream with max (VAD==true, then highest SNR).
* Hub:

  * Forwards only **active** mic to OpenAI.
  * Sends control message to **non-active**: `mic_gate=true` (local mute).
  * Downlink audio is played on **all** devices (or only the active room—configurable).

## Scheduling

* Schedules live under `/homes/{home_id}` or under hub’s `/devices`.
* **Only the hub** listens to the schedule to fire the check-in.
* If hub offline: satellites run a **leader election** (small Firestore doc `/homes/{home_id}.leader`) identical to lease logic; winner becomes hub until original hub returns.

## Onboarding flow (adding a second room)

1. Boot satellite → BLE onboarding (same as single-device).
2. During claim, the app chooses an existing **home** (or creates one). Backend sets `home_id` on the new device.
3. App tags room name, sets role=`satellite`.
4. Device subscribes to `/homes/{home_id}` and `sessions/{home_id}`.
5. Satellite discovers hub via:

   * Firestore presence (hub’s LAN IP) + **mDNS** (`_eldercare._udp.local`) for local connect.
   * Fallback to TURN/WebRTC if you ever allow WAN mixing (later).

## Control plane messages (hub ↔ satellites)

* Transport: small gRPC or WebSocket over LAN; fallback WebRTC data channel.
* Messages:

  * `HELLO {device_id, room, version}`
  * `AUDIO_UPLINK_START/STOP`
  * `ACTIVE_TALKER {device_id}`
  * `DOWNLINK_PARAMS {latency_ms}`
  * `SETTINGS {volume, voice}`
  * `PING/PONG`

## Failure modes

* Satellite down: hub ignores; session continues with remaining rooms.
* Hub down mid-session: lease expires; one satellite promotes and re-opens the OpenAI session (you’ll get a brief gap—acceptable for MVP).
* Packet loss: WebRTC handles with Jitter + PLC; log MOS to telemetry.

---

# Option B (Alternative): True peer-to-peer, no hub (all devices talk to OpenAI)

All devices open their own Realtime session but acquire a **session-mutex**; only the mutex owner streams audio, others stay read-only (playback). Once a device detects strong local speech, it tries to **steal** the mutex with higher SNR.
**Pros**: No hub bottleneck.
**Cons**: More OpenAI connections, coordination is trickier, risk of “audio flapping”.
**Verdict**: Viable later; stick with Hub/Satellite first.

---

# Why not “extra mic/speaker over Bluetooth” to a single main device?

* **Linux BlueZ** + **HFP** for mic is fragile; A2DP (speaker) and HFP (mic) can’t run high-quality simultaneously. Expect device profile switching, dropouts, and mono 8 kHz audio. LE Audio not ready on Pi for this UX. It will burn time and still sound bad.

---

# Concrete components to build

* **device-agent (hub)**:

  * Firestore listeners (home, session)
  * Lease/heartbeat logic
  * WebRTC up/down mixers
  * OpenAI Realtime client
  * Arbitration + control plane server
* **device-agent (satellite)**:

  * WebRTC uplink to hub
  * Control plane client (receive ACTIVE\_TALKER, settings)
  * Playback engine
* **backend (Firebase + CF)**:

  * `mint-claim-token`, `claim`
  * `homes` CRUD, add device to home
  * Security rules (device scoped to `home_id`; users scoped to `owner_uid`)
* **mobile app**:

  * Add device → choose/create home → set room name
  * Device list (rooms), volume per room, test chime
  * Schedules at home level (and overrides per room, later)

---

# Security & privacy

* All LAN control/audio links are DTLS/SRTP (WebRTC). No raw audio stored by default.
* Firestore rules ensure devices can only read/write their `home_id`.
* Session transcripts saved once per **home**, not per device.

---

# Minimal success criteria (MVP)

1. Two Pis in different rooms join the same home.
2. At schedule time, hub opens session; satellites play audio.
3. Speaking in either room makes that room the **active talker** within ≤300 ms; the other room mutes.
4. Power cycle both; they rejoin automatically and resume roles.

