# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Carlink Native is a Kotlin/Java Android AAOS app that connects to a **Carlinkit CPC200-CCPA** USB adapter to project CarPlay and Android Auto onto a car head unit. Target hardware is a 2024 Chevy Silverado with a **gminfo3.7 Intel AAOS** radio.

This is a fork of [lvalen91/carlink_native](https://github.com/lvalen91/carlink_native), developed by YarinT (werksmangm). The fork adds:
- **Video reliability**: enhanced resume logic, delayed keyframe requests to reduce black screens on background/Settings return, exposed "Reset Video Stream" for instant decoder recovery without USB reconnect, improved surface invalidation and decoder stall handling.
- **Google Play readiness**: harmonized package name (`com.werksmangm.cpaaplayer`), clean native-only codebase, optimized for AAOS 12L/14 on GM Intel-based systems.
- **Hardware-accelerated H.264** via async `MediaCodec.Callback` (intentional — see Design Invariants).

Android Auto is **force-enabled on every connection** regardless of phone type. CarPlay and Android Auto are both supported; CarPlay has received more tuning.

## Build Commands

```bash
# Assemble debug APK
./gradlew :app:assembleDebug

# Assemble release APK
./gradlew :app:assembleRelease

# Run lint
./gradlew :app:lint

# Run unit tests
./gradlew :app:test

# Run a single test class
./gradlew :app:test --tests "com.carlink.SomeTestClass"

# Run instrumented tests (requires connected device/emulator)
./gradlew :app:connectedAndroidTest

# Install debug build to connected device
./gradlew :app:installDebug
```

Build config: `compileSdk 36`, `minSdk 32`, `targetSdk 36`, Java 17. App ID is `com.werksmangm.cpaaplayer`. versionCode 61.

### Suppressed lint rules
- `DiscouragedApi` — `Timer.scheduleAtFixedRate` is intentional for microphone timing (see revisions [19], [21])

## Architecture

### Data flow

```
USB (CPC200-CCPA adapter)
    │
    ├──► UsbDeviceWrapper / BulkTransferManager / UsbDeviceManager
    │        Raw USB bulk I/O, permission, device discovery, retry logic.
    │        Video frames bypass the message parser via VideoDataProcessor callback (zero-copy).
    │
    ├──► PacketRingByteBuffer   — 8–64MB video ring buffer (resolution-adaptive)
    │
    ├──► H264Renderer           — async MediaCodec (callbacks), Intel Quick Sync preferred
    │        └──► Surface (HWC overlay)
    │
    ├──► AdapterDriver          — protocol framing, init sequence, 2s heartbeat
    │
    ├──► MessageParser          — binary protocol → typed Message subclasses
    │
    └──► CarlinkManager         — central orchestrator; dispatches to subsystems below
            ├──► DualStreamAudioManager  — 4-stream audio (media/nav/voice/call)
            │        └──► AudioPlaybackManager (one per stream) + AudioRingBuffer
            ├──► MicrophoneCaptureManager — AudioRecord → adapter (Timer-based send)
            ├──► NavigationStateManager  — NaviJSON state → cluster
            ├──► MediaSessionManager     — AAOS media session / album art
            └──► GnssForwarder           — GPS → adapter
```

### Key subsystems

**[H264Renderer.java](app/src/main/java/com/carlink/video/H264Renderer.java)**
Async-mode MediaCodec decoder using `MediaCodec.Callback` on a dedicated `HandlerThread` (`codec_callback_thread`). Input buffer feeding runs on a high-priority `AppExecutors.mediaCodec1()` thread. Key behaviors:
- Intel Quick Sync (`OMX.Intel.hw_vd.h264`) preferred; falls back to `createDecoderByType`
- Frames fed from `PacketRingByteBuffer` (8–64MB, resolution-adaptive, emergency reset at 32MB)
- Three-tier buffer pool (small ≤64KB / medium ≤256KB / large >256KB), 8–24 buffers by resolution
- Startup stabilization: 2s warmup before first reset (prevents Surface-sizing corruption)
- Reset rate limiting: `MIN_RESET_INTERVAL=500ms`, max 3 rapid resets within 5s
- Post-reset keyframe request if decoding stalls after 15 frames or 5s
- Enhanced resume logic: additional/delayed keyframe requests on background return to reduce black screens
- **`reset()`** is exposed to the UI as "Reset Video Stream" — instant decoder recovery without USB reconnect

**[BulkTransferManager.kt](app/src/main/kotlin/com/carlink/usb/BulkTransferManager.kt)**
USB transfer reliability layer. Retry on timeout (exponential backoff: 100→200→400ms), fail immediately on disconnect. Reads in 16KB chunks (USB 2.0 optimized). Validates buffer sizes; max frame 1MB. Dynamic timeout = base + 1ms per 60KB.

**[UsbDeviceManager.kt](app/src/main/kotlin/com/carlink/usb/UsbDeviceManager.kt)**
Device discovery and permission. Known Carlinkit VIDs/PIDs: `0x1314/0x1520`, `0x1314/0x1521`, `0x08e4/0x01c0`. Timer-based permission timeout.

**[DualStreamAudioManager.kt](app/src/main/kotlin/com/carlink/audio/DualStreamAudioManager.kt)**
Manages four concurrent audio streams, each with an independent `AudioRingBuffer` and `AudioPlaybackManager`:
- **Media** (250ms buffer) — `USAGE_MEDIA` / CarAudioContext.MUSIC
- **Navigation** (120ms buffer) — `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` / CarAudioContext.NAVIGATION
- **Voice/Siri** (150ms buffer) — `USAGE_ASSISTANT` / CarAudioContext.VOICE_COMMAND
- **Call** (150ms buffer) — `USAGE_VOICE_COMMUNICATION` / CarAudioContext.CALL

USB thread writes to ring buffers non-blocking. A dedicated playback thread (`THREAD_PRIORITY_URGENT_AUDIO`) drains all streams. GM AAOS uses 8× min buffer size (vs 5× default) due to FAST track denial. Navigation audio has special handling: 250ms warmup skip, 0xFFFF end-marker detection, zero-packet flush, 300ms minimum play duration before stop.

**[AudioPlaybackManager.kt](app/src/main/kotlin/com/carlink/audio/AudioPlaybackManager.kt)**
Single-stream `AudioTrack` wrapper. Dynamic format switching from CPC200 decode types (8kHz mono for calls → 48kHz stereo for media). `PERFORMANCE_MODE_LOW_LATENCY`. Per-track volume control and underrun tracking.

**[CarlinkManager.kt](app/src/main/kotlin/com/carlink/CarlinkManager.kt)**
Central orchestrator. Owns the session lifecycle: `DISCONNECTED → CONNECTING → DEVICE_CONNECTED → STREAMING`. Handles USB attach/detach, init modes (`FULL`, `MINIMAL_PLUS_CHANGES`, `MINIMAL_ONLY`), auto-reconnect (5 attempts, 2–30s exponential backoff), Surface debouncing (120ms throttle), codec recovery limiting (3-reset window, 30s), `PARTIAL_WAKE_LOCK`.

**[AdapterDriver.kt](app/src/main/kotlin/com/carlink/protocol/AdapterDriver.kt)**
Protocol-level driver. Sends the init sequence (120ms between messages), manages the 2-second heartbeat timer, WiFi connect (~600ms after init), tracks performance counters. `start()` accepts `initMode` and `pendingChanges` so only changed config keys are re-sent on reconnect.

**[MessageTypes.kt](app/src/main/kotlin/com/carlink/protocol/MessageTypes.kt)**
All protocol constants, enums, and data classes. Protocol magic: `0x55aa55aa`, header 16 bytes. `SESSION_TOKEN` (0xA3) is AES-128-CBC encrypted. See `documents/reference/firmware/` for protocol details.

**[PlatformDetector.kt](app/src/main/kotlin/com/carlink/platform/PlatformDetector.kt)**
Detects CPU arch (x86_64 = Intel), GM AAOS (manufacturer `Harman_Samsung` or product/device containing `gminfo`), hardware H.264 decoder name, native audio sample rate. Call `detect(context)` once; result passed to `AudioConfig`. `requiresIntelMediaCodecFixes()` and `requiresGmAaosAudioFixes()` gate platform-specific paths.

**Cluster / Navigation ([cluster/](app/src/main/kotlin/com/carlink/cluster/), [navigation/](app/src/main/kotlin/com/carlink/navigation/))**
Car App Library for instrument cluster nav metadata. `CarlinkClusterService` binds to Templates Host. `ClusterMainSession` uses a primary/secondary model — only the first session owns `NavigationManager` and calls `updateTrip()`. `NavigationStateManager` holds accumulated NaviJSON state; detects double-maneuver bursts (≤50ms apart) to populate current + next-step fields without overwriting. Feature is toggleable (default OFF).

**Logging ([logging/](app/src/main/kotlin/com/carlink/logging/))**
Tag-based filtering, multiple listeners (Logcat + file). Nine `LogPreset` profiles from `SILENT` to `VIDEO_PIPELINE`. `VideoDebugLogger.java` and `AudioDebugLogger.java` provide pipeline-level debug logging (NAL units, ring buffer health, underruns, latency breakdown) with 100ms throttling.

## Threading Model

| Thread | Purpose | Priority |
|--------|---------|----------|
| Main | UI, Surface init, state machine | Normal |
| USB Read Loop (daemon) | Bulk reads from adapter | Normal |
| codec_callback_thread (HandlerThread) | MediaCodec async callbacks | Normal |
| AppExecutors.mediaCodec1() | H264Renderer input buffer feeding | URGENT_AUDIO |
| AudioPlayback (in DualStreamAudioManager) | Drains all 4 audio ring buffers | URGENT_AUDIO |
| MicCaptureThread | AudioRecord.read() blocking call | URGENT_AUDIO |
| USB Send Timer | Dispatches mic data to adapter | Normal |
| CoroutineScope(Main) | Navigation state observation | Main dispatcher |

## Design Invariants

- **Video is live UI state, not media.** Late frames must be dropped. Corruption triggers reset, not buffering.
- **H264Renderer uses async MediaCodec callbacks — this is intentional in this fork.** `MediaCodec.Callback` on a dedicated `HandlerThread`. Input feeding on `AppExecutors.mediaCodec1()`. Do not revert to sync polling (`dequeueInputBuffer` loops). The upstream (lvalen91) used sync mode; this fork switched to async for Play readiness and hardware acceleration. The "Reset Video Stream" action calls `H264Renderer.reset()` directly for instant decoder recovery without a USB reconnect.
- **Audio must never stall.** Ring buffers absorb USB jitter. The playback thread is `THREAD_PRIORITY_URGENT_AUDIO`. Never block it.
- **USB thread is non-blocking for both video and audio.** Video writes to `PacketRingByteBuffer`; audio writes to `AudioRingBuffer`. Both drop if full — never block to wait.
- **`Timer.scheduleAtFixedRate` for microphone is intentional.** Coroutines were tested and caused timing jitter that broke mic capture (revision [21]). Do not replace the Timer-based mic send loop with a coroutine.
- **Surface rebind is throttled, not debounced.** One immediate rebind always fires; subsequent rebinds are gated at 120ms. Prevents stale `BufferQueue` black screen without blocking valid resizes.
- **Codec reset is rate-limited.** `MIN_RESET_INTERVAL=500ms`, max 3 rapid resets within 5s window, 2s startup stabilization before first reset. Do not bypass these guards — rapid resets during surface sizing corrupt the callback thread state.
