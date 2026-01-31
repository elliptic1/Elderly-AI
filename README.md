# ElderCare AI Voice Companion

**An AI-powered voice check-in system for elderly care running on Raspberry Pi**

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

**Device:** Raspberry Pi 4 (or newer) with speaker and microphone

**Key Features:**
- Python-based voice agent running as systemd service
- Remote configuration via Firebase Firestore
- Automatic software updates via git pull
- Zero-touch remote management
- Simple, cost-effective deployment (~$50/device)

**Tech Stack:**
- Python 3.9+ (async/await for WebSocket)
- OpenAI Realtime API (GPT-4o voice mode)
- Firebase (Firestore, Storage, Cloud Functions)
- systemd (service management)
- PortAudio/PyAudio (audio I/O)

## 📋 Quick Start

### Prerequisites

- Raspberry Pi 4 (2GB+ RAM recommended)
- USB microphone or HAT with mic
- Speaker (USB, 3.5mm, or HDMI audio)
- SD card (16GB+)
- Firebase project
- OpenAI API key

### Setup (High-Level)

1. **Flash Raspberry Pi OS Lite**
   ```bash
   # Use Raspberry Pi Imager
   # Select: Raspberry Pi OS Lite (64-bit)
   ```

2. **Run setup script**
   ```bash
   curl -fsSL https://raw.githubusercontent.com/YOUR_USERNAME/Elderly-AI/main/device/install.sh | bash
   ```

3. **Configure remotely**
   ```bash
   cd admin/
   node push-config.js <deviceId> --api-key sk-proj-YOUR_KEY
   node push-config.js <deviceId> --schedule 09:00 --prompt "Morning check-in"
   ```

4. **Deploy**  
   Device runs autonomously. Update remotely via config.

**📖 Full setup guide:** [docs/PI_SETUP.md](docs/PI_SETUP.md)

## 🗂️ Documentation

### Essential Guides

- **[docs/PI_SETUP.md](docs/PI_SETUP.md)** - Complete Pi setup from scratch
- **[docs/PI_ARCHITECTURE.md](docs/PI_ARCHITECTURE.md)** - System design for Pi
- **[QUICK_REFERENCE.md](QUICK_REFERENCE.md)** - Command cheat sheet

### Remote Management

- **[REMOTE_CONFIG.md](REMOTE_CONFIG.md)** - Remote configuration (API keys, schedules)
- **[docs/PI_UPDATES.md](docs/PI_UPDATES.md)** - Software updates (git-based)
- **[admin/README.md](admin/README.md)** - Admin CLI tools

### Development

- **[DEVELOPMENT.md](DEVELOPMENT.md)** - Developer guide
- **[TROUBLESHOOTING.md](TROUBLESHOOTING.md)** - Problem solving

### Alternative Implementations

- **[docs/AOSP_IMPLEMENTATION.md](docs/AOSP_IMPLEMENTATION.md)** - Android/Pixel 7 version (archived)

## 🔧 Remote Management

### Configuration Updates

Update device settings remotely:

```bash
# Update OpenAI API key
node push-config.js <deviceId> --api-key sk-proj-NEW_KEY

# Add/modify check-in schedule
node push-config.js <deviceId> --schedule 13:00 --prompt "Afternoon check-in"

# Push full configuration
node push-config.js <deviceId> --config device-config.json
```

Devices poll Firestore every 15 minutes and apply changes automatically.

### Software Updates

Deploy code updates remotely:

```bash
# Update all devices to latest code
node update-devices.js --all

# Or target specific device
node update-devices.js --device <deviceId>
```

Devices automatically pull latest code from git and restart service.

## 🏛️ Architecture

```
┌─────────────────────────┐
│  Firebase (Cloud)       │
│  - Firestore (config)   │
│  - Functions (alerts)   │
└────────────┬────────────┘
             │ HTTPS
             ▼
┌─────────────────────────┐
│  Raspberry Pi           │
│  ┌─────────────────────┐│
│  │ Raspberry Pi OS     ││
│  │                     ││
│  │ eldercare-agent.py  ││
│  │ - Check-in Service  ││
│  │ - Remote Config     ││
│  │ - Auto Updates      ││
│  │ - Audio I/O         ││
│  └─────────────────────┘│
└────────────┬────────────┘
             │ WebSocket
             ▼
┌─────────────────────────┐
│  OpenAI Realtime API    │
│  - GPT-4o voice mode    │
└─────────────────────────┘
```

## 📱 Project Structure

```
Elderly-AI/
├── device/                        # Raspberry Pi device code
│   ├── eldercare_agent.py         # Main voice agent
│   ├── openai_realtime.py         # OpenAI WebSocket client
│   ├── audio_manager.py           # Audio I/O
│   ├── config_manager.py          # Remote config sync
│   ├── session_analyzer.py        # Concern detection
│   ├── install.sh                 # Device setup script
│   ├── eldercare.service          # systemd service
│   └── requirements.txt           # Python dependencies
├── admin/                         # Admin CLI tools
│   ├── push-config.js             # Config management
│   ├── update-devices.js          # Software updates
│   └── package.json
├── mobile-android/                # Caregiver mobile app (TODO)
├── functions/                     # Firebase Cloud Functions
├── docs/                          # Documentation
│   ├── PI_SETUP.md                # Pi setup guide
│   ├── PI_ARCHITECTURE.md         # Pi architecture
│   ├── PI_UPDATES.md              # Update system
│   └── AOSP_IMPLEMENTATION.md     # Android version (archived)
├── aosp/                          # AOSP implementation (archived)
└── README.md                      # This file
```

## 🚀 Features

### Voice Conversations

- **Low-latency streaming** - Real-time bidirectional audio (~500ms response)
- **Natural speech** - GPT-4o voice mode
- **Context-aware** - Remembers conversation flow
- **Server-side VAD** - Automatic turn detection
- **Transcription** - Both user and AI speech logged

### Session Analysis

- **Quick analysis** - Local keyword detection
- **Deep analysis** - GPT-4 background analysis
- **Concern detection** - Falls, pain, emergencies, distress

### Alerts

- **Caregiver notifications** - Via Firebase + FCM
- **Urgency levels** - 1-5 scale based on severity
- **Session transcripts** - Full conversation included

### Device Management

- **Zero-touch config** - Remote updates via Firestore
- **Automatic updates** - Git pull on schedule
- **Status monitoring** - Device health reports
- **Simple deployment** - One script install

## 💰 Cost

**Per device:**
- Raspberry Pi 4 (2GB): ~$35
- USB microphone: ~$10
- Powered speaker: ~$15
- SD card (16GB): ~$8
- Power supply: ~$8
- **Total: ~$75**

**Operating costs (100 devices):**
- Firebase: <$1/month (free tier covers it)
- OpenAI API: $0.06/min × ~5 min/check-in × 2 check-ins/day = ~$9/device/month

**Total TCO:** ~$75 upfront + ~$9/month/device

## 🛡️ Security

- **TLS 1.3** - All network communications encrypted
- **SSH keys** - Secure remote access
- **API key encryption** - Stored in encrypted config
- **Automatic updates** - Security patches via git
- **Firestore rules** - Device access control

## 📊 Performance

- Check-in trigger → audio: <10s
- User speech → AI response: 500-800ms
- Session analysis: <5s
- Config sync: <2s
- Software update: ~1 min

## 🗺️ Roadmap

### v1.0 (Current)

- [x] Python voice agent
- [x] OpenAI Realtime API integration
- [x] Remote configuration
- [x] Automatic updates
- [x] Session analysis
- [ ] Caregiver alerts (in progress)

### v1.1 (Next)

- [ ] Caregiver mobile app
- [ ] Web dashboard
- [ ] Multi-language support
- [ ] Custom wake word

### v2.0 (Future)

- [ ] Video check-ins (Pi Camera)
- [ ] Fall detection (sensors)
- [ ] Medication reminders
- [ ] Health device integration

## 🤝 Contributing

Contributions welcome! See [CONTRIBUTING.md](CONTRIBUTING.md)

## 📄 License

MIT License - see [LICENSE](LICENSE)

## 🔗 Links

- [Firebase Console](https://console.firebase.google.com)
- [OpenAI Platform](https://platform.openai.com)
- [Raspberry Pi](https://www.raspberrypi.com/)

## 💡 Why Raspberry Pi?

**vs. AOSP/Android:**
- ✅ Simpler development (Python vs Java/AOSP)
- ✅ Faster iteration (no AOSP build, ~2 min vs 2 hours)
- ✅ Lower cost ($75 vs $400+ for Pixel)
- ✅ Standard Linux (easier to debug)
- ✅ More flexible hardware (GPIO, HATs, cameras)

**vs. Commercial tablets:**
- ✅ No distractions (dedicated device)
- ✅ Can't be "escaped" from kiosk mode
- ✅ Easier remote management
- ✅ Lower cost

**Trade-offs:**
- ⚠️ No A/B updates (but git rollback is simple)
- ⚠️ No verified boot (but read-only root possible)
- ⚠️ Requires external speaker/mic (vs integrated)

## 📞 Support

- **Documentation:** See docs/ directory
- **Issues:** [GitHub Issues](https://github.com/YOUR_USERNAME/Elderly-AI/issues)
- **Discussions:** [GitHub Discussions](https://github.com/YOUR_USERNAME/Elderly-AI/discussions)

---

**Built with ❤️ for elder care**

**Platform:** Raspberry Pi  
**Version:** 1.0.0  
**Last Updated:** 2024-01-31

**Note:** An AOSP/Android implementation is also available in the `archive/aosp-implementation` branch. See [docs/AOSP_IMPLEMENTATION.md](docs/AOSP_IMPLEMENTATION.md) for details.
