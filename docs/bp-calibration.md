# Blood-pressure (BP) calibration and data formats — Galaxy Watch (clean-room specification)

Reconstructed 2026-10-15 from the official firmware of devices the owner owns, for the OpenSHM
`BpAdapter` that feeds the watch SHM BP flow ([patches.md](patches.md) watch rows 7c/7d) with the
green PPG ADC stream delivered by the Health Tracking Service
([health-tracking-protocol.md](health-tracking-protocol.md) §4.4). Datasheet facts only — inputs,
processing steps, calibration model, rate claims, constants with units and provenance (binary file +
build + offset). No decompiled code; every fact was read from binaries the owner's devices run, by
static analysis of official firmware, plus byte re-reads of the raw files this revision.

Purpose: state the **`green_raw` equivalence verdict** (§1), the **functional datasheet of
`libbp_v3_06_10.so`** (§2), and the **constants with provenance** (§3); report — not implement — the
correction `BpAdapter` would need (§4); collect everything not pinnable in §5. This is the BP
counterpart of the committed `docs/spo2-calibration.md` and `docs/ecg-calibration.md` (same format
model; read-only, not modified).

Provenance convention: citations of the form "note 70" (analyst working record) and "note 90"
(independent confirmation record) refer to dated working records kept in the analysis work directory
outside this repository; this document is self-contained without them. Evidence tags:

- **[V]** — verified: read or executed in the cited record's session (byte reads, disassembly /
  decompile reads, smali reads, sha256 / readelf runs); **this revision's own byte re-reads are [V] too**.
- **[C]** — independently confirmed: reproduced by the second session that had not read the first
  analyst's records; corrections that process produced are authoritative (§0.2).
- **[I]** — inferred: fits the evidence but not directly proven.
- **[D]** — derived here, from verified facts.
- **[N]** — **not reproduced / not recorded**: absent from the analyzed artifacts; it is listed in
  §5, never invented.

Baseline device/build: **SM-R920 (Galaxy Watch5 Pro), `R920XXS2DZG3/R920OXM2DZG3` (CSC ARO)** — the
owner's device and running build, One UI 8 Watch / Android 16, 32-bit userspace (`/system/lib`),
Exynos W920. The analyzed SHM watch is **1.5.1.022** (`watch8-crash/shm`, its
`lib/armeabi-v7a/libbp_v3_06_10.so` + `libshm-bp.so`); the sensor HAL is the SM-R920
`sensors.default.so` (§6). All firmware was pulled from Samsung's FUS server and unpacked per recipe
note 30.

---

## 0. Verification status — what was independently confirmed and what was not

### 0.1 Independently confirmed (two sessions; the confirmer had not read the analyst's notes)

The second session re-derived every value from binaries with its own Ghidra project plus direct
capstone / pyelftools / `struct` raw-byte reads. Its per-question verdicts, all reaching the same
conclusions as the analyst's:

| Finding | First stated | Confirmed by |
|---|---|---|
| `green_raw` equivalence: both producer handlers read a **raw signed little-endian int32**, no scale/offset; both HAL `parse` wrappers only **length-validate** | note 70 §a.1 | note 90 §Q1 (fresh handler + HAL reads) |
| Rate family + consumer drive: PPG100Hz = 100 Hz; consumer watchdog **200**, drain **20 samples/call** | note 70 §a.2 | note 90 §Q2 |
| Native input contract: exactly **20 samples** (`!= 0x14` ⇒ error); `[S]`/`[D]` are **inputs**; DC ref = first sample locked at block **16**; resample **60 → 150** (axis `i·100/250`); **4th-order IIR**, block **64** doubles, out = **LP − HP**; result triple `(result, progress, systole, diastole, hr)` | note 70 §b.1/b.2 | note 90 §Q3 |
| Calibration model: `recal_BP(ref, calFeat, curFeat, out)` present; const pools at 0xc870 / 0xcdf8; stock consumer supplies **zeros** ⇒ uncalibrated | note 70 §b.2/§c | note 90 §Q4 |
| Consumer state machine: watchdog > 200 ⇒ cancel, drop 100, reset, restart 10 ms @1 sample/tick; drain 20/call; stall ≥ 22 ⇒ abort `h(3)` | note 70 §b.3 | note 90 §Q5 |
| Consumer reads **no** quality/accuracy flag — only `SemBpManualSensorEvent.getGreenRaw()` | note 70 §a.4 | note 90 §Q6 |
| `BpAdapter` needs **no software-determined numeric correction**; physical AFE gain is not pinnable from binaries | note 70 §c/§d1 | note 90 §Q7 |

Every load-bearing number below (§2/§3) agrees between the two notes after the corrections of §0.2.
The **`libbp` sha256 and BuildID were re-computed this revision** and match both notes (§3).

### 0.2 Corrections this consolidation produced (authoritative — use these values)

1. **Coefficient-pool addresses — the tie-break (re-read from the bytes this revision).** The two
   notes cite the same four arrays at different addresses, and both are internally consistent; the
   gap is a **duplicated object**:
   - The `.data` section is **ELF VA `0x25d90`, file offset `0x23d90`** (Δ = **0x2000**; `.text` and
     `.rodata` have VA = file offset). The full symbol table (`readelf --dyn-syms`) carries **eight**
     40-byte (5-double) `GLOBAL OBJECT`s — the four coefficient arrays each in **two byte-identical
     copies**:
     - `glpf_coefB_byte` @VA `0x25d90` / file `0x23d90`, `glpf_coefA_byte` @VA `0x25db8` / file
       `0x23db8`, `ghpf_coefB_byte` @VA `0x25e30` / file `0x23e30`, `ghpf_coefA_byte` @VA `0x25e58` /
       file `0x23e58`;
     - `glpf_coefB` @VA `0x25de0` / file `0x23de0`, `glpf_coefA` @VA `0x25e08` / file `0x23e08`,
       `ghpf_coefB` @VA `0x25e80` / file `0x23e80`, `ghpf_coefA` @VA `0x25ea8` / file `0x23ea8`.
   - **Only the four `_byte` twins are referenced by the code**: the GOT carries `R_ARM_GLOB_DAT`
     relocations for `glpf_coefB_byte` (GOT 0x24b50), `glpf_coefA_byte` (0x24b4c), `ghpf_coefB_byte`
     (0x24b48) and `ghpf_coefA_byte` (0x24b44) — and **none** for the plain-named twins. Both
     copies are byte-identical (verified this revision, all four pairs `True`).
   - **Resolution:** note 70's appendix cited the ELF **VAs of the plain twins** (which the code does
     not read); note 90 cited the **file offsets of the `_byte` twins** (which the code does read).
     Because the twins are byte-identical the **values** are identical in both notes, so no numeric
     correction is needed; the **canonical object** the code loads is the `_byte` twin. §3 records the
     resolved pool ladder once, correctly, in **both address spaces**. [V — `readelf -SW`, `-sW`,
     `-rW` + raw `struct` reads this revision]

2. **Coefficient values (byte-identical confirmed this revision).** The four arrays decode exactly
   as note 90 §Q3 and note 70's appendix list them (§3). Note 70's decimals (`7.957e-5`,
   `3.183e-4`, …) are the same numbers rounded; the full-precision doubles are in §3.

3. **`estimateBP` tail pool.** note 70 places `11.399999999999999, 3.6, 0.7, 0.3` at ELF `0xd740`
   (with `300.0` noted separately at `0xd218`); note 90 §Q4 describes the same list as "`+0x10/+0x18`".
   Re-read this revision: file `0xd208` = `{100.0, 250.0, 300.0}` (axis scales), file `0xd740` =
   `{11.4, 3.6, 0.7, 0.3}` (fs/quality thresholds), and the fusion weight pair `{0.7, 0.3}` sits at
   file `0xd750`/`0xd758`. **note 70's `0xd740` attribution is correct**; the `300.0` is the third
   element of the `0xd208` triple, not part of the `0xd740` list. [V this revision]

4. **Symbol-name aliases (resolved as naming, not reproduced literally).** The analyst's questions
   named "recur_BP" and "gpfl"; the exported symbols are **`recal_BP`** (FUNC, `0xc6a9`) and
   **`glpf`** (`glpf_coefA/B`, OBJECTs) [V note 90 §Q4; V this revision — `readelf --dyn-syms`].

5. **HAL-advertised `maxDelay` for the manual-BP sensor (type 0x1c) was not reproduced.** Neither
   session extracted it from `sensors.default.so`; consequently the BPManual **≈100 Hz** nominal rate
   is **[C]/[I]** (inferred from the consumer cadence: 20 samples / 200 ms tick, and the 200-sample
   watchdog treated as "≈2 s"), not a read constant. The PPG100Hz family rate (type 0x3d = 61) is
   **[V]** (sensor name + `PPG100HzSensor`). [C note 90 §Q2/§4]

6. **Duplication rationale — [N].** *Why* the compiler emitted two copies per array (with only the
   `_byte` twins GOT-referenced) is not determinable from the binaries; it is a compiler/perf-mode
   artifact (see §5). The **fact** of the duplication and the reference set is [V] (§0.2 item 1).

### 0.3 Independence caveat, and single-session findings NOT confirmed

**Independence caveat (recorded, not overstated).** The confirmation record's own preamble states its
session "never read the first analyst's BP notes" (`70-*`, the hand-offs, any BP-lane note), and every
value was re-derived with a fresh Ghidra project plus capstone / pyelftools / `struct` raw reads
[V note 90 §0]. No snippet of note 70 is recorded as having surfaced in that session (unlike the ECG
track, where a grep preview did). Independence is therefore **taken at face value as recorded**, on
the strength of the binary-only derivation; the tags on the §0.1 rows remain [C].

**Single-session, not independently confirmed** (note 70 only):

- The consumer-side smali chain in full detail (`pl`/`rl`/`zl`/`sl` field-level reads) — both notes
  read it, but the confirmer's coverage is Q5/Q6-level (state machine + no-flag-read), not a
  line-for-line re-read of every slot. Treated as **[V] (single session) where line-level**.
- The `setParam` tunable-parameter struct layout (pool doubles partially identified; layout [N]) —
  **note 70 only**; it affects only *how* the model is tuned, not the input contract.
- The `libshm-bp.so` JNI shim's assembly-level address mapping (which `JNIEnv` vtable slots) — note 70
  only; the **contract** it establishes (arrays are inputs; no `Release…` copy-back) is [V]/[C] on
  both notes.

### 0.4 Could-not-reproduce list (folded from the confirmer's §4)

1. **HAL `maxDelay` / nominal rate for HRM_BP (type 0x1c)** — not extracted; BPManual ≈ 100 Hz is
   inferred (§0.2 item 5).
2. **AFE analog gain / optical-to-code transfer** for `green_raw` — no gain table in any analyzed
   binary; physically unpinnable (§1.5, §5).
3. **The real runtime calibration values** (reference BP `[S]` and the 16-double features `[D]`) —
   the stock consumer hard-codes zeros; the calibration workflow that populates them is not in these
   artifacts (§2.4, §5).
4. **Why the coefficient objects are duplicated** — §0.2 item 6.
5. **Symbol-name aliases** in the questions ("recur_BP"/"gpfl") resolved as naming (§0.2 item 4).

The absolute addresses 0x25de0/0x25e08/0x25e80/0x25ea8 (note 70) and the file offsets
0x23d90/0x23db8/0x23e30/0x23e58 (note 90) are **not a contradiction** once the duplicated twin set is
recognised (§0.2 item 1): they name the two copies of the same four arrays.

---

## 1. The `green_raw` equivalence verdict

**Verdict: at the software level the two streams are equivalent in scale, format and (nominal) rate;
neither producer filters; all conditioning happens inside the native library. Quality flags exist on
the stock path but are unused by the BP consumer, so `BpAdapter` omitting them is
behaviour-preserving. The one axis the binaries cannot settle is whether the hub's analog front end
applies the same gain to the manual-BP green channel (sensor 28) as to the PPG green channel
(sensor 61) — that gain ratio is device-measurable only (§1.5, §5).**

### 1.1 Scale / format — MATCH [V][C]

Both paths carry a single **untransformed little-endian signed int32 ADC count** per sample; no
scaling, offset or conversion is applied by any producer or wrapper:

| Stage | sensor 28 (manual-BP → stock consumer) | "green" the adapter substitutes (sensor 61 / `PPG_ON_DEMAND`) | tag |
|---|---|---|---|
| HAL frame → service | `HRMBPSensor::parse` (`sensors.default.so` Thumb VA `0x975cc`, file `0x965cc`): rejects frame `≤ 0x17` (23 B), else forwards the raw buffer with sensor type `0x1c` (28), length `0x18`; no arithmetic on the sample bytes | `PPG100HzSensor::parse` (VA `0xaebb8`, file `0xadbb8`): rejects `≤ 0x22` (34 B), else forwards with sensor type `0x3d` (61), length `0x23`; no rescale | [V] |
| service handler → Bundle | `BPManualHandler.parse`: `ByteBuffer` LITTLE_ENDIAN, `green_raw = getInt(0x3)` → `putInt` — raw int, no cast/scale | `PPG100HzHandler.parse`: `green = getInt(0x17)`, `red = getInt(0x1b)`, `ir = getInt(0x1f)` → `putInt` — raw ints | [V] |
| transport | `SemBpManualSensorEvent` key `"green_raw"` | HTS `PpgOnDemandTracker.fetchValueFromData` emits 6 values in order `(green, green_status, red, red_status, ir, ir_status)`; posts under key `PPG_ON_DEMAND` | [V] |
| our client | — | `shminterop.hsdk.PpgSample.greenAdc` = that first int ("raw sensor units, the service applies no offset or scaling") | [V adapter] |
| consumer intake | `pl.smali` BP case: `SemBpManualSensorEvent.getGreenRaw()` → `int→float` → ring `fn.h:[F` at `[d]`, `d = (d+1) & 0xfff`, `f++` | identical (`BpAdapter.deliver(int)` puts the same int under `"green_raw"`) | [V] |

So the int that reaches the consumer is, in both cases, the raw green ADC count exactly as the
service produced it. **[V]: both producer handlers; the two HAL `parse` wrappers; `pl.smali`;
`PpgSample.java`.** (The handler/HAL offsets were byte-verified in both sessions — note 70 §a.1,
note 90 §Q1.)

### 1.2 Rate — MATCH (nominal 100 Hz family) [V]/[C]

- Sensor 28 (BPManual) and sensor 61 (PPG100Hz) are both the **100 Hz family**; the service delivers
  one event per sample. PPG100Hz **= 100 Hz [V]** (sensor name + class); BPManual **≈ 100 Hz [C]**
  (inferred — the HAL-advertised `maxDelay` for type 0x1c was not reproduced, §0.2 item 5 / §0.4).
- The consumer is **rate-driven off its own timers, not off event timestamps**. `sl.g()` first arms a
  **watchdog** at period `0xc8` = 200 ms; once the ring holds `> 0xc8` (200) samples the watchdog
  cancels, rewinds the read cursor to `d − 0x64` (100), calls `resetLibrary()` (→ native `reset_bp`),
  and arms the **drain** timer at period `0xa` = 10 ms (`rl.smali` mode 0 → `zl.e()`), which drains
  **exactly one ring sample per tick** and, every **`0x14` = 20** samples, calls
  `estBpProcess(int[20], 20, short[3], double[16])`. The ring (`fn.h:[F`) is **4096** deep, so short
  bursts are absorbed. [V]
- The native confirms the 20-sample cadence: `estimateBP` rejects any batch whose count ≠ `0x14`
  (20) — `cmp r5,#0x14` at Ghidra VA `0x1cf8a` / file `0xcf8a` [V]. [V]: `sl.g()`
  `scheduleAtFixedRate(…, 0, 0xc8)`; `rl.smali` `0xa`; `zl.smali` `const/16 v0,0x14`.

Note: the consumer's effective input rate stays 100 Hz only while the source delivers ≈100 samples/s;
the drain is a fixed 10 ms/1-sample timer decoupled from arrival, so a slower/faster substituted
source ages or backs the ring up — §4 item 2, §5.

The rate leg is now **observed on device**, not merely nominal: the patched-SHM `BpAdapter` run of
2026-10-14 delivered **one sample per `onBatch` at ≈100 batches/s ≈ 100 samples/s** to a third-party
HTS client [V — `$CACHE/bp-logcat.txt`], so the substituted source's rate matches the stock 100 Hz
family in practice, not only in the datasheet. → §5 item 5.

### 1.3 Filtering — MATCH: none on either producer path [V]

Neither producer filters. The only conditioning anywhere is inside `libbp` (a per-call DC-reference
subtraction + the `glpf − ghpf` band-pass; §2.2). Substituting one raw stream for the other therefore
does not change what the native sees pre-filter.

### 1.4 Quality flags — both exist; the consumer ignores them → omission is safe [V]

| Flag | sensor 28 frame | sensor 61 frame | read by the BP consumer? |
|---|---|---|---|
| per-channel quality | `accuracy` byte @0x15 → `NORMAL` (0) / `ERROR` | `green_accuracy` @0x8, `red_accuracy` @0x9, `ir_accuracy` @0xa (bytes) | **no** — `pl.smali` calls only `getGreenRaw()` |
| AGC | `agc_current_set_value` @0x7, `agc_tia_set_value` @0x9, `agc_ioffset_set_value` @0xb, `agc_valid_flag` @0xd (shorts) | — | no |
| accel | `acc_x/y/z` @0xf/0x11/0x13 (shorts) | `acc_x/y/z` @0x11/0x13/0x15 (shorts) | no |

The BP consumer reads **only** `getGreenRaw()`; every other Bundle key is never touched
(`BpAdapter` documents this and relies on Bundle getters defaulting to 0). `green_status` maps to our
`PpgSample.isGreenNormal()` / `PpgBatch.STATUS_NORMAL = 0`, which `BpAdapter` currently only counts
for the `adc abnormal=n/N` log line and feeds anyway — mirroring the stock path, which delivers frames
regardless of the `accuracy` flag. [V both sessions]

### 1.5 The residual: physical hub AFE gain — cross-reference a hard blocker

Software exposes only raw counts on both channels; the **analog front-end (AFE) gain / optical-to-code
transfer** (green_raw LSB → nA / optical power) is not represented by any constant in `libbp`,
`libshm-bp` or `sensors.default.so`. It depends on the sensor-hub AFE/LED/PD configuration at capture
time, which lives in the hub image.

The hub image is the **CHUB (Cortex-M55) sensor hub** one-binary firmware **`os.checked.bin`**, loaded
by `nanohub.ko` → `imgloader` → `request_firmware()` from `/vendor/firmware`. That file is **not
shipped as plaintext** in the R920XXS2DZG3 build (absent from AP / ramdisk / BL members / CSC / FOTA);
the only firmware container present is the **signed + encrypted `ldfw.img`** (`CryptoManagerV50`,
payload entropy 7.997 bits/byte). The `lpd.core.bin` in `/vendor/firmware` is the Exynos **LPD
(display) coprocessor** image, **not** the sensor hub. This is an **encryption blocker**, and no
encryption-breaking was attempted (project stop rule). **Consequence: the sensor-28 vs sensor-61 green
gain ratio is device-measurable only** (see §5). [V `fw/notes/spo2/72-hub-estimator-location.md`;
the same blocker is recorded in `docs/spo2-calibration.md` §1.2/§7.1.]

**Conclusion of §1:** format/scale match; nominal rate matches; no producer-side filtering to
reconcile; quality flags are informational and unused by this consumer. The residual uncertainty is
purely physical (the hub AFE gain), not numeric in the software.

---

## 2. `libbp_v3_06_10.so` — functional datasheet

Version string `get_ver(mode)` formats `"%u.%02u.%02u"` (rodata file `0xa14c`) with **(3, 6, 10)** →
**"3.06.10"** (matches the filename); `get_alg_version`/`get_full_version` are pthread-guarded caches
of it [V]. The wrapper `libshm-bp.so` has `NEEDED libbp_v3_06_10.so` (JNI shim → algorithm) [V this
revision].

### 2.1 JNI contract [V]

`libshm-bp.so` exports (Java `BpLibrary`, all `native`); the shim forwards to the algorithm's exported
symbols:

| Java method | JNI export (shim) | → algorithm (libbp) |
|---|---|---|
| `estBpProc([IS[S[D)EstimateResult` | `Java_..._BpLibrary_estBpProc` | `est_bp_proc` |
| `calBpProc([IS)EstimateResult` | `Java_..._BpLibrary_calBpProc` | `cal_bp_proc` |
| `averageMcal([[S[[D)MultiCalResult` | `Java_..._BpLibrary_averageMcal` | `average_mcal` |
| `reset()V` | `Java_..._BpLibrary_reset` | `reset_bp` |
| `getFullVersion()String` | `Java_..._BpLibrary_getFullVersion` | `get_alg_version` |

The class name is `com.samsung.android.shealthmonitor.bp.library.BpLibrary`. The shim's imported
(undefined) symbols are `est_bp_proc`, `cal_bp_proc`, `average_mcal`, `reset_bp`, `get_alg_version`
[V this revision — `readelf -sW libshm-bp.so`].

Argument mapping (resolved from the shim asm and `estimateBP`'s reads):

```
short est_bp_proc(int*    out_progress,  // OUT  0..100          <- internal buffer, not a Java arg
                  int     count,         // IN, must be 20 (0x14)
                  int*    data,          // IN   arg1 [I : 20 green_raw ADC ints
                  ushort* ref,           // IN   arg3 [S : short[3] calibration reference
                  double* feat,          // IN   arg4 [D : double[16] feature/config buffer
                  short*  res_out);      // OUT  internal: [0]=sys, [1]=dia, [2]=hr
```

- **The Java arrays `[I`, `[S`, `[D` are all INPUTS**; the shim never calls a `Release<Type>…` with
  copy-back, and `estimateBP` only reads `param_3`/uses `param_4` as an input buffer. **The only
  outputs come back inside the returned `EstimateResult`** — the shim builds it with
  `(result, progress, sys, dia, hr)` taken from `est_bp_proc`'s return code, its `out_progress`, and
  its `res_out` triple. [V]
- `EstimateResult` (smali) fields: `result:S` (status code), `progress:I`, `bp:[D` = `[sys, dia]`,
  `hr:S`, plus `feat`, `sqi`, `cor` arrays. Native `NewObject` order is `(result, progress, sys, dia,
  hr)`; the Java ctor maps to `BpResult(result, sys, dia)` so `bp[0]=systolic`, `bp[1]=diastolic`. [V]
- **Input gate:** `estimateBP` bails to its `-8` default if any of `data`/`count`/… is null, or
  `count != 0x14`. **A batch of exactly 20 is mandatory.** [V]
- **Return code:** `estimateBP` returns `(short)r10`; `1` on success, `-8` invalid input, other small
  codes for intermediate/abort states. The consumer treats `result == 1` as success, `result < 0` as
  failure. [V]

### 2.2 Signal chain (exported symbols, in call order) [V]

1. **`est_bp_proc`** — per-batch entry; builds the internal context and calls **`estimateBP`** once
   per 20-sample batch.
2. **DC subtraction** — `estimateBP` keeps a DC reference in a module-global slot (`.bss`,
   `+0x1d028`). On init (`param_10 == 1`) it is `0` and the block counter (`+0x1d008`) is set to 1;
   each later call increments the counter and **only when it reaches `0x10` (16)** is the DC reference
   overwritten with that batch's first sample (`samples[0]`). Every sample enters the ring as
   `sample − DCref`. The reference is **not** re-adapted after call 16. **So the absolute ADC centre
   never needs to match a fixed value — only the gain affects the filter output.** [V both sessions]
3. **Ring + resample** — a **60-sample** (`0x3c`) circular buffer receives the 20 subtracted samples
   per call; when full, **`spline1_c`** resamples **60 → 150** points using a linear time scale
   `i · 100/250` (pool `100.0` and `250.0` at file `0xd208`/`0xd210`); the ring keeps the newest 40.
   `spline1_c` uses `DBL_MAX` as its "empty point" sentinel. [V]
4. **`PPG_main` / `PPG_preprocess`** — warm-up: an internal counter (`> 0xb`, i.e. the **first 12
   calls**) injects synthetic patterns; real processing starts on **call 12**. `PPG_preprocess` applies
   a **4th-order IIR band-pass** implemented as **`glpf − ghpf`** on **64-double (`0x200` B)** blocks:
   stage 1 = `glpf` (low-pass), stage 2 = `ghpf` (high-pass), output = **LP − HP**. `PPG_main` sets
   order = **4** (array lengths = order+1 = 5, matching the 5-double pools of §3). [V]
5. **`PPG_represent`** — peak detection + template/correlation quality; feeds the `sqi` / `cor`
   quality arrays of `EstimateResult`. [V]
6. **`CCD_main` + `ccd_feat_extract` + `setParam`** — build the feature vector from the band-passed
   signal. `setParam` loads tunable parameters (pool includes `1/30`, `5.0`, `0.04`, `0.2`, `1.8`;
   struct layout [N], §0.3). [V]/[I]
7. **Model / calibration** — **`recal_BP`**, **`multi_cal`**, **`average_mcal`** combine features into
   sys/dia. `recal_BP(ushort* ref, double* calFeat, double* curFeat, double* out)` writer of
   `out[0]=systolic`, `out[1]=diastolic`; constant pools in §3. **The stock consumer runs
   uncalibrated**: `[S]` = `new short[3]` of zeros and `[D]` = `new double[16]` of zeros (neither is
   ever written by the app), so `estimateBP` takes the "no-recal" branch and returns the raw values
   rather than the recalibration transform. When `[D]` is non-zero it requires a valid reference
   (`ref_sys ∈ [10,300]`, `ref_dia` valid, `ref_sys ≥ ref_dia`) or bails. [V]
8. **Progress — `calc_progress_rate`** maps an internal counter to `1..99 %` via a piecewise map
   (`99, 150, 70, 100, 90, 200`); a high counter (`>>2 > 0x4a`) clamps to 99. `estimateBP` fuses two
   sub-scores with weights `0.7/0.3` in one branch, else takes the raw `calc_progress_rate` value.
   Progress **100** is written on the success path. [V]
9. **Result** — on success `estimateBP` stores the sys/dia doubles into the shared output;
   `est_bp_proc` rounds them into `res_out` as `[0]=round(sys)`, `[1]=round(dia)`, `[2]=hr(pulse)`,
   sets `out_progress = 100`, and returns `1`. [V]

### 2.3 Consumer-side state machine (SHM — not native) [V]

- **Watchdog (`rl.smali`, mode 1, polled at 200 ms):** when the ring write index `fn.d > 0xc8` (200),
  the timer is cancelled, the read cursor is rewound to `d − 0x64` (100), **`resetLibrary()`** is
  called (→ native `reset_bp`), then the **10 ms** drain timer (`rl.smali` mode 0 → `zl.e()`) starts.
- **Drain (`zl.smali e()`):** pops one ring sample per tick; if starved (`h.f < h.g`) logs
  `"isReadBufferInvalid"` and returns; on the **20th** sample, calls
  `estBpProcess(k:[I, 20, f:[S, g:[D])`.
  - `progress` vs the last floor `l`: if `progress <= l` the **stall counter `i`** increments; when
    `i >= 0x16` (22 consecutive stalled batches ≈ 440 samples ≈ 4.4 s) it aborts with code **3**
    (`h(3)`) and resets `i`; any advance of progress resets `i` to 0.
  - `result == 1` → persist to Room DB + success stop; `result < 0` → `h(result)` fail stop; else
    report progress via the callback.
- **Recall for `BpAdapter`:** the state machine is driven entirely by the ring, so the only things the
  adapter must preserve are *one raw sample per event* and *≈100 Hz* — it already does.

---

## 3. Constants table with provenance (authoritative, SM-R920 R920XXS2DZG3; SHM 1.5.1.022)

### 3.1 Library identity

| file | where | size | sha256 | BuildID |
|---|---|---|---|---|
| `libbp_v3_06_10.so` | SHM watch 1.5.1.022, `lib/armeabi-v7a/` | 148 708 B | `9b30c1de424e77b110a8be14bc4152e3b212349af3ca9c564da82e190538d4a9` | `2a2e6587072ff392e959814db9ade834f6cce073` |
| `libshm-bp.so` (JNI shim) | SHM watch 1.5.1.022, `lib/armeabi-v7a/` | 6 228 B | `d6f69fe504d21ceefcc184797e57f64ba2133b889ad875483aa05c0387b07b20` | `fa3b74b604bf2b55cf43dc1f1cad76623d93db08` |
| `sensors.default.so` (sensor HAL) | `fw/SM-R920/R920XXS2DZG3/out/vendorlib/hw/` | 801 612 B | `924fd4fecbad5f46fbdf37522929107f776ded97fcf71a3d02b2408f856ffb49` | (not recorded) |

All ELF32 ARM EABI5; `libbp` built with Android clang 14.0.7, the shim with clang 18.0.1 [V]. Version
= **3.06.10** (`"%u.%02u.%02u"` @ file `0xa14c`, rodata) [V].

**Address model (read this before the tables).** In `libbp_v3_06_10.so`: `.text` VA `0xc288` = file
`0xc288` and `.rodata` VA `0x9fe8` = file `0x9fe8` (**VA = file offset**); `.data` **VA `0x25d90` =
file `0x23d90`** and `.data.rel.ro` VA `0x232c0` = file `0x222c0` (**Δ = 0x2000**). Ghidra loads the
PIE at base `+0x10000`, so Ghidra VA = ELF VA + `0x10000`. In `sensors.default.so`: file offset =
VA − `0x1000`. [V this revision — `readelf -SW`; both notes agree.]

**Coefficient pools — the resolved ladder (all eight objects, contiguous, file `0x23d90`–`0x23ed0`).** The code
GOT-references **only the four `_byte` twins** (`R_ARM_GLOB_DAT` in `.got`); the plain-named twins are
byte-identical and unreferenced (§0.2 item 1). All are 5 doubles (40 B) [V this revision]:

| object | ELF VA | file offset | references | value (5 doubles) |
|---|---|---|---|---|
| `glpf_coefB_byte` | `0x25d90` | `0x23d90` | GOT 0x24b50 | `7.957016345209317e-05, 3.182806538083727e-04, 4.77420980712559e-04, 3.182806538083727e-04, 7.957016345209317e-05` |
| `glpf_coefA_byte` | `0x25db8` | `0x23db8` | GOT 0x24b4c | `1.0, -3.475022404941131, 4.558511914371504, -2.672919854274467, 0.590703467459327` |
| `ghpf_coefB_byte` | `0x25e30` | `0x23e30` | GOT 0x24b48 | `6.300711549722671e-10, 2.520284619889068e-09, 3.780426929833602e-09, 2.520284619889068e-09, 6.300711549722671e-10` |
| `ghpf_coefA_byte` | `0x25e58` | `0x23e58` | GOT 0x24b44 | `1.0, -3.973730046565231, 5.921534645073681, -3.921876509896709, 0.9740719214693984` |
| `glpf_coefB` | `0x25de0` | `0x23de0` | — (unreferenced) | byte-identical to `glpf_coefB_byte` |
| `glpf_coefA` | `0x25e08` | `0x23e08` | — | byte-identical to `glpf_coefA_byte` |
| `ghpf_coefB` | `0x25e80` | `0x23e80` | — | byte-identical to `ghpf_coefB_byte` |
| `ghpf_coefA` | `0x25ea8` | `0x23ea8` | — | byte-identical to `ghpf_coefA_byte` |

The `glpf` pair is the low-pass stage and the `ghpf` pair the high-pass stage of the
`glpf − ghpf` band-pass (§2.2 step 4). The analyst's appendix VAs (`0x25de0/0x25e08/0x25e80/0x25ea8`)
and the confirmer's file offsets (`0x23d90/0x23db8/0x23e30/0x23e58`) name the two copies; the values
are identical in both (byte-verified) [V this revision].

### 3.2 Pipeline and model constants (all `[V]`, byte-re-read this revision)

| constant | value | provenance |
|---|---|---|
| sample count per batch | **20** (`0x14`); `!= 0x14` ⇒ error | `estimateBP` `cmp r5,#0x14` @ Ghidra `0x1cf8a` / file `0xcf8a` |
| DC reference lock | first sample, locked on the **16th** processing call (`+0x1d028`); counter `+0x1d008` init 1 | `estimateBP` |
| ring depth / resample | ring **60** (`0x3c`) → **150** (`0x96`), axis `i·100/250`, keeps newest 40 | `estimateBP`/`spline1_c` |
| resample scales | **100.0** @file `0xd208`, **250.0** @file `0xd210` | `estimateBP` pool |
| warm-up | **12** processing calls (synthetic patterns while counter `> 0xb`) | `PPG_main` |
| band-pass order / block | order **4** (5 coeffs), block **64** doubles (`0x200` B), out = LP − HP | `PPG_preprocess`/`PPG_main` |
| `estimateBP` axis/quality pool | **100.0, 250.0, 300.0** @file `0xd208` | [V this revision] |
| `estimateBP` fs/quality thresholds | **11.4, 3.6, 0.7, 0.3** @file `0xd740` | [V this revision] |
| `estimateBP` fusion weights | **0.7** @file `0xd750`, **0.3** @file `0xd758` | [V this revision] |
| `calc_progress_rate` pool | **99.0, 150.0, 70.0, 100.0, 90.0, 200.0** @file `0xc438`; high counter `>>2 > 0x4a` clamps to 99; progress 100 on success | [V this revision] |
| `recal_BP` pool (A) | **1.22, 0.07, 0.9, 1.1, 1.18, 1.2, 0.8, 3.1622776601683795 (√10), 0.65, 1.3, 0.95, 1.4142135623730951 (√2)** @file `0xc870` | [V this revision] |
| `recal_BP` pool (B) | **1.3, 2.4, -0.6, -0.41, -0.47, 0.47, 1.8560434585785424, 1.4, 0.6, 0.4, -0.4, 0.3999999999999999, 0.7, 50.0, 1.8, 100.0, 83.33333333333333, 42.0, 1.02, 4.000001, 15.000001, 10.000001, -13.2, 30.4, -20.6** @file `0xcdf8`–`0xceb8` | [V this revision] |
| reference-validity gate | `ref_sys ∈ [10,300]`, `ref_dia ≥ 10`, `ref_sys ≥ ref_dia` (only when `[D]` non-zero) | `estimateBP` |
| version string | `"%u.%02u.%02u"` @file `0xa14c` → **3.06.10** | [V this revision] |

### 3.3 Consumer-side (smali) constants [V]

| constant | value | provenance |
|---|---|---|
| watchdog period | `0xc8` = **200 ms** | `sl.g()` `scheduleAtFixedRate(…, 0, 0xc8)` |
| watchdog trigger | ring write index `> 0xc8` (200 samples); drop 100 (`−0x64`), `resetLibrary()`, restart 10 ms | `rl.smali` mode 1 |
| drain period / batch | `0xa` = **10 ms** per tick; recover **20** samples per `estBpProcess` call (`0x14`) | `rl.smali` mode 0, `zl.smali` |
| ring depth (consumer) | **4096** floats (`fn.h:[F`, mask `& 0xfff`) | `pl.smali` |
| abort / stall | progress stall counter `>= 0x16` (**22**) ⇒ `h(3)` (abort code **3**) | `zl.smali` |
| success / fail codes | `result == 1` success (persist + stop); `result < 0` fail | `zl.smali` |
| intercept sensor type | `0x1c` (**28**, `TYPE_BP`); data key `"green_raw"` | `BpAdapter`; `patches.md` rows 7c/7d |

---

## 4. `BpAdapter` correction — REPORT ONLY, not implemented

**Structurally: none.** Format (one raw int per event), channel count and nominal rate already match
the stock path, and the native removes DC itself, so an absolute-offset difference is absorbed. The
only candidate corrections, in priority order (each one-line "would need"; the owner decides):

1. **Conditional per-sample constant gain — only if a device test ever shows an AFE delta between
   sensor 28 and 61 green.** Because `estimateBP` subtracts its own DC reference (§2.2 step 2), a pure
   *offset* between the two channels is irrelevant; only a **gain ratio** rescales the feature ratios
   the model uses. *Would need:* `green' = green * k` (optionally `+ b`) with a single constant
   derived from a hardware/config source — **not** invented from these binaries (they contain no
   per-channel AFE gain). [V note 70 §c1; C note 90 §Q7]
2. **Rate coupling — only if the delivered `PPG_ON_DEMAND` rate is measurably ≠ 100 Hz.** The drain is
   a fixed 10 ms/1-sample timer decoupled from arrival, so the native's count-based timing (warm-up 12
   calls, baseline at call 16, watchdog 200 samples) drifts if the source is slower/faster.
   *Would need:* pace `deliver` to 100 Hz or pre-resample, only if a measurement shows a mismatch.
   [V note 70 §c4; §1.2]
3. **Quality gating — optional, not required.** The consumer ignores `green_status`; the adapter
   already logs `adc abnormal=n/N` and feeds anyway, matching the stock HAL. *Would need:* nothing —
   keeping status in the log only is the faithful behaviour. [V §1.4]
4. **Keepalive flatline (`green_raw = 0` at 10 Hz until first real sample) — intended and safe.** The
   native's warm-up + DC removal produce no pulse from a flatline; the keepalive stops on `everReal`.
   *Would need:* nothing. [V `BpAdapter`; §2.2]

No code was changed: this section is a report (`AGENTS.md`, hand-off §8 — report needed corrections,
the owner decides).

---

## 5. Open questions (device-only or unpinnable)

Each item is mapped to a device test; the BP device test is a **probe session recording `PPG_GREEN`
while the patched SHM runs a real BP measurement** (the owner operates the watch normally; pull the
green stream + the BP result + logcat). Device sessions are owner-gated; the watch's wireless adb
serves one client.

1. **BPManual (sensor 28) nominal rate.** PPG100Hz = 100 Hz is [V]; BPManual ≈ 100 Hz is [C]
   (inferred from the consumer cadence; the HAL `maxDelay` for type 0x1c was not reproduced, §0.2
   item 5). *Test:* read the HAL sensor-info for type 0x1c on device, and time the real delivering
   rate. → §1.2, §4 item 2.
2. **AFE gain / optical-to-code transfer** for green_raw — no gain table in any analyzed binary; the
   hub image that carries it (`os.checked.bin`) is **not shipped as plaintext** (encryption blocker,
   §1.5). *Test:* record `PPG_GREEN` during a real BP measurement and compare the recorded green
   statistics against the stock delivery contract to quantify the sensor-28↔61 gain ratio as an exact
   constant; only then would §4 item 1 apply. Device-measurable only. → §1.5, §4 item 1.
3. **Runtime calibration values** (reference BP `[S]` and the 16-double features `[D]`). The stock
   consumer hard-codes zeros and runs uncalibrated; the calibration workflow that populates them is
   not in the analyzed artifacts. *Test:* capture the values the calibration flow feeds at runtime
   (logcat / a hooked run) — otherwise the adapter's uncalibrated default is the shipping behaviour.
   → §2.2 step 7, §0.4 item 3.
4. **The `_byte` twin duplication rationale.** The four coefficient arrays ship in two byte-identical
   copies and only the `_byte` twins are GOT-referenced; *why* is a compiler/perf-mode artifact not
   determinable from the binaries. Not device-settlable; recorded as [N] (no further static material).
   → §0.2 item 6.
5. **`PPG_ON_DEMAND` delivery cadence to a third-party HTS client — OBSERVED (2026-10-14).** The
   protocol states one data point per sensor event (100 Hz family) and `PpgOnDemandTracker` maps
   sensor events 1:1; the on-device run now confirms the third-party batch cadence end to end. The
   patched-SHM `BpAdapter` client's own stat lines (printed once per **100 batches**) read
   `stats: n=100 … adc abnormal=0/100` about once per second, i.e. **one sample per `onBatch` at
   ≈100 batches/s ≈ 100 samples/s**, with **0 abnormal** samples in every window (10 300 green
   samples over 103 lines, two sessions); the `min/max/mean` in those lines are the raw green ADC
   counts (run span ≈ **+2.0e5 … −4.1e5**). [V — `$CACHE/bp-logcat.txt`, 2026-10-14] →
   [health-tracking-protocol.md](health-tracking-protocol.md) §4.3/§4.4; §1.2.
6. **Libraries outside the swept roots.** Only `armeabi-v7a` under the SHM/Watch8 tree and the
   SM-R920 `sensors.default.so` were searched; `/odm/lib`, `/product/lib`, `/system_ext/lib` were not
   swept (recipe note 30). If a producer-side lib there rescales green before HTS, this analysis would
   not see it. *Test:* a firmware sweep of those roots (cheap re-pull) plus the device capture above.
   → §1.1.

The comparison CSVs that settle items 1 and 2 come from the owner-gated BP device session; they
will land in the analysis cache after that session and are not in this document by design. (Item 5
is settled already by the 2026-10-14 logcat above, not by a CSV.)

---

## 6. Scope and evidence

**Binaries and trees analyzed:**

| file | where | hash / identity |
|---|---|---|
| `libbp_v3_06_10.so` | `watch8-crash/shm/lib/armeabi-v7a/` | 148 708 B; sha256 `9b30c1de…`; BuildID `2a2e6587…` [V] |
| `libshm-bp.so` (JNI shim) | `watch8-crash/shm/lib/armeabi-v7a/` | 6 228 B; sha256 `d6f69fe5…`; BuildID `fa3b74b6…` [V] |
| `sensors.default.so` (sensor HAL) | `fw/SM-R920/R920XXS2DZG3/out/vendorlib/hw/` | 801 612 B; sha256 `924fd4fe…` [V] |

Decoded trees read (provenance only, no code copied): the SHM watch 1.5.1.022 smali consumer chain
(`pl`, `rl`, `zl`, `sl`, and the `SemBpManualSensorEvent` class), the sensor-service jar
(`BPManualHandler`, `PPG100HzHandler`), the HTS `PpgOnDemandTracker` / `ServiceDataPoint`, and the
HAL wrappers (`HRMBPSensor`, `PPG100HzSensor`) [V both sessions]. No vendor code bodies are reproduced
— only exported/local symbol names, class names, file offsets and constants.

**NOT analyzed** (declared, not papered over):

- The **sensor-hub AFE/LED/PD configuration** — its image `os.checked.bin` is not shipped as plaintext
  (encryption blocker, §1.5); the manual-BP-vs-PPG green gain ratio is unrecoverable from the pulled
  firmware.
- The **`setParam` tunable-parameter struct layout** (partial; [N], §0.3) — affects tuning, not the
  input contract.
- **Libraries outside the swept roots** (§5 item 6).
- The **device**: the 2026-10-14 patched-SHM `BpAdapter` logcat observed the delivery rate and
  third-party batch cadence (§1.2, §5 item 5); the AFE gain ratio and the runtime calibration
  values remain unverified on hardware (the gain needs a cuff-calibrated system-SHM measurement;
  the stock consumer runs uncalibrated — §2).
- A **per-model comparison** of `libbp` / the HAL across the seven watch families (only the SM-R920
  SHM 1.5.1.022 + HAL were analyzed; the SpO2 carriers were byte-identical across families, but that
  was not re-established for BP).
- Clinical validation of any output; and any implementation change (report-only, §4).

`docs/spo2-calibration.md` and `docs/ecg-calibration.md` were used as the format model and were **not
modified**; `docs/health-tracking-protocol.md` §4.2–4.4 and `docs/patches.md` rows 7c/7d were
cross-referenced, not duplicated.
