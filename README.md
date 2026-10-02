# AirHop / iTantra
### Offline Neural Voice Transceiver for Disaster Communication (ISRO SIH26173 Target)

AirHop / iTantra is an offline, multi-hop disaster communications system designed for extreme infrastructure failure scenarios (floods, earthquakes, landslides). It provides long-range neural voice transmission and geofenced SOS beaconing without cellular connectivity, satellite internet, or base stations.

---

## Key Highlights & Specifications

### 1. Module 1: Native C++ Core (`app/src/main/cpp/`)
* **Target**: C++17 with CMake 3.22.1+.
* **Exact 40-byte Packed Frame (`AirHopPacket`)**:
  - `preamble`: `0x7E` (1 byte)
  - `flags`: Emergency SOS (bit 7), Priority (bit 6..5), Language bits (bit 4..0) (1 byte)
  - `ttl`: Hop count (default 10 hops) (1 byte)
  - `msg_id`: 32-bit FNV-1a hash over coordinates, timestamp, and tokens (4 bytes)
  - `target_zone`: 32-bit geofence cluster identifier (4 bytes)
  - `lat_e7`: NavIC / GPS latitude scaled by $10^7$ (4 bytes)
  - `lon_e7`: NavIC / GPS longitude scaled by $10^7$ (4 bytes)
  - `tokens[13]`: 13 phonemic indices from neural voice encoder (13 bytes)
  - `fec_parity[8]`: Reed-Solomon RS(40,32) parity bytes (8 bytes)
  - Guarded with `static_assert(sizeof(AirHopPacket) == 40)`.
* **Mathematical Galois Field Arithmetic $GF(2^8)$**:
  - Primitive polynomial $p(x) = 0x11D$ ($x^8 + x^4 + x^3 + x^2 + 1$).
  - 512-entry exponential table for branchless modulo multiplication.
* **Reed-Solomon RS(40,32) Forward Error Correction Codec**:
  - $N=40, K=32, 2t=8$ parity symbols, corrects up to $t=4$ corrupted bytes per frame.
  - Berlekamp-Massey algorithm for solving the error locator polynomial $\Lambda(x)$.
  - Chien search for error root location.
  - Forney algorithm for error magnitude calculation and automatic packet repair.
* **Audio Ingest Ring Buffer & Silero VAD**:
  - Lock-free circular SPSC ring buffer for 16 kHz 16-bit mono PCM.
  - Silero VAD state machine enforcing strict 450 ms silence hangover detection (7,200 samples at 16 kHz).
* **JNI Bridge (`AirHopNative`)**: Exposes packet encoding, RS decoding/repair, TTL verification & decrement, and audio chunk streaming.

---

### 2. Module 2: Physical Mesh & Radio Layer (`com.team.vocalink.mesh`)
* **BLE Coded PHY Engine (`BleMeshEngine`)**:
  - Configures `BluetoothLeAdvertiser` and `BluetoothLeScanner` for `PHY_LE_CODED` (S=8 mode, max range) with non-connectable anonymous packets.
  - Raw 40-byte payload carried in Service Data UUID `0xFD6F`.
* **Rotating Bloom Filter (`RotatingBloomFilter`)**:
  - In-memory 1024-bit Bloom filter with generational rotating window and Kirsch-Mitzenmacher multi-hashing for sub-millisecond duplicate suppression.
* **Blind Relay (`BlindRelayManager`)**:
  - Background relay pipeline: Bloom filter check $\to$ RS(40,32) repair $\to$ TTL decrement & parity update in native C++ $\to$ immediate BLE Coded PHY rebroadcast without UI thread dispatch.
* **Wi-Fi Aware NAN (`WifiAwareMeshEngine`)**:
  - Publishes and subscribes to service `"AirHopDisasterMesh"` for high-density node clustering fallback.

---

### 3. Module 3: Geo-Fencing, DND Bypass & Neural Alert (`com.team.vocalink.alert`)
* **Offline Haversine Geofence Engine (`GeofenceManager`)**:
  - Computes spherical distance without external maps or network access.
  - Preloaded with the Mysuru flood disaster benchmark (`Lat: 12.2958° N, Lon: 76.6393° E`, radius: 5,000 m).
  - NavIC (IRNSS constellation) and GPS satellite tracking.
* **DND / Silent Mode Bypass (`DndBypassAlertManager`)**:
  - Binds to `AudioManager.STREAM_ALARM` with `FLAG_ALLOW_RINGER_MODES`, locks volume to max, and generates 800–1300 Hz tactical disaster warble sirens via `AudioTrack`.
* **Neural Indic Speech Synthesis (`NeuralTtsHook`)**:
  - Translates 13 phonemic indices into Indic disaster speech:
    - **Kannada (Primary)**: e.g. "ತುರ್ತು ಎಚ್ಚರಿಕೆ! ಪ್ರವಾಹ ಮಟ್ಟ ಏರುತ್ತಿದೆ"
    - **Hindi (Secondary)**: e.g. "आपातकालीन चेतावनी! बाढ़ का स्तर बढ़ रहा है"
  - Formant acoustic voice synthesis engine streaming directly through `AudioTrack`.
* **Disaster Log Store (`DisasterLogStore`)**:
  - File-backed JSONL triage logger (`airhop_disaster_triage.jsonl`) with instant CSV export for rescue teams.

---

### 4. Module 4: UI & Operations (`com.team.vocalink.ui`)
* **Tactical Dark Mode HUD (`MainActivity`)**:
  - OLED black (`#000000`) high-contrast theme.
  - Real-time peer counter, Coded PHY, Wi-Fi Aware, and NavIC lock status.
  - 1-tap Emergency SOS Beacon button.
  - Push-To-Talk voice recorder with live RMS oscilloscope.
  - Injected test frame button demonstrating RS(40,32) auto-repair.
  - Live waterfall RecyclerView showing hop counts, coordinates, and error correction status.
* **Node Auto-Recovery (`BootReceiver`)**:
  - Automatically relaunches the disaster transceiver service when the device reboots in a crisis zone.

---

## Build & Test Instructions

### Prerequisites
- Android SDK (API 35, Build-tools 34.0.0+)
- Android NDK 27+
- CMake 3.22.1+
- Java 17+

### Run Automated Unit Tests
```bash
./gradlew test --console=plain
```

### Build Full Debug APK
```bash
./gradlew assembleDebug --console=plain
```
Output APK location:
`app/build/outputs/apk/debug/app-debug.apk`

### Install on Device via ADB
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```


## Production validation

The production branch requires Android build/test CI plus a real-device BLE/Wi-Fi Aware matrix before claiming radio interoperability.


## Physical validation gate

Software CI cannot prove radio interoperability. Before release, test physical devices for:
- A→B→C→D delivery and end-to-end acknowledgment
- relay power-off/recovery
- corrupted-frame/FEC handling
- restart/persistence behavior
- BLE extended advertising and coded-PHY capability differences
- Wi-Fi Aware discovery/reconnect
- 1/6/24-hour battery, memory, crash and ANR soak tests

Devices that lack the required radio capability must be reported as unsupported rather than presented as long-range mesh nodes.
