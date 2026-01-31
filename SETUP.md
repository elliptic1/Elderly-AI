# ElderCare Setup Guide

Complete step-by-step guide to set up the ElderCare system from scratch.

## Prerequisites

### Hardware

- **Device:** Google Pixel 7 or Pixel 7 Pro
- **Development Machine:** Linux (Ubuntu 20.04+ recommended)
  - 16GB+ RAM
  - 300GB+ free disk space
  - USB-A port (for ADB)
- **USB Cable:** USB-C to USB-A (for device connection)
- **Network:** Internet connection for both machines

### Software (Development Machine)

- **OS:** Ubuntu 20.04 LTS or newer
- **Python:** 3.8+
- **Node.js:** 18+ and npm
- **Git:** 2.25+
- **ADB/Fastboot:** Android SDK Platform Tools
- **Java:** OpenJDK 11

### Accounts

- **Firebase Project:** [console.firebase.google.com](https://console.firebase.google.com)
- **OpenAI API Key:** [platform.openai.com](https://platform.openai.com)

## Part 1: AOSP Build Environment

### 1.1 Install Dependencies

```bash
# Update system
sudo apt update && sudo apt upgrade -y

# Install build essentials
sudo apt install -y \
  git-core gnupg flex bison build-essential zip curl zlib1g-dev \
  gcc-multilib g++-multilib libc6-dev-i386 libncurses5 \
  lib32ncurses5-dev x11proto-core-dev libx11-dev lib32z1-dev \
  libgl1-mesa-dev libxml2-utils xsltproc unzip fontconfig \
  python3 python3-pip openjdk-11-jdk

# Install repo tool
mkdir -p ~/bin
curl https://storage.googleapis.com/git-repo-downloads/repo > ~/bin/repo
chmod a+x ~/bin/repo
echo 'export PATH="$HOME/bin:$PATH"' >> ~/.bashrc
source ~/.bashrc
```

### 1.2 Download AOSP Source

```bash
# Create AOSP directory
mkdir -p ~/aosp-panther
cd ~/aosp-panther

# Configure git
git config --global user.name "Your Name"
git config --global user.email "you@example.com"

# Initialize repo (Android 15 for Pixel 7)
repo init -u https://android.googlesource.com/platform/manifest -b android-15.0.0_r1

# Sync source (takes 1-3 hours, ~150GB)
repo sync -c -j$(nproc)
```

### 1.3 Download Pixel 7 Binaries

```bash
# Download proprietary binaries for Pixel 7 (panther)
cd ~/aosp-panther

# Visit https://developers.google.com/android/drivers#pantheraosp_ap2a.240805.005
# Download both vendor and device binaries for AP2A.240805.005

# Extract binaries
./extract-google_devices-panther.sh
./extract-qcom-panther.sh
```

### 1.4 Add ElderCare Customization

```bash
# Clone ElderCare repository
cd ~/workspace
git clone https://github.com/YOUR_USERNAME/Elderly-AI.git
cd Elderly-AI

# Link to AOSP tree
cd ~/aosp-panther
mkdir -p vendor/eldercare
ln -s ~/workspace/Elderly-AI/aosp vendor/eldercare/aosp
```

### 1.5 Build AOSP

```bash
cd ~/aosp-panther

# Set up environment
source build/envsetup.sh

# Select ElderCare product
lunch eldercare_panther-userdebug

# Build (takes 1-3 hours on first build)
m -j$(nproc)
```

**Expected output:**
```
#### build completed successfully (02:15:33 (hh:mm:ss)) ####
```

## Part 2: Device Preparation

### 2.1 Unlock Bootloader

**⚠️ WARNING: This wipes all data on the device!**

```bash
# Enable Developer Options on Pixel 7
# Settings → About Phone → Tap "Build number" 7 times

# Enable OEM Unlocking and USB Debugging
# Settings → System → Developer Options
#   - Enable "OEM unlocking"
#   - Enable "USB debugging"

# Connect device via USB
adb devices
# Device should appear; accept USB debugging prompt on phone

# Reboot to bootloader
adb reboot bootloader

# Unlock bootloader (WIPES DEVICE!)
fastboot flashing unlock

# Confirm on device screen
```

### 2.2 Flash ElderCare System

```bash
cd ~/aosp-panther

# Ensure device is in bootloader mode
# (Power + Volume Down to boot into bootloader)

# Flash all partitions (-w wipes data)
fastboot flashall -w

# Wait for flash to complete (~5-10 minutes)
# Device will reboot automatically
```

### 2.3 Verify Installation

```bash
# Device should boot to fullscreen smiley face (CheckInActivity)

# Connect via ADB
adb shell

# Check system properties
getprop ro.build.id  # Should show: eldercare-{version}
getprop ro.product.name  # Should show: eldercare_panther

# Exit shell
exit
```

## Part 3: Firebase Setup

### 3.1 Create Firebase Project

1. Go to [Firebase Console](https://console.firebase.google.com)
2. Click "Add project"
3. Name: `eldercare-production` (or your choice)
4. Disable Google Analytics (optional)
5. Click "Create project"

### 3.2 Enable Firestore

1. In Firebase Console → Build → Firestore Database
2. Click "Create database"
3. Choose "Start in production mode"
4. Select location (choose closest to your devices)
5. Click "Enable"

### 3.3 Enable Firebase Storage

1. In Firebase Console → Build → Storage
2. Click "Get started"
3. Click "Next" → "Done"

### 3.4 Configure Security Rules

**Firestore Rules:**

Go to Firestore → Rules, replace with:

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    // Device config (read-only for devices)
    match /devices/{deviceId}/config/{document=**} {
      allow read: if true;  // TODO: Add device auth
      allow write: if false; // Only admin SDK
    }
    
    // Device status (write-only for devices)
    match /devices/{deviceId}/status/{document=**} {
      allow write: if true;  // TODO: Add device auth
      allow read: if false;  // Only admin SDK
    }
    
    // Session logs
    match /devices/{deviceId}/sessions/{document=**} {
      allow write: if true;  // TODO: Add device auth
      allow read: if false;  // Only admin SDK
    }
    
    // OTA updates (read-only for all)
    match /updates/{document=**} {
      allow read: if true;
      allow write: if false;  // Only admin SDK
    }
    
    // Default deny
    match /{document=**} {
      allow read, write: if false;
    }
  }
}
```

Click "Publish"

**Storage Rules:**

Go to Storage → Rules, replace with:

```javascript
rules_version = '2';
service firebase.storage {
  match /b/{bucket}/o {
    // OTA packages (public read)
    match /ota-updates/{fileName} {
      allow read: if true;
      allow write: if false;
    }
    
    // Session recordings (device write, admin read)
    match /sessions/{deviceId}/{fileName} {
      allow write: if true;  // TODO: Add device auth
      allow read: if false;  // Only admin SDK
    }
    
    // Default deny
    match /{allPaths=**} {
      allow read, write: if false;
    }
  }
}
```

Click "Publish"

### 3.5 Generate Service Account Key

1. Firebase Console → Project Settings (gear icon)
2. Service Accounts tab
3. Click "Generate New Private Key"
4. Save as `~/workspace/Elderly-AI/admin/service-account.json`

**⚠️ IMPORTANT: Never commit this file to git!**

### 3.6 Get Project ID

1. Firebase Console → Project Settings
2. Note the "Project ID" (e.g., `eldercare-prod-12345`)
3. Update `PROJECT_ID` in:
   - `aosp/ElderCareApp/src/com/eldercare/app/config/RemoteConfigService.java`
   - `aosp/ElderCareApp/src/com/eldercare/app/update/OTAUpdateService.java`

```bash
cd ~/workspace/Elderly-AI

# Update PROJECT_ID
sed -i 's/eldercare-ai-project/YOUR_PROJECT_ID/g' \
  aosp/ElderCareApp/src/com/eldercare/app/config/RemoteConfigService.java \
  aosp/ElderCareApp/src/com/eldercare/app/update/OTAUpdateService.java

# Rebuild and reflash
cd ~/aosp-panther
source build/envsetup.sh
lunch eldercare_panther-userdebug
m -j$(nproc)
fastboot flashall
```

## Part 4: Admin Tools Setup

### 4.1 Install Dependencies

```bash
cd ~/workspace/Elderly-AI/admin

# Run setup script
./setup.sh

# Or manually:
npm install
```

### 4.2 Configure Firebase

```bash
# Ensure service account key is in place
ls -la service-account.json

# Should see: service-account.json (not committed to git)
```

### 4.3 Test Connection

```bash
# Try fetching current update (should return 404 if none published)
node -e "
const admin = require('firebase-admin');
const key = require('./service-account.json');
admin.initializeApp({ credential: admin.credential.cert(key) });
admin.firestore().collection('updates').doc('latest').get()
  .then(doc => console.log('Firebase connected!', doc.exists))
  .catch(err => console.error('Error:', err));
"
```

## Part 5: Initial Device Configuration

### 5.1 Get Device ID

```bash
# Connect device via ADB
adb devices

# Get Android ID (unique device identifier)
DEVICE_ID=$(adb shell settings get secure android_id | tr -d '\r')
echo "Device ID: $DEVICE_ID"

# Save for later
echo $DEVICE_ID > ~/eldercare-device-id.txt
```

### 5.2 Configure Device

```bash
cd ~/workspace/Elderly-AI/admin

# Set OpenAI API key
node push-config.js $DEVICE_ID --api-key sk-proj-YOUR_OPENAI_API_KEY

# Add check-in schedules
node push-config.js $DEVICE_ID \
  --schedule 09:00 --prompt "Good morning! How are you feeling today?" \
  --schedule 13:00 --prompt "Afternoon check-in. How's your day going?" \
  --schedule 18:00 --prompt "Evening check-in. How was your day?"
```

### 5.3 Verify Configuration

```bash
# Watch device logs
adb logcat -s RemoteConfigService:I ElderCareService:I

# You should see:
# RemoteConfigService: Fetching config for device: abc123...
# RemoteConfigService: Config fetched successfully
# RemoteConfigService: Updating API key
# RemoteConfigService: Config applied successfully
```

## Part 6: Test Check-In

### 6.1 Trigger Test Check-In

**Option A: Wait for scheduled time**

Device will automatically trigger check-in at scheduled times.

**Option B: Trigger manually via ADB**

```bash
# Get first schedule ID
adb shell "
  su
  cd /data/data/com.eldercare.app/shared_prefs
  cat SchedulePrefs.xml | grep 'schedule_id'
"

# Trigger check-in for that schedule
SCHEDULE_ID="default_morning"  # Or ID from above
adb shell am broadcast \
  -a com.eldercare.app.SCHEDULED_CHECKIN \
  -n com.eldercare.app/.receiver.ScheduleReceiver \
  --es schedule_id "$SCHEDULE_ID"
```

### 6.2 Verify Check-In Works

```bash
# Watch logs
adb logcat -s ElderCareService:I OpenAIRealtime:I

# Expected flow:
# ElderCareService: Starting voice session
# OpenAIRealtime: Connecting to OpenAI Realtime API...
# OpenAIRealtime: WebSocket connected
# OpenAIRealtime: Session created
# ElderCareService: Connected to OpenAI Realtime API
# OpenAIRealtime: Assistant started speaking
# (you should hear audio from device)
```

**Speak to the device** - you should hear it respond!

### 6.3 Check Session Logs

```bash
# View session logs on device
adb shell "
  su
  ls -lh /data/data/com.eldercare.app/files/sessions/
  cat /data/data/com.eldercare.app/files/sessions/session_*.txt | tail -50
"

# Check for alerts (if any concerning content detected)
adb shell "
  su
  ls -lh /data/data/com.eldercare.app/files/alerts/
"
```

## Part 7: Deploy Updates

### 7.1 Build OTA Package

```bash
cd ~/workspace/Elderly-AI/aosp
./build-ota.sh v1.0.0
```

### 7.2 Publish OTA Update

```bash
cd ~/workspace/Elderly-AI/admin
node push-ota.js \
  --file ../aosp/ota-packages/eldercare-ota-v1.0.0.zip \
  --upload \
  --notes "Initial production release"
```

### 7.3 Verify Update Applied

```bash
# Watch OTA logs
adb logcat -s OTAUpdateService:I

# After ~6 hours (or force with):
adb shell am startservice com.eldercare.app/.update.OTAUpdateService

# You should see:
# OTAUpdateService: Checking for updates (current: eldercare-dev)
# OTAUpdateService: Latest available: eldercare-v1.0.0
# OTAUpdateService: Update available!
# OTAUpdateService: Downloading update package...
# ... (download progress)
# OTAUpdateService: Checksum verified
# OTAUpdateService: Applying OTA update...
# ... (apply progress)
# OTAUpdateService: Update applied successfully!
# OTAUpdateService: Rebooting in 5 seconds...

# Device reboots to new version
```

## Part 8: Production Deployment

### 8.1 Generate Release Keys

```bash
cd ~/aosp-panther

# Generate release signing keys
development/tools/make_key releasekey '/C=US/ST=CA/L=YourCity/O=ElderCare/OU=Engineering/CN=ElderCare Release'
development/tools/make_key platform '/C=US/ST=CA/L=YourCity/O=ElderCare/OU=Engineering/CN=ElderCare Platform'
development/tools/make_key shared '/C=US/ST=CA/L=YourCity/O=ElderCare/OU=Engineering/CN=ElderCare Shared'
development/tools/make_key media '/C=US/ST=CA/L=YourCity/O=ElderCare/OU=Engineering/CN=ElderCare Media'

# Store keys securely (NOT in AOSP tree)
mkdir -p ~/.android-certs/eldercare
mv *.pk8 *.x509.pem ~/.android-certs/eldercare/

# Update build to use release keys
# Edit: build/target/product/security/Android.mk
# Point to your keys instead of testkey
```

### 8.2 Build Production Image

```bash
cd ~/aosp-panther
source build/envsetup.sh
lunch eldercare_panther-user  # Note: 'user', not 'userdebug'

# Set release version
export BUILD_ID="eldercare-v1.0.0"

# Build with release keys
m -j$(nproc) dist DIST_DIR=~/eldercare-release
```

### 8.3 Lock Bootloader

```bash
# Flash production image
cd ~/eldercare-release
fastboot flashall

# Lock bootloader (IMPORTANT for security)
fastboot flashing lock

# Confirm on device
# Device will wipe and reboot
```

**⚠️ After locking, you can only flash signed OTA updates!**

### 8.4 Mass Deployment Checklist

- [ ] Build production image with release keys
- [ ] Test on 1-3 devices thoroughly
- [ ] Flash all devices
- [ ] Lock bootloaders
- [ ] Register device IDs in Firestore
- [ ] Push initial config to all devices
- [ ] Verify all devices check in successfully
- [ ] Ship devices to deployment locations
- [ ] Monitor via admin dashboard (TODO)

## Troubleshooting

See [TROUBLESHOOTING.md](TROUBLESHOOTING.md) for detailed troubleshooting guide.

**Common issues:**

- **Build fails:** Check disk space, RAM, dependencies
- **Flash fails:** Ensure bootloader unlocked, correct cables
- **Device won't boot:** Try factory reset, flash again
- **No audio:** Check permissions, audio routing
- **Config not syncing:** Check Firebase project ID, network
- **OTA not downloading:** Check Firestore, package URL

## Next Steps

- Read [ARCHITECTURE.md](ARCHITECTURE.md) for system design
- Read [OTA_UPDATES.md](OTA_UPDATES.md) for update management
- Read [REMOTE_CONFIG.md](REMOTE_CONFIG.md) for config management
- Read [TROUBLESHOOTING.md](TROUBLESHOOTING.md) for common issues
- Set up monitoring dashboard (TODO)
- Build caregiver mobile app

---

**Last Updated:** 2024-01-31  
**For Support:** See documentation or file an issue
