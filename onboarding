Here’s a focused onboarding architecture you can ship. It covers: first-boot startup, BLE pairing, Wi-Fi credential transfer, device↔account association, and the handoff to cloud so you can control the box from anywhere afterward.

# 1) Components

* **Device (Pi 4)**: `device-agent` (Python) + `ble-onboard` (Python or Go).

  * BlueZ (BLE), ALSA/PipeWire (audio), systemd (autostart), Firestore client (REST/SDK).
* **Mobile app** (iOS/Android): BLE central + Firebase Auth.
* **Backend** (Firebase + Cloud Functions):

  * **Auth**: email/password or OAuth for caregivers.
  * **Provisioning API**: mint short-lived `claim_token`; exchange `device_id`→Firebase **custom token** for the device.
  * **DB**: Firestore: `/devices`, `/owners`, `/profiles`, `/schedules`.

---

# 2) First-boot startup (device)

1. **Autostart**: systemd starts `device-agent`. If it detects **no Wi-Fi** or **cannot reach backend**, it launches `ble-onboard` and starts BLE advertising (`ElderCare-XXXX`).
2. **Identity**: if missing, generate `ed25519` keypair; persist in `/var/lib/eldercare/`.
3. **State**: set `onboarding_state=ADVERTISING` (for logs/LED).

---

# 3) BLE pairing protocol (GATT)

Expose a dedicated **Onboarding Service**. Example 128-bit UUIDs shown for clarity.

**Service**: `0000FFF0-0000-1000-8000-00805F9B34FB`

* `ssid` (write): `0000FFF1-…` (UTF-8)
* `psk` (write no response): `0000FFF2-…` (UTF-8)
* `timezone` (write): `0000FFF3-…` (IANA tz, e.g., `America/New_York`)
* `claim_token` (write): `0000FFF4-…` (opaque base64 from backend)
* `status` (notify): `0000FFF5-…` (JSON: `{state, detail}`)

**Flow (phone app)**
a) Scan → show `ElderCare-XXXX` (XXXX = last 2 bytes of device\_id).
b) Connect; write `ssid`, `psk`, `timezone`.
c) Call backend `/provision/mint-claim-token` (user logged in) → get `claim_token`.
d) Write `claim_token`.
e) Subscribe to `status` notifications.

**Flow (device)**

1. On `ssid/psk` writes: stage to temp, validate lengths.
2. Apply Wi-Fi atomically: write `/etc/wpa_supplicant/wpa_supplicant.conf`, `wpa_cli reconfigure`, wait up to 30s for `COMPLETED`.
3. If online: `status={"state":"WIFI_OK"}`; else `WIFI_FAIL`.
4. On `claim_token`: POST to backend `/provision/claim` with `{device_id, device_pubkey, claim_token}`.
5. Backend returns **device custom token** (Firebase) or a short-lived **device\_access\_token**; device signs in and creates/updates Firestore doc.
6. `status={"state":"CLAIMED"}` then `{"state":"READY"}`; stop advertising; quit `ble-onboard`.
7. `device-agent` begins cloud sync (schedules, settings).

**Security notes**

* Use BLE **LE Secure Connections** pairing (Just Works + extra pairing code). On first connect, device **speaks/displays a 4-digit code**; app must echo it back via a small `pair_code` characteristic before accepting `psk`.
* Zero log of `psk`. Store `wpa_supplicant.conf` mode `0600`.
* `claim_token` TTL ≤ 10 minutes. Single-use.

---

# 4) Device↔account association (backend)

**/provision/mint-claim-token (auth required)**
Input: `{owner_uid, device_hint?}`
Output: `{claim_token}` (JWT signed by backend; embeds `owner_uid`, `exp`).

**/provision/claim (device → backend)**
Input: `{device_id, device_pubkey, claim_token}`
Steps:

* Validate token; bind `device_id`→`owner_uid`.
* Create/merge Firestore:

```
/devices/{device_id} = {
  owner_uid, tz, version, status:{last_seen}, audio:{...}
}
```

* Issue Firebase **custom token** restricted to `/devices/{device_id}/*`.
  Output: `{firebase_custom_token}`

**Device login**: exchange custom token → ID token; initialize Firestore listeners.

**Multiple devices per account**

* Each claim just writes another doc under `/devices` with the same `owner_uid`.
* App lists devices via query: `/devices where owner_uid == currentUser.uid`.
* Shared settings pattern (optional): keep a single `/profiles/{owner_uid}`; devices subscribe to both their `/devices/{device_id}` and `/profiles/{owner_uid}`. (This gives “same settings on all boxes” without duplicating data.)

---

# 5) Post-onboarding remote control (no BLE)

Once Wi-Fi + claim complete:

* **App** updates settings/schedules by writing Firestore docs:

  * `/devices/{device_id}/schedules/{schedule_id}`
  * `/devices/{device_id}` (flags like `enabled`, `volume`, `voice`)
* **Device** has a live listener; applies changes instantly.
* **User anywhere**: since both app and device talk to Firestore over the internet, physical proximity is irrelevant.

(If you need push-style “do it now” commands, either:

* write a `commands` doc and device consumes it (simple), or
* send FCM to a topic `device_{id}`; device agent subscribes and reacts. Firestore-only is usually enough.)

---

# 6) State machine (device side)

```
UNINITIALIZED
  → ADVERTISING (BLE on, Wi-Fi unknown)
    - on ssid+psk written → APPLYING_WIFI
APPLYING_WIFI
  - success → ONLINE_UNCLAIMED
  - fail → ADVERTISING (report WIFI_FAIL)
ONLINE_UNCLAIMED
  - on claim_token write & backend ok → CLAIMED
CLAIMED
  - sign in to Firebase → READY
READY
  - Firestore listeners active; schedules loaded
```

Transitions emit `status` notifications over BLE until BLE shuts down.

---

# 7) Firestore data model (minimal)

```
/devices/{device_id}
  owner_uid: string
  tz: string                // IANA
  status: { last_seen: ts }
  audio: { input:"hw:1,0", output:"hw:0,0", gain_db:0 }
  version: "1.0.0"

/devices/{device_id}/schedules/{schedule_id}
  cron: "0 9 * * *"
  enabled: true
  promptVariant: "daily_checkin_v1"

/profiles/{owner_uid}
  displayName: "Mom"
  emergencyContacts: [{name, phone}]
  defaultScheduleProfile: "weekday_mornings"
```

**Rules sketch (enforced least-privilege)**

* App user (owner) can read/write `devices` where `resource.data.owner_uid == request.auth.uid`.
* Device token can read/write only its own `/devices/{device_id}/…` subtree.

---

# 8) Phone app onboarding UX

1. User logs in → “Add device” → BLE scan (only show `ElderCare-XXXX`).
2. Tap device → enter Wi-Fi SSID/PSK → choose timezone.
3. App calls `/provision/mint-claim-token` → writes `claim_token` via BLE.
4. Show live `status` updates: “Connecting to Wi-Fi… Claimed… Ready.”
5. On success, switch UI to cloud-backed control screen (lists all devices).
6. From now on, all changes write Firestore; no BLE needed unless factory reset.

---

# 9) Edge handling

* **Wi-Fi wrong PSK**: notify `WIFI_FAIL`; keep advertising; allow retry.
* **BLE drop mid-onboarding**: staged creds kept in RAM; require re-send (idempotent writes OK).
* **Factory reset**: hold button 10s → wipe `/var/lib/eldercare`, remove `wpa_supplicant.conf`, restart into ADVERTISING.
* **Two devices, one schedule**: put schedule in `/profiles/{owner_uid}` and have both devices mirror it; device-local overrides can live under `/devices/{id}/schedules`.

---

# 10) Minimal implementation notes

* **BLE peripheral**: Python `bleak` (Linux peripheral supported via BlueZ) or Go + `tinygo-bluetooth`. If Python peripheral support is flaky on your kernel, use BlueZ D-Bus directly (stable).
* **Atomic Wi-Fi apply**: write temp file, fsync, move into place, `wpa_cli reconfigure`, poll `wpa_cli status`.
* **Idempotency**: Re-writing same `claim_token` should be safe; backend treats token as one-time.
* **Observability**: Post onboarding telemetry to `/devices/{id}.status`: `{phase:"onboarding", step:"wifi_ok"}` for remote debugging.

---

This keeps BLE strictly for **secure, local, first-run provisioning**. After that, everything is cloud: the phone app and each device converge in Firestore, which gives you remote control “from across the country” with no port-forwarding, no direct sockets, and clean multi-device support.
