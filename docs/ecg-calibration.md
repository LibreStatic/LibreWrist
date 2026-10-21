# ECG calibration and data formats — Galaxy Watch (clean-room specification)

Reconstructed 2026-10-15 from the official firmware of devices the owner owns, for the OpenSHM
`EcgAdapter` that feeds the watch SHM ECG flow ([patches.md](patches.md) watch rows 7/7b) with ECG
delivered by the Health Tracking Service ([health-tracking-protocol.md](health-tracking-protocol.md)
§4). Datasheet facts only — inputs, formats, scaling and rate claims, each with provenance
(binary file + build + offset, or smali file + method). No decompiled code; public/exported symbol
and class names appear as provenance only. Every fact was read from binaries the owner's devices
run, by static analysis of official firmware.

Purpose: state the **stock ECG contract** (SemSensorServices type `0x1b`) and the **HTS/adapter
contract** side by side, give the provenance of every scale/rate/filtering claim, and deliver an
**equivalence verdict** for `EcgAdapter`. §5 lists what only a device test settles; nothing is
papered over.

Provenance convention: citations "note 70" (analyst working record), "note 90" (independent
confirmation record) and "note 95" (consolidation changelog) refer to dated working records kept in the
analysis work directory outside this repository; this document is self-contained without them. Evidence
tags:

- **[V]** — verified: read or executed in the cited record's session (byte reads, disassembly /
  decompile reads, smali reads, hash runs).
- **[C]** — independently confirmed: reproduced by the second session; corrections that process
  produced are authoritative (§0.2).
- **[I]** — inferred: fits the evidence but not directly proven.
- **[D]** — derived here, from verified facts.
- **[N]** — **not recorded**: the records do not contain this detail; it is never invented.

Baseline device/build: **SM-R920 (Galaxy Watch5 Pro), `R920XXS2DZG3/R920OXM2DZG3` (CSC ARO)** —
the owner's device and running build — One UI 8 Watch / Android 16, 32-bit userspace (`/system/lib`),
Exynos W920. The installed HTS is 1.7.00.05 (versionCode 170005001), matching the analyzed APK.
Firmware was pulled from Samsung's FUS server and unpacked per recipe note 30; the HAL and framework
facts come from the extracted files (§6).

---

## 0. Verification status — what was independently confirmed and what was not

### 0.1 Independently confirmed (two sessions; the confirmation re-derived every value from binaries)

| Finding | First stated | Confirmed by |
|---|---|---|
| HAL registration & identity: handle `0x11`, SemSensorServices id `0x1b` (27), event type `0x11025`, read-size `0x88` | note 70 §2.1 | note 90 §Q1 (fresh Ghidra project + byte reads) |
| Producer framing: `HEADER = 2`, `VERSION_INDEX = 1` (`=1`), `MODE_INDEX = 0` (`DATA=0`/`DIAGNOSIS=1`), `DATA_SIZE = 0x80`, `GAIN_FACTOR = 2.661416696947674e-4` | note 70 §2.2 | note 90 §Q2 |
| Consumer scaling: center `0x200000`, LSB, unavailable sentinel | note 70 §2.2/A3 | note 90 §Q3 |
| `raw[]` slot layout (ADC `raw[4..13]`; lead `raw[14]` bits 16-23; count `raw[15]` bits 8-15; seq `raw[15]` bits 0-7; thresholds `raw[25]`/`raw[26]`; `adc_offset` v1 `raw[29]` / HTS-parsed `raw[20]`) | note 70 §2.2 | note 90 §Q4 |
| Sample-rate constants: literal `0x1f4` (500) and the `0.002 s` per-sample step | note 70 §2.5 | note 90 §Q5 |
| Adapter output matches the resident SHM consumer (`pl`/`c90`/`f80`), plus the divergences of §4 | note 70 §3/§5 | note 90 §Q6 |

### 0.2 Corrections the confirmation produced (authoritative — use these values)

- **Producer v1 second PPG-green word is `raw[21]` (byte 0x56), not `raw[27]`.** note 70 §2.2 lists
  the v1 green pair as `[raw[18], mux? raw[27]:0]`; note 90 §Q4 reads the pair at bytes `0x4a`/`0x56`
  ⇒ `raw[18]`/`raw[21]` (and its HTS-legacy trace uses the same `raw[18]`/`raw[21]` pair). note 70's
  own HTS section is already consistent with `raw[21]`. **Use `raw[21]`** (§1.2).
- **The valid-signal band is data-driven, not a `±5 mV` firmware constant.** SHM's validity window
  comes from the producer's `raw[25]` (upper) and `raw[26]` (lower) [note 90 §Q3]. No `±5 mV` literal
  exists in the HAL, the framework or the SHM path (float/double scan for `5.0f`/`5.0` → 0 hits; only
  int `0x200000` appears) [note 90 §Q3/§4]. The `±5 mV` window is the **adapter's own substitution**;
  note 70's comparison table (§3) is corrected accordingly.
- **Validity-window polarity fixed:** **lower = `raw[26]`, upper = `raw[25]`** (expressed in mV)
  [note 90 §Q3]; note 70 records the two scale expressions but does not assign upper/lower.
- **The count word matters to a legacy consumer.** The adapter writes `count = 10` into `raw[15]`
  bits 8-15; SHM `pl` accepts any non-zero as 10, but the HTS legacy decoder maps `count == 1 ⇒ 10,
  else 5` [note 90 §Q6.1]. A value of `1` satisfies both consumers. (note 70 did not flag this.)
- **Two number spaces are distinct and must not be conflated:** the SemSensorServices small id
  `0x1b` (27) versus the `sensors_event_t.type` `0x11025` [note 90 §Q1]. note 70 states both numbers
  but does not call out the distinction (§1.3).
- **Annotator identity added.** note 90 §Q5 supplies the annotator binaries' sha256 (`d01518cf…`,
  `bcdb977e…`), the `500.0`/`0.002` constant offsets, and the `SamsungOfflineECG_Process500_1` symbol;
  note 70 §2.5 names only the `…_LP_50_500` / `…_LP_50_250` filter symbols.
- **The lone `500` int in the HAL is not attributable.** note 90 §Q5/§4 places an int `500` at ELF
  vaddr `0x856c` (file `0x756c`) in a `.text`-embedded config table that belongs to no identified ECG
  path; it is **not counted** as HAL rate evidence. note 70 §2.1 does not mention it.

### 0.3 Independence caveat, and single-session findings NOT confirmed

**Independence caveat (recorded, not overstated).** The confirmation record's own §5 states its
session "never opened the first analyst's ECG notes". The consolidation changelog, however, records
that in that session **a grep preview surfaced a few snippet lines of the analyst note (note 70)**
before the notes were closed [note 95]. Every value of §0.1 was still **re-derived** from the binaries
with a fresh Ghidra project, byte reads and `capstone`/`pyelftools` decoding [note 90 §0], but the topic
selection was not fully blind to note 70. Independence is therefore **partial**: treat §0.1 as
independent binary re-reads, not as a fully blind replication. The confirmed rows remain [C].

**Single-session, not independently confirmed** (note 70 only):

- HAL sensor-info record values: `minDelay = maxDelay = 10000 µs`, maxRange 1000.0, resolution 1.0,
  power 0.01, flags 0x2, chip identity strings (`AFE4500S/AFE4510S ECG`) [V].
- The 24-bit µs timestamp-counter derivation in the HAL `parse` [V].
- The framework **v2** field-map details beyond the slots note 90 re-read [V].
- The `f80`/`u70` timer schedule (100 ms / 20 ms / 5000 ms) and the internal 250-vs-500 disagreement
  [V].
- The `tx0`/`sa2` registration values and the `EcgLibrary.init` argument list [V].
- The `libshm-ecg.so` / `libshm-ecg-2.0.so` **hashes are not recorded** in either note [N] (§6).
- Everything tagged [I] below.

---

## 1. The stock ECG path end to end

### 1.1 HAL producer — `ECGSensor` (`sensors.default.so`)

- Registration (`_GLOBAL__sub_I_ECGSensor.cpp` @ Ghidra `0xa9370`):
  `HubConnection::addSemPhysicalSensor(hub, 0x11, 0x1b, this)` — **handle `0x11`,
  SemSensorServices id `0x1b` (27)** [V+C note 70 §2.1 / note 90 §Q1].
- Constructor (@ Ghidra `0xa8c28`) calls `SemPhysicalSensor::SemPhysicalSensor(this, 0x11, 0x11025,
  name)` — **event type `0x11025`**; sets the read-size field `this+0x1c = 0x88` (136) [V+C].
- `getSensorInfo` (@ Ghidra `0xa8ccc`) returns the static sensor-info record at Ghidra `0xd1040`
  (ELF vaddr `0xc1040`, file `0xbe040`): vendor `"Samsung Inc."`, stringType
  `"com.samsung.sensor.ecg"`, permission `"com.samsung.permission.SSENSOR"`, handle `0x11`, type
  `0x11025`, version `1`, maxRange `1000.0`, resolution `1.0`, power `0.01`,
  **minDelay = maxDelay = 10000 µs**, flags `0x2` [V note 70 §2.1; the record was not re-read by
  note 90].
- Name/version are filled at run time from sysfs (`/sys/class/sensors/ecg_sensor`, `atoi`); the
  version is exposed only as **1 or 2** (anything else logs and falls back to 1) [V note 70 §2.1].
- `readSensorData` (@ Ghidra `0xa8ec8`) reads up to `this+0x1c = 0x88` bytes from the sysfs fd,
  rejects `iVar1 < 0x80`, then calls `parse` via the vtable slot [V+C].
- `parse` (@ Ghidra `0xa8f50`) builds the Java-visible message **`[mode, version, 0x80-byte blob]`**
  and sends it with `SemSensorServicesCallbackManager::sendDataCallback(0x1b, data, ts)` [V+C]:
  - `blob[0] = 0` (mode DATA); `blob[1] = 1` (version; low byte of the word at ELF `0xC1048`); then a
    `0x80`-byte payload copied verbatim (`memcpy(blob+2, src, 0x80)`) — total `130 = 2 + 0x80`
    [V+C note 90 §Q2].
  - **timestamp**: base wall-clock ms + `accumulated_µs / 1000`, where `accumulated` is accumulated
    from a **24-bit counter in blob bytes `[0..2]`** with `0x1000000` wraparound; its unit is **µs**
    [V note 70 §2.1; not covered by note 90].
  - Bytes `[0x80..0x87]` of the `0x88` read fill the `sensors_event_t` only (not sent to Java)
    [V note 70 §2.1].
- Chip identity strings in the same binary: `"AFE4500S ECG"`, `"AFE4510S ECG"`,
  `"ECG_HEAVY_SIGNAL_NOISE"`; sysfs `/sys/class/sensors/ecg_sensor/{mode,leadoff}`; lead-off /
  self-diagnosis in the `EcgDiagnosis` class (`init`/`setMode`/`getLeadoff`/`checkElectrode`)
  [V note 70 §2.1].
- **The HAL contains no sample-rate constant.** The only rate-shaped value is the sensor-info
  `minDelay = maxDelay = 10000 µs`; the per-event sample count is chosen by the hub (the blob `mux`
  byte), which the HAL does not set [V note 70 §2.1; the lone `500` int is unattributable, §0.2].
- Event-struct modelling caveat [note 90 §3]: `parse` writes `0x11025` at `event + param_2*0x68 + 8`
  and copies payload with a `0x68` stride; the array-stride semantics are not fully pinned, but every
  written value (`type = 0x11025`, `sensor = 0x11`, 0x80-byte payload) is unambiguous.

### 1.2 Framework producer — `ECGHandler` (framework sensor service)

`parse(byte[], long)`: `mode = byte[0]` (0 = DATA, 1 = DIAGNOSIS); for DATA, `version = byte[1]`
selects v1 (`== 1`) or v2 (`== 2`). Static constants [V+C note 70 §2.2 / note 90 §Q2]:
`HEADER = 2`; `VERSION_INDEX = 1`; `MODE_INDEX = 0`; `ECG_DATA_SIZE = 0x80`;
`GAIN_FACTOR = 2.661416696947674e-4` (literal bits `0x3f31711dc47711dc`).
`setFeatureSupportMap` sets **`parsed_data = true`** and **`diagnosis = true`** unconditionally
[V note 70 §2.2].

The shared `0x80`-byte payload is `raw[0..31]` — 32 little-endian int32 at byte `2 + 4i`.

**v1 field map** (`raw[i]` = int32 LE at byte `2 + 4i`), §0.2 corrections applied:

- `ecg[10] = raw[4..13]`; each `ecg = (raw[i] − adc_offset) × GAIN_FACTOR` [V].
- `adc_offset = raw[29]` (int @ byte `0x76`) [V+C].
- `mux = byte[0x3f]` → `0 ⇒ ecg_count 5, ppg_green_count 1`; `1 ⇒ 10, 2`; anything else `⇒ 0, 0` [V].
- `lead_off = byte[0x3c] & 0xff` (= `raw[14]` bits 16-23) [V+C].
- `pattern = byte[0x3e] & 0xff` (= `raw[15]` bits 0-7) [V+C].
- `positive_threshold = round((raw[25] − adc_offset) × GAIN_FACTOR)`;
  `negative_threshold = round((raw[26] − adc_offset) × GAIN_FACTOR)` [V]; bytes `0x66`/`0x6a` ⇒
  `raw[25]`/`raw[26]` [V+C].
- `accuracy = byte[0x6e] & 0xff` (= `raw[27]` bits 0-7) [V+C].
- PPG-green `[raw[18], (mux == 1) ? raw[21] : 0]` [V; **C correction §0.2**].
- PPG-IR `[raw[16], (mux == 1) ? raw[19] : 0]` [V+C].

**v2 field map:**

- `timestamp = long @ byte 0x0a` [V+C].
- `ecg_count = byte @ 0x19` [V+C].
- `adc_offset = raw[20]` (int @ byte `0x52`) [V+C].
- `ecg[10] = float @ (0x1a + 4i)` [V+C].
- `lead_off = byte[0x17] & 0xff`; `pattern = byte[0x16] & 0xff` [V+C].
- `positive/negative_threshold = short @ byte 0x6 / 0x8` [V+C].
- `accuracy = byte[0x18] & 0xff` [V].
- PPG-green `[raw[16], raw[17]]`; PPG-IR `[raw[18], ecg_count == 10 ? raw[19] : 0]` [V+C].

### 1.3 The two number spaces (do not conflate)

- **SemSensorServices id space:** `0x1b` = 27 — used by the framework `ECGHandler.setAttribute`, the
  HTS capability query (`sb.k()` returns `0x1b`), SHM's `sa2` wrapper registration and
  `SensorType.TYPE_ECG` logging (`TYPE_ECG:27`) [V+C note 90 §Q1].
- **`sensors_event_t.type` space:** `0x11025` — written by the HAL `parse`;
  `SensorType.TYPE_WEAR_ECG = 0x11025` [V+C note 90 §Q1].

They are different encodings of "ECG": `0x1b` is the registration/capability id, `0x11025` the event
struct's `type` field. The adapter's `TYPE_ECG = 27` lives in the first space; the HAL's event
identity lives in the second.

### 1.4 HTS re-encode (`sb`/`tb`/`t3` + `EcgTracker`)

The HTS re-encoder decodes the same `0x80` blob and re-emits per-sample data-point values. The
wire value positions, the per-batch field placement (lead/sequence/thresholds live only on data-point
index 0) and the wall-clock timestamp semantics are specified in
[health-tracking-protocol.md](health-tracking-protocol.md) §4.1–4.3; they are **not duplicated here**.

- `sb.n()` sets a flag `g = isFeatureSupported(0x1b, "parsed_data")` (missing method → false); `sb.m`
  dispatches on it [V note 70 §2.3].
- **Parsed branch** (the one that runs on this build, §4): `offset = raw[20]`;
  `ADC[i] = raw[4+i] − raw[20]`; `mV[i] = getEcg()[i]`; per-point ppg green
  `= getPpgGreen()[i] − getAdcOffset()`; `lead = getLeadOff() == 0 ? 0 : 5`;
  `sequence = getPattern()`; threshold ADC `= raw[25]/raw[26] − offset`; threshold mV
  `= getPositiveThreshold()/getNegativeThreshold()`; `ts = System.currentTimeMillis()` [V+C].
- **Legacy branch:** `count = ((raw[15] >> 8) & 0xff) == 1 ? 10 : 5`; `ADC[i] = raw[4+i] − 0x200000`;
  every `mV = −9999.0f` (bits `0xC61C3C00`, smali literal `const, -0x39e3c400` — **not**
  `0xc6390000`); ppg green `[raw[18] − 0x200000]` (count 5) or
  `[raw[18] − 0x200000, raw[21] − 0x200000]` (count 10); `lead = (raw[14] & 0xff0000) >> 16`;
  `sequence = raw[15] & 0xff`; threshold ADC `= raw[25]/raw[26] − 0x200000`; threshold mV `= 0f`
  [V+C note 70 §2.3 / note 90 §Q3/Q4].
- `Ltb` fields (from its `toString`): `a` timestamp, `b` ecgAdc, `c` ecgMv, `d` ppgGreen, `e`
  leadOff, `f` sequence, `g` thresholdMax(adc), `h` thresholdMin(adc), `i` thresholdMaxMv, `j`
  thresholdMinMv [V note 70 §2.3].
- `EcgTracker.fetchOtherValueFromData` builds the data-point value list; at index 0:
  `[0] = (adc, mv)`, `[1] = ppgGreen[0]`, `[2] = leadOff`, `[3] = sequence`, `[4] = (g, i)`,
  `[5] = (h, j)` [V note 70 §2.3].
- Adapter ingest (`hsdk/ServiceProtocol.parseBatch`): reads value position 0 → mV float; reads `[2]`
  lead, `[3]` seq, `[4]` maxTh, `[5]` minTh **only** for `i == 0`; maps
  `MV_UNAVAILABLE = −9999.0f` → NaN; wire codes `CB_DATA = 1`, `CB_ERROR = 2`, `CB_FLUSH = 3`
  [V read of the adapter; §4].

### 1.5 SHM consumer (`pl` / `c90` / `f80`)

- `pl` (decodes a delivered raw `int[]`): `lead = (raw[14] & 0xff0000) >> 16`;
  `sequence = raw[15] & 0xff`; `count = ((raw[15] & 0xff00) >> 8) == 0 ? 5 : 10`; loop
  `c90.k[c90.g++] = (raw[i] − 0x200000) × 2.6614e-4 × c90.d`, with `c90.g` capped at `0x3a98`
  (15000); the first pass sets `c90.e = (raw[25] − 0x200000) × scale` and
  `c90.f = (raw[26] − 0x200000) × scale` [V+C note 70 §2.4 / note 90 §Q3/Q4].
- Validity window polarity: **lower = `raw[26]`, upper = `raw[25]`** (in mV); `f80` flags
  `"Invalid Signal Value Range"` when a sample falls outside `[raw[26], raw[25]]` [C note 90 §Q3].
- `c90`: `k = double[15000]`; `d = +1`; `g,h = 0`; `i,j` bool [V note 70 §2.4].
- `f80` (TimerTask, two variants): process-variant guard `c90.h + 10 <= c90.g`; emits **5 output
  samples per run**, each the average of 2 consecutive `c90.k` entries (2:1 decimation); `g80.l++`
  per sample; per-sample timestamp step **0.002 s** (bits `0x3f60624dd2f1a9fc`); builds the report
  when `g80.l >= 0x1d4c` (7500) and labels its rate **`500.0`** (bits `0x407f400000000000`); the
  countdown variant decrements `g80.n` [V note 70 §2.4; the `0.002` step confirmed note 90 §Q5].
- `u70.start`: `EcgAlgorithmLoader.load().init()`; `c90.i = true`;
  `g80.j = currentTimeMillis()/1000.0`; `l = k = 0`; `n = 0x12c` (300); Timer `g80.m` = f80 variant 0,
  period **100 ms** (countdown → 30 s); Timer `g80.g` = f80 variant 1, period **20 ms**
  (process → 5 samples / 20 ms = 250 Hz out); activity timer `0x1388` (5000 ms) [V note 70 §2.4;
  single-session].
- `tx0.g` init `0x32` (50); `EcgReadyActivity` sets `tx0.g = sa2.b(0x1b, "parsed_data") ? 0x32 : 0x64`
  (50 vs 100) and registers via `sa2.c(listener, 0x1b)` (2-arg `registerListener`, no rate
  attribute) [V note 70 §2.4].
- `sa2` = SensorManagerEx wrapper: `a` isAvailable, `b` isFeatureSupported, `c`
  registerListener→int(0=ok), `d` setAttribute, `e` unregisterListener [V note 70 §2.4].

### 1.6 SHM native ECG stack

- `libshm-ecg.so` NEEDED: `libcalctruefs.so`, `liboffline_filter.so`,
  `libfinalrhythmclassification.so`, `libsamsung_ecgannotator.so`; the v2.0 set is the same shape
  [V note 70 §2.5].
- `EcgLibrary.offlineSignalFilter([D)` calls `nativeOfflineSignalFiltering(500, samples)`: the
  **literal `0x1f4` (= 500)** is read in the smali (v1 @ line 512, v2 @ line 540) and passed through
  the JNI into `OfflineSignalFiltering(fs, …)` [V+C note 70 §2.5 / note 90 §Q5].
- `EcgLibrary.offlineFilter([D)` allocates a `120000`-byte buffer = **15000 doubles**
  (30 s @ 500 Hz) [V note 70 §2.5].
- `nativeOnlineSignalFilter_Init()` takes **no fs argument** (rate chosen inside the annotator via a
  jump table). The annotator exports filter variants named by rate:
  `Samsung_IIRFilter_Float_Init_LP_50_500` (50 Hz low-pass @ 500 Hz) and
  `Samsung_IIRFilter_Float_Init_LP_50_250` (designs at 250 Hz), plus `SamsungOfflineECG_Process500_1`
  [V+C note 70 §2.5 / note 90 §Q5].
- `EcgLibrary.init()` calls `initAnnotator(0x32, 0x64, 0xbb8, …)` (50, 100, 3000), then
  `initCalcTrueFs()`, then `initOnlineSignalFilter()`; `Annotator_Init` @ annotator `0x31d58`
  forwards the first three args to a version string and `SamsungFilter_Init`, and calls
  `THRCalculator(…, 0xfa /* 250 */, …)` [V note 70 §2.5].
- `calcTrueFs(width=5, n)` is a native "true fs" **estimator** — it measures the rate, it does not
  set it [V note 70 §2.5].

---

## 2. Constants table with provenance

Offsets below are file offsets in the cited binary unless stated; every value was byte-verified in at
least one session, and the load-bearing ones in two (§0.1).

| constant | value | provenance |
|---|---|---|
| LSB (producer `GAIN_FACTOR`) | `2.661416696947674e-4`, bits `0x3f31711dc47711dc` | framework `ECGHandler` static field [V+C] |
| LSB (SHM consumer scale) | `2.6614e-4`, bits `0x3f317116989dc624` | `pl` L200 [V+C] |
| LSB (adapter) | `2.6614e-4` | `EcgAdapter.LSB_MV` L45 [V] |
| LSB delta | **≈ 6.3 ppm** between producer and consumer constants | note 90 §Q3: same physical LSB, different decimal rounding of the literal on each side [C] |
| ADC center | `0x200000` | `pl` L202; HTS legacy; `EcgAdapter.ADC_CENTER` [V+C] |
| Unavailable sentinel | `−9999.0f` (bits `0xC61C3C00`; smali `const, -0x39e3c400`) | HTS `sb` L416; `hsdk/ServiceProtocol.java` L49 (`MV_UNAVAILABLE`) [V+C] |
| SHM per-sample step | `0.002 s` → 500 Hz | `f80` L515 [V+C] |
| SHM target-rate literal | `0x1f4` = 500 | `EcgLibrary` v1 L512 / v2 L540 [V+C] |
| SHM offline buffer | 15000 doubles (120000 B) = 30 s @ 500 Hz; `c90.k` cap `0x3a98` = 15000 | `EcgLibrary.offlineFilter`; `pl` [V] |
| Annotator rate constants | `f64 500.0` @ file `0x28f58`; `f64 0.002` @ `0x24980`; `f32 0.002` @ `0x252c8` | `libsamsung_ecgannotator.so` [C] |
| Annotator filter symbols | `Samsung_IIRFilter_Float_Init_LP_50_500`, `Samsung_IIRFilter_Float_Init_LP_50_250`, `SamsungOfflineECG_Process500_1` | annotator exports (exported spelling; note 90 §Q5 wrote the LP name without `Float`) [V] |
| HAL event period | `minDelay = maxDelay = 10000 µs` | sensor-info record @ file `0xbe040` [V, single-session] |
| HAL sensor ids | handle `0x11`; SemSensorServices id `0x1b` (27); event type `0x11025`; read size `0x88` | HAL global init `0xa9370` / ctor `0xa8c28` [V+C] |
| HAL timestamp counter | 24-bit, µs, blob bytes `[0..2]`, wrap `0x1000000` | HAL `parse` `0xa8f50` [V, single-session] |
| v1 count selector | byte `0x3f`: `0 ⇒ count 5`, `1 ⇒ 10`, else `0` | `ECGHandler` v1 [V] |
| lead normalization | `0` = contact, `5` = no contact | HTS `sb` parsed path [V+C] |
| adapter band | `bandHi/bandLo = 0x200000 ± round(5.0 / LSB)` | `EcgAdapter` L54-55; **no firmware `±5 mV` literal** [V adapter / C negative] |
| adapter EMA high-pass | `alpha = 0.001` (~2 s @ 500 Hz) | `EcgAdapter` L171 [V] |
| adapter keepalive | synthetic events, `lead = 5`, 10 Hz | `EcgAdapter` L239-260 [V] |

---

## 3. The rate question — 500 Hz is the stack's constant, not the HAL's

**500 Hz is the SHM stack's constant**, present in three independent AP-side places (each an
independent fact, not a repeat):

1. `EcgLibrary.offlineSignalFilter` passes the literal **`0x1f4` (500)** into
   `nativeOfflineSignalFiltering` (smali v1 L512 / v2 L540) [V+C].
2. `f80` advances each sample by **`0.002 s`** ⇒ 1 / 0.002 = 500 Hz [V+C].
3. The annotator carries a **`f64 500.0`** constant and a 50 Hz low-pass **designed for 500 Hz**
   (`…_LP_50_500`), alongside the `SamsungOfflineECG_Process500_1` symbol [V+C].

Supporting: `offlineFilter` allocates exactly **15000 doubles** = 30 s at 500 Hz, and `c90.k` is
capped at **15000** [V].

**The HAL does not set the rate.** No rate constant exists in `sensors.default.so`; the only
rate-shaped value is `minDelay = maxDelay = 10000 µs` (100 events/s), and the per-frame ECG sample
count is chosen by the hub (`mux`), which the HAL does not set [V]. The lone `500` int at ELF vaddr
`0x856c` is unattributable and not counted [C].

**The hub `mux` decides 5-vs-10 samples/event.** With 100 events/s, the delivered rate is
`100 × (samples/event)`: `mux == 0 ⇒ ecg_count 5 ⇒ 500 Hz`; `mux == 1 ⇒ ecg_count 10 ⇒ 1000 Hz`
[I from V facts]. The adapter assumes 500 Hz [V].

**Internal-consistency caveat.** SHM's own consumer disagrees with itself: `f80` emits 5 samples per
20 ms run (250 Hz out) yet stamps the per-sample time at `0.002 s` and labels the record `500.0`
[V note 70 §2.4/A10].

**What the device measured (2026-10-15, §5a).** The events/s and ECG slots per event on the owner's
watch ([health-tracking-protocol.md](health-tracking-protocol.md) §9.2) and the HTS branch that runs
(§9.3) are now measured [V]: every batch carries **10 samples**, and the delivered rate is
**≈487–502 Hz** — within −2.7 % of the stack's 500 Hz constant, so the run confirms the ~500 Hz design
rather than overturning it. A rate other than 500 Hz would still rescale every filter time constant and
the 15000-sample / 30 s record; the measured ≤ 2.7 % delta bounds that rescale (§5a).

---

## 4. Equivalence verdict for `EcgAdapter`

The adapter is an **HTS client**, not a HAL speaker: it consumes HTS per-sample values and
**reconstructs the v1 `int[]` raw form** that the SHM consumer `pl` reads (`getRawValueList()`), never
the `getEcg()` float path [V note 70 §3; C note 90 §Q6].

**Faithful items** [V+C note 90 §Q6]:

- The `int[]` raw contract: center `0x200000`, LSB, slots `raw[4..13]`, `raw[14]`, `raw[15]`,
  `raw[25]`/`raw[26]`.
- Event/key shape: `SemEcgSensorEvent` with `mSensorId = TYPE_ECG = 27`, Bundle key `"value"`,
  `getRawValueList()`.
- Count encoding `raw[15] = (count << 8) | seq` with sequence wraparound.
- Lead `0/5` normalization (`5` stays `5`, anything else → `0`).
- `raw[4+i] = 0x200000 + round(mV / 2.6614e-4)` ⇔ `pl`.
- The adapter's output is indistinguishable from a stock v1 event at the `pl` boundary.

**REPORT-ONLY divergences** (adapter-only choices; listed, **not** fixed — the owner decides):

- **(a) Count word.** The adapter writes `count = 10` into `raw[15]` bits 8-15. SHM `pl` accepts any
  non-zero as 10 (so it is fine there), but the **HTS legacy** decoder (`sb.m`) maps `count == 1 ⇒ 10,
  else 5`, so it would decode **5** and mis-read the ppg pair `raw[18]`/`raw[21]`. A value of `1`
  satisfies both consumers. **Would need:** count word `= 1` to satisfy both; no change implemented
  (owner decides).
- **(b) `±5 mV` band is the adapter's substitution, not a firmware constant.** Stock `raw[25]`/`raw[26]`
  are producer data (the sensor's own thresholds); no `±5 mV` literal exists in the firmware [C
  negative]. The adapter substitutes a fixed window `0x200000 ± round(5.0 / LSB)`.
- **(c) raw length `27` vs framework `32`.** The framework `parseRawData` wants 32 ints, but that path
  is **bypassed** (the adapter feeds `pl` directly), and `pl` accesses at most index 26 — OK [C note
  90 §Q6.3].
- **(d) `adc_offset` (`raw[20]`) left at 0.** `pl`'s legacy path ignores it (fixed `0x200000`), so this
  only matters if a **parsed-data consumer** is used instead [C note 90 §Q6.4].

**Adapter-only choices that are not contract divergences** (recorded for completeness) [V note 70 §3]:
the adapter synthesises only the `"value"` key (stock never sends it — HTS re-derives the parsed
fields); it applies its own EMA high-pass (`alpha = 0.001`), because the HTS-consumed signal is
DC-coupled (−142…−31 mV observed) whereas the stock front-end couples the signal in AC; and it runs a
10 Hz `lead = 5` keepalive to feed the pipeline watchdog until real data flows. None of these changes
the `pl`-visible raw contract.

**Lead-off debounce (adapter choice, 2026-10-20).** SHM's lead state machine needs 50 consecutive
`lead = 0` events to reach `LEAD_ON_CHECKED`, but from there a **single** `lead != 0` event cancels the
measurement (`LEAD_OFF`) [V smali `f8.c` / `k51.c`]. Field logs (issue #13, SM-L7050) show lone
lead-off events 37–900 ms after contact aborting otherwise good runs, and §5a saw an isolated `5` right
after settle. `LeadDebouncer` forwards a `0 → 5` transition only after 4 consecutive `lead = 5` batches
(~80 ms); `5 → 0` passes at once, so the contact check is unchanged. Every transition and every ignored
blip is logged (`lead: …`). `BeatMonitor` is a logging-only beat detector on the filtered signal: each
stats window logs the beat-to-beat rate and the beats' polarity (`beats: …`), to compare against the rate
SHM displays. Neither changes the raw sample values.

**Verdict:** the adapter's output is a **faithful reconstruction** of the stock v1 event at the `pl`
boundary, with the four report-only divergences above [D]. The one item that is not a code question —
the actual delivered rate/timeline — is now measured for the rate (§5a); the adapter's own timeline
still awaits an adapter-driven run (§5, Q3).

---

## 5. Open questions (device test)

Each item from the analyst's list, mapped to [health-tracking-protocol.md](health-tracking-protocol.md)
§9. Device sessions are owner-gated; the watch's wireless adb serves one client. The 2026-10-15
on-wrist / off-body run measured items 1, 4 and 5 (see §5a, tagged [V]); items 2, 3 and 6 remain open.

1. **Event cadence and samples/event on the SM-R920. → MEASURED (§5a).** The HAL advertises a 10 ms
   period but does not set the per-frame count (hub `mux` decides `ecg_count` 5 vs 10). Measured on
   device: **every batch carries 10 samples** (all batches, both sessions), ≈48.7–50.1 events/s ⇒
   **≈487–502 Hz** — the 10-slot case, i.e. the `mux = 1` shape, rather than the `mux = 0` 5-slot case.
   → protocol §9.2.
2. **Message version on the SM-R920. → still unobserved.** `getSensorInfo` fills the version from sysfs
   (only 1 or 2), which selects the v1 vs v2 producer layout. The device run did not read the sysfs
   version; note that the delivered `m = 6` parsed-data shape is consistent with the parsed HTS path
   **regardless** of which producer layout (v1/v2) runs upstream (§5a). → protocol §9.3 (producer
   layout).
3. **Adapter timeline vs the 15000-sample / 30 s record. → still open.** Confirm the adapter's
   reconstructed stream yields the correct `pl`→`f80` timing on device. The probe recorded the HTS
   stream, not the adapter's reconstructed event stream, so this still needs an adapter-driven run.
   → protocol §9.2.
4. **Lead-off mapping in the field. → MEASURED (§5a).** Stock `0` = on, `5` = off. Measured: on-wrist
   **`0` dominant** (a single `5` on the very first batch, after settle), off-body **`5` dominant** (a
   single transient `0` at the second batch head). The `5 → 5`, else `0` normalization survives HTS for
   the SM-R920 tracker. → protocol §9.5.
5. **Which HTS branch actually runs. → MEASURED (§5a).** `parsed_data` is advertised true and the
   override forces it true. Measured on device: the **parsed** path runs — real mV in **100 %** of
   delivered samples and **zero** `−9999.0f` sentinels (on-wrist 24950/24950, off-body 12058/12058).
   → protocol §9.3.
6. **Online-filter rate inside the annotator. → still open.** `OnlineSignalFilter_Init()` takes no fs;
   the fs is internal to the annotator (which ships both a 500 Hz and a 250 Hz low-pass). Relevant only
   if the online filter's assumed rate ever diverges from the delivered stream. Note the measured
   delivered rate is ≈487–502 Hz (§5a), so the 500 Hz low-pass variant is the one that matches, to
   within ≤ 2.7 %. → protocol §9.2.

The **comparison CSVs** that would settle the concurrent stock-vs-HTS question do not exist: the
**§9.10 A/B was SKIPPED by owner decision**, and the adapter-timeline run has not happened yet. The
**on-wrist / off-body HTS datasets** of 2026-10-15 did land in the analysis cache; their numbers are
§5a. (Full per-session tables: `ecg-val/RESULTS.md`, outside this repository.)

### 5a. Device measurements — 2026-10-15 (SM-R920, on-wrist + off-body) [V]

**Provenance.** Owner-authorized sessions of 2026-10-15 recorded over the watch's wireless adb; raw
files in the analysis cache under `ecg-val/`: the `onwrist1/` session (CSV + logcat + meta) and the
`offbody1/` session (CSV + logcat; its meta is corrupted, note 3). Each row is the delivered stream the
probe read from HTS (tracker `ECG`, sensor 27, `ECG_MV`); the §0–§4 code-reading results are the [V]
baseline this run tests against.

| measurement | on-wrist (`onwrist1/`) | off-body (`offbody1/`) |
|---|---|---|
| points / batches | 24950 / 2495 | 12058 / 1206 |
| samples per batch | **10**, all batches | 10 (last batch partial) |
| delivered rate | **≈487–502 Hz** (CSV wall span 501.4 Hz; probe summary 498.9 Hz) | ≈502 Hz (CSV wall span; full-session summary 499.6 Hz) |
| `m` field width | 6 | 6 |
| mV real / sentinel | 24950 / **0** | 12058 / **0** |
| mV mean / [min, max] | +127.60 / [+123.82, +147.81] | −131.96 / [−745.26, +745.26] |
| lead-off | `0` × 2494, `5` × 1 (first batch only) | `5` × 1205, `0` × 1 (second batch head) |
| maxThMv / minThMv | **+745.0 / −745.0**, constant | **+745.0 / −745.0**, constant |
| HTS branch | parsed (real mV) | parsed (real mV) |

1. **Rate delta vs the 500 Hz assumption.** Across both sessions and all three measures (CSV wall
   span, probe summary, meta) the delivered rate spans **≈486.75–502.2 Hz**, i.e. within **−2.7 %** of
   the stack's 500 Hz constant. Practical implication: a 486–502 Hz stream run through a 500 Hz-designed
   filter leaves **every filter time constant off by ≤ 2.7 %** — a sub-sample timing error, not a
   structural mismatch, so the `0x1f4` / `0.002 s` design holds.
2. **Stock thresholds measured: ±745.0 mV.** Both sessions deliver a constant `maxThMv = +745.0` and
   `minThMv = −745.0` on every batch head. This **sharpens §4 divergence (b):** the adapter substitutes
   a fixed window of `±5 mV` (`bandHi/bandLo = 0x200000 ± round(5.0 / LSB)`), whereas the stock
   producer's own validity window on this build is **±745 mV** — the adapter's ±5 mV band is far narrower
   than the stock producer's ±745 mV window (≈149×).
3. **Anomalies recorded, not speculated on.** (a) `offbody1`'s `*-meta.txt` is corrupted (declares
   `type=SPO2` / `points=3294`); the CSV + logcat are the authoritative ECG record. (b) That off-body
   CSV is truncated to ~24.0 s of the 30 s recorded (the probe's own summary reports the full 30 s).
   Concurrent probe use by another workstream is the suspected cause of both — recorded as an anomaly
   only, not concluded. (c) `onwrist1`'s meta wall window (51258 ms) disagrees with the probe's log
   (50011 ms) and the CSV span (49757 ms); the CSV matches the log, so the meta value is the outlier.
4. **§9.10 A/B was SKIPPED by owner decision** — no concurrent stock SHM ECG measurement was run (no
   `EcgServiceModule` line in either logcat), so the gain/rate/lead-off stock-vs-HTS comparison has no
   data yet.

---

## 6. Scope and evidence

**Binaries and trees analyzed** (hashes recorded where the records carry them):

| file | where | hash / identity |
|---|---|---|
| `sensors.default.so` (the sensor HAL) | `fw/SM-R920/R920XXS2DZG3/out/vendorlib/hw/` | 801612 B; sha256 `924fd4fecbad5f46fbdf37522929107f776ded97fcf71a3d02b2408f856ffb49` [V] |
| HealthPlatform APK (HTS 1.7.00.05) | `fw/SM-R920/R920XXS2DZG3/out/HealthPlatform/` | 5871371 B; sha256 `93298b71f4335c34c4969f4b82fca337165797b656a7f6b29d43e2c4e40d2b5e` [V] |
| `libsamsung_ecgannotator.so` | SHM watch 1.5.1.022, `lib/armeabi-v7a/` | sha256 `d01518cf…` (prefix as recorded) [V] |
| `libsamsung_ecgannotator_v2_0.so` | SHM watch 1.5.1.022, `lib/armeabi-v7a/` | sha256 `bcdb977e…` (prefix as recorded) [V] |
| `libshm-ecg.so`, `libshm-ecg-2.0.so` | SHM watch 1.5.1.022, `lib/armeabi-v7a/` | analyzed (JNI contract, literal `0x1f4`, buffer size, NEEDED set); **hashes not recorded** [V / N] |

Decoded trees read (provenance only, no code copied): SHM watch 1.5.1.022 smali (`pl`, `c90`, `f80`,
`u70`, `tx0`, `sa2`, `EcgReadyActivity`, `SemEcgSensorEvent`, `EcgLibrary` v1/v2); the framework
sensor service `ECGHandler`; HTS `sb`/`tb`/`t3` + `EcgTracker` + `ValueKey$EcgSet`; the
SemSensorManager framework library [V note 70 §0; note 90 §5].

**NOT analyzed** (declared, not papered over):

- The **ECG sensor-hub configuration** — the per-event `mux`, the true delivered rate and the
  samples-per-event live in the hub firmware, which was not analyzed for ECG.
- Any ECG/BP **producer library** under `system/lib` / `vendor/lib` beyond the HAL and the framework
  service (the SpO2 extraction kept only SpO2-named libs; validation hand-off §3).
- The **device**: an owner-authorized session ran 2026-10-15 (§5a) — the delivered rate, the
  parsed-vs-legacy branch and the lead-off semantics are now **measured**; the sysfs message
  version (Q2) and an adapter-driven timeline run (Q3) remain unobserved, and the §9.10 A/B
  against the system SHM was skipped by owner decision.
- A **per-model comparison** of the HAL / HealthPlatform across the seven watch families (the ECG HAL
  was analyzed on the SM-R920 only; the SpO2 binaries were byte-identical across families, but that
  was not re-established for the ECG HAL).
- The **BP** and **SpO2** tracks (separate documents).
- Clinical validation of any output; and any implementation change (report-only, §4).
