# OTA (Over-The-Air) System Updates

## Overview

ElderCare devices support **automatic OTA system updates**, allowing you to remotely deploy new AOSP builds without physical access to devices.

**How it works:**
- Build new AOSP system image
- Generate OTA package
- Upload to cloud storage
- Publish update to Firestore
- Devices auto-check, download, verify, apply, and reboot

## Architecture

### A/B (Seamless) Updates

Pixel 7 uses Android's A/B partition system:

```
┌─────────────────┐
│  Slot A (Boot)  │ ← Currently running
│  System, Vendor │
└─────────────────┘
┌─────────────────┐
│  Slot B (Idle)  │ ← Update applied here
│  System, Vendor │
└─────────────────┘
```

**Update flow:**
1. Device downloads OTA to inactive slot (B)
2. `update_engine` applies update in background
3. Device reboots
4. Bootloader switches to updated slot (B)
5. Old slot (A) kept as fallback

**Benefits:**
- Zero downtime (update while running)
- Automatic rollback on boot failure
- Verified boot ensures integrity

### Component Roles

**Device:**
- `OTAUpdateService` - checks Firestore every 6 hours
- Downloads OTA package to `/data/eldercare/updates/`
- Verifies SHA-256 checksum
- Calls `update_engine` API
- Reboots after successful application

**Admin:**
- `build-ota.sh` - generates OTA package from AOSP build
- `push-ota.js` - uploads package and publishes to Firestore

**Firestore:**
- `updates/latest` - current published update
- `updates/history/versions/{buildId}` - update history

## Building OTA Packages

### Prerequisites

- AOSP source tree built successfully
- ~100GB free disk space (for build artifacts)
- Signing keys (uses testkey for development)

### Build Script

```bash
cd ~/workspace/Elderly-AI/aosp
./build-ota.sh v1.0.1
```

**What it does:**
1. Sets up AOSP build environment
2. Builds target-files package (30-60 min)
3. Generates OTA zip from target files
4. Calculates SHA-256 checksum
5. Creates metadata JSON

**Output:**
```
aosp/ota-packages/
  eldercare-ota-v1.0.1.zip      (OTA package ~800MB)
  eldercare-ota-v1.0.1.json     (metadata)
```

### Metadata Format

```json
{
  "buildId": "eldercare-v1.0.1",
  "version": "v1.0.1",
  "buildFingerprint": "google/panther/panther:15/...",
  "packageFile": "eldercare-ota-v1.0.1.zip",
  "packageSize": 838860800,
  "sha256": "a1b2c3d4...",
  "buildDate": "2024-01-31T18:00:00Z",
  "releaseNotes": "ElderCare system update v1.0.1"
}
```

## Publishing Updates

### Option 1: Firebase Storage (Recommended)

Upload and publish in one command:

```bash
cd ~/workspace/Elderly-AI/admin
node push-ota.js \
  --file ../aosp/ota-packages/eldercare-ota-v1.0.1.zip \
  --upload \
  --notes "Security fixes and performance improvements"
```

**What happens:**
1. Uploads ZIP to Firebase Storage (`ota-updates/`)
2. Makes file publicly accessible
3. Publishes metadata to Firestore
4. Saves to update history

### Option 2: External Hosting (S3, CDN, etc.)

If you host packages externally:

```bash
# 1. Upload package to your CDN
aws s3 cp eldercare-ota-v1.0.1.zip s3://my-bucket/ota/
aws s3api put-object-acl --bucket my-bucket --key ota/eldercare-ota-v1.0.1.zip --acl public-read

# 2. Get public URL
PACKAGE_URL="https://cdn.example.com/ota/eldercare-ota-v1.0.1.zip"

# 3. Publish to Firestore
node push-ota.js \
  --metadata ../aosp/ota-packages/eldercare-ota-v1.0.1.json \
  --url "$PACKAGE_URL"
```

## Device Update Process

### Automatic Updates

Devices check for updates:
- **Every 6 hours** while running
- **On boot** (immediate check)

No user interaction required.

### Update Stages

**1. Check**
```
OTAUpdateService: Checking for updates (current: eldercare-v1.0.0)
OTAUpdateService: Latest available: eldercare-v1.0.1
OTAUpdateService: Update available!
```

**2. Download**
```
OTAUpdateService: Downloading update package...
OTAUpdateService: Downloaded: 10 MB
OTAUpdateService: Downloaded: 20 MB
...
OTAUpdateService: Download complete: 800 MB
```

**3. Verify**
```
OTAUpdateService: Checksum verified
```

**4. Apply**
```
OTAUpdateService: Applying OTA update...
OTAUpdateService: Update engine started
OTAUpdateService: Update status: DOWNLOADING (15%)
OTAUpdateService: Update status: VERIFYING (45%)
OTAUpdateService: Update status: FINALIZING (95%)
OTAUpdateService: Update status: NEED_REBOOT (100%)
```

**5. Reboot**
```
OTAUpdateService: Update applied successfully!
OTAUpdateService: Preparing to reboot...
OTAUpdateService: Rebooting in 5 seconds...
```

### Monitoring Updates

**Device logs:**
```bash
adb logcat -s OTAUpdateService:I
```

**Force update check:**
```bash
adb shell am startservice com.eldercare.app/.update.OTAUpdateService
```

**Check current build:**
```bash
adb shell getprop ro.build.id
```

## Rollback & Recovery

### Automatic Rollback

If updated system fails to boot:
1. Bootloader detects failed boot
2. Switches back to previous slot
3. Device boots to working system
4. Update marked as failed

### Manual Rollback

If update caused issues but device boots:

```bash
# Check current slot
adb shell getprop ro.boot.slot_suffix

# Switch to other slot
adb reboot bootloader
fastboot set_active other
fastboot reboot
```

### Recovery

If device won't boot at all:

```bash
# Enter fastboot
# (Power + Volume Down on Pixel 7)

# Flash factory image
fastboot flashall
```

## Testing Updates

### Test on Single Device

Before deploying to all devices:

1. **Build OTA package**
   ```bash
   cd aosp/
   ./build-ota.sh v1.0.1-test
   ```

2. **Sideload to test device**
   ```bash
   adb reboot sideload
   adb sideload aosp/ota-packages/eldercare-ota-v1.0.1-test.zip
   ```

3. **Verify device boots and functions**

4. **If good, publish to production:**
   ```bash
   cd admin/
   node push-ota.js --file ../aosp/ota-packages/eldercare-ota-v1.0.1.zip --upload
   ```

### Gradual Rollout

For staged deployment (not yet implemented):

```javascript
// Future: Per-device targeting
{
  "buildId": "eldercare-v1.0.1",
  "targetDevices": ["device-123", "device-456"],  // Beta group
  // ... rest of metadata
}
```

## Security Considerations

### Current Implementation

**Checksums:**
- SHA-256 verification before applying
- Prevents corrupted downloads
- Detects tampering

**Package signing:**
- Uses AOSP testkey (development)
- Verified boot checks signature
- Rollback protection

### Production Recommendations

1. **Use release keys**
   ```bash
   # Generate production keys
   development/tools/make_key releasekey '/C=US/ST=CA/L=Mountain View/O=ElderCare/OU=Engineering/CN=ElderCare'
   
   # Sign OTA package
   ota_from_target_files --key releasekey target_files.zip ota.zip
   ```

2. **Secure hosting**
   - HTTPS only
   - CDN with DDoS protection
   - Access logging

3. **Update authentication**
   - Add device authentication to Firestore
   - Per-device update authorization
   - Rate limiting

4. **Monitoring**
   - Track update success rates
   - Alert on failures
   - Rollback triggers

## Troubleshooting

### Update not detected

**Check Firestore:**
```bash
firebase firestore:get updates/latest
```

**Check device logs:**
```bash
adb logcat -s OTAUpdateService:I | grep "Checking for updates"
```

**Force check:**
```bash
adb shell am startservice com.eldercare.app/.update.OTAUpdateService
```

### Download fails

**Check URL accessibility:**
```bash
curl -I "https://storage.googleapis.com/..."
```

**Check device network:**
```bash
adb shell ping -c 3 8.8.8.8
```

### Checksum mismatch

**Verify package integrity:**
```bash
sha256sum aosp/ota-packages/eldercare-ota-v1.0.1.zip
```

**Re-upload:**
```bash
node push-ota.js --file eldercare-ota-v1.0.1.zip --upload
```

### Update_engine errors

**Check update_engine status:**
```bash
adb shell dumpsys update_engine
```

**Common error codes:**
- `1` - Download error (network issue)
- `7` - Verification failed (checksum/signature)
- `10` - Payload mismatch (wrong device/build)

**Clear and retry:**
```bash
adb shell stop update_engine
adb shell rm -rf /data/ota_package/*
adb shell start update_engine
```

## Best Practices

### Before Building

1. **Test changes thoroughly**
   - Flash to test device
   - Run all check-in scenarios
   - Verify OpenAI connectivity
   - Test config updates

2. **Version numbering**
   - Use semantic versioning: `v{major}.{minor}.{patch}`
   - Increment major for breaking changes
   - Minor for features
   - Patch for bugs

3. **Release notes**
   - Be specific about changes
   - Note any required actions
   - Warn about breaking changes

### Before Publishing

1. **Test OTA on one device**
   - Sideload package first
   - Verify successful boot
   - Test all functionality

2. **Have rollback plan**
   - Keep previous build handy
   - Know how to switch slots
   - Have factory image ready

3. **Schedule updates**
   - Off-peak hours (3-5 AM)
   - Avoid during check-ins
   - Consider time zones

### After Publishing

1. **Monitor rollout**
   - Watch device logs
   - Check Firestore for status reports
   - Track successful updates

2. **Be ready to rollback**
   - If >10% failure rate, pull update
   - Publish previous version
   - Investigate issues

3. **Document issues**
   - Log any problems
   - Update release notes
   - Fix for next release

## Next Steps

- [ ] Test OTA build process
- [ ] Test sideload on device
- [ ] Set up Firebase Storage
- [ ] Build first OTA package
- [ ] Test remote update end-to-end
- [ ] Generate release signing keys
- [ ] Set up production hosting
- [ ] Implement gradual rollout
- [ ] Add update success tracking
- [ ] Create monitoring dashboard

## Files

**Device code:**
- `aosp/ElderCareApp/src/com/eldercare/app/update/OTAUpdateService.java`

**Build tools:**
- `aosp/build-ota.sh` - OTA package builder

**Admin tools:**
- `admin/push-ota.js` - Update publisher

**Documentation:**
- `OTA_UPDATES.md` - This file

---

Built: 2024-01-31  
Status: Ready for testing
