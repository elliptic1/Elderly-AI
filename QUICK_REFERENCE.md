# ElderCare Quick Reference

## Device Management Cheat Sheet

### Initial Setup

```bash
# Get device ID
adb shell settings get secure android_id

# Set up admin tools
cd admin/
./setup.sh
```

### Configuration Updates

```bash
# Update API key
node push-config.js <deviceId> --api-key sk-proj-NEW_KEY

# Add schedule
node push-config.js <deviceId> --schedule 09:00 --prompt "Morning check-in"

# Push full config
node push-config.js <deviceId> --config device-config.json
```

### System Updates

```bash
# Build OTA package
cd aosp/
./build-ota.sh v1.0.1

# Publish update
cd ../admin/
node push-ota.js --file ../aosp/ota-packages/eldercare-ota-v1.0.1.zip --upload

# Or with external hosting
node push-ota.js \
  --metadata ../aosp/ota-packages/eldercare-ota-v1.0.1.json \
  --url https://cdn.example.com/eldercare-ota-v1.0.1.zip
```

### Device Monitoring

```bash
# View all logs
adb logcat

# Config service logs only
adb logcat -s RemoteConfigService:I

# Update service logs only
adb logcat -s OTAUpdateService:I

# Check-in logs
adb logcat -s ElderCareService:I

# Check current build
adb shell getprop ro.build.id

# Check current slot
adb shell getprop ro.boot.slot_suffix

# Force config sync
adb shell am startservice com.eldercare.app/.config.RemoteConfigService

# Force update check
adb shell am startservice com.eldercare.app/.update.OTAUpdateService
```

### Troubleshooting

```bash
# Reboot device
adb reboot

# Reboot to bootloader
adb reboot bootloader

# Sideload OTA for testing
adb reboot sideload
adb sideload eldercare-ota-v1.0.1.zip

# Switch A/B slots
adb reboot bootloader
fastboot set_active other
fastboot reboot

# Factory reset (last resort)
adb reboot bootloader
fastboot flashall
```

### Build Commands

```bash
# Set up AOSP environment
cd ~/aosp-panther
source build/envsetup.sh
lunch eldercare_panther-userdebug

# Full build
m -j$(nproc)

# OTA package
./vendor/eldercare/build-ota.sh v1.0.1

# Flash to device
adb reboot bootloader
fastboot flashall -w
```

## Update Timing

| What | When | Notes |
|------|------|-------|
| Config sync | Every 15 min | Also on boot |
| OTA check | Every 6 hours | Also on boot |
| Check-ins | Per schedule | User-configured times |

## Firestore Structure

```
devices/
  {deviceId}/
    config/
      current         ← Device polls this
    config-history/
      {timestamp}     ← Config change history
    status/
      current         ← Device reports here
    sessions/
      {sessionId}     ← Check-in logs

updates/
  latest              ← Current OTA update
  history/
    versions/
      {buildId}       ← OTA update history
```

## Common Issues

**Device not syncing config:**
- Check network: `adb shell ping -c 3 8.8.8.8`
- Check logs: `adb logcat -s RemoteConfigService:I`
- Force sync: `adb shell am startservice com.eldercare.app/.config.RemoteConfigService`

**OTA not downloading:**
- Verify Firestore has `updates/latest`
- Check package URL is accessible
- Verify SHA-256 in metadata matches package
- Check device storage: `adb shell df -h /data`

**Check-in not working:**
- Verify API key: `adb logcat | grep "API key"`
- Check audio: `adb shell tinymix` (look for mic/speaker)
- Test OpenAI connectivity: `adb logcat -s OpenAIRealtime:I`

**Device won't boot after update:**
- Bootloader auto-rolls back after 3 failed boots
- Or manually: `fastboot set_active other && fastboot reboot`

## File Locations

**On device:**
- Configs: SharedPreferences (`/data/data/com.eldercare.app/shared_prefs/`)
- Session logs: `/data/data/com.eldercare.app/files/sessions/`
- Alerts: `/data/data/com.eldercare.app/files/alerts/`
- OTA downloads: `/data/eldercare/updates/`

**On dev machine:**
- Admin tools: `~/workspace/Elderly-AI/admin/`
- OTA packages: `~/workspace/Elderly-AI/aosp/ota-packages/`
- AOSP source: `~/aosp-panther/`
- Build output: `~/aosp-panther/out/target/product/panther/`

## Security Checklist

- [ ] Firebase service account key secured
- [ ] Firestore rules configured
- [ ] Release signing keys generated (production)
- [ ] OTA packages hosted on HTTPS
- [ ] Device authentication implemented (production)
- [ ] Config encryption enabled (production)

## Documentation

- **Remote Config:** [admin/README.md](admin/README.md)
- **OTA Updates:** [OTA_UPDATES.md](OTA_UPDATES.md)
- **Remote Config Details:** [REMOTE_CONFIG.md](REMOTE_CONFIG.md)
- **Main README:** [README.md](README.md)

---

For detailed guides, see the full documentation files above.
