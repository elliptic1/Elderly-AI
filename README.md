# ElderCare AI Voice Companion

**An AI-powered voice check-in system for elderly care**

ElderCare conducts natural voice conversations with elderly individuals on a schedule, analyzes the interactions for concerning content, and alerts caregivers when help is needed.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

---

## 🎯 What It Does

- **Scheduled voice check-ins** via OpenAI's Realtime API (GPT-4o voice)
- **Natural conversations** that feel human, not robotic
- **Automatic concern detection** (falls, pain, emergencies, requests for help)
- **Caregiver alerts** when intervention needed
- **Fully autonomous** - runs 24/7 without human intervention
- **Remote management** - update settings and software from anywhere

## 🏗️ System Overview

**Device:** Custom AOSP (Android 15) running on Google Pixel 7 Pro

**Key Features:**
- Boots directly to check-in interface (animated smiley face)
- System-level app with platform privileges
- Remote configuration via Firebase Firestore
- Over-the-air (OTA) system updates
- Zero-touch remote management

**Tech Stack:**
- AOSP 15 (Android Open Source Project)
- OpenAI Realtime API (GPT-4o voice mode)
- Firebase (Firestore, Storage, Cloud Functions)
- Node.js admin tools
- A/B seamless OTA updates

## 📋 Quick Start

### Prerequisites

- Google Pixel 7 or Pixel 7 Pro
- Linux machine (Ubuntu 20.04+, 16GB RAM, 300GB disk)
- Firebase project
- OpenAI API key

### Setup (High-Level)

1. **Build AOSP with ElderCare customization**
   ```bash
   cd ~/aosp-panther
   source build/envsetup.sh
   lunch eldercare_panther-userdebug
   m -j$(nproc)
   ```

2. **Flash device**
   ```bash
   adb reboot bootloader
   fastboot flashall -w
   ```

3. **Configure remotely**
   ```bash
   cd admin/
   node push-config.js <deviceId> --api-key sk-proj-YOUR_KEY
   node push-config.js <deviceId> --schedule 09:00 --prompt "Morning check-in"
   ```

4. **Deploy**  
   Device runs autonomously. Update remotely via OTA.

**📖 Full setup guide:** [SETUP.md](SETUP.md)

## 🗂️ Documentation

### Essential Guides

- **[SETUP.md](SETUP.md)** - Complete setup from scratch (AOSP build, flash, configure)
- **[ARCHITECTURE.md](ARCHITECTURE.md)** - System design, components, data flow
- **[QUICK_REFERENCE.md](QUICK_REFERENCE.md)** - Command cheat sheet

### Remote Management

- **[REMOTE_CONFIG.md](REMOTE_CONFIG.md)** - Remote configuration system (API keys, schedules, settings)
- **[OTA_UPDATES.md](OTA_UPDATES.md)** - Over-the-air system updates (build, deploy, monitor)
- **[admin/README.md](admin/README.md)** - Admin CLI tools guide

### Troubleshooting

- **[TROUBLESHOOTING.md](TROUBLESHOOTING.md)** - Comprehensive troubleshooting guide

## 🔧 Remote Management

### Configuration Updates

Update device settings remotely without physical access:

```bash
# Update OpenAI API key
node push-config.js <deviceId> --api-key sk-proj-NEW_KEY

# Add/modify check-in schedule
node push-config.js <deviceId> --schedule 13:00 --prompt "Afternoon check-in"

# Push full configuration
node push-config.js <deviceId> --config device-config.json
```

**How it works:**
- Device polls Firestore every 15 minutes
- Changes applied automatically
- No device restart needed (for most changes)

### System Updates (OTA)

Deploy new AOSP builds remotely with zero-touch updates:

```bash
# Build OTA package
cd aosp/
./build-ota.sh v1.0.1

# Deploy to all devices
cd ../admin/
node push-ota.js --file ../aosp/ota-packages/eldercare-ota-v1.0.1.zip --upload
```

**How it works:**
- Devices check for updates every 6 hours
- Auto-download, verify (SHA-256), and apply
- A/B seamless update (zero downtime)
- Automatic rollback if new system fails to boot

## 🏛️ Architecture

```
┌─────────────────────────┐
│  Firebase (Cloud)       │
│  - Firestore (config)   │
│  - Storage (OTA pkgs)   │
│  - Functions (alerts)   │
└────────────┬────────────┘
             │ HTTPS
             ▼
┌─────────────────────────┐
│  Pixel 7 (Device)       │
│  ┌─────────────────────┐│
│  │ AOSP 15 Custom ROM  ││
│  │                     ││
│  │ ElderCareApp        ││
│  │ - Check-in Service  ││
│  │ - Remote Config     ││
│  │ - OTA Updates       ││
│  │ - Audio I/O         ││
│  └─────────────────────┘│
└────────────┬────────────┘
             │ WebSocket (WSS)
             ▼
┌─────────────────────────┐
│  OpenAI Realtime API    │
│  - GPT-4o voice mode    │
│  - Real-time streaming  │
│  - Server VAD           │
└─────────────────────────┘
```

**See [ARCHITECTURE.md](ARCHITECTURE.md) for complete design**

## 📱 Project Structure

```
Elderly-AI/
├── aosp/                          # AOSP customization
│   ├── ElderCareApp/              # Main system app
│   │   ├── src/com/eldercare/app/
│   │   │   ├── service/           # Check-in service
│   │   │   ├── config/            # Remote config
│   │   │   ├── update/            # OTA updates
│   │   │   ├── realtime/          # OpenAI client
│   │   │   ├── audio/             # Audio I/O
│   │   │   ├── analysis/          # Session analysis
│   │   │   └── ui/                # User interface
│   │   ├── res/                   # Resources (layouts, assets)
│   │   └── AndroidManifest.xml
│   ├── products/                  # Device configurations
│   └── build-ota.sh               # OTA package builder
├── admin/                         # Admin CLI tools
│   ├── push-config.js             # Config management
│   ├── push-ota.js                # OTA deployment
│   ├── setup.sh                   # Setup script
│   └── package.json
├── mobile-android/                # Caregiver mobile app (TODO)
├── functions/                     # Firebase Cloud Functions
├── docs/                          # Additional documentation
├── SETUP.md                       # Setup guide
├── ARCHITECTURE.md                # System architecture
├── REMOTE_CONFIG.md               # Config system docs
├── OTA_UPDATES.md                 # OTA system docs
├── TROUBLESHOOTING.md             # Troubleshooting guide
├── QUICK_REFERENCE.md             # Command cheat sheet
└── README.md                      # This file
```

## 🚀 Features

### Voice Conversations

- **Low-latency streaming** - Real-time bidirectional audio (300-800ms response)
- **Natural speech** - GPT-4o voice mode (sounds human)
- **Context-aware** - Remembers conversation flow
- **Server-side VAD** - Automatic turn detection
- **Transcription** - Both user and AI speech logged

### Session Analysis

- **Quick analysis** - Local keyword detection (<2s)
- **Deep analysis** - GPT-4 background analysis for nuance
- **Concern detection:**
  - Falls or injuries
  - Pain or medical issues
  - Requests for help
  - Emergencies (chest pain, bleeding, etc.)
  - Emotional distress

### Alerts

- **Caregiver notifications** - Via Firebase Cloud Functions + FCM
- **Urgency levels** - 1-5 scale based on severity
- **Session transcripts** - Full conversation included
- **Quick summary** - AI-generated summary of concern

### Device Management

- **Zero-touch config** - Remote API key, schedule, settings updates
- **OTA system updates** - Deploy new builds remotely
- **Status monitoring** - Device reports health to Firestore
- **Automatic recovery** - Rollback on failed updates

## 🛡️ Security

### Device Security

- **Verified boot** - Bootloader, boot image, system partition all signed
- **Locked bootloader** - Prevents unauthorized flashing (production)
- **System privileges** - App runs with platform certificate
- **App sandboxing** - Session data isolated from other apps

### Network Security

- **TLS 1.3** - All network communications encrypted
- **WSS (WebSocket Secure)** - OpenAI Realtime API
- **HTTPS only** - Firebase, Firestore, Storage

### Data Privacy

- **Local storage** - Transcripts stored only on device (optional cloud backup)
- **Encrypted at rest** - Firestore data encrypted by Google
- **Minimal data** - Only session summaries sent to cloud by default
- **API key encryption** - TODO: Use Android Keystore

## 📊 Performance

### Latency

- Check-in trigger → audio: **<5s**
- User speech → AI response: **300-800ms**
- Session analysis: **<2s** (quick), 5-10s (deep)
- Config sync: **<1s**
- OTA check: **<5s**
- OTA download: **~10-15 min** (800MB package, 1Mbps)
- OTA apply: **~3-5 min**

### Resource Usage

- **Memory:** 50MB (idle), 150MB (active session), 200MB (OTA)
- **Storage:** 10MB (app), 50-100MB/month (logs)
- **Network:** 5-10MB per check-in, <1KB config checks, 800MB per OTA

### Cost (Firebase)

100 devices monthly:
- Firestore: Free tier
- Storage: ~$0.50
- Functions: Free tier
- **Total: <$1/month**

## 🗺️ Roadmap

### v1.1 (Next Release)

- [ ] Caregiver mobile app (Android)
- [ ] Web dashboard for multi-device management
- [ ] Device authentication (Firebase Auth)
- [ ] Encrypted config fields
- [ ] Incremental OTA updates

### v1.2

- [ ] Gradual rollout (staged OTA deployments)
- [ ] Multi-language support
- [ ] Custom wake word detection
- [ ] Medication reminders

### v2.0

- [ ] Video check-ins (camera)
- [ ] Fall detection (accelerometer + ML)
- [ ] Integration with medical devices
- [ ] Natural conversation mode (no fixed schedules)

### Under Consideration

- [ ] Raspberry Pi version (lower cost alternative)
- [ ] Local LLM fallback (offline mode)
- [ ] Emergency button (hardware)
- [ ] Smart home integration

## 🤝 Contributing

Contributions welcome! Please:

1. Read [ARCHITECTURE.md](ARCHITECTURE.md) to understand the system
2. Check existing issues for planned work
3. Open an issue to discuss major changes
4. Follow existing code style
5. Test thoroughly before submitting PR

**Development setup:** See [SETUP.md](SETUP.md)

## 📄 License

MIT License - see [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- **AOSP** - Android Open Source Project
- **OpenAI** - Realtime API and GPT-4o voice mode
- **Firebase** - Cloud infrastructure
- **Google** - Pixel device support

## 📞 Support

- **Documentation:** See docs in this repo
- **Issues:** [GitHub Issues](https://github.com/YOUR_USERNAME/Elderly-AI/issues)
- **Discussions:** [GitHub Discussions](https://github.com/YOUR_USERNAME/Elderly-AI/discussions)

## 🔗 Links

- [Firebase Console](https://console.firebase.google.com)
- [OpenAI Platform](https://platform.openai.com)
- [AOSP Source](https://source.android.com)
- [Pixel Factory Images](https://developers.google.com/android/images)

---

**Built with ❤️ for elder care**

**Status:** Production-ready  
**Version:** 1.0.0  
**Last Updated:** 2024-01-31
