# ElderCare Device Agent (Raspberry Pi)

Voice check-in companion that runs on a Raspberry Pi with ReSpeaker 2-Mic HAT.

## Hardware Requirements

- **Raspberry Pi Zero 2 W** (or Pi 4/5 for better performance)
- **ReSpeaker 2-Mic HAT** (~$12) - microphone array + speaker output + RGB LEDs
- **3W Speaker** connected to speaker terminals
- **USB-C Power Supply** (5V 2.5A recommended)
- **MicroSD Card** (8GB+ with Raspberry Pi OS Lite)

## Software Setup

### 1. Install System Dependencies

```bash
# Update system
sudo apt update && sudo apt upgrade -y

# Install PortAudio for PyAudio
sudo apt install -y portaudio19-dev python3-pip python3-venv

# Install SPI for LED control (already enabled on most Pi images)
sudo raspi-config  # Enable SPI under Interface Options
```

### 2. Install ReSpeaker Driver (Optional for LEDs)

```bash
# The 2-Mic HAT works out of the box for audio
# Only needed if LEDs don't work:
git clone https://github.com/respeaker/seeed-voicecard
cd seeed-voicecard
sudo ./install.sh
sudo reboot
```

### 3. Set Up Python Environment

```bash
cd /path/to/Elderly-AI/device

# Create virtual environment
python3 -m venv venv
source venv/bin/activate

# Install dependencies
pip install -r requirements.txt
```

### 4. Configure Secrets

Create `/var/lib/eldercare/secrets.json` (production) or `~/.eldercare/secrets.json` (dev):

```json
{
  "openai_api_key": "sk-...",
  "device_id": "pi-living-room",
  "firebase_project_id": "your-project-id",
  "timezone": "America/New_York"
}
```

Or use environment variables:
```bash
export OPENAI_API_KEY="sk-..."
export ELDERCARE_DEVICE_ID="pi-living-room"
```

## Running

### Demo Mode (Single Session)

```bash
python -m device.src.main --demo
```

### Normal Operation (With Firebase)

```bash
python -m device.src.main
```

### Test Audio Devices

```bash
python -m device.src.audio_manager
```

## LED Status Indicators

| State | LED Pattern |
|-------|-------------|
| Idle | Dim blue breathing |
| Listening | Green pulsing |
| Speaking | Solid blue |
| Processing | Yellow spinning |
| Error | Red flashing |
| Alert Sent | Red pulsing |

## Systemd Service

Create `/etc/systemd/system/eldercare.service`:

```ini
[Unit]
Description=ElderCare Device Agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=pi
WorkingDirectory=/home/pi/Elderly-AI/device
Environment="PATH=/home/pi/Elderly-AI/device/venv/bin"
ExecStart=/home/pi/Elderly-AI/device/venv/bin/python -m device.src.main
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Enable and start:
```bash
sudo systemctl enable eldercare
sudo systemctl start eldercare
sudo systemctl status eldercare
```

## WiFi Provisioning

If WiFi is not configured, the device starts BLE advertising.
Use the caregiver mobile app to:

1. Scan for "ElderCare-XXXX" device
2. Connect and send WiFi credentials
3. Device will connect and start normal operation

## Architecture

```
device/src/
├── main.py              # Entry point
├── config.py            # Configuration and settings
├── models.py            # Data models
├── audio_manager.py     # PyAudio mic/speaker handling
├── realtime_client.py   # OpenAI Realtime API WebSocket client
├── session_manager.py   # Voice session orchestration
├── session_analyzer.py  # Conversation analysis (local + API)
├── led_controller.py    # ReSpeaker LED status indication
├── ble_onboarding.py    # BLE WiFi provisioning
├── firebase_client.py   # Firestore sync for schedules
└── provisioning.py      # Device claim token handling
```

## Session Flow

1. Schedule triggers (from Firebase or test mode)
2. LED → Processing (yellow spin)
3. Connect to OpenAI Realtime API
4. LED → Listening (green pulse)
5. AI greets the person
6. LED → Speaking (solid blue) when AI talks
7. LED → Listening when waiting for response
8. Session ends after:
   - 3 minutes max duration, OR
   - 20 seconds silence after AI speaks
9. Quick keyword analysis (immediate)
10. Deep GPT analysis (background)
11. If concerning → Alert logged + caregiver notified
12. LED → Idle (dim blue) or Alert (red pulse)

## Development

```bash
# Run tests
pytest

# Format code
black device/src/

# Type check
mypy device/src/
```

## Troubleshooting

### No Audio

```bash
# List audio devices
arecord -l
aplay -l

# Test microphone
arecord -D plughw:1,0 -f cd -t wav test.wav

# Test speaker
aplay -D plughw:1,0 test.wav
```

### LEDs Not Working

```bash
# Check SPI is enabled
ls /dev/spidev*

# Should see /dev/spidev0.0
```

### WebSocket Connection Fails

- Check internet connectivity
- Verify API key is valid
- Check OpenAI API status
