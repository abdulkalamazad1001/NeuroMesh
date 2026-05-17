# NeuroMesh

> A decentralized multi-agent AI crisis response system that runs entirely offline on Android phones using Gemma 4. When disaster strikes and infrastructure fails, phones in the affected area collaborate over Bluetooth/WiFi-Direct to detect emergencies and coordinate response — without internet, cloud, or central servers.

**Hackathon:** Kaggle Gemma 4 Good Hackathon
**Deadline:** May 19, 2026
**Target Prize Tracks:** Global Resilience ($10K) + Cactus Track ($10K) + LiteRT Track ($10K)

---

## Table of Contents

1. [The Problem](#1-the-problem)
2. [Our Solution](#2-our-solution)
3. [Why This Matters](#3-why-this-matters)
4. [System Architecture](#4-system-architecture)
5. [How It Works — End-to-End Flow](#5-how-it-works--end-to-end-flow)
6. [Tech Stack](#6-tech-stack)
7. [Project Structure](#7-project-structure)
8. [Component Deep Dive](#8-component-deep-dive)
9. [Setup & Build](#9-setup--build)
10. [Testing Guide](#10-testing-guide)
11. [Demo Scenarios](#11-demo-scenarios)
- [Detection Modes](#detection-modes)
12. [Prize Track Alignment](#12-prize-track-alignment)
13. [Known Limitations & Roadmap](#13-known-limitations--roadmap)
14. [Glossary](#14-glossary)

---

## 1. The Problem

When a natural disaster — earthquake, wildfire, flood, building collapse — hits a populated area, the very infrastructure people depend on to call for help is the *first thing to fail*. Cell towers go dark, internet routes break, power grids drop. Studies of recent disasters (Türkiye 2023, Maui wildfires 2023, Kerala floods 2018) consistently show:

- **The first 60 minutes ("golden hour") account for the majority of preventable deaths.**
- **Survivors trapped in rubble or smoke cannot reach 911/112 because all infrastructure-dependent channels are down.**
- **Existing emergency apps assume a working internet connection** — when they're needed most, they're useless.
- **Centralized warning systems (sirens, broadcasts) are blunt** — they can tell you "earthquake" but not "you specifically need to evacuate east, not west, because the bridge is out."

There is a 1–48 hour window after a disaster where:
1. Hundreds of survivors are within Bluetooth/WiFi range of each other.
2. Each one is carrying a phone with a camera, microphone, accelerometer, and a 4GB+ RAM SoC.
3. No external help has arrived yet.

**That collection of phones is, in aggregate, a more powerful sensor and reasoning network than anything the responders bring.** But today they sit isolated, each one a useless brick because it can't reach a cell tower.

---

## 2. Our Solution

**NeuroMesh** turns every Android phone in the disaster zone into a node in a self-organizing AI swarm. Each phone runs three specialized agents built on top of **Gemma 4 E2B** (2B-parameter, 4-bit quantized, ~1.3 GB on disk) using **Google AI Edge LiteRT** for on-device inference:

| Agent | Job |
|---|---|
| **Observer** | Look at the camera frame, listen to the microphone, read the accelerometer, and emit a structured observation ("smoke visible, alarm audible, no seismic activity") |
| **Reasoner** | Fuse observations from this phone *and* neighboring phones, build a multi-step reasoning trace, and produce a confident situation assessment |
| **Action** | Translate the assessment into concrete, life-saving guidance ("Evacuate east via Stairwell B. Do NOT use elevator. Cover face with wet cloth.") |

Phones discover each other via **Android Nearby Connections API** (Bluetooth LE + WiFi-Direct), gossip their assessments through a **TTL-bounded gossip protocol**, and reach a **confidence-weighted consensus** on what is happening — all without ever touching a cell tower or the internet.

The output is not just an alert. It is an **explainable** alert: every conclusion ships with the chain of evidence that produced it ("3 of 5 nearby phones detected smoke + 2 detected fire alarm audio + this device's reasoner concluded HIGH severity fire") so survivors can trust and act on it.

---

## 3. Why This Matters

- **Resilience.** The system works in *exactly* the conditions where centralized systems fail.
- **Speed.** Detection-to-alert is **under 5 seconds** on a mid-range phone — faster than any human-in-the-loop emergency dispatch.
- **Privacy.** No data ever leaves the local mesh. There is no server to compromise. There is no operator to subpoena.
- **Accessibility.** Runs on phones people already own. No new hardware, no subscription, no setup.
- **Trust.** Because the reasoning is transparent and locally verifiable, people can act on the guidance instead of second-guessing it.
- **Global reach.** Works in any country, any language Gemma supports, any disaster type. No regional deployment, no telco partnerships.

---

## 4. System Architecture

```
+--------------------------------------------------------------------+
|                       ANDROID DEVICE (one node)                    |
+--------------------------------------------------------------------+
|  PRESENTATION LAYER                                                |
|  +-- MainActivity      (camera preview + status + alert overlay)   |
|  +-- CrisisAlertActivity (full-screen alert with reasoning trace)  |
|  +-- ViewModels (Main, Mesh, Crisis)                               |
|  +-- Custom Views (MeshStatusIndicator, CrisisAlertView, ...)      |
+--------------------------------------------------------------------+
|  DOMAIN LAYER                                                      |
|  +-- Multi-Agent Pipeline                                          |
|  |   +-- ObserverAgent  (sensors -> Gemma -> Observation)          |
|  |   +-- ReasonerAgent  (observations -> Gemma -> Assessment)      |
|  |   +-- ActionAgent    (assessment -> Gemma -> CrisisAlert)       |
|  +-- ConsensusEngine                                               |
|  |   +-- GossipProtocol (TTL-bounded mesh broadcast)               |
|  |   +-- VotingStrategy (confidence-weighted majority)             |
|  +-- Use Cases                                                     |
|      +-- DetectCrisisUseCase / ShareObservationUseCase /           |
|          GenerateAlertUseCase                                      |
+--------------------------------------------------------------------+
|  INFRASTRUCTURE LAYER                                              |
|  +-- ML: Gemma4ModelRunner (MediaPipe LiteRT), PromptBuilder,      |
|          OutputParser, ModelLoader                                 |
|  +-- Network: MeshNetworkManager, NearbyConnectionsWrapper,        |
|               MessageSerializer, ConnectionStrategy                |
|  +-- Sensor: CameraSensorManager (CameraX),                        |
|              AudioSensorManager (AudioRecord),                     |
|              EnvironmentalSensorManager (accel/pressure/humidity)  |
|  +-- Storage: Room DB, DataStore preferences                       |
+--------------------------------------------------------------------+
                                  v
                  Bluetooth LE / WiFi-Direct P2P mesh
                                  v
+--------------------------------------------------------------------+
|              OTHER ANDROID DEVICES (2-5 mesh peers)                |
+--------------------------------------------------------------------+
```

We follow **Clean Architecture + MVVM**:

- **Presentation** depends on **Domain** (never the other way around).
- **Domain** depends only on its own interfaces — it has no Android imports inside the agent/consensus/usecase logic that doesn't need them. This makes the core logic testable without an emulator.
- **Infrastructure** is the only layer that talks to Android SDKs, MediaPipe, and Nearby Connections.
- **Hilt** wires it all together through three DI modules (`AppModule`, `DatabaseModule`, `NetworkModule`).

---

## 5. How It Works — End-to-End Flow

A single detection cycle, from sensor to alert, takes approximately 3-5 seconds. Here's what happens:

### Phase 1: Local Observation (≈1–2 s)

1. `MainViewModel` triggers `DetectCrisisUseCase` every `DETECTION_INTERVAL_MS` (3 s).
2. `ObserverAgent` gathers fresh sensor data:
   - `CameraSensorManager.captureAndDescribe()` → analyzes the latest 224×224 frame (color histograms, brightness ratios, smoke/fire/water/dark-scene heuristics) into a short text description.
   - `AudioSensorManager.recordAndDescribe()` → records ~200 ms of audio, computes RMS in dB, dominant frequency via zero-crossings, peak amplitude → text description ("very loud noise, high-frequency alarm pattern detected").
   - `EnvironmentalSensorManager.readSensors()` → accelerometer magnitude (peak over a 50-sample sliding window), pressure, humidity → text description ("SEISMIC ACTIVITY DETECTED (18.4 m/s²)").
3. `PromptBuilder.buildObserverPrompt(...)` packs that into a Gemma chat-formatted prompt (`<start_of_turn>user ... <end_of_turn> <start_of_turn>model`) requesting a strict JSON response.
4. `Gemma4ModelRunner.generate(prompt)` runs inference on-device via MediaPipe LiteRT (top-K=40, temp=0.7, max 1024 tokens).
5. `OutputParser.parseObservation(...)` extracts JSON from the response, builds a typed `Observation` (crisis type, confidence, visual/audio features, reasoning text).
6. The observation is saved to Room (`observations` table) and emitted on a SharedFlow.

### Phase 2: Reasoning (≈1–2 s)

7. `DetectCrisisUseCase` pulls all observations from the last 60 seconds (`OBSERVATION_WINDOW_MS`) — this includes both local observations and observations received from the mesh.
8. `ReasonerAgent.reason(observations)` builds a prompt that asks Gemma to synthesize them step-by-step into a `SituationAssessment` with severity (LOW → CATASTROPHIC), affected area, immediate risks, and an explicit reasoning trace (numbered steps, evidence per step, per-step confidence).
9. The assessment is emitted on a SharedFlow.

### Phase 3: Mesh Gossip & Consensus (asynchronous, <1 s)

10. `ShareObservationUseCase.shareAssessment(assessment)` serializes the assessment to JSON, wraps it in a `MeshMessage(type=ASSESSMENT, ttl=3)`, and hands it to `GossipProtocol`.
11. `GossipProtocol.broadcast` calls `MeshNetworkManager.broadcastToAll(...)` which uses the `NearbyConnectionsWrapper` to send via Bluetooth LE / WiFi-Direct.
12. Each peer that receives the message:
    - Decodes it via `MessageSerializer`.
    - Submits the assessment to its own local `ConsensusEngine`.
    - Decrements TTL and relays it to its peers (subject to `seenMessages` dedup cache, max 500 entries).
13. `ConsensusEngine.evaluateConsensus()` runs `VotingStrategy.computeConsensus(...)` over all assessments received in the last 30 seconds. The strategy is confidence-weighted:
    - Each assessment's vote weight = its confidence (0.0–1.0).
    - If the dominant crisis type receives ≥ 60% of weighted votes (`CONSENSUS_THRESHOLD = 0.6`), consensus is **Reached** and the engine emits a `ConsensusEvent.ConsensusReached`.
    - Severity is voted on independently; the dominant severity from the same window is used.
    - Immediate risks across assessments are merged by frequency (top 5 most common).

### Phase 4: Alert Generation (≈1 s)

14. `MainViewModel` listens for `ConsensusEvent.ConsensusReached`. When fired, it calls `ConsensusEngine.buildConsensusAssessment(...)` to lift the local best-confidence assessment with consensus-merged crisis type/severity/risks.
15. `GenerateAlertUseCase(consensusAssessment)` invokes `ActionAgent.generateAlert(...)`.
16. `ActionAgent` builds a prompt asking Gemma for: title, summary, 3+ immediate actions, evacuation routes, things to avoid, detailed guidance text, and a list of emergency contacts.
17. `OutputParser.parseAlert(...)` builds a `CrisisAlert` with `isConsensusAlert = true` and `contributingDevices = N`.
18. The alert is persisted in Room (`alerts` table) and surfaced to the UI as a full-screen overlay.

### What goes on the wire

```
+------------------------------------------------+
| MeshMessage (JSON, ~1-4 KB per assessment)     |
+------------------------------------------------+
| type:       ASSESSMENT | OBSERVATION | ALERT   |
|             | CONSENSUS_VOTE | PEER_HEARTBEAT  |
| senderId:   "ABCDEF1234567890" (16 chars)      |
| timestamp:  1715619200000                      |
| payload:    <serialized Observation/Assessment>|
| ttl:        3 (decremented on relay)           |
+------------------------------------------------+
```

We do **not** ship images, audio, or raw model outputs across the mesh — only the structured, parsed observations and assessments. This keeps mesh bandwidth tiny (kilobytes per second) and preserves privacy.

---

## 6. Tech Stack

| Layer | Choice | Why |
|---|---|---|
| Platform | Android 10+ (API 29+) | Covers ~95% of in-use Android devices |
| Language | Kotlin 1.9 | Coroutines + Flow are essential for the async/multi-agent pipeline |
| ML Runtime | Google AI Edge LiteRT via MediaPipe `tasks-genai` | Official supported path for running Gemma on-device |
| Model | Gemma 4 E2B, 4-bit quantized (~1.3 GB) | Smallest Gemma 4 that still produces structured JSON reliably. Needs ~4 GB+ RAM to host on-device; phones below that fall back to a rule-based detector and still join the mesh (see [Detection Modes](#detection-modes)) |
| P2P | Android Nearby Connections API 2.0 (`P2P_STAR` strategy) | Bluetooth-preferred; does not force Wi-Fi on. Works with Bluetooth alone, no internet/router |
| Architecture | Clean Architecture + MVVM | Domain logic stays testable without Android |
| DI | Hilt 2.51 | Standard for modern Android, plays nicely with ViewModel |
| DB | Room 2.6 | Type-safe SQL, Flow integration |
| Camera | CameraX 1.3 | Lifecycle-aware, handles vendor fragmentation |
| Async | Kotlin Coroutines + Flow | Backpressure-aware, lifecycle-aware |
| Serialization | kotlinx.serialization | Compile-time safe, no reflection |
| Build | Gradle 8.6 + AGP 8.3.2 + Kotlin DSL + version catalog (`libs.versions.toml`) | Single source of truth for versions |

---

## 7. Project Structure

```
NeuroMesh/
+-- app/
|   +-- src/main/
|   |   +-- assets/
|   |   |   +-- gemma4_e2b_q4.tflite        <-- DROP THE MODEL HERE
|   |   +-- AndroidManifest.xml             <-- Permissions + activities
|   |   +-- java/com/neuromesh/crisis/
|   |   |   +-- NeuroMeshApplication.kt     <-- @HiltAndroidApp, notif channels
|   |   |   +-- data/
|   |   |   |   +-- model/                  <-- Observation, Assessment, Alert, Trace, Peer
|   |   |   |   +-- local/
|   |   |   |   |   +-- NeuroMeshDatabase.kt
|   |   |   |   |   +-- Converters.kt
|   |   |   |   |   +-- entity/             <-- Room entities
|   |   |   |   |   +-- dao/                <-- ObservationDao, AlertDao
|   |   |   |   +-- repository/             <-- Observation + Mesh repos
|   |   |   +-- domain/
|   |   |   |   +-- agent/                  <-- Observer, Reasoner, Action
|   |   |   |   +-- consensus/              <-- Engine, Gossip, Voting
|   |   |   |   +-- usecase/                <-- DetectCrisis, ShareObservation, GenerateAlert
|   |   |   +-- infrastructure/
|   |   |   |   +-- ml/                     <-- Gemma4ModelRunner, ModelLoader, Prompt, Parser
|   |   |   |   +-- network/                <-- MeshNetworkMgr, NearbyWrapper, Serializer, Strategy
|   |   |   |   +-- sensor/                 <-- Camera, Audio, Environmental
|   |   |   |   +-- storage/                <-- Preferences, File
|   |   |   +-- presentation/
|   |   |   |   +-- MainActivity.kt
|   |   |   |   +-- CrisisAlertActivity.kt
|   |   |   |   +-- viewmodel/              <-- Main, Mesh, Crisis VMs
|   |   |   |   +-- ui/
|   |   |   |   |   +-- camera/             <-- CameraPreviewView, ObservationOverlay
|   |   |   |   |   +-- mesh/               <-- MeshStatusIndicator, PeerListView, ConnectionQualityView
|   |   |   |   |   +-- alert/              <-- CrisisAlertView, GuidancePanel, ReasoningTraceView
|   |   |   |   +-- adapter/                <-- PeerListAdapter, ObservationListAdapter
|   |   |   +-- di/                         <-- AppModule, DatabaseModule, NetworkModule
|   |   |   +-- util/                       <-- Constants, Extensions, Result, Logger
|   |   |   +-- receiver/                   <-- Boot, Connectivity
|   |   +-- res/
|   |       +-- layout/                     <-- 8 layout XMLs
|   |       +-- drawable/                   <-- icons, backgrounds, gradients
|   |       +-- values/                     <-- strings, colors, themes, dimens
|   |       +-- mipmap-anydpi-v26/          <-- adaptive launcher icon
|   +-- build.gradle.kts
|   +-- proguard-rules.pro
+-- gradle/
|   +-- libs.versions.toml                  <-- Version catalog
|   +-- wrapper/gradle-wrapper.properties
+-- build.gradle.kts
+-- settings.gradle.kts
+-- gradle.properties
+-- README.md
```

Total: ~95 source/config files. Roughly **5,000 lines of Kotlin** + **800 lines of XML** + **300 lines of build config**.

---

## 8. Component Deep Dive

### 8.1 The Three Agents

All three live in `domain/agent/` and share the same `Gemma4ModelRunner`. Each is a thin Kotlin class that builds a prompt → calls the model → parses JSON → emits on a SharedFlow.

**Why three agents instead of one?** Specialization improves reliability of structured output. A single prompt that says "look at this camera + audio + sensors AND fuse with neighbors AND produce evacuation guidance" routinely fails the JSON contract. Splitting reduces each prompt to one job, each with its own well-defined JSON schema, and gives us a natural place to inject mesh data (between Observer and Reasoner).

**`ObserverAgent.kt`** — vision/audio/sensor → `Observation`.
**`ReasonerAgent.kt`** — `List<Observation>` → `SituationAssessment` (with `ReasoningTrace`).
**`ActionAgent.kt`** — `SituationAssessment` → `CrisisAlert` (with `EmergencyContact`s, evacuation routes, do-not-do list).

### 8.2 Gemma 4 Inference (`infrastructure/ml/`)

- **`Gemma4ModelRunner.kt`** wraps MediaPipe `LlmInference`. Configured for max 1024 output tokens, top-K=40, temperature=0.7, fixed seed for reproducibility. Has both synchronous (`generate`) and streaming (`generateStreaming`) APIs.
- **`ModelLoader.kt`** copies `gemma4_e2b_q4.tflite` from APK assets to internal storage on first launch (one-time, ~30-60 s, with a 4 MB buffer). Caches the path. MediaPipe needs an absolute filesystem path, not an asset URI.
- **`PromptBuilder.kt`** holds the three system prompts and the three JSON output schemas, formatted with Gemma's chat tokens (`<start_of_turn>user ... <end_of_turn> <start_of_turn>model`).
- **`OutputParser.kt`** robust JSON extraction (finds the first `{` and last `}` in case the model adds prose around the JSON), with `runCatching` fallbacks on every enum decode.

### 8.3 Sensors (`infrastructure/sensor/`)

We deliberately **do not** ship raw pixels/audio into Gemma. The E2B model is text-only — we use the camera/audio/IMU as classical signal processors that output text descriptors, then let Gemma reason over the text.

- **`CameraSensorManager`** binds to the lifecycle, uses CameraX `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST`. Each frame is scaled to 224×224 and analyzed: dominant-color histogram (red/orange → fire, blue → flood, yellow/amber → smoke/dust), bright-pixel ratio, dark-pixel ratio. The output is a one-line English description.
- **`AudioSensorManager`** records ~200 ms of 44.1 kHz PCM, computes RMS dB SPL, zero-crossing-based dominant frequency, peak amplitude. Heuristics: 300-3400 Hz at >80 dB → screaming/voices; <100 Hz at >85 dB → low-frequency rumble (earthquake/explosion); 800-4000 Hz with high peaks → alarm pattern.
- **`EnvironmentalSensorManager`** keeps a 50-sample sliding window on the accelerometer. Magnitude > 15 m/s² triggers a seismic flag (also pushed to a `seismicEvents` Flow for instant reaction). Also reads pressure and humidity if available.

### 8.4 Mesh Networking (`infrastructure/network/`)

- **`NearbyConnectionsWrapper`** is the only class that touches the Google Nearby SDK. Uses `Strategy.P2P_STAR` (a single shared `MESH_STRATEGY` constant so advertising and discovery can never diverge). STAR keeps Bluetooth as the primary transport and does **not** aggressively bring up Wi-Fi/Wi-Fi-Direct the way `P2P_CLUSTER` does — `P2P_CLUSTER` was forcing Wi-Fi on and causing Wi-Fi/Bluetooth radio contention that left devices unable to connect. STAR is sufficient for the ≤5-device mesh. Handles advertising, discovery, auto-accept-on-discovery, payload send/receive. Exposes incoming bytes and connection events via Kotlin Flows so the rest of the app stays SDK-agnostic.
- **`MeshNetworkManager`** is the orchestrator. On `start()` it begins advertising + discovery, spins up coroutines for:
  - Incoming payload collection → deserialize → push onto `incomingMessages` SharedFlow.
  - Connection event collection → update `MeshRepository`.
  - Heartbeat loop (every 10 s) → broadcasts a `PEER_HEARTBEAT` so we can detect silent disconnects.
- **`MessageSerializer`** is a thin kotlinx.serialization wrapper with typed decoders for each payload kind.
- **`ConnectionStrategy`** decides which peers to prioritize when we hit the 5-peer connection limit (prefer peers with model loaded, more free RAM, stronger signal).

### 8.5 Consensus (`domain/consensus/`)

- **`GossipProtocol`** — TTL-bounded broadcast with a seen-message cache (FIFO, 500 entries) to prevent infinite relay loops. Relays add a 100 ms jitter to avoid storms.
- **`VotingStrategy.computeConsensus(...)`** — confidence-weighted voting on crisis type and severity independently. Returns `Reached` / `Disputed` / `NoConsensus`.
- **`ConsensusEngine`** — holds a per-device map of pending assessments (deduplicated by sender), prunes anything older than `CONSENSUS_WINDOW_MS = 30s`, runs the voting strategy every time a new assessment lands, emits `ConsensusEvent`s.

### 8.6 UI (`presentation/`)

- **`MainActivity`** — single-activity hub. Camera preview occupies the full screen as background. Top bar shows app name + mesh status. Bottom status pill shows current AI state. When an alert fires, a full-screen overlay slides in.
- **`CrisisAlertActivity`** — full-screen, lock-screen-bypassing, screen-waking activity for critical alerts. Shows the alert with reasoning trace and two buttons: Acknowledge and Call Emergency (fires `ACTION_DIAL` intent).
- **`MainViewModel`** — the brain of the UI. Owns the detection loop, the mesh message collector, the consensus event collector. Exposes `uiState`, `activeAlert`, `latestAssessment`, `peers`, `connectedPeerCount` as `StateFlow`s.
- **Custom Views** — `MeshStatusIndicator`, `CrisisAlertView`, `ReasoningTraceView`, `ObservationOverlay` (animated scanning corners + pulsing radar circle), `ConnectionQualityView` (signal-bar style indicator).

---

## 9. Setup & Build

### 9.1 Prerequisites

- **Android Studio Hedgehog (2023.1.1) or newer**
- **JDK 17** (set in Android Studio: Settings → Build → Gradle → Gradle JDK)
- **Android SDK API 34** (install via SDK Manager)
- **Physical Android device** — Android 10+ (API 29+). **Emulators will not work** (no real camera, no real Bluetooth, no real accelerometer).
  - **~4 GB+ RAM** to run the on-device Gemma model. ~6 GB+ recommended for headroom (the model is text-only inference on top of camera + mesh).
  - **Lower-RAM phones still work**: below ~4 GB the app automatically runs in heuristic detection mode (rule-based fire/flood/earthquake detection) and still participates in the mesh — it does not crash. See [Detection Modes](#detection-modes).
- **3 GB free storage on the device** (1.3 GB APK + 1.3 GB extracted model).

### 9.2 Get the Model

The model is **not** committed to git (it's 1.3 GB — too large for GitHub). Each teammate must download it once.

1. Download `gemma4_e2b_q4.tflite` from the Kaggle hackathon's model release page.
2. Place it at exactly:
   ```
   app/src/main/assets/gemma4_e2b_q4.tflite
   ```
3. Verify the filename matches `ModelLoader.MODEL_FILENAME` (`gemma4_e2b_q4.tflite`).

The `.gitignore` (added below — make sure it's committed) already excludes this file.

### 9.3 First Build

```bash
# From the project root
./gradlew assembleDebug

# Or to build + install onto a connected device:
./gradlew installDebug
```

First build is slow (3-5 minutes) because Gradle downloads ~300 MB of dependencies and the 1.3 GB model has to be packed into the APK. Subsequent builds are <30 s.

### 9.4 Recommended `.gitignore`

(Create at repo root before pushing.)

```gitignore
# Build output
build/
app/build/
.gradle/
local.properties

# IDE
.idea/
*.iml
.vscode/
captures/

# Model file (too large for git)
app/src/main/assets/*.tflite
app/src/main/assets/*.bin

# Keystore
*.jks
*.keystore

# OS
.DS_Store
Thumbs.db
```

### 9.5 Project Setup for Teammates (clone & run)

```bash
git clone <repo-url>
cd NeuroMesh

# Drop the model in:
#   app/src/main/assets/gemma4_e2b_q4.tflite

# Open in Android Studio -> let Gradle sync -> Run
```

---

## 10. Testing Guide

### 10.1 Permissions Granted at First Launch

On first launch the app requests:

- **Camera** — required, the observer agent uses it
- **Microphone** — required, the observer agent uses it
- **Fine Location** — required by Android Nearby Connections SDK (not used by us for actual location)
- **Bluetooth Scan / Advertise / Connect** — for the mesh
- **Nearby WiFi Devices** (Android 13+) — for WiFi-Direct mesh

If any are denied, the affected subsystem will degrade gracefully (e.g., camera denied → observer falls back to audio + sensors).

### 10.2 What to look for in logcat

Filter logcat to tag prefix `NeuroMesh/`:

```
NeuroMesh/ModelLoader: Model copied from assets: 1284MB
NeuroMesh/Gemma4ModelRunner: Gemma 4 model initialized at /data/.../files/gemma4_e2b_q4.tflite
NeuroMesh/MeshNetworkManager: Advertising started as <DEVICE_ID>
NeuroMesh/MeshNetworkManager: Discovery started
NeuroMesh/ObserverAgent: Observer inference completed in 1380ms
NeuroMesh/ReasonerAgent: Reasoner inference completed in 1620ms
NeuroMesh/ConsensusEngine: Consensus reached: FIRE (78% agreement)
NeuroMesh/ActionAgent: Action inference completed in 980ms
```

If you don't see "Gemma 4 model initialized" within ~60 s of launch, check that the model file is actually in `assets/` and has the exact filename.

### 10.3 Single-device sanity test

1. Install the app on **one phone**.
2. Grant all permissions.
3. Wait for the loading card to disappear (model loaded, ~30-60 s on first run).
4. Status pill should show "Monitoring".
5. Trigger a detection: point the camera at a candle flame, or play a fire alarm sound from another device. Within 3-5 s the status pill should flip to "Analyzing", then a `CrisisAlert` overlay should appear on screen.
6. Dismiss it. The system goes back to monitoring.

### 10.4 Mesh consensus test (the headline demo)

1. Install on **at least 2 phones** (3-5 is the sweet spot).
2. Bring them within ~10 m of each other. Bluetooth and WiFi on, on the same WiFi network is best.
3. Watch the mesh indicator in the top right — it should go from "No mesh" → "Scanning…" → "1 device" → "2 devices" → … within 10-30 s.
4. Trigger a crisis indicator on **one** of the phones (camera at flame, audio of alarm).
5. **Expected behavior**: that phone broadcasts its assessment. Within a few seconds, the other phones should fire a `CrisisAlert` even though their own cameras saw nothing — because they trust the consensus. The alert on those phones will say "Confirmed by N mesh devices" (where N = number of phones that contributed).

### 10.5 Unit test scenarios (for later)

The domain layer was deliberately built without Android imports where possible, so it's unit-testable. Suggested tests:

- `VotingStrategy.computeConsensus` — feed synthetic assessments, verify Reached/Disputed/NoConsensus boundaries.
- `OutputParser` — feed messy LLM responses (extra prose, broken JSON, missing fields) and verify graceful degradation.
- `GossipProtocol.broadcast` — verify dedup cache prevents loops.
- `ConsensusEngine.pruneStaleAssessments` — verify window expiry.

---

## 11. Demo Scenarios

For the hackathon submission video, we want each of the four crisis types showcased plus the mesh consensus magic moment.

### Scenario A — Local Fire Detection
- Single phone, point at a candle/fireplace + play fire-alarm audio from a laptop.
- Observer detects red-orange dominant color + high-frequency alarm pattern.
- Reasoner concludes FIRE / HIGH severity.
- Action emits "Evacuate via Stairwell B. Call 911. Do not use elevator."

### Scenario B — Earthquake Detection
- Single phone, shake it vigorously (>15 m/s²) for 2-3 s.
- Observer detects seismic activity from accelerometer history.
- Reasoner concludes EARTHQUAKE / MODERATE severity.
- Action emits "Drop, cover, hold. Stay away from windows."

### Scenario C — Mesh Consensus (Multi-Device)
- Phone A: point at fire indicator. Phones B, C, D: pointed at neutral scenes.
- A broadcasts assessment. B/C/D's consensus engines receive it but have no local fire signal.
- **Expected**: because only 1/4 devices saw fire, the consensus ratio is 25% (below 60% threshold) → no alert fires on B/C/D.
- Now point B and C also at fire indicators.
- 3/4 devices = 75% agreement → consensus crosses threshold → alert fires on **all** devices including D (which never saw fire itself).
- This demonstrates the core value: a single sensor reading is unreliable, but a majority of independent sensors is a high-trust signal.

### Scenario D — Disputed Consensus (Negative Case)
- 2 phones see fire indicators, 2 phones see flood indicators.
- VotingStrategy outputs `Disputed` (no crisis type ≥ 60%).
- No alert fires. ConsensusEvent.ConsensusDisputed is logged.
- This shows the system errs on the side of "don't panic" when the evidence is genuinely conflicting.

---

## Detection Modes

NeuroMesh runs in one of two modes, chosen automatically at startup based on device RAM. **Both modes participate in the mesh** — the difference is only how a device produces its *own* local detection.

| | LLM mode | Heuristic mode |
|---|---|---|
| **When** | Device has ~4 GB+ RAM and the model loads | Device below the RAM floor, or model init fails |
| **Local detection** | Observer → Reasoner → Action agents on Gemma 4 | Rule-based classifier on raw sensor numbers (fire-colored-pixel ratio, alarm-band audio, accelerometer spike) |
| **Guidance text** | LLM-generated, situation-specific | Templated risk hints per crisis type |
| **Mesh consensus** | Full participant | Full participant |
| **Never crashes** | ✓ (falls back to heuristic if the model fails) | ✓ |

Two design rules make this robust:

1. **Sensor gate first.** Every detection cycle starts by reading raw sensor numbers. If nothing crosses a real threshold, the cycle short-circuits and the (expensive) model is never invoked. This is what prevents false "random" alerts and stops the device running inference 100% of the time (the old behaviour that overheated phones).
2. **Graceful RAM fallback.** A low-RAM phone, or a model-init OOM, drops to heuristic mode instead of force-closing. The phone stays useful: it detects the obvious crises locally and still trusts/relays mesh consensus from capable peers.

Mesh transport is **Bluetooth-preferred** (`P2P_STAR`). It needs Bluetooth on; it does **not** require or force Wi-Fi, and it never needs the internet. It works in airplane mode with Bluetooth enabled.

---

## 12. Prize Track Alignment

### Global Resilience ($10K)
NeuroMesh is purpose-built for the exact failure mode this track targets: disaster zones where centralized infrastructure is broken. The offline-first architecture means it works in any country, any disaster type, with no setup. Privacy preservation means people in authoritarian regimes can use it without risking exposure to a central operator.

### Cactus Track ($10K)
We don't just have multiple agents — we have **three distinct cognitive roles** (perception, reasoning, action) operating both *within* a device and *across* a mesh of devices, with explicit consensus over reasoning traces. Every alert ships with the chain of evidence that produced it. The agent-as-message paradigm (where assessments and reasoning traces are gossiped across the mesh) is genuinely novel.

### LiteRT Track ($10K)
Gemma 4 E2B running on-device via MediaPipe LiteRT, on mid-range hardware, doing real structured-output inference inside a 5-second budget. We use the LiteRT path that MediaPipe officially supports (`tasks-genai`), which is the recommended on-device runtime for Gemma. The model is 4-bit quantized to fit the constraints of phones that survivors actually carry.

---

## 13. Known Limitations & Roadmap

### Limitations as of the hackathon cut

- **Model size & RAM floor**. 1.3 GB is uncomfortable for an APK, and a 2B-parameter model realistically needs ~4 GB+ device RAM to host (mmap + KV-cache + activations on top of camera and mesh). This is a hard physical constraint, not a tuning issue — phones with ~2-3 GB RAM **cannot** run the LLM. We handle this gracefully: such phones auto-fall-back to heuristic mode and still mesh (see [Detection Modes](#detection-modes)), rather than crashing. For production we'd also download the model on first launch rather than bundle it.
- **Text-only Gemma**. E2B is text-only — we feed it descriptions, not pixels. The full multimodal variant would improve observer reliability significantly.
- **No persistent peer identity**. Device IDs are random per install; we don't yet have cryptographic peer identities, so a malicious node could in theory poison consensus. Mitigation: confidence weighting limits the impact of one bad actor.
- **English prompts only**. Gemma 4 is multilingual; we just haven't translated our prompts yet.
- **No background service**. Detection only runs while the app is in the foreground. A foreground service with notification would be the next addition.

### Post-hackathon roadmap

- [ ] Foreground service + persistent notification so detection runs while the screen is off
- [ ] Cryptographic peer identities (Ed25519 keypair on install, signed assessments)
- [ ] Multilingual prompts driven by device locale
- [ ] On-device fine-tuning of the observer's color/audio heuristics from confirmed-correct alerts
- [ ] Optional uplink relay: when one phone in the mesh *does* find internet (e.g., comes back into cell coverage), it relays consensus alerts upstream to 911 dispatch with the full mesh-attested evidence chain
- [ ] Integration with `Crisis Connect` / `Earthquake Early Warning` style standards if those open up to community implementations

---

## 14. Glossary

- **Agent** — A single role (Observer / Reasoner / Action) implemented as a Kotlin class that builds a Gemma prompt, runs inference, and parses the output.
- **Assessment** (`SituationAssessment`) — The output of the Reasoner: crisis type + severity + confidence + reasoning trace + immediate risks.
- **CONSENSUS_THRESHOLD** — The fraction of weighted votes needed for a consensus to be considered Reached. Currently 0.6.
- **Consensus** — A confidence-weighted majority vote across recent assessments from all mesh peers.
- **Gemma 4 E2B** — Google's 2-billion-parameter multilingual LLM. We use the 4-bit quantized variant (~1.3 GB).
- **Gossip** — A mesh protocol where each node forwards messages to its peers, with a TTL to prevent infinite relay.
- **LiteRT** — Google AI Edge's on-device inference runtime (the successor branding to TensorFlow Lite for AI).
- **MediaPipe** — Google's framework for on-device ML pipelines; we use its `tasks-genai` API for Gemma inference.
- **Nearby Connections** — Android's official P2P API. Hides the BLE-vs-WiFi-Direct choice and gives us a simple advertise/discover/connect/send-payload API.
- **Observation** (`Observation`) — The output of the Observer: structured features from camera + audio + sensors at a single point in time.
- **P2P_STAR** — The Nearby Connections strategy NeuroMesh uses. Bluetooth-preferred; does not force Wi-Fi on. (We deliberately moved off `P2P_CLUSTER`, which forced Wi-Fi/Wi-Fi-Direct up and caused radio contention.)
- **Heuristic mode** — The rule-based detection path (color/audio/accelerometer thresholds, no LLM) used on phones that lack the RAM to host Gemma. Still feeds the mesh.
- **Reasoning Trace** — A list of numbered reasoning steps with evidence and per-step confidence. Attached to every assessment so users can audit *why* an alert fired.
- **TTL** (Time To Live) — A hop counter on mesh messages. Decremented on each relay; messages stop propagating at TTL=0.

---

## Contributing

Branch names: `feat/<short-desc>`, `fix/<short-desc>`, `docs/<short-desc>`.

Before pushing:
1. Run `./gradlew assembleDebug` — it should compile cleanly.
2. If you added new constants, put them in `util/Constants.kt` not scattered.
3. New domain classes should be plain Kotlin (no Android imports) where possible.
4. New infrastructure classes that touch SDKs should expose Kotlin Flows / suspend functions, not callbacks, so the domain layer can stay coroutine-native.

## License

Hackathon submission, all rights reserved until we pick a license post-event.

## Authors
Abdul Kalam Azad
Mahak Agarwal
Soham 
Angan 
