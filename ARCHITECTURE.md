# ElderCare System Architecture

## Overview

ElderCare is an AI-powered voice companion system designed for elderly care. The system conducts scheduled voice check-ins via OpenAI's Realtime API, analyzes conversations for concerns, and alerts caregivers when needed.

**Current implementation:** Custom AOSP system app running on Google Pixel 7 Pro

## System Components

```
┌─────────────────────────────────────────────────────────────────┐
│                        Cloud (Firebase)                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │
│  │  Firestore   │  │   Storage    │  │  Functions   │          │
│  │  - Configs   │  │  - OTA pkgs  │  │  - Analytics │          │
│  │  - Updates   │  │  - Logs      │  │  - Alerts    │          │
│  │  - Sessions  │  │              │  │              │          │
│  └──────────────┘  └──────────────┘  └──────────────┘          │
└─────────────────────────────────────────────────────────────────┘
                              ▲
                              │ HTTPS
                              │
┌─────────────────────────────┼─────────────────────────────────┐
│                    Elderly Device (Pixel 7)                    │
│                                                                 │
│  ┌────────────────────────────────────────────────────────┐   │
│  │              AOSP Android 15 (Custom)                   │   │
│  └────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌────────────────────────────────────────────────────────┐   │
│  │           ElderCareApp (System Privileged)              │   │
│  │                                                          │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │   │
│  │  │ Check-In     │  │ Remote       │  │ OTA Update   │ │   │
│  │  │ Service      │  │ Config       │  │ Service      │ │   │
│  │  │              │  │ Service      │  │              │ │   │
│  │  │ - WebSocket  │  │              │  │              │ │   │
│  │  │   to OpenAI  │  │ - Polls      │  │ - Polls      │ │   │
│  │  │ - Audio I/O  │  │   Firestore  │  │   Firestore  │ │   │
│  │  │ - Analysis   │  │ - Applies    │  │ - Downloads  │ │   │
│  │  │ - Alerts     │  │   settings   │  │ - Verifies   │ │   │
│  │  │              │  │              │  │ - Applies    │ │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘ │   │
│  │                                                          │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │   │
│  │  │ Schedule     │  │ Audio        │  │ OpenAI       │ │   │
│  │  │ Manager      │  │ Manager      │  │ Realtime     │ │   │
│  │  │              │  │              │  │ Client       │ │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘ │   │
│  └────────────────────────────────────────────────────────┘   │
│                                                                 │
│  ┌────────────────────────────────────────────────────────┐   │
│  │                  UI (Fullscreen)                        │   │
│  │  - Animated smiley face (check-in state)               │   │
│  │  - Setup activity (hidden)                             │   │
│  └────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                              ▲
                              │ WebRTC Audio
                              │
┌─────────────────────────────┼─────────────────────────────────┐
│                    OpenAI Realtime API                         │
│  - GPT-4o voice model                                          │
│  - Real-time audio streaming (16kHz PCM)                       │
│  - Server-side VAD (Voice Activity Detection)                  │
│  - Natural conversation flow                                   │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│               Caregiver Mobile App (Android)                    │
│  - View check-in summaries                                      │
│  - Receive alerts                                               │
│  - Manage schedules                                             │
│  - Configure devices                                            │
└─────────────────────────────────────────────────────────────────┘
```

## Design Decisions

### Why AOSP Custom ROM?

**Benefits:**
1. **System-level control** - Privileged app with platform certificates
2. **Boot-to-app** - Device launches directly into check-in interface
3. **No distractions** - No launcher, no other apps
4. **Reliability** - Can't be uninstalled or disabled
5. **Remote updates** - A/B OTA updates via update_engine
6. **Security** - Locked bootloader, verified boot

**Alternatives considered:**
- **Raspberry Pi** - Simpler but less polished, Linux update complexity
- **Android tablet kiosk mode** - Can be escaped, less reliable
- **Dedicated hardware** - Higher development cost

### Why Pixel 7 Pro?

1. **A/B partitioning** - Seamless OTA updates with rollback
2. **Mainline AOSP support** - Clean, well-documented build process
3. **Good audio hardware** - Quality mic and speaker
4. **Security updates** - Google maintains security patches
5. **Availability** - Easy to source, reasonable cost

### Why OpenAI Realtime API?

1. **Low latency** - Real-time voice streaming (WebRTC)
2. **Natural conversations** - GPT-4o voice mode sounds human
3. **Server-side VAD** - Automatic turn detection
4. **Transcripts included** - Both user and assistant speech
5. **Context awareness** - Can detect concerning content

**Alternatives considered:**
- **Twilio/AssemblyAI** - More complex pipeline
- **On-device Whisper** - Higher latency, worse quality
- **Google Dialogflow** - Less natural, more robotic

### Why Firebase?

1. **Firestore** - Real-time config sync, low-latency reads
2. **Cloud Functions** - Serverless alert processing
3. **Authentication** - Device and caregiver auth
4. **Storage** - OTA packages, session recordings
5. **FCM** - Push notifications to caregiver app

**Alternatives considered:**
- **AWS** - More complex, higher cost for this use case
- **Self-hosted** - Maintenance burden, reliability concerns

## Data Flow

### Check-In Session

```
1. AlarmManager triggers ScheduleReceiver
2. ScheduleReceiver starts ElderCareService (foreground)
3. ElderCareService launches CheckInActivity (fullscreen)
4. Service connects to OpenAI Realtime API (WebSocket)
5. Audio capture starts (16kHz mono PCM)
6. OpenAI responds with greeting
7. Conversation proceeds (bidirectional audio)
8. User speech transcribed, assistant responses recorded
9. Session ends (timeout or natural conclusion)
10. SessionAnalyzer performs quick analysis
11. If concerning content detected → alert caregiver
12. Session logged to local storage + Firestore
13. Deep analysis runs in background (GPT-4)
14. Service stops, UI closes
```

### Remote Config Sync

```
1. RemoteConfigService starts on boot
2. Gets device ID (Android ID)
3. Polls Firestore: devices/{deviceId}/config/current
4. Compares to local config
5. If changed:
   - Update API key → ScheduleManager
   - Update schedules → reschedule alarms
   - Update settings → SharedPreferences
6. Reports status to devices/{deviceId}/status/current
7. Schedules next check (15 minutes)
```

### OTA Update Flow

```
1. OTAUpdateService starts on boot
2. Gets current build ID (ro.build.id)
3. Polls Firestore: updates/latest
4. Compares buildId
5. If new version available:
   a. Downloads package to /data/eldercare/updates/
   b. Verifies SHA-256 checksum
   c. Binds to update_engine
   d. Calls applyPayload() with package path
   e. Update_engine writes to inactive slot
   f. Progress callbacks logged
   g. On completion: reboot
6. Bootloader switches to updated slot
7. New system boots
8. If boot fails 3 times → auto-rollback to old slot
```

## Security Model

### Device Security

**Boot Chain:**
```
Verified Boot (vboot)
  → Bootloader (locked)
    → Boot partition (signed)
      → System partition (signed, dm-verity)
        → ElderCareApp (platform certificate)
```

**Permissions:**
- Runs as `system` user (sharedUserId="android.uid.system")
- Platform certificate (elevated privileges)
- Can't be disabled by user
- Survives factory reset (system app)

### Network Security

**API Communications:**
- OpenAI: WSS (WebSocket Secure) with TLS 1.3
- Firebase: HTTPS only
- Firestore REST API: Bearer token auth (planned)

**OTA Updates:**
- SHA-256 checksum verification
- Package signing (platform keys)
- Verified boot ensures integrity

### Data Privacy

**Local Storage:**
- Session transcripts → `/data/data/com.eldercare.app/files/sessions/`
- Alerts → `/data/data/com.eldercare.app/files/alerts/`
- Not accessible to other apps (app sandboxing)

**Cloud Storage:**
- Session summaries → Firestore (encrypted at rest)
- Audio recordings → Firebase Storage (optional, encrypted)
- Firestore security rules (device auth required)

### API Key Management

**Current:**
- Stored in SharedPreferences
- Updated remotely via Firestore
- Accessible only to ElderCareApp (sandbox)

**Production TODO:**
- Encrypt API keys at rest
- Use Android Keystore
- Rotate keys periodically

## Scalability

### Device Management

**Current capacity:**
- Unlimited devices (Firestore scales)
- Per-device config isolation
- Batch updates via Firestore writes

**Limits:**
- Firestore: 1M reads/day free tier
- Firebase Storage: 5GB free tier
- Cloud Functions: 2M invocations/month free

### OTA Distribution

**Bottlenecks:**
- Package download bandwidth
- Firestore read limits (check for updates)

**Optimizations:**
- CDN for package hosting (Firebase Hosting)
- Stagger update checks (random delay on boot)
- Incremental updates (not yet implemented)

### Cost Estimation (100 devices)

**Per device monthly:**
- Firestore reads: ~8,640 (config) + ~720 (OTA checks) = 9,360
- Firestore writes: ~50 (status reports + sessions)
- Storage: ~100MB (logs + recordings)
- Functions: ~100 invocations (alerts)

**Total (100 devices):**
- Firestore: Free tier covers it
- Storage: ~10GB ($0.50/month)
- Functions: Free tier covers it
- **Total: <$1/month**

At 1,000 devices: ~$10/month  
At 10,000 devices: ~$100/month

## Reliability & Resilience

### Failure Modes

| Failure | Impact | Recovery |
|---------|--------|----------|
| No internet | Can't connect to OpenAI | Retry on next schedule |
| OpenAI API down | Check-in fails | Log locally, retry |
| Invalid API key | Auth failure | Alert admin, use backup key |
| OTA download fails | Update delayed | Retry next check (6h) |
| OTA apply fails | No update | Keep current system, log |
| Device crash mid-update | Boot to old system | A/B rollback automatic |
| Power loss | Session interrupted | Next schedule proceeds |
| Mic failure | Can't capture audio | Alert admin (TODO) |

### Monitoring & Alerts

**Device health checks:**
- Status reports every 15 min → Firestore
- Last check-in timestamp tracked
- Failed check-ins logged

**Alert triggers:**
- Check-in missed (>24h)
- Update failure
- Repeated crashes
- Audio hardware failure
- API auth failure

**Admin dashboard (TODO):**
- Device online/offline status
- Last check-in time
- Current software version
- Failed update count
- Alert history

## Performance

### Latency

- **Check-in trigger to audio**: <5s
- **User speech to AI response**: 300-800ms (OpenAI Realtime)
- **Session analysis**: <2s (local quick analysis)
- **Deep analysis**: 5-10s (background GPT-4)
- **Config update apply**: <1s
- **OTA download**: ~10-15 min (800MB package, 1Mbps)
- **OTA apply**: ~3-5 min (update_engine)

### Resource Usage

**Memory (ElderCareApp):**
- Idle: ~50MB
- During check-in: ~150MB (audio buffers + WebSocket)
- During OTA: ~200MB (download + verification)

**Storage:**
- App: ~10MB
- Logs (per month): ~50-100MB
- OTA package (during update): ~800MB

**Network:**
- Check-in session: ~5-10MB (audio streaming)
- Config sync: <1KB per check
- OTA check: <5KB per check
- OTA download: ~800MB per update

**Battery:**
- Pixel 7 plugged in (not battery-powered)
- Could run on battery: ~8-12 hours (check-ins only)

## Development Workflow

### Local Development

```bash
# 1. AOSP environment setup
cd ~/aosp-panther
source build/envsetup.sh
lunch eldercare_panther-userdebug

# 2. Make changes to ElderCareApp
vim vendor/eldercare/aosp/ElderCareApp/src/...

# 3. Build (incremental)
m ElderCareApp

# 4. Install to connected device
adb install -r out/target/product/panther/system/priv-app/ElderCareApp/ElderCareApp.apk

# 5. View logs
adb logcat -s ElderCareApp:I
```

### Full System Build

```bash
# Build entire system image
m -j$(nproc)

# Flash to device (wipes data)
adb reboot bootloader
fastboot flashall -w
```

### OTA Package Build

```bash
# Build OTA package
cd vendor/eldercare/aosp
./build-ota.sh v1.0.1

# Test via sideload
adb reboot sideload
adb sideload ota-packages/eldercare-ota-v1.0.1.zip

# Deploy to production
cd ../../admin
node push-ota.js --file ../aosp/ota-packages/eldercare-ota-v1.0.1.zip --upload
```

### Testing Checklist

- [ ] Check-in triggers on schedule
- [ ] OpenAI connects and responds
- [ ] Audio capture works (clear, no echo)
- [ ] Audio playback works (volume appropriate)
- [ ] Session analysis detects concerning content
- [ ] Alerts sent on concerning content
- [ ] Config sync updates API key
- [ ] Config sync updates schedules
- [ ] OTA check detects new version
- [ ] OTA downloads and verifies package
- [ ] OTA applies and reboots successfully
- [ ] Rollback works on bad update

## Deployment

### Initial Device Setup

```bash
# 1. Build system image
cd ~/aosp-panther
source build/envsetup.sh
lunch eldercare_panther-userdebug
m -j$(nproc)

# 2. Flash to Pixel 7
adb reboot bootloader
fastboot flashall -w

# 3. Device boots to CheckInActivity (smiley)

# 4. Get device ID
adb shell settings get secure android_id

# 5. Configure via admin tool
cd ~/workspace/Elderly-AI/admin
node push-config.js <deviceId> \
  --api-key sk-proj-YOUR_KEY \
  --schedule 09:00 --prompt "Morning check-in" \
  --schedule 18:00 --prompt "Evening check-in"

# 6. Verify config synced
adb logcat -s RemoteConfigService:I

# 7. Disconnect and deploy
# Device now runs autonomously
```

### Production Deployment (100+ devices)

1. **Batch flash devices** (one-time)
2. **Register in Firestore** (device IDs)
3. **Push config** (batch script)
4. **Ship to locations**
5. **Monitor** (dashboard)
6. **Update remotely** (OTA)

## Future Enhancements

### Planned

- [ ] Caregiver mobile app (in progress)
- [ ] Web dashboard for multi-device management
- [ ] Device authentication (Firebase Auth)
- [ ] Encrypted config fields
- [ ] Incremental OTA updates
- [ ] Gradual rollout (staged updates)
- [ ] Voice sample upload for training
- [ ] Multi-language support
- [ ] Custom wake word detection
- [ ] Fall detection (accelerometer)

### Under Consideration

- [ ] Video check-ins (camera)
- [ ] Medication reminders
- [ ] Emergency button (hardware)
- [ ] Integration with medical devices (glucose, BP)
- [ ] Natural conversation (no fixed schedules)
- [ ] Local LLM fallback (offline mode)
- [ ] Raspberry Pi version (lower cost)

## Known Issues

### Current Limitations

1. **Single user per device** - No multi-tenant support
2. **No offline mode** - Requires internet for check-ins
3. **No incremental updates** - Full OTA packages only
4. **No device auth** - Firestore uses unauthenticated REST
5. **Test keys only** - Need release signing keys for production
6. **Manual setup** - No automated device provisioning
7. **No rollout control** - All devices get update simultaneously

### Workarounds

- Offline mode: Could implement local speech-to-text fallback
- Device auth: Implement Firebase Auth with device credentials
- Release keys: Generate with `development/tools/make_key`
- Rollout: Implement per-device targeting in Firestore

## References

- [AOSP Source](https://source.android.com/)
- [OpenAI Realtime API](https://platform.openai.com/docs/guides/realtime)
- [Firebase Documentation](https://firebase.google.com/docs)
- [Android OTA Updates](https://source.android.com/docs/core/ota)
- [A/B System Updates](https://source.android.com/docs/core/ota/ab)

---

**Last Updated:** 2024-01-31  
**Version:** ElderCare v1.0.0  
**Authors:** Todd Smith, Claude (Anthropic)
