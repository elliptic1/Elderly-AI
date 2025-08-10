Here’s a tight technical spec for **scheduled check-ins → realtime voice → transcript + summary → Firebase → caregiver app**. It assumes the hub/single-device case; multi-room just feeds one active mic to the hub.

# 1) Components

* **Device Agent (Pi)**: Python service (systemd) with three subsystems

  1. **Scheduler** (APScheduler, tz-aware)
  2. **Realtime Voice Client** (OpenAI Realtime via WebSocket/WebRTC)
  3. **Event Pipeline** (transcript streaming + summary writeback)
* **Firebase**: Firestore (data), Cloud Functions (optional summarizer & guards), FCM (optional notifications)
* **Caregiver App**: Firestore listener UI for session history and summaries

# 2) Data model (Firestore)

```
/devices/{device_id}
  owner_uid: string
  home_id: string
  tz: string
  status: { last_seen: ts, version: string }

/devices/{device_id}/schedules/{schedule_id}
  cron: "0 9 * * *"
  enabled: true
  promptVariant: "daily_checkin_v1"

/sessions/{session_id}
  device_id: string
  home_id: string
  started_at: ts
  ended_at: ts|null
  status: "active"|"completed"|"aborted"|"no_response"
  disposition: "ok"|"needs_attention"|"no_response"|null
  metrics: { duration_s: number, user_speech_s: number, ai_speech_s: number }
  summary: { text: string|null, confidence: number|null, model: string|null }
  transcript_ref: "/sessionTranscripts/{session_id}" | null
  alert_level: 0|1|2|3

/sessionTranscripts/{session_id}
  chunks: [ { t: number, who: "user"|"ai", text: string } ]  // paginated or chunked
  complete: boolean
```

Notes:

* Session “header” is in `/sessions/{session_id}` for fast listing. Full transcript is split out to its own doc/collection to keep reads cheap.

# 3) Scheduling → Session lifecycle

**On device boot**

* Load schedules (Firestore listener). Build/refresh APScheduler jobs.

**At trigger time (cron fires)**

1. Create session doc (optimistic):

   * `status="active"`, `started_at=now`, link `device_id`, `home_id`.
   * Create transcript doc with `complete=false`.
2. Build **prompt context**

   * System prompt template (on device or fetched once & cached)
   * Care profile from `/profiles/{owner_uid}` (name, preferences)
   * Recent session signals (last summary/disposition) to add continuity
3. Connect to **OpenAI Realtime**

   * WebSocket/WebRTC; send initial instructions to **start by asking if they’re okay**.
   * Audio I/O: mic → 16kHz PCM upstream, TTS frames downstream → speaker.
   * Timeouts: `no_user_audio_timeout=20s`, `hard_session_cap=180s`.

**While running**

* **Transcript stream**: as partials/finals arrive from the Realtime API:

  * Append to `/sessionTranscripts/{session_id}.chunks` in batches (e.g., every 1–2 seconds or 5–10 lines).
  * Keep local queue; flush on timer to reduce write ops.
* **Signals & metrics**: compute incremental features: user\_speech\_s, silence spans, sentiment flags.
* **Abort conditions**: network down, hard cap reached, or explicit end (“I’m okay, thank you, goodbye”).

**On end**

* Close Realtime connection.
* Mark transcript `complete=true`.
* Compute **disposition** and **summary** (see #4).
* Update `/sessions/{session_id}` with `ended_at`, `status`, `metrics`, `summary`, `disposition`, `alert_level`.
* Optional: send FCM push to caregiver app if `alert_level>0`.

# 4) Summary generation

Two options; pick one for v1:

**A. On-device summary (simpler path)**

* Device generates summary locally with a **text LLM** call (cheap mini model) using the assembled transcript text (or last N turns).
* Prompt: “Summarize in 3–6 sentences: how the person is doing; whether they responded; any concerns; recommended follow-up. Return also a one-word disposition: ok | needs\_attention | no\_response.”
* Write to `/sessions/{session_id}.summary` + `disposition`.

**B. Cloud Function summary (centralized & consistent)**

* Device sets `status="completed"` and writes transcript; triggers a **Cloud Function (onWrite for /sessionTranscripts/{id}.complete==true)**.
* Function pulls transcript, calls LLM, writes `summary` + `disposition` back to `/sessions/{id}`.
* Pros: uniform cost & model control; Cons: extra latency.

**Retention**

* Keep summaries indefinitely; transcripts optional TTL (e.g., 30–90 days) via scheduled function that prunes `/sessionTranscripts`.

# 5) Caregiver app UI / data access

* **List view**: query `/sessions where home_id==X orderBy(started_at desc) limit 50` -> show `status`, `disposition`, `summary.text`, `started_at`, `duration`.
* **Detail view**: optional “View transcript” loads `/sessionTranscripts/{id}` lazily (paginate chunks).
* **Filters**: by `disposition`, date range.

# 6) Firestore rules (sketch)

```
// Caregiver (app) can see sessions for their home(s)
match /sessions/{sid} {
  allow read: if isOwnerOfHome(resource.data.home_id);
  allow write: if isDeviceForHome(request.resource.data.home_id) && isTrustedDevice();
}

match /sessionTranscripts/{sid} {
  allow read: if isOwnerOfHome(get(/databases/$(database)/documents/sessions/$(sid)).data.home_id);
  allow write: if isDeviceForHome(get(/databases/$(database)/documents/sessions/$(sid)).data.home_id);
}

// Helpers implemented via Firebase Functions + custom claims or owner_uid linkage
```

# 7) Device implementation details

* **Realtime client**

  * WS/WebRTC with heartbeats, backoff reconnect.
  * Audio pipeline: PortAudio (PyAudio) capture & playback; small jitter buffer (150–250 ms).
  * Graceful stop: on timeout or “goodbye” intent.
* **Transcript buffering**

  * Maintain `chunk_buffer = []` with timestamps + speaker.
  * Every 1–2 s: `batchWrite(chunks[N..M])` to `/sessionTranscripts/{id}` (or subcollection `/chunks/{page}`).
  * Cap doc size \~1MB; if large, paginate into `/sessionTranscripts/{id}/pages/{k}`.
* **Summary computation**

  * If doing on-device: after end, concatenate transcript (or last 100–200 lines), call LLM once, store result.
* **Metrics**

  * Track `user_speech_s` via VAD or from ASR “user segments”; `duration_s = ended-started`; set `no_response` if user\_speech\_s < threshold.

# 8) Error handling and idempotency

* If session fails after creation:

  * Set `status="aborted"`, `ended_at=now`, `disposition="no_response"` if no user speech detected.
* If device restarts mid-session:

  * On boot, find any `status="active"` older than N minutes → finalize as `aborted`.
* All writes include a `session_epoch` to avoid double-finalize.
* Use Firestore transactions for creating the session header and linking transcript doc.

# 9) Privacy & compliance

* **Default**: store summary + metrics; store transcript, but **audio is never stored**.
* Config flag per device/home: “Store full transcript?” (opt-in).
* PII handling: summaries must not include secrets like Wi-Fi passwords (strip with a simple PII mask if needed).

# 10) Optional notifications

* Cloud Function watches `/sessions/{id}` updates:

  * If `disposition=="needs_attention"` or `no_response` → send FCM to caregiver devices with session\_id and short preview.
  * App opens detail view.

# 11) Minimal pseudo-code (device)

```python
def run_schedule_job(schedule):
    sid = create_session_doc()
    tlog = TranscriptLogger(sid)

    client = RealtimeClient(prompt=build_prompt())
    client.on_partial(lambda seg: tlog.add(seg))
    client.on_final(lambda seg: tlog.add(seg, final=True))

    status = "completed"
    disposition = "ok"
    try:
        client.start(max_seconds=180, silence_timeout=20)
        metrics = client.metrics()
        if metrics.user_speech_s < 2:
            disposition = "no_response"
    except Exception:
        status = "aborted"
        disposition = "no_response"
    finally:
        tlog.flush(final=True)
        summary = summarize_local(tlog.snapshot())  # or signal CF to do it
        finalize_session(sid, status, disposition, summary, metrics)
```

# 12) Cost & performance notes

* Streaming session: keep to ≤3 minutes; summarize once.
* Batch Firestore writes for transcript to minimize ops.
* If summaries are large, compress or store in GCS and reference URL.

---

**Deliverable outcome:**

* Caregiver opens the app → sees a reverse-chronological list of sessions with a **concise summary** and quick disposition badge, taps for details to view the full transcript if desired.
* Device reliably executes on schedule, even after power cycles; every run is captured, summarized, and queryable with minimal reads.
