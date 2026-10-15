# SpO2 calibration and data formats — Galaxy Watch (clean-room specification)

Reconstructed 2026-10-15 from the official firmware of devices the owner owns, for the future
OpenSHM `SpO2Adapter` that must feed the watch SHM sleep-apnea flow ([patches.md](patches.md)
watch rows 7–9) with SpO2 computed from the raw red + IR PPG that the Health Tracking Service
delivers ([health-tracking-protocol.md](health-tracking-protocol.md) §4.4). Datasheet facts
only — inputs, processing steps, formulas in standard math notation, constants with units and
provenance (binary file + build + offset). No decompiled code; every fact was read from
binaries the owner's devices run, by static analysis of official firmware.

Purpose: give a clean-room implementer what the analyzed material supports — the stock
**on-demand** algorithm (byte-verified, §2.1/§3) and the stock data **formats** (§4) — so the
adapter can be built from this document alone. §1.3/§1.4 state precisely which parts of stock
behavior are and are not reproducible from this material; every gap is collected in §7 instead
of being papered over.

Provenance convention: citations of the form "note N" refer to dated working records kept in
the analysis work directory outside this repository; this document is self-contained without
them. Evidence tags:

- **[V]** — verified: read or executed in the cited record's session (byte reads, disassembly /
  decompile reads, smali reads, sha256 / cmp runs).
- **[C]** — independently confirmed: reproduced by a second session that had not read the
  first analyst's records; corrections that process produced are authoritative (§0.2).
- **[I]** — inferred: fits the evidence but not directly proven.
- **[D]** — derived here, from verified facts.
- **[N]** — **not recorded**: the records do not contain this detail; it is listed in §7, never
  invented.

Baseline device/build: **SM-R920 (Galaxy Watch5 Pro), `R920XXS2DZG3/R920OXM2DZG3` (CSC ARO)** —
the owner's device and running build [V notes 10, 31]. All seven watch families compared in §6
ship **byte-identical** algorithm `.so` files, so the **library** constants in §3 apply verbatim
to every model listed there. The **Java-side** constants (accelerometer scale, accuracy mapping,
§3.1) were verified only in the analyzed HTS APK copy; the per-model HealthPlatform APKs differ
and were never smali-compared across models [V scope of evidence; §7.1]. All firmware was pulled
from Samsung's FUS server (samloader-rs 2.2.0, CSC ARO, inline-decrypted downloads) and unpacked
per recipe note 30 [V notes 10, 30, 31, 60–6x].

---

## 0. Verification status — what was independently confirmed and what was not

### 0.1 Independently confirmed (two sessions; the confirmer had not read the analyst's notes)

| Finding | First stated | Confirmed by |
|---|---|---|
| Mapping formula end-to-end: R definition, both acceptance limits, polynomial coefficients, 70/100 clamp, band edges 94/90, share gates 5/30/60 (reported) and 8/30/70 (secondary), else-0 / else-invalid | note 20 §3–§4 | note 21 §D (fresh decompiles + disassembly; verdict "agrees") |
| Every mapping / voting / timing constant byte-exact (raw f32 words decoded and compared) | note 20 §5 | note 21 §A (table of raw words; all "matches") |
| R channel order — **A = irRaw** (denominator side), **B = redRaw**: `R = (AC_red/DC_red)/(AC_ir/DC_ir)` | note 20 §3.4 (marked *inferred*) | note 21 §D.1 — **upgraded to proven** (traced Java field origin → `funcr` → `RunProc` → `dc_filter`/`fir_bpf` call order) |
| Status machine: internal statuses {0, −4, −52 (reported as −5), 2}; READY 1 / COMPLETE_NOTILT 3 / TIMEOUT −6 never emitted by this build; 3000-sample decision requires HR ≥ 1 and mapped SpO2 > 0; motion re-report every 200th sample, give-up at 999; PPG-HR fallback after sample 1999 | note 20 §6 | note 21 §E |
| The three algorithm binaries' sizes and sha256 hashes | notes 20/22/31 | notes 60, 61, 6x — each family session re-computed the same hashes and ran its own `cmp -s` |
| Overnight wrapper datasheet — JNI contract, 4-lane input word, +69 decode, the full RunProc pipeline (§2.2), every constant byte-exact, and the absence of any dcRatio/accPower computation | note 40 | note 41 — verdict "agrees" on every library-side claim; one grammar correction in the unused `decode_eht_entry` export (§4.4) and two nuances folded into §2.2 steps 4/6 |
| §7.2 residue: FIR (151-tap, ×2⁻¹⁶) / IIR (7+7) / Kaiser (95) tables, and the template Pearson threshold 0.65 with the 900.0/59.0 window caps | note 70 §7.2 items 3,6,7 | note 90 §Q3 (fresh byte reads + Ghidra; all values reproduced) |
| §7.2 residue: adaptive-bank init/LMS constants {0.96, 0.9, 0.95} and the −0.2/1e-8/+0.2 steers; `an_init_bound_for_spo2` = 24 f32 | note 70 §7.2 items 1,2 | note 90 §Q2 |
| §7.2 residue: `judge_result_flag` returns −4 against a **runtime** field (state+0x28) compared to a caller-supplied value — no static threshold; −52 on the 4th large jump | note 70 §7.2 item 3 | note 90 §Q4 |
| §7.2 residue: output field out[3] is never written in steady state → the hr-source byte is not enumerable | note 70 §7.2 item 12 | note 90 §Q5 |
| §7.2 residue: internal PPG HR = acos(ω)·190.9859317102744; the external-HR latch holds external samples only | note 70 §7.2 items 13,14 | note 90 §Q6 |
| §7.2 residue: irRaw/redRaw ADC full-scale — no constant exists or is applied; R is scale-invariant | note 70 §7.2 item 16 | note 90 §Q8 |
| Hub: `lpd.core.bin` is the Exynos LPD **display** Cortex-M4 firmware, not the sensor hub (supersedes note 31 §5) | note 71 §1 | note 90 §Q1 |
| SSM producer: attribute key is lowercase `"cmd"`, `Cmd = {FLUSH, GET_DATA, GET_VERSION}` — no `SET_OPERATION_MODE`; `secSize = min(remaining, 30000)`, `secRemainCount = remaining ÷ 30001`; no `versionMap` | note 71 §2 | note 90 §Q10 |
| `dcRatio` / `accPower` are absent from the wrapper, the SSM producer class, the framework event class and HTS — no analyzed producer computes them | notes 71 §2.6, 72 | note 90 §Q9 |

### 0.2 Corrections the confirmation and this revision produced (authoritative — use these values)

- **IIR band-pass coefficients** and the **adaptive-notch bound table** values: note 20's
  original numbers are wrong (absent from the file by exact whole-file byte search); note 21
  §C1/C2 hold the authoritative values (§3.1 below).
- Accumulator-2 lowest band is **[70, 90)**; values < 70 are dropped (note 21 §D.4).
- Secondary-stream selector "else" leaves the result invalid: the getter reports **−1.0**, not 0
  (note 21 §D.4).
- Java-side: READY (1) falls through the dispatch switch and produces **no callback at all**
  (note 21 §E; moot — 1 is never emitted).
- Address nits in note 20 fixed by note 21 §B/§I: `init_dc_filter` calls at 0x1373c/0x1374e;
  `init_fir_bpf` calls at 0x1371a/0x1372c; selector-2 block at 0x14ca2–0x14d46.
- Transcription slip in note 12: "≈0.00219727f" — the constant it cites (smali word
  `0x3b1cf660`) decodes to **0.002395056189** = 9.81/4095.9375, confirmed twice (notes 20 §2/§5,
  21 §A/§G). The hex word and the two later notes agree; note 12's decimal is the error.
- **Sanity anchors (corrected this revision).** Note 20 §3.5's illustration values contain
  arithmetic slips. Re-evaluated this session with the byte-verified coefficients — python3:
  `struct.unpack('>f', bytes.fromhex(w))` on the raw words `c00afa05 / c1a2eb1c / 42db61e5 /
  3feb851f` (decodes exactly to −2.1715099812 / −20.3647995 / 109.6912003 / 1.84), then
  `c0 + c1·R + c2·R²`:
  `R=0.5 → 98.97 · R=0.75 → 93.20 · R=1.0 → 87.15 · R=1.5 → 74.26`
  and the 70.0 clamp starts binding at **R ≈ 1.6564** (the polynomial's raw value at the 1.84
  acceptance limit is 64.87, pinned to 70.0). Note 20's "0.75 → 93.3" and "1.5 → 70.7" are
  therefore impossible outputs of these coefficients. **The polynomial is the ground truth**
  (byte-verified twice, §0.1); the anchors are illustrations derived from it and are corrected
  in §2.1 step 8 — when they disagree, the formula wins.
- The 75.0 store's downstream use: the code at 0x13d1a computes **(75/60)·28** from it — note 21
  records the computation but not the quantity's meaning; see §3.1 and §7.2.
- **§7.2 residue resolved (notes 70/90) — corrections to the earlier text:**
  - The three "waveform range floats at file 0x3e8c–0x3e94" (§2.1 step 5, §7.2 item 4) are **not
    floats** — they are VFP/ALU instruction encodings (`ldr.w`/`vneg.f32`/`add.w`) misread on a
    straddling boundary (note 70 §7.2 item 4).
  - The `judge_result_flag` −4/−52 "thresholds" are **runtime/caller values**, not static constants
    (note 70 §7.2 item 3; note 90 §Q4) — §2.1 steps 4c/5.1 and §7.2 item 3 are corrected.
  - Output field **out[3] is never written a nonzero value** (note 70 §7.2 item 12; note 90 §Q5):
    the hr-source byte is not enumerable in this build — this supersedes §4.1's earlier role claim.
  - The **(75/60)·28 numerator is the `.data` sample-rate word 75.0** at vaddr 0xe004 (file 0xd004),
    **not** an opaque runtime field (note 70 §7.2 item 15; re-read this revision): `int((75.0/60.0)·28)
    = 35` is stored to state+0x13c0e (§3.1).
  - **FIR table↔channel assignment** (§2.1 step 4b): `fir_filter_for_spo2` (0xa5a8) feeds **both**
    main-pipeline channels (one GOT load at file 0x3c22, its pointer reused at the two channel calls
    0x3c2c/0x3c3e); `fir_filter_f_for_spo2` (0xa1d0) is referenced **only** from the adaptive-bank
    function (GOT load file 0x591c). Note 70 is authoritative here; note 90 could not pin it (§4.1).
  - **Vote share gates** (§2.1 step 10): the reported selector gates are **5/30/60 %** (5.0 @file
    0x4864, 30.0 @0x48a0, 60.0 @0x4c10) and the secondary selector **8/30/70 %** (8.0 @0x4cb0,
    30.0 @0x4cfa, 70.0 @0x4c34); both sets byte-verified this revision (note 90's table listed only
    8/30).
  - **`an2bpm` multiplier**: the earlier "−5.264523029" was the **f32 truncation of the low 4 bytes**
    of a **double**; the real value is the f64 **190.9859317102744** (@file 0x2a48, re-read this
    revision) — §3.1 corrected.
  - **Filter tables** (§3.1): tap values are now recorded (note 70 §7.2 item 7, full lists); the
    Kaiser table is the **SDFT window/compensation** table (consumed by `sdft`); `ekp`/`ekK` are
    **240 f64** twiddles each (not 480 f32); the IIR is consumed **only** by `iir_bpf` (no
    main-pipeline use).
- **Hub finding (notes 71/72).** `lpd.core.bin` is the Exynos **LPD display** Cortex-M4 coprocessor
  firmware, **not** the sensor hub — note 31 §5/§6's identification is superseded (note 71 §1; note 90
  §Q1). The 4 Hz overnight estimator runs on the CHUB (Cortex-M55) hub, whose one-binary image is
  `os.checked.bin`; that file is **not shipped as plaintext** in this build (absent from AP / ramdisk /
  bootloader members / CSC / FOTA; the only container present is the signed+encrypted `ldfw.img`,
  `CryptoManagerV50`) → a hard **encryption blocker** (note 72), and no encryption-breaking was
  attempted (project stop rule).
- **Producer-side (note 71).** The SSM SpO2 bundle builder's semantics are resolved (§2.3/§4.3/§4.4):
  the attribute key is lowercase `"cmd"` with `Cmd = {FLUSH, GET_DATA, GET_VERSION}` (no
  `SET_OPERATION_MODE` on this sensor); `secFullSize` = total, `secSize = min(remaining, 30000)`,
  `secRemainCount = remaining ÷ 30001` (minutes 500/501); `version` = int[4] @ packet offsets
  {9,10,11,12}; `version_type` from packet type 2/4; pages carry `data_type` NORMAL; **no `versionMap`
  key exists**. `dcRatioList`/`accPowerList` are **not produced** by any analyzed tree (wrapper, SSM
  service, framework event class, HTS) — only SHM's own event-class copy references them (§4.4).

### 0.3 Single-session findings NOT independently confirmed

- The system-copy **code-differ** datasheet, including its two calibration tables (note 22:
  one session; byte reads + Ghidra; consistent with the notes 20/21 hashes, but nobody re-read
  its tables). Moot for the adapter's APK-build target, but unconfirmed material.
- The `lib_hmspo2wrap` datasheet (note 40) — **resolved**: independently confirmed at the
  binary level by note 41 (§0.1) — JNI contract, pipeline, constants, and the negative result
  that dcRatio/accPower are absent from this library. The phase summary's "discrepancy" label
  referred only to a grammar detail of the unused `decode_eht_entry` export (corrected in
  §4.4); the two nuances the confirmation added are folded into §2.2 steps 4 and 6. What
  remains single-session is the consumer-side smali chain (next bullet).
- All SHM-watch smali consumption facts (note 12; read-only single session). **The SHM-consumer
  side of the interface the adapter must satisfy (§2.3, §4.3, §5.3) inherits this caveat.** The
  producer side of that interface (the SSM bundle builder and attribute classes) is now analysed
  in note 71 and cross-checked in note 90 (§0.1), so §2.3/§4.3's *producer* claims are on two
  sessions.
- Everything tagged [I] below.

---

## 1. Scope — the watch SpO2 data paths

### 1.1 Path A — HTS one-shot ("on-demand") SPO2 tracker

- Carrier: the Health Tracking Service APK (HTS, `com.samsung.android.service.health.tracking`).
  Tracker type `SPO2_ON_DEMAND` = 15 (`SPO2` = 14) [V note 12]. The tracker binds the vendor STS
  service (`com.samsung.android.service.health.wear.sts.ACTION_SERVICE`, package
  `com.samsung.android.service.health`) [V note 12].
- Estimation is **native**, in `libspo2.so` packaged inside the HTS APK (`lib/armeabi-v7a`),
  JNI class `com.samsung.spo2.spo2library.SpO2LibManager`, loaded via `System.loadLibrary("spo2")`;
  the per-sample entry point is `funcr([I[FII[I)I` [V notes 12, 20, 22]. HTS loads the **APK copy**
  (version string `ver01.2824`), not the system copy — proven from the class initializer, the APK
  packaging (only libspo2 / libbialibrary / liblib_wrapper ship in the APK) and zero
  `spo2manual`/`hmspo2wrap` references anywhere in the HTS smali [V note 22 §7].
- Input per sample: `{irRaw, redRaw}` raw ADC ints (**unscaled**; no ADC full-scale constant
  exists or is applied — the ratio path is scale-invariant, §7.2 item 16) [V note 70; C note 90];
  accelerometer XYZ in g — the Java side scales each raw axis by
  0.0023950562 = 9.81/4095.9375 [V+C]; `redLOffset` u16 and `externalHr` u8 [V]. The event
  source is the dedicated manual-SpO2 sensor (`SemSpo2ManualSensorEvent`), **not**
  PPG_ON_DEMAND [V note 20 §2]. The algorithm's internal sample-rate constant is **75 Hz** [V+C].
- Alternative ADI path: if the sensor-service version ≥ 0.27 and the sensor reports feature 0x23
  `fresh_hrm_dualized`, HTS uses `sensorListenerADI` and never instantiates SpO2LibManager — the
  sensor hub then delivers already-computed (status, spo2, hr, accuracy) [V note 20 §1]. Which
  path a given physical watch takes at runtime is a device-side property (§7.1).

### 1.2 Path B — HM overnight (sleep-apnea) SpO2

- Chain: the sensor hub (Exynos **CHUB / nanohub**, `samsung,exynos-nanohub` Cortex-M55 on the
  R920's S5E5515 SoC) produces ~4 Hz per-sample SpO2 estimates quantized to 5 bits, 4 per second
  (4 Hz is the wrapper's input contract; the true hub rate is unverified) [V note 40 §2–§3; I rate;
  C hub identity note 72 §1].
  The vendor post-processing wrapper `lib_hmspo2wrap.sensor.samsung.so` (version 1.11.01)
  aggregates them into per-second SpO2 and per-minute anchors [V note 40]. The Samsung
  sensor-manager service (SSM) assembles the `SemSleepWatchSpO2SensorEvent` bundle (event type
  0x31 = 49); its producer class `SleepWatchSpO2Handler` **is** analyzed (note 71 §2), and the
  bundle carries the `sec*`, `min*`, `version*` and `data_type` keys [V note 71 §2]. The
  `dcRatioList` / `accPowerList` keys do **not** exist in any analyzed tree (§4.4). The SHM
  watch APK (`com.samsung.android.shealthmonitor` 1.5.1.022) pulls it with `getData(start, end)`
  and feeds the apnea flow [V note 12].
- SHM fork [V note 12]: **V1** (`Apnea1Manager`) evaluates per-second values locally (values
  only); **V2** (`Apnea2Manager`) persists per-sample `SleepRaw(timestamp, spO2:B, dcRatio:S,
  accPower:S)` into Room and syncs raw sessions to a Samsung phone via ApneaSyncManager
  (transport below that not traced).
- **Hub image (corrected).** The hub is the Exynos **CHUB / nanohub** (Cortex-M55) sensor hub. The
  image `lpd.core.bin` that note 31 identified as the "sensor hub" is in fact the Exynos **LPD
  (Low-Power-Display) Cortex-M4** coprocessor firmware — wrong component (note 71 §1; note 90 §Q1).
  The 4 Hz estimator's actual carrier is the CHUB one-binary image `os.checked.bin`, which is **not
  shipped as plaintext** in the R920XXS2DZG3 build (absent from AP / ramdisk / bootloader members /
  CSC / FOTA; the only firmware container present is the signed+encrypted `ldfw.img`,
  `CryptoManagerV50`) → hard blocker, no encryption-breaking attempted (note 72). Whether the
  estimator shares the libspo2 mapping is therefore **not determinable** from the analyzed material
  [I].

### 1.3 What an adapter can actually reproduce from raw red + IR

- **Reproducible: the Path-A mapping contract.** The mapping consumes only raw red + IR (plus
  the accelerometer for motion gating; plus `redLOffset`, a no-op selector in this build; plus
  optional external HR). An adapter fed raw red+IR can compute per-beat R, per-beat SpO2 and the
  band-voted measurement value exactly as specified in §2.1 steps 7–10 / §3.1 [V algorithm;
  D adapter reading]. The green channel is never used by the algorithm [V note 20 §2].
- **Reproducible: the formats and acceptance rules** the SHM apnea flow applies (§2.3, §4, §5.3)
  [V note 12; single-session caveat §0.3].
- **Not reproducible bit-exactly: the stock pre-processing decisions** (which beats survive,
  when motion fires, the internal HR path) — those pipeline stages are structurally described
  and their constants are now pinned (notes 70/90), but the **motion threshold that fires −4 is a
  caller-supplied runtime value with no static constant** (§2.1 steps 4c/5.1), so stock-identical
  beat acceptance cannot be guaranteed from this material. An adapter can implement the exact
  mapping/voting contract on top of its own pre-processing.
- **Not reproducible bit-exactly: the stock overnight per-second stream.** Stock overnight
  values come from the hub's 4 Hz estimator plus the wrapper aggregation (§2.2); the estimator
  is not recoverable from any analyzed binary, and dcRatio/accPower have no definition in any
  analyzed binary (§4.4). An adapter can emit the stock **format** (per-second 0/70..100, §4.3)
  computed with the Path-A mapping, but bit-identical per-second equality with the stock device
  is not achievable with the analyzed material [I — synthesis of verified facts].
- **Cadence mismatch (adapter-defining):** the on-demand algorithm yields **one band-voted
  value per 40 s session** (plus WAIT/MOTION status updates); the SHM apnea flow consumes **one
  value per second**. The stock per-second stream has a different, unrecoverable origin (hub
  estimator). What per-second value the adapter emits is therefore a design decision with no
  stock counterpart derivable from the analyzed material — §7.3.
- **Rate caveat:** all algorithm time constants assume 75 Hz input. HTS PPG_ON_DEMAND is a
  different feed (~100 Hz, green/red/IR, sensor type 61 — `docs/health-tracking-protocol.md`
  §4.4, cited in note 20 §2). Feeding a differently-rated stream without resampling rescales the
  warm-up (2 s), the decision window (40 s) and every beat gate [V facts; I consequence]. No
  resampling method is specified anywhere in the notes [N] — §7.3.

### 1.4 Implementability statement (what this document is sufficient for)

**Fully specified — implementable from this document alone:**

- the SpO2 mapping contract: R inputs and definition, acceptance limits, polynomial, clamp,
  band classification, voting gates (§2.1 steps 7–10, §3.1);
- the per-sample I/O contract, status machine and decision rule of the one-shot tracker
  (§2.1 steps 2/12/13, §4.1, §5.1);
- the overnight wrapper aggregation, end to end (§2.2, §4.2, §5.2) — binary-level confirmed
  (notes 40 ↔ 41);
- the SHM consumption interface: bundle keys, batching, acceptance rules, timers (§2.3, §4.3,
  §5.3) — single-session caveat.

**Structurally described but NOT reproducible bit-exactly from the notes** (remaining [N] items,
collected in §7.2): the numeric value of the **caller-supplied −4 motion threshold** (a runtime field,
no static constant); whether `R ≥ 0.08` is enforced on the per-beat stream; the exact **M+0x98
"ratio of two channel-statistic floats"** definition; the vote re-evaluation **cadence** as beats
accumulate; the **hr-source byte** (out[3], never written); and `irRaw`/`redRaw` **ADC full-scale**
(no such constant exists). The adaptive filter bank, z-score normalization, motion metric, beat
detection, template/calmness machinery, FIR/IIR/Kaiser tables and the internal PPG HR path are now
pinned by byte reads (notes 70/90) — see §2.1/§3.1.

---

## 2. Algorithm

### 2.1 On-demand mapping (`libspo2.so`, APK build ver01.2824) — step by step

Read this together with §1.4: the mapping/voting contract (steps 7–10) and the pre-processing
internals (steps 4b/4c/5/6/11) are now pinned by byte reads (notes 70, 90); every remaining
non-reproducible item is called out inline and collected in §7.2.

1. **Init** (`funci`, once per tracker init): clears debug/flag structs, sets the initialized
   byte, performs a full reset. The reset initializes per-channel state — two 150-tap FIR
   band-pass states, two DC filters, normalization state, accelerometer-variance state, MA(5)
   filters, template buffers — and copies the calibration constants into the mapping struct
   M (values in §3.1) [C].
2. **Per sample** (`funcr`): unpack `{irRaw, redRaw}` (unscaled ints) + accelerometer (g) +
   `redLOffset` + `externalHr`; call chain RunProc → Execute → wrap → `secspo2_algorithm`
   (the core) [V].
3. **Warm-up**: the first **150 samples** are discarded — status 0 reported, getter values
   forced to −1.0 [C]. 150 samples = 2.0 s at 75 Hz [D from V rate].
4. **Per-channel pre-processing** (two independent channel paths; channel A = IR, B = red):
   - **4a. DC removal** [V equation; C init constants]: single-pole EMA per channel,
     `DC ← (1 − α)·x + α·DC` with **α = 0.98** (i.e. `DC ← 0.02·x + 0.98·DC`); state zeroed at
     init; the AC passed on is `x − DC_prev` (the DC value from before this sample's update).
   - **4b. FIR band-pass** [C]: transposed direct-form FIR, **151 taps** (length argument `0x96` =
     150 plus tap[150] added explicitly in `fir_bpf`; output scaled by **2⁻¹⁶**, f32 `0x37800000`
     @file 0x2bc0) [V note 70 §7.2 item 7; C note 90 §Q3]. Two symmetric linear-phase f32 tables of
     151 values each: `fir_filter_f_for_spo2` @0xa1d0 and `fir_filter_for_spo2` @0xa5a8 (full value
     lists in note 70 §7.2 item 7). **Assignment:** `fir_filter_for_spo2` feeds **both** main-pipeline
     channels — one GOT load at file 0x3c22 whose pointer is reused at the two channel calls
     0x3c2c / 0x3c3e; `fir_filter_f_for_spo2` is referenced **only** from the adaptive-bank function
     `FUN_000157a0` (GOT load file 0x591c) [V note 70 §7.2 item 7; re-verified this revision]. This
     settles note 20 vs note 21 in favour of the call-site evidence.
   - **4c. Adaptive notch, normalization, motion metric** [C] — three sub-blocks, all now pinned by
     byte reads (note 70 §7.2 items 1–3; note 90 §Q2):
     - **Adaptive band-stop** (`adaptive_bpf` @0x2ca0; `init_adaptive_bpf` @0x2c40): five tap slots
       (+0x24…+0x37); init constants α₁ = **0.96** (f32 `0x3f75c28f` @file 0x2c88), β = **0.9**
       (`0x3f666666` @0x2c8c), α₂ = **0.95** (`0x3f733333` @0x2c98), two slots at 0.0; steering
       accumulates `p38 += p24·(−0.2)` and the centre coefficient is clamped to `[bound_lo, bound_hi]`
       (two caller pointers); step constants f64 **−0.2 / 1e-8 / +0.2** @file 0x2e30/0x2e38/0x2e40.
     - **Bounds table** `an_init_bound_for_spo2` @0xa804 = **24 f32** (96 B, dynsym size); the
       pair-averaging consumer reads adjacent pairs `0.5·(tab[k]+tab[k+1])` for k = 0,2,…,20 (11
       iterations, counter 0→0x39c stepping 0x54, output stride 0x54) → touches entries **0…21; no
       25th slot is accessed** [V note 70 §7.2 item 1; C note 90 §Q2; re-verified this revision].
     - **Z-score normalization** (`normalization_for_spo2` @0x2bd0): `mean' = mean·α + x·(1−α)`,
       `var' = (x−mean')²·(1−α) + var·α`, `result = (x−mean')/√var'`; **α = 0.95** (f32 `0x3f733333`
       @file 0x2c38), **1−α = 0.05** (@0x2c3c); warm-up (`param_3 < 1`) forces result 0.
     - **Motion metric** (`acc_var` @0x2e70): one input per call, invoked only every 5th sample; the
       input is the **signed sum of the three accelerometer axes** (not a magnitude); EMA α @+0xd4,
       a 50-entry ring at +0x08…+0xcf (index wraps at 49), variance recomputed on each 50-wrap, a long
       EMA `0.999·e + 0.001·x` (f64 @0x2f28 / 0x2f30), output `+0xe0 = clamp(2·x, 0.95, 1.0)`
       (f32 `0x3f733333` @0x2f38). The metric's **numeric threshold that fires −4 is caller-supplied**
       (§5.1), not a constant.
   - **4d. IIR band-pass** [C]: 6th-order, coefficient tables at 0xa198/0xa1b4 (§3.1). Its position
     is now pinned: `filter_b`/`filter_a` (GOT 0xde14/0xde18) are consumed **only** by `iir_bpf`
     @0x2ab8 — there is **no** main-pipeline use, so it is exported infrastructure, not a specified
     per-sample stage [V note 70 §7.2 item 7].
5. **Beat segmentation** (from sample 77) [C]: the filtered channels are pushed into 630-sample
   (8.4 s) beat buffers (index guards at 0x274); the smoothed value is read at ring index `i + 0x99`
   (153 = FIR group-delay compensation; file 0x3ea4 / 0x4090). **Both channels are processed
   symmetrically** — two per-channel contexts, each with a ring count at +0x1e0, flags at +0xe4, and
   three beat-time slots at +0x134 (prev-prev) / +0x138 (prev) / +0x13c (current); a beat requires
   each channel's count ≥ 3 (file 0x3f40 / 0x3f4c). **Peak criterion:** the beat time is the
   per-sample regression turning point (`find_max_point_by_regression` @0x6aa8), and both channels
   must agree within the interval gates [C]/[I]. Interval gates are **286** (`0x11e`, file 0x4048 /
   0x406e) and **210** (`0xd2`, file 0x3f68 / 0x3f8e / 0x3fa6); variability gate **105** (`0x69`).
   Per accepted beat the two per-beat segments (the spans between consecutive beat-time slots) are
   copied and summed. The words the earlier text cited as "range floats at file 0x3e8c–0x3e94" are
   **not floats** — they are VFP/ALU instruction encodings (`ldr.w`/`vneg.f32`/`add.w`) misread on a
   straddling boundary; the compared floats are runtime `.bss` values (base+0x13e8c/90/94) [V note 70
   §7.2 item 4; C note 90 §Q3]. The `(flags & 0xe0) == 0xe0` "all four quality bits set" condition
   advances beat acceptance (counter +0x1da4; any-flag counter +0x1da8) [V note 70 §7.2 item 4].
6. **Artifact rejection** [C]: a window of the beat waveform (from a **300-entry** ring — values at
   +0x6b4, timestamps at +0x1010; window = `nearest−2 … nearest+2`, requiring span ≥ 10 samples) is
   linearly detrended, min-shifted, max-normalized, resampled to **64 points** via `interpolate_array`
   @0x6d5c, and Pearson-correlated against the running template (`calc_pearson_coef_and_index`
   @0x7028; `calc_correlation` @0x6fc0 over 49 upsampled lags, divisor f64 **15.50384075** @0x7110);
   the coefficient is stored at state+0x14d8. **Pearson threshold = 0.65** (f64 `0x3fe4cccccccccccd`
   @file 0x75d0); the window caps are **dt ≤ 900.0** (f32 `0x44610000` @0x75d8) and **widen 59.0**
   (f32 `0x426c0000` @0x75e0) [V note 70 §7.2 item 6; C note 90 §Q3]. `judge_result_flag` @0x360c
   returns internal −52 (`0xffffffcc`) after 4 consecutive large |Δ| of the coefficient (counter
   `+0x136aa` wraps 0…3); the motion metric over the **runtime** threshold → −4 (§5.1). The template is
   initialized by `init_template_data` @0x7170 (zeroes the buffers). The exact re-sampling "widen"
   value's role within the window selection is [I].
7. **Per accepted beat — ratio of ratios** [C]:
   - AC per side = the **sum of the two per-beat segments of that channel's FIR-filtered
     signal**, made absolute after summation: `AC_A = |M[0x00] + M[0x04]|` (IR),
     `AC_B = |M[0x08] + M[0x0c]|` (red). The two segments are the spans **between consecutive
     beat-time slots** (+0x134 / +0x138 / +0x13c); the smoothed value is read at ring index
     `i + 0x99` [V note 70 §7.2 item 5].
   - `DC_A = M[0x18]`, `DC_B = M[0x1c]`: the per-channel DC-filter (4a) state values; the
     sample at which they are read is [N].
   - Perfusion-like indices per side = `100·|AC|/DC` (exposed via `get_perfusion_index`).
   - `R = (AC_red / DC_red) / (AC_ir / DC_ir)` [C — proven channel order].
   - Accept iff `R ≤ limit`; limit = **1.84** on both `redLOffset` selections in this build
     (slot M+0x3c vs M+0x50 chosen by `redLOffset < threshold`). On reject, the internal R slot
     (M+0x74) and the internal DC-ratio slot (M+0x2c, which holds DC_B/DC_A on accept) are
     zeroed. On accept (caller's "beat valid" argument ≥ 1): M+0x74 = R, M+0x2c = DC_B/DC_A.
   - The **0.08 lower bound** (f32 @file 0x38cc, inline copy @0x3868) is verified enforced for the
     secondary stream (out-of-bounds → zeroed at 0x14bd2); there is **no distinct static comparison**
     separating the per-beat stream from the M+0x98 stream, so whether the beat path enforces
     `R ≥ 0.08` remains [N] — §7.2 item 11 [V note 70 §7.2 item 11].
   - Clarifier: the internal M+0x2c slot is on-demand-library state with **no known
     relationship** to the sleep-watch event's `dcRatioList` (§4.4) — different components,
     different dataflows [D].
8. **Per accepted beat — mapping** [C]:
   `SpO2_beat = clamp(109.6912 − 20.3648·R − 2.17151·R², 70.0, 100.0)  [%SpO2]`
   Coefficient sets A/B are selected per beat by the same `redLOffset < threshold` test —
   both sets are identical in this build. R = 0 → result 0. **The polynomial is authoritative**;
   corrected illustration values (computed this session; see §0.2): R = 0.5 → 98.97; R = 0.75 →
   93.20; R = 1.0 → 87.15; R = 1.5 → 74.26; the 70.0 clamp binds from R ≈ 1.6564, so for
   R ∈ [1.6564, 1.84] the mapped value is pinned at 70.0.
9. **Two estimate streams, same polynomial** [C]:
   - Stream 1's R source is the **per-beat slot M+0x74** (written per accepted beat by
     `FUN_0001667c` @0x667c, step 7); its mapped results land at **M+0x7c** (`FUN_00016760` @0x6760,
     flag 1) and are classified with pools 0x447c/0x4480 (94.0/90.0) into bands ≥94 / [90,94) / < 90.
   - Stream 2's R source is the **M+0x98 "ratio of two channel-statistic floats"** block; its mapped
     results land at **M+0x9c** (flag 2) and are classified with pools 0x4c2c/0x4c30 (94.0/90.0) into
     bands ≥94 / [90,94) / [70,90), with < 70 dropped. It is bounded by the same constants
     (1.84 / 0.08, selected by the same `redLOffset` test) and **zeroed when out of bounds**. The
     closer definition of the two channel statistics remains [N].
   - **Stream↔source association (resolved):** the block locality places the reported selector after
     the per-beat M+0x7c accumulator, and the M+0x98 block is the secondary selector's ratio block —
     note 21's reading; note 20's "reported = M+0x98" label is superseded [C note 70 §7.2 item 8].
   - **Dedup guard (resolved):** the predicate is **exact float equality** `R == *(float*)(state +
     0x143d6)` (no tolerance); on a duplicate the branch **jumps over the band-counter increments**,
     i.e. the band counters are **frozen** [V note 70 §7.2 item 9].
10. **Band voting** [C]:
    - The **reported** selector fires with gates: share(≥ 94) ≥ **5 %** (5.0 @file 0x4864) →
      mean(≥ 94); else share(≥ 90) ≥ **30 %** (30.0 @0x48a0) → mean(hi+mid); else share(< 90) ≥
      **60 %** (60.0 @0x4c10) → mean(lo); else **0** (0.0 @0x4c14). It writes the `get_spo2` slots of
      the results struct at 0x28D28; the share scale is ×100 (100.0 @0x4c08). It consumes the
      **per-beat M+0x7c** accumulator block (widths A / A+B / C at +0x80/+0x84, +0x88/+0x8c,
      +0x90/+0x94) [V note 70 §7.2 items 8,10].
    - The **secondary** selector fires with gates **8 %** (8.0 @file 0x4cb0) / **30 %** (30.0
      @0x4cfa) / **70 %** (70.0 @0x4c34), else no valid flag (the getter then reports −1.0). It
      consumes the M+0x98 statistic block and writes `get_template_spo2`. Both gate sets were
      byte-verified this revision; the reported set is **5/30/60**, the secondary **8/30/70** [V note
      70 §7.2 item 8; note 90 §Q4 lists only the secondary 8/30].
    - **Firing schedule:** the reported selector re-evaluates per valid beat; a **6-beat boundary**
      exists (decompile line 1025: `if (iVar12 < 6)` switches a state flag). The band thresholds on
      mapped SpO2 are **94.0 / 90.0 / 70.0** (file 0x447c / 0x4480 / 0x4810, mirrored at
      0x4c2c/0x4c30/0x4c34). The exact re-evaluation cadence as beats accumulate remains [I]
      [C note 70 §7.2 item 10].
11. **HR fusion** [C]: the reported HR is the current `externalHr` sample when ≠ 0, otherwise the
    **latched** previous HR; when that value is < 1 and the sample count ≥ 2000 (i.e. after sample
    1999), the internal PPG HR is used instead, if ≥ 1; otherwise 0. **The external-HR latch**
    (`*(int*)(base + 0x178e6)` inside `__Execute_SpO2_Algorithm` @0x7884) is updated **only** from
    the external `param_7` when it is ≠ 0 (previous value reused otherwise; reset to 0 at start) — the
    internal HR **never** enters this latch [V note 70 §7.2 item 14; C note 90 §Q6]. The internal PPG
    HR = **acos(ω)·190.9859317102744** (f64 @file 0x5f78 = 1200/2π), emitted inline in `FUN_000157a0`;
    the exported `an2bpm_for_spo2` @0x2a18 applies the same K (f64 @0x2a48) and has **no in-library
    caller**; `bpm2an_for_spo2` @0x2a50 = cos(x·0.005235987755983) (k f64 @0x2a80) is called twice in
    `FUN_000157a0`; the SDFT chain is `sdft` @0x2ffc → `find_sdft_snr_idx` @0x33a0 →
    `check_sdft_local_peak` @0x31b0 [V note 70 §7.2 item 13; C note 90 §Q6]. With `externalHr = 0` the
    internal HR is what feeds the ≥ 1 gate.
12. **Output packing** (`secspo2_algorithm_wrap` @0x76e0) [V]: out[0] = status, out[1] = `get_spo2()`,
    out[2] = `get_hr()`, out[4] = `get_template_spo2()`, then a 0x30-byte memcpy into out[5..];
    **out[3] is never written** in the steady-state path (and is 0.0 in the warm-up path), so the
    "hr-source" slot is always 0 / untouched caller memory [note 70 §7.2 item 12; C note 90 §Q5].
    `funcr` spreads the 4 bytes into the Java `int[4]` (§4.1); the spo2 value is the char-truncation of
    the band-voted mean [C].
13. **Decision**: forced at sample **3000** (= 40 s at 75 Hz): COMPLETE iff HR ≥ 1 and mapped
    SpO2 > 0, else LOWSQ. **No earlier completion path exists in this build** [C].

### 2.2 Overnight post-processing (`lib_hmspo2wrap.sensor.samsung.so` v1.11.01)

> [V note 40; independently confirmed at the binary level by note 41 — verdict "agrees" on
> every library-side claim (§0.1). The confirmation's two nuances are folded into steps 4 and
> 6; the consumer-side smali chain that receives these outputs (§2.3) remains single-session.]

The wrapper receives per-sample values that are **already SpO2 estimates quantized to 5 bits**
(4 per second) and produces per-second and per-minute lists. It contains no PPG pipeline — no
FIR/IIR/DC filters, no ratio-of-ratios, no polynomial mapping (every libspo2 mapping constant
is absent from the file by byte search) [V].

Input geometry: lane = `i mod 4`, second = `i div 4` — **successive input samples within one
second are the four "channels"** `ch[0..3][sec]`. Only the low byte of each input word is
interpreted (§4.2); bits 8–31 are unread. A source that produces only one value per second
cannot populate the four lanes without a design decision (duplicated lanes would trivially pass
the ≤ 7 gate, but that configuration is not stock-observed) [I] — §7.3.

1. **Decode**: `ch[i mod 4][i div 4] = code + 69` (code 0 = invalid); the bit-7 flag is read
   from lane 0 only and marks an invalid second.
2. **4-channel consensus**: the second earns a consensus vote iff all four channel values are
   non-zero and all six pairwise |differences| ≤ 7; the vote is the 4-channel mean
   `(sum >> 2)` clamped to [70, 100]. **Independently**, ch0 and ch3 alone also vote when both
   are non-zero and |ch0 − ch3| ≤ 7, contributing the pair mean `((ch0+ch3) >> 1)` clamped to
   [70, 100]. The vote total is V.
3. **Histogram**: votes go into `hist[31]` — 31 bins of width 1 %SpO2 covering 70..100, bin
   index = value − 70.
4. **Global anchor**: walk `hist` from bin 100 down, accumulating until the sum is
   > (1 − p3)·V (p3 default 0.95, i.e. the top 5 % of vote mass) → global anchor (≈ 95th
   percentile); if never set, default **95**. The anchor is cached in library state across
   RunProc calls, so a later call whose walk never reaches the threshold reuses the previous
   anchor [C note 41].
5. **Second wipe**: seconds flagged invalid have all four channel values zeroed.
6. **Per-stretch recalibration**: split the second axis at flag rising edges; each stretch of
   ≥ p0 (default **120**) seconds with valid values gets, **per channel**, a histogram of that
   channel's stretch values and — if > 3 valid values — a stretch anchor from a top-mass walk
   (> (1 − p4)·n_valid, p4 default 0.95; else anchor 0). Every value in the stretch is then
   shifted by (global anchor − stretch anchor) in integer arithmetic (anchors are histogram
   bins, i.e. ints) and clamped > 99 → 100; after all stretches, a final global pass clamps any
   value ≥ 101 → 100 (that order is as recorded). Whether each channel's own anchor is applied
   only to that channel's values is the natural reading of the note but not fully explicit [I].
   Values are never raised above 100 nor forced ≥ 70 at this stage. A confirmation nuance
   [C note 41]: after a stretch is processed, the stretch-length counter restarts from the
   walked bin value (70..100) rather than from 0, so a later stretch can reach the 120-second
   threshold after as few as ~20 real seconds.
7. **Per-second merge**: for each second in [3, n−3), collect the 28-value window (4 channels ×
   sec−3..sec+3). `HM_merge_ch_statistics` counts only values in [70, 100] (cnt), computes
   `mean = sum / cnt` in **integer division (truncated)** over the valid values only, and
   `range = max − min`. Output: `outSecSpO2[sec] = mean` iff `cnt / 28.0 > 0.1`, else 0.
8. **Per-minute anchors**: at each closed 60 s window (`sec % 60 == 0`): the minute histogram
   is filled from `outSecSpO2` values clamped to [70, 100] (window count w). If w ≥ **21**:
   walk top-down until > (1 − p1)·w (p1 default 0.9 → top 10 %) → minute high anchor (≈ P90);
   walk again until > (1 − p2)·w (p2 default 0.1 → top 90 %) → minute low anchor (≈ P10).
   Else (w < 21): both anchors = 0 (minute invalid). `outLowCount +=` the histogram mass in
   [P10 anchor, 89] (seconds at/above the low anchor and below 90).
9. **Gap interpolation**: a run of ≤ 8 zero seconds terminated by a valid value at index
   i ≥ 11 gets a 10-wide linear ramp `prev + (next − prev)·k/10` (k = 0..9) ending at i − 1.
   Quirk: the ramp always spans the full 10 positions, so short zero-runs also rewrite up to
   9 − (run length) valid seconds before the run (recorded, not judged).
10. Cap: at most 86400 s (24 h) per call. The parameter block is runtime-settable via
    `HM_SetSpO2PostAlgo_PARAM` (§3.1); init/deinit reset it to defaults.

### 2.3 SHM consumption — the interface the adapter must satisfy

> Single read-only smali session [V note 12] — see §0.3.

- **Mode gate**: the apnea version comes from pref `apnea_feature_version` (−1 UNKNOWN /
  0 NOT_SUPPORTED / 1 V1 / 2 V2). `setOperationMode` (HM_SPO2_VERSION_1 / _2 on the SSM
  attribute) is sent only when the version is ready; otherwise skipped. (Observed SHM-side call
  only: this sensor's attribute key is the lowercase `cmd` with {FLUSH, GET_DATA, GET_VERSION},
  so there is **no `SET_OPERATION_MODE`** on this sensor — it exists on the Stress attribute;
  see the reconciliation below.)
- **Pull**: `getData(startTimestamp, endTimestamp)` on event type 0x31, then register; a
  **5000 ms watchdog** arms after the request.
- **Batching**: data arrives in multiple events; accumulate until the accumulated count equals
  `secFullSize`; the usable batch = min(`secFullSize`, lengths of secSpO2List, secTimeList,
  dcRatioList, accPowerList); any null array → abort; `data_type` EMPTY completes with the
  partial buffer.
- **Observable SHM-side surface** (what SHM calls — the answering side is not in any analyzed
  tree, §7.3): `SemSensorManager` via `getSystemService("sem_sensor")`, reached through SHM's
  wrapper with methods isAvailable(type), isFeatureSupported(type, String),
  registerListener(listener, type) (true iff result 0), setAttribute(listener, type, attribute),
  unregisterListener(listener, type). Attribute bundle (producer class
  `SemSleepWatchSpO2SensorAttribute`, note 71 §2): key **`cmd`** (lowercase) holding an
  `EnumSet<Cmd>` with `Cmd = {FLUSH, GET_DATA, GET_VERSION}`; `getData` adds `startTimestamp`/
  `endTimestamp` (longs) and `getVersion` adds `versionType` (2 = CALCULATION_ALGO_VERSION,
  4 = SIGNALCHECK_ALGO_VERSION). **There is no `SET_OPERATION_MODE` member on this sensor's Cmd
  enum** (that member belongs to the Stress attribute), so the `setOperationMode` step above has no
  producer-side counterpart in the analyzed trees [V note 71 §2.6.2; C note 90 §Q10]. Events arrive
  at `SemSensorListener.onEventChanged`; SHM gates on type 0x31 and reads the bundle keys of §4.3.
- **Producer-side semantics (resolved, note 71 §2)** — producer class `SleepWatchSpO2Handler` posts
  `SemSensorHalAdapter.postDirectEvent(0x31, bundle)`:
  - **Paging**: each event carries a second-resolution page (≤ 30000 pairs) and a minute-resolution
    page (≤ 500 triples) simultaneously, sliced oldest-first (`Arrays.copyOfRange` with a forward
    offset). `secFullSize` = total seconds; `secSize = min(remaining, 30000)`; `secRemainCount =
    remaining ÷ 30001`. Minute fields are analogous with page 500 / divisor 501. Every page stamps
    `data_type = DataType.NORMAL`. If the per-second list is empty, **no event is posted at all**
    [V note 71 §2.1/§2.2].
  - **version**: HAL packet type 2 (CALCULATION) / 4 (SIGNALCHECK) → `data_type = VERSION`,
    `version` = int[4] from packet offsets **{9,10,11,12}** (MODE/MAJOR/BUILD/DEVICE), `version_type`
    via `intToVersionType` (2 → CALCULATION_ALGO_VERSION, 4 → SIGNALCHECK_ALGO_VERSION)
    [V note 71 §2.3].
  - **`versionMap` does not exist**: absent from the producer, the event class and HTS; the SHM "SQC"
    path's `versionMap` is a local fallback map, not a producer bundle key [N note 71 §2.3].
  - **Unconsumed keys**: `min*` / `maximumSpO2List` / `minimumSpO2List` are produced and exposed but
    have **zero consuming call sites** in the analyzed trees (there is also no `getMax*` getter)
    [V note 71 §2.5].
- **V1** (Apnea1Manager): input is the per-second values only (as doubles). Needs ≥ 3600 samples
  to evaluate; result "Insufficient 1" unless length ≥ config_hours × 3600 (config 3 → 10800);
  "Insufficient 2/3" when the result value is negative; the valid fraction (non-NaN,
  |v| > 1e-6) must be ≥ 0.7 or that result component stays −1.0. If insufficient at wake, a
  **90-minute** retry timer arms (a 30-minute timer exists elsewhere in the library).
- **V2** (Apnea2Manager): builds `SleepRaw(secTimeList[i], (byte) secSpO2List[i],
  dcRatioList[i], accPowerList[i])` per sample until `secFullSize` samples accumulate, then
  persists a SleepRawSession (Room, sqlcipher) and requests vendor sync.

---

## 3. Calibration

### 3.1 Binary identity and constants (authoritative, baseline R920XXS2DZG3 — library constants identical in all §6 families)

Algorithm-carrier files (byte-identical across all seven families; verified per family by
sha256sum + stat + cmp -s) [V notes 20/22/31, 60, 61, 6x]:

| file | where | size | sha256 | BuildID | version |
|---|---|---|---|---|---|
| `libspo2.so` | inside HTS/HealthPlatform APK, `lib/armeabi-v7a/` — **the one HTS loads** | 54 936 B | `9b259236ed1c6665803b7c7cec982bfc3fac53bcc6beaadb552741357b40e8ed` | `7444229eb647e1d993e5539de96c1c50e525f2e6` | `ver01.2824` |
| `lib_spo2manual.sensor.samsung.so` | system `/system/lib` (vendor sensor-stack flavour; same JNI class, parameterized init) | 54 936 B | `c28dd9cfd54dacdb64e334e3891377ae56f8ccecfdfcf948ed0c8974843129a1` | `e9c955625cbcf36b4c84497c4efa5a3ee36b048f` | `ver01.3101` |
| `lib_hmspo2wrap.sensor.samsung.so` | system `/system/lib` (overnight post-processing) | 22 084 B | `01d968c6746183fca611a5fbe0128df6c544e94da4c8b2b091af9a1c2ada7e11` | `ae2ce26d…` (prefix as recorded) | `1.11.01` |

All ELF32 ARM; the two spo2 libs Android 26 / NDK r20; the wrapper Android 28 / NDK r21b [V].
Offsets below are **file offsets in `libspo2.so`** (APK build) unless stated; each was byte-verified
in note 21 §A [C], and the tables/constants resolved from the §7.2 residue were re-read in note 70
and reproduced independently in note 90 [V/C].

| constant | value | provenance |
|---|---|---|
| poly c2 | −2.171509981 | file 0x3858 (raw `0xc00afa05`) → mapping slots M+0x30 / M+0x44 |
| poly c1 | −20.3647995 | file 0x385c (`0xc1a2eb1c`) → M+0x34 / M+0x48 |
| poly c0 | 109.6912 | file 0x3860 (`0x42db61e5`) → M+0x38 / M+0x4c |
| R upper limit (set A) | 1.84 | file 0x3864 (`0x3feb851f`) → M+0x3c |
| R upper limit (set B) | 1.84 | file 0x38c8 → M+0x50 |
| R lower bound / init R | 0.08 | file 0x3868 → M+0x40; also M+0x54 |
| SpO2 clamp | 70.0 / 100.0 % | files 0x681c / 0x6818 |
| perfusion ratio scale | ×100 | file 0x6758 |
| band edges acc-1 | 94.0 / 90.0 % | files 0x447c / 0x4480 |
| band edges acc-2 | 94.0 / 90.0 %, lower edge 70.0 | files 0x4c2c / 0x4c30 / 0x4c34 |
| share gates (reported) | 5 % / 30 % / 60 % | vfp immediates `0x40a00000` @Ghidra 0x14864, `0x41f00000` @Ghidra 0x148a0; 60.0 file 0x4c10 |
| share gates (secondary) | 8 % / 30 % / 70 % | 8.0 @Ghidra 0x14cb0; 30.0 @Ghidra 0x14cfa; 70.0 pool file 0x4c34, loaded @Ghidra 0x14d08 |
| sample rate | 75.0 Hz | `.data` vaddr 0xe004 = file 0xd004 (`0x42960000`); (75/60)·28 = **int 35** stored to state+0x13c0e (divisor f64 60.0 @file 0x4008, ×28.0, `vcvt.s32`) [V note 70 §7.2 item 15; C note 90 §Q7; re-read this revision] |
| DC filter α | 0.98 | file 0x3850 (`0x3f7ae148`) |
| FIR band-pass | **151 taps** (length `0x96` = 150 + tap[150] added explicitly in `fir_bpf`), output scaled ×**2⁻¹⁶** (`0x37800000` @0x2bc0); tables `fir_filter_for_spo2` @0xa5a8 (feeds **both** main channels) and `fir_filter_f_for_spo2` @0xa1d0 (adaptive bank only) — 151 symmetric f32 each; full value lists in note 70 §7.2 item 7 | [V note 70 §7.2 item 7; C note 90 §Q3] |
| IIR band-pass (infrastructure) | b = [0.1044481, 0, −0.3133443, 0, 0.3133443, 0, −0.1044481] @0xa198; a = [1.0, −3.3464386, 4.5369792, −3.4548595, 1.7421415, −0.5275742, 0.0499856] @0xa1b4; consumed **only** by `iir_bpf` @0x2ab8 (GOT 0xde14/0xde18) — no main-pipeline use | **corrected values** [C note 21 §C2]; position [V note 70 §7.2 item 7] |
| adaptive-notch bounds | **24 f32** @0xa804 (dynsym size 96 B): 0.9659258 0.9876884 0.9426415 0.9781476 0.9135454 0.9588197 0.8633956 0.9335804 0.7996846 0.9092361 0.7253744 0.8553643 0.6414496 0.7901550 0.5490228 0.7144727 0.4493190 0.6293204 0.3436597 0.5358268 0.2840154 0.4352311 0.3090170 0.9876884; the pair-averaging consumer reads entries **0…21** (no 25th slot) | **corrected values** [C note 21 §C1]; count/consumer [V note 70 §7.2 item 1; C note 90 §Q2] |
| adaptive-bank init/LMS | α₁ = 0.96 (`0x3f75c28f` @0x2c88), β = 0.9 (`0x3f666666` @0x2c8c), α₂ = 0.95 (`0x3f733333` @0x2c98); step doubles −0.2 / 1e-8 / +0.2 @0x2e30/0x2e38/0x2e40; five tap slots (+0x24…+0x37) | [V note 70 §7.2 items 1,2; C note 90 §Q2] |
| normalization α | 0.95 @0x2c38, 1−α = 0.05 @0x2c3c (mean/variance EMA; `result = (x−mean)/√var`) | [V note 70 §7.2 item 2; C note 90 §Q2] |
| motion metric | input = **signed sum of 3 accelerometer axes**, sampled every 5th call; 50-entry ring (+0x08), EMA α @+0xd4, variance @+0xdc, EMA 0.999/0.001 @0x2f28/0x2f30, output clamp(2·x, 0.95, 1.0) @0x2f38 | [V note 70 §7.2 item 3; C note 90 §Q2] |
| template / calmness | 300-entry ring (+0x6b4 value, +0x1010 u64 time); 64-pt linear interp (`interpolate_array` @0x6d5c); **Pearson threshold 0.65** f64 `0x3fe4cccccccccccd` @0x75d0; dt cap **900.0** f32 @0x75d8; widen **59.0** f32 @0x75e0; correlation divisor f64 **15.50384075** @0x7110 | [V note 70 §7.2 item 6; C note 90 §Q3] |
| dedup guard | predicate is **exact float equality** `R == *(float*)(state+0x143d6)` (no tolerance); a duplicate **freezes** the band counters | [V note 70 §7.2 item 9] |
| external-HR latch | `*(int*)(base+0x178e6)` in `__Execute_SpO2_Algorithm` @0x7884; updated **only** from external `param_7` ≠ 0; internal HR never enters it | [V note 70 §7.2 item 14; C note 90 §Q6] |
| Kaiser window table | 95 f32 `compansate_kaiser_for_spo2` @0xa42c; the **SDFT window/compensation** table, consumed by `sdft` @0x2ffc (GOT load @file 0x3018); full values in note 70 §7.2 item 7 | [V note 70 §7.2 item 7; C note 90 §Q3] |
| an2bpm constant | **190.9859317102744** f64 @0x2a48 (`0x4067df8cc0a876f9`) — the acos→bpm multiplier of `an2bpm_for_spo2` (the earlier "−5.264523029" was the f32 read of the low 4 bytes of this double) | [V note 70 §7.2 item 13; C note 90 §Q6; re-read this revision] |
| timeout floats | 94.0 / 90.0 @0x7be4 / 0x7be8 — predicate vestigial (both branches accept a positive value; HR < 1 is what fails) | [C] |
| accelerometer scale | 0.0023950562 g/LSB = 9.81/4095.9375 | HTS Java side (smali word `0x3b1cf660`), not in the library; verified in the analyzed HTS APK copy only — not compared across models' APKs [C scope-limited] |
| window counts | warm-up 150; decision 3000; HR fallback after 1999; motion re-report 200; motion give-up 999; log cadence 100; accel ring 50; beat buffer 630; beat gates 286/287, 210/211, 105; template resample 64; FIR length arg 150 / 151 taps (length argument `0x96` = 150 + tap[150] added explicitly, §2.1 step 4b) | all [C] |

Wrapper (overnight) constants [V note 40; single session]:

| constant | value | provenance |
|---|---|---|
| parameter block | {120 s, 0.9, 0.1, 0.95, 0.95} — min valid-stretch seconds; 1−p1 = 10 % (high-anchor share); 1−p2 = 90 % (low-anchor share); 1−p3 = 5 % (global anchor share); 1−p4 = 5 % (stretch anchor share) | `.data` vaddr 0x6000 = file 0x5000, raw words read; runtime-settable via `HM_SetSpO2PostAlgo_PARAM`; reset by init/deinit |
| seconds cap | 86400 s (24 h) per RunProc call | pool word file 0x193c |
| SpO2 decode bias | +69: bits 0–4 code 1..31 → 70..100; 0 = invalid | immediate `add.ne r3,#0x45` @Ghidra 0x1172a |
| clamp | 70 / 100 %SpO2 | immediates 0x46 / 0x64 throughout RunProc |
| pairwise gate | accept iff all six pairwise channel \|diff\| ≤ 7 (plus the ch0/ch3 pair variant) | `cmp #7` sites 0x11782–0x117f2 (Ghidra) |
| merge window | 28 values (4 ch × 7 s), scale float 28.0 | @0x11a76 (Ghidra) |
| window validity | > 0.1 of the 28 values must be valid (f64 0.1 at file 0x1d20) | else second output = 0 |
| minute threshold | ≥ 21 valid seconds of 60, else minute anchors = 0 | `cmp r5,#0x15` @0x11b60 (Ghidra) |
| low-count band | seconds with value in [P10 anchor, 89] | upper bound 0x59 |
| interpolation | zero-gaps ≤ 8 s with i ≥ 11; 10-wide linear ramp (float 10.0) | @0x11c4e–0x11ce4 (Ghidra) |
| histogram | hist[31], bin index = value − 70 (1 %SpO2 bins over 70..100) | `.bss` base Ghidra 0x16098 |
| version | "1.11.01" (`%u.%02u.%02u` of 1, 11, 1) | `HM_getPostSpO2Version` 0x113f0–0x11410 (Ghidra) |

### 3.2 System-copy two-table calibration (`lib_spo2manual.sensor.samsung.so`, ver01.3101)

Single-session [V note 22] — unconfirmed (§0.3); moot for the adapter's APK-build target. The
system build is a **code-differ** vs the APK build: identical symbol set, byte-identical
`.rodata` tables, same status/window machinery, but the init selects between **two calibration
tables by an init-mode argument** (APK build hardcodes one table into both slots; none of the
APK polynomial words exist anywhere in the system build — exact byte search, zero hits):

| slot | mode ≠ 2 (table A) | mode = 2 (table B) |
|---|---|---|
| c2 | −2.821340084 | −1.229400039 |
| c1 | −18.66830063 | −20.60540009 |
| c0 | 109.0469971 | 108.8828964 |
| R upper limit | 1.85 | 1.91 |
| R lower bound / init R | 0.06 | 0.04 |

Clamps 70/100 unchanged. The system `funci` JNI takes an `int mode` argument; the APK `funci`
takes none. Which mode the sensor-stack side passes (if it uses this library at all) is a device
question (§7.1).

### 3.3 Per-unit calibration and re-signed apps

- **No per-unit factory calibration is read in any analyzed build.** In `libspo2.so` the
  exported `get_prop` returns constant 0, `read_fs` returns −1, `write_fs` is a 2-instruction
  stub; no `/efs`, sysfs or system-property access exists anywhere in the library [V note 20
  §7; C note 21 read the same code]. `lib_hmspo2wrap` imports no file/property functions at all
  [V note 40 §1].
- **The plumbing exists**: the algorithm is parameterized for two calibration sets selected per
  sample by `redLOffset` (u16 delivered by the sensor in every manual-SpO2 event) — coefficient
  sets and R limits. In the APK build both sets are identical, so the selection is a no-op; the
  system build makes the selection an init-mode parameter (§3.2). That plumbing is where a
  per-unit factory value would land [V plumbing; I per-unit reading].
- **A re-signed/repacked app cannot read any per-unit calibration — there is none to read** in
  the analyzed builds [V negative result]. What a re-signed app *can* do: the algorithm library
  ships **inside** the APK (`lib/armeabi-v7a/libspo2.so`), so a repack carries its own copy and
  may substitute the library or its constants wholesale; the accelerometer scaling and the
  accuracy-flag mapping live in the HTS Java side, equally under the app's control [V
  packaging; D consequence]. This is a vendor-override capability, not a calibration source.

---

## 4. Formats

### 4.1 One-shot SPO2 tracker result ints

`funcr([I[FII[I)I` fills the caller's `int[4]` [V+C]:

| index | content |
|---|---|
| 0 | status (§5.1), mapped to `SpO2OnDemandTracker$Status` |
| 1 | SpO2 — char-truncation of the band-voted mean; −1 when no fresh estimate (warm-up forces −1.0; the underlying getter is one-shot: flag cleared on read) [C getter semantics; D byte-level consequence] |
| 2 | heart rate (see §2.1 step 11) |
| 3 | hr-source / aux — **out[3] is never written a nonzero value in this build**: `secspo2_algorithm_wrap` writes out[0], out[1], out[2] and out[4] and memcpys into out[5..], skipping index 3; the warm-up path writes 0.0 there. Its value set is therefore **not enumerable**, and the earlier "which HR source was used" role is unsupported [V note 70 §7.2 item 12; C note 90 §Q5] (§7.2) |

- HTS `ValueKey$SpO2Set` order: STATUS, SPO2, HEART_RATE, ACCURACY_FLAG [V note 12].
- ACCURACY_FLAG ∈ {0 (NORMAL), −999 (ERROR)}, default 500 when the sensor omits it; it is the
  sensor-provided accuracy, **not** a library output [V+C].
- Unknown status ints are dropped silently by `Status.Companion.fromInt` [V note 12].

### 4.2 Overnight wrapper input word and outputs [V note 40; C note 41]

Per-sample input word (`samples[i]`, int; **only the low byte is interpreted**; bits 8–31
unread; the words are little-endian):

| bits | meaning |
|---|---|
| 0–4 | SpO2 code: 0 = invalid; 1..31 → value = code + 69 → 70..100 |
| 5 | unused in this build |
| 6 | flag stored into a scratch buffer that is never read (dead/reserved) |
| 7 | second-level invalid marker (read from lane 0 only): 1 → the second's four channel values are wiped to 0 |
| 8–31 | not read |

Lane/second mapping: lane = `i mod 4`, second = `i div 4` — the four successive samples of one
second are the four channels (§2.2).

Outputs of one RunProc call: `outSecTimes` (long, count/4), `outSecSpO2` (int, count/4),
`outMinTimes` / `outMinHigh` / `outMinLow` (count/240 + 1 each), `outLowCount[0]` (scalar).
`outSecSpO2`: **0 = invalid second**, else 70..100. `outMinHigh`/`outMinLow`: **0 = invalid
minute**, else 70..100. `outLowCount`: non-negative counter. Timestamps are pass-through longs;
their unit is caller-owned (unknown — §7.1) [V; I unit].

### 4.3 `secSpO2List` and the SSM sleep-watch event bundle [V notes 12, 40 §6]

- Event: `SemSleepWatchSpO2SensorEvent`, type **0x31 (49)**. **Producer-side key set** (note 71 §2,
  class `SleepWatchSpO2Handler`): `secSpO2List` (int[]), `secTimeList` (long[]), `secFullSize`,
  `secSize`, `secRemainCount`, `minTimeList` (long[]), `minimumSpO2List` / `maximumSpO2List` (int[]),
  `minFullSize`, `minSize`, `minRemainCount`, `version` (int[]), `version_type`, `data_type` (enum
  NORMAL / EMPTY / VERSION), `timestamps` (long[]). The **unconsumed** producer keys (`min*` /
  `minimumSpO2List` / `maximumSpO2List`) have zero consuming call sites [V note 71 §2.5]. There is
  **no `versionMap` key** in the producer or the event class (the SHM "SQC" path's `versionMap` is a
  local fallback map) [N note 71 §2.3]. `dcRatioList` / `accPowerList` are **not** in the producer set
  either; they appear only in SHM's own copy of the event class (`getDcRatioList`/`getAccPowerList`) —
  see §4.4.
- **secSpO2List value range**: on the stock device the per-second values come from the wrapper,
  whose output domain is **0 = invalid second, else 70..100** [V]. SHM applies **no clamp, no
  range check, no scale** anywhere in the consumption path (ac, k8, x10, ApneaSessionManager,
  framework classes) [V]; the only narrowing is the V2 persistence cast to byte at k8.smali:309,
  which wraps rather than clamps [V]. The V1 evaluator works on raw doubles, excluding only NaN
  and |v| ≤ 1e-6; the percent unit is implied by use, never enforced [V]. Consistently, a 0
  counts as invalid for the V1 valid-fraction test too [D].
- **Cadence**: one value per second is *inferred* — from the sec*/min* key families, the
  index-wise pairing of secSpO2List[i] with secTimeList[i], and the evaluator treating sample
  count as seconds (≥ 3600 = 1 h). No explicit 1 Hz constant exists in the smali [I].
- **Producer-side semantics (resolved, note 71 §2)**: `secFullSize` = total seconds;
  `secSize = min(remaining, 30000)`; `secRemainCount = remaining ÷ 30001`; the minute fields are
  analogous (page 500 / divisor 501); pages are oldest-first and each carries both a second and a
  minute page; pages carry `data_type = NORMAL`; an empty second list posts no event; `version` =
  int[4] from packet offsets {9,10,11,12} with `version_type` from packet type 2/4
  [V note 71 §2.1–§2.3]. The only unresolved piece is the actual `secTimeList` unit (§7.1 item 5).

### 4.4 `dcRatio` and `accPower` — status: not defined in any analyzed binary

- **No definition, no computation, no fixed-point scale, range or sentinel can be stated from
  the analyzed material.** In `lib_hmspo2wrap` they appear in no function, no bitfield, no
  constant (all 12 algorithm functions decompiled and traced; the library writes exactly the
  five outputs of §4.2 plus one scalar) [V note 40 §7]. The libspo2 pipeline's outputs (status /
  spo2 / hr / aux and the getter set) contain nothing by these names either [V notes 20/21]. They
  are absent from the **SSM producer class**, the **framework event class** and **HTS** as well
  (zero case-insensitive grep hits) [N notes 71 §2.6, 90 §Q9].
- What is verified (consumer side) [V notes 12, 40 §6, 71 §2.6]: `dcRatioList` / `accPowerList` are
  `short[]`, one entry per second, parallel to `secSpO2List`, and exist **only** in SHM's copy of
  the sleep-watch event class (`getDcRatioList`/`getAccPowerList`); the producer-side event class in
  the analyzed framework tree has no such getters. SHM reads them with no bounds (only the batch
  min), stores `SleepRaw.dcRatio:S` / `SleepRaw.accPower:S`, persists via Room (Apnea2Manager) and
  ships them into vendor sync. They pass through as opaque shorts.
- Origin: **upstream of both the SHM APK and the wrapper** — since the SSM bundle builder does
  **not** produce them (note 71 §2.6) and no userspace library computes them, their producer sits
  further upstream (the sensor hub, whose image is unreachable — §1.2). The wrapper's input word
  carries only the 5-bit SpO2 code + 2 flag bits, so dcRatio/accPower must travel in a different
  field or stream than the wrapper's sample array (plausibly the EHT entry payloads; the wrapper
  exports `decode_eht_entry`, which has no internal callers — grammar per note 41: entries with bit 31
  set carry a kind in bits 24–27 of the first word, kind 0 → type 1 / size 4, kinds 1 and 3 →
  payload-word count in bits 16–23, type 2; otherwise the type-1 header is effectively two words, the
  payload-word count living in bits 24–31 of the *second* word) [C note 41; N note 71 §2.6].
- The names suggest DC-ratio and accelerometer-power quality signals — naming inference only
  [I]. Do **not** confuse them with libspo2's internal M+0x2c slot (DC_B/DC_A, §2.1 step 7):
  that is on-demand-library state with no known relationship to the event's `dcRatioList` [D].
- **Adapter consequence** [D]: an adapter feeding V2 must choose its own dcRatio/accPower
  encoding; stock SHM persists whatever shorts it receives, so any encoding "works" but is
  non-stock by construction. Feeding V1 (values only) avoids inventing them. The V1/V2 choice
  is an open design decision — §7.3.

---

## 5. Status and quality rules

### 5.1 On-demand status machine (`libspo2.so` APK build) [V+C]

| value | enum name | emission condition (this build) | HTS callback |
|---|---|---|---|
| 0 | WAIT | measuring: warm-up, beat collection, or a motion sample that is not a multiple of 200 | `onEstimationStatusChanged(t, 0)` |
| 2 | COMPLETE | exactly once, at sample 3000, provided HR ≥ 1 and mapped SpO2 > 0 (else −5) | `onEstimationCompleted(spo2, hr, accuracyFlag)` |
| 3 | COMPLETE_NOTILT | never emitted (switch case writes no output) | (would take the completed callback) |
| −4 | MOTION | motion threshold exceeded; re-emitted only every 200th motion sample, otherwise 0 is reported; after 999 motion samples → −5 and reset | `onEstimationStatusChanged(t, −4)` |
| −5 | LOWSQ | (a) motion counter saturated; (b) the 3000-sample decision fails quality (HR < 1 or mapped SpO2 ≤ 0); (c) internal −52 remapped, −52 kept as sentinel in the state struct | `onEstimationStatusChanged(t, −5)` |
| 1 | READY | unreachable in this build (nothing sets internal status 1); Java side would emit no callback at all | none |
| −6 | TIMEOUT | never emitted by the library (client-side concept only) | — |
| −52 | (internal) | instability: more than 3 consecutive alternations of the beat-template Pearson coefficient beyond a threshold (mod-4 ring counter); reported as −5 | (internal only) |

- Measurement duration: 150-sample (2 s) warm-up inside a fixed 3000-sample (40 s) window at the
  75 Hz constant; no earlier completion path exists in this build [C].
- Byte values the library can write to out[0]: 0x00, 0x01 (unreachable path), 0x02, 0xfb (−5),
  0xfc (−4) — never 3 or −6 [C].
- The spo2-byte cadence during WAIT (which firing of the voted mean reaches out[1] on which
  sample, given the one-shot getter) is not pinned [N] — §7.2.
- `accuracyFlag` is the sensor-provided accuracy (0 / −999 / default 500), passed through by the
  Java layer, not computed by the library [V+C].

### 5.2 Overnight quality gates (`lib_hmspo2wrap`) [V note 40; C note 41]

Pairwise gate ≤ 7 (all six channel pairs; independent ch0/ch3 pair variant) → window validity
> 10 % of 28 → per-minute threshold ≥ 21 valid seconds of 60 → anchors = top-mass walks over
the 31-bin histograms (global 5 %, stretch 5 %, minute high 10 %, minute low 90 %) → drift
correction by (global − stretch) anchor → low-count band [P10 anchor, 89] → gap interpolation
≤ 8 zeros. (Full step list §2.2.)

### 5.3 SHM-side acceptance rules [V note 12]

- V1: ≥ 3600 samples to evaluate; "Insufficient 1" below config_hours × 3600 (3 h → 10800);
  "Insufficient 2/3" on negative results; valid fraction (non-NaN, |v| > 1e-6) ≥ 0.7 required
  per result component (else −1.0); 90-minute retry when insufficient at wake; 30-minute timer
  elsewhere in the library.
- V2: batch usable size = min(secFullSize, 4 list lengths); null array aborts; EMPTY data_type
  completes with the partial buffer; accumulation runs until count == secFullSize.
- 5000 ms watchdog on the data pull.

---

## 6. Model comparison

All firmware pulled from Samsung FUS, CSC ARO (first serving CSC in every family; XAR/EUX empty
for the L-series), unpacked per recipe note 30 (ext4/debugfs for R920/R860; EROFS/fsck.erofs
from R861 on) [V notes 30–61]. Every family: **all three SpO2 binaries byte-identical to the
SM-R920 baseline** (sha256 + size + cmp -s per pair) [V per-family notes].

| family | model of record | firmware build (PDA/CSC) | binaries carrying the algorithm | mapping formula | constants | output rate | vs SM-R920 |
|---|---|---|---|---|---|---|---|
| Watch5 Pro (baseline) | SM-R920 | R920XXS2DZG3 / R920OXM2DZG3 | APK `libspo2.so` ver01.2824 (HTS loads this) + system `lib_spo2manual` ver01.3101 (code-differ, two-table init) + system `lib_hmspo2wrap` 1.11.01 | §2.1 | §3.1 | 75 Hz; warm-up 150 / decision 3000 samples (40 s) | — (baseline) |
| Watch4 / Watch4 Classic | SM-R860 | R860XXS2JZG3 / R860OXM2JZG3 | same three files | identical | identical | identical | **same** (byte-identical) |
| Watch FE | SM-R861 | R861XXS2CZG3 / R861OXM2CZG3 | same three files (libspo2 only inside the APK — no native-lib dir on fs) | identical | identical | identical | **same** |
| Watch6 / Watch6 Classic | SM-R930 | R930XXS2CZF5 / R930OXM2CZF5 | same three files | identical | identical | identical | **same** |
| Watch7 | SM-L300 | L300XXS2BZF4 / L300OXM2BZF4 | same three files (APK-only packaging) | identical | identical | identical | **same** |
| Watch Ultra | SM-L705F | L705FXXS3BZF4 / L705FOWO3BZF4 | same three files (APK-only packaging) | identical | identical | identical | **same** |
| Watch8 Classic | SM-L500 | L500XXS2AZF4 / L500OXM2AZF4 | same three files (APK-only packaging) | identical | identical | identical | **same** |

Container notes: the HealthPlatform.apk container differs per model — **seven distinct APK
hashes recorded, one per family** (R920 `93298b71…`, R860 `bf158ae4…`, R861 `d3bd1b7f…`, R930
`71dd2037…`, L300 `230adac5…`, L705F `e28f6a6a…`, L500 `4a7c48d1…`) — but each carries the
byte-identical `libspo2.so`; the APK-vs-system `code-differ` relationship is preserved in every
family [V per-family notes]. The Java-side constants inside those APKs were not compared across
models (§ preamble, §7.1).

Hand-off table corrections discovered on FUS [V notes 6x-watch-ultra, 6x-watch8-classic]:
`SM-L705` does not exist as a queryable model — the family is **SM-L705F** (Korean SM-L705N also
exists); and of SM-L320/SM-L330/SM-L500 only **SM-L500 is the Watch8 Classic** (L320/L330 are
the plain Watch8 per FUS display names).

Equality *inferred, not verified* (same version string served; binaries not pulled): SM-R870 /
R880 / R890; SM-R940 / R950 / R960; SM-L310; SM-L705N; SM-L320 / SM-L330. SM-R865 (Watch FE
LTE) is not served by FUS at all (unparsable empty history on ARO/XAR/EUX) [V note 61].

---

## 7. Open questions

### 7.1 Only a device test can settle

1. **Which path the owner's R920 takes at runtime** — TI (APK `libspo2.so` via HTS
   SpO2LibManager) vs ADI (sensor-hub-computed, library never loaded): depends on the on-device
   SSM version and the `fresh_hrm_dualized` (feature 0x23) answer [notes 20 §8, 12].
   **RESOLVED-BY-OBSERVATION — the R920 takes the ADI path.** The 2026-10-15 note below settled
   it (logcat tag `SHS#SpO2ManualADISensor`, so `libspo2.so` is not loaded here), and the same-day
   on-wrist sessions `ds1`/`ds2` corroborate it from a second capture: their logcats carry only
   the `SHS#SpO2ManualADISensor` tag and no TI/`libspo2.so` activity
   (`$CACHE/spo2-val/ds{1,2}/logcat.txt`).
   *Note (2026-10-15, SM-R920 R920XXS2DZG3):* the HTS on-demand SPO2 tracker runs the **ADI**
   path on this watch (logcat tag `SHS#SpO2ManualADISensor`), so `libspo2.so` is not loaded here.
   The orientation finding of [spo2-adapter.md](spo2-adapter.md) (labelled IR behaves as red) is
   consistent with that path.
2. **Actual delivery rate of `SemSpo2ManualSensorEvent`** as configured by the sensor service —
   **MEASURED ≈ 75 rows/s.** Counting phase-B rows over the capture wall span in the same-day
   `ds1`/`ds2` sessions gives `ds1`: 3694 rows / ~50.03 s ≈ 73.8 Hz, `ds2`: 4095 / ~49.97 s ≈
   82.0 Hz early then throttled (provenance `$CACHE/spo2-val/ds{1,2}/logcat.txt` and the session
   CSVs). The delivered rate therefore **matches the library's 75 Hz constant** (§3.1); no
   different true rate is in play on this watch [note 20 §8].
3. **Whether the hub's 4 Hz overnight estimator shares the libspo2 mapping** — the estimator runs
   on the CHUB (Cortex-M55) hub, whose image `os.checked.bin` is **not shipped as plaintext** in this
   build (only inside the signed+encrypted `ldfw.img`); the previously named `lpd.core.bin` is the LPD
   **display** image, not the estimator (notes 71 §1, 72). Not determinable from the analyzed material;
   would require the encrypted image or a device capture [notes 40 §5, 71, 72].
4. **dcRatio / accPower production and encoding** — the producing component, scale/offset/sentinel of
   the shorts, and the stream they travel in are absent from every analyzed tree (wrapper, SSM service,
   framework event class, HTS); the producer is upstream/unreachable, so only a device capture of the
   SSM sleep-watch SpO2 event settles it [notes 40 §7, 71 §2.6].
5. **Timestamp unit** of `secTimeList` / wrapper long arrays (caller-owned) [note 40 §9].
6. **Per-second cadence of `secSpO2List`** on a real device (1 Hz is inferred; no explicit
   constant) [note 12 §4].
7. **Whether the stock on-demand measurement ever completes before the fixed 3000-sample (40 s)
   window in normal use**, and the spo2-byte cadence during WAIT on a real session
   [notes 20 §8, 21 §D.5].
8. **Whether the sensor-stack side uses `lib_spo2manual` for on-demand at all, and which
   calibration mode it passes to `funci(int)`** (table A vs B of §3.2) [note 22 §7].
9. **Whether READY(1) / COMPLETE_NOTILT(3) are emitted by any sibling build** of this library
   (the switch cases exist; this build cannot reach them) [note 20 §8].
10. **The V2 watch→phone transport below ApneaSyncManager** (relevant only if the adapter must
    coexist with vendor sync) [note 12 §5].
11. **Whether the Java-side constants (accelerometer scale 0.0023950562, accuracy mapping) are
    identical in the other models' HealthPlatform APKs** — the APKs differ per model and were
    never smali-compared across families (§ preamble).

**Dataset outcome (2026-10-15).** The on-wrist collection ended this day: 15 capture sessions
(16 directory entries under `$CACHE/spo2-val/` — one is the analyzer's `__pycache__`), of which
**1 is evaluable** (n = 1: `s173708`, mapping error **−2.03** at 75 Hz resample / **+0.56** at the
native rate). The completion bottleneck is the ADI path (§7.1 item 1): the sensor measures, but
the ADI path times out before a COMPLETE value, so only 1 of the 15 sessions yields a phase-B
COMPLETE to compare against. The mapping-level validation therefore stands at **n = 1**, pending
sessions in which ADI completes.

### 7.2 Binary-analysis leftovers ([N] items — each blocks bit-exact reproduction of the named step; resolvable by re-reading the byte-identical binaries, no device needed)

Status after the §7.2 residue pass (note 70, byte reads) independently reproduced by note 90:
**1–2, 5–9, 12–16 resolved; 3, 4, 10, 17 partially resolved; 11 remains unresolved** (item 8's
M+0x98 statistic definition also stays [N]).

1. **Adaptive filter bank** (§2.1 step 4c) — **resolved**: update equations, tap count (5 slots),
   steering rule and constants in note 70 §7.2 item 1 / §2.1 step 4c. The bound-table indexing
   resolves to **24 resident entries** (the pair-averaging consumer touches 0…21; no 25th slot).
2. **Z-score normalization** — **resolved**: `normalization_for_spo2` @0x2bd0, α = 0.95 / 0.05
   (note 70 §7.2 item 2; §2.1 step 4c).
3. **Motion metric** — **partially resolved**: formula, axes (signed 3-axis sum, every 5th sample),
   ring and clamp are pinned (note 70 §7.2 item 3); the **−4 threshold remains a caller-supplied
   runtime field** with no static constant, so the numeric trigger is not pinnable (§5.1).
4. **`judge_result_flag` thresholds / "range floats" / quality bits** — **partially resolved**: the
   cited 0x3e8c–0x3e94 words are **instructions, not floats**; the compared floats are runtime `.bss`
   values; the `(flags & 0xe0) == 0xe0` all-pass condition and its counters are pinned, the per-bit
   staging is [C] (note 70 §7.2 item 4).
5. **Beat detection** — **resolved** (structural): both channels symmetric, count ≥ 3, gates
   286/210/105, read at `i+0x99`; the peak criterion is `find_max_point_by_regression` [C]/[I]
   (note 70 §7.2 item 5).
6. **Template / calmness machinery** — **resolved**: 300-entry ring, 64-pt interp, Pearson 0.65,
   dt cap 900.0, widen 59.0, correlation divisor 15.50384075, alternation via sign flips (note 70
   §7.2 item 6).
7. **Filter tables** — **resolved**: 151+151 FIR (full values, ×2⁻¹⁶ output scale), 7+7 IIR (consumed
   only by `iir_bpf`), 95-float Kaiser (SDFT window), 24-float bound table, `ekp`/`ekK` = 240 f64
   each; table↔channel assignment pinned (note 70 §7.2 item 7).
8. **Stream↔source association** — **resolved**: reported selector consumes the per-beat M+0x7c
   accumulator, secondary consumes the M+0x98 block (note 21's reading; note 70 §7.2 item 8). The
   closer definition of the two channel statistics in the M+0x98 block stays [N].
9. **Dedup guard** — **resolved**: exact float equality (`state+0x143d6`); duplicates freeze the band
   counters (note 70 §7.2 item 9).
10. **Vote firing schedule** — **partially resolved**: reported selector re-evaluates per valid beat,
   6-beat boundary exists, band thresholds 94/90/70 (note 70 §7.2 item 10); the exact cadence is [I].
11. **Beat-path R lower bound** — **unresolved**: 0.08 @0x3868/0x38cc is consumed, but no static
   comparison separates the per-beat stream from the M+0x98 stream (note 70 §7.2 item 11).
12. **hr-source byte (out[3])** — **resolved (negative)**: the wrap never writes index 3 → always
   0 / untouched; not enumerable (note 70 §7.2 item 12; §4.1).
13. **PPG HR path** — **resolved**: HR = acos(ω)·190.9859317102744; `an2bpm` (no in-lib caller),
    `bpm2an`, and the SDFT chain `sdft → find_sdft_snr_idx → check_sdft_local_peak` (note 70 §7.2
   item 13; §2.1 step 11).
14. **External-HR latch** — **resolved**: `*(int*)(base+0x178e6)` holds **external samples only**
   (note 70 §7.2 item 14; §2.1 step 11).
15. **(75/60)·28** — **resolved**: numerator = the `.data` sample-rate word 75.0 (file 0xd004, vaddr 0xe004), divisor
   60.0 (@0x4008), result int **35** stored to state+0x13c0e (note 70 §7.2 item 15; §3.1).
16. **ADC full-scale** — **resolved (negative)**: no full-scale constant exists or is applied; the
   ratio path is scale-invariant (note 70 §7.2 item 16; §1.1).
17. **Independent confirmation of the remaining single-session datasheets** — **partially resolved**:
   the producer-side smali chain (bundle builder, attribute, version handling) is now corroborated by
   notes 71/90; the **SHM-consumer smali claims (note 12)** that define the adapter's interface remain
   single-session, as does note 22's two-table constants for the system copy (§0.3).

### 7.3 Design decisions this material cannot make (must be settled before implementation; none is answerable from the analyzed binaries alone)

1. **Injection contract** (which interface the SpO2Adapter must expose so that SHM's
   `getData(start, end)` + listener registration on type 0x31 is satisfied): the observable
   SHM-side surface is in §2.3, and the producer side (the SSM `SleepWatchSpO2Handler` bundle
   builder) is now analyzed (note 71). What remains is the OpenSHM-side injection point/design —
   where the adapter's emitted bundle enters the `sem_sensor` pipeline — which is a design decision,
   not a binary question.
2. **Per-second emission**: which value constitutes "the SpO2 of second t". The specified
   algorithm produces one band-voted mean per 40 s session (plus status updates); the stock
   per-second stream originates in the unrecoverable hub estimator (§1.2/§1.3, now a hard blocker —
   note 72). Candidate policies (latest voted mean, per-beat estimates, re-running the mapping per
   second) are adapter designs with no stock counterpart in this material.
3. **V1 vs V2 target, and the dcRatio/accPower encoding**: V1 needs per-second values only;
   V2 additionally requires two shorts per second that the adapter must invent (non-stock by
   construction, §4.4; the stock producer for them is unreachable). Owner/design decision.
4. **Resampling method** for feeding a ~100 Hz PPG_ON_DEMAND stream into the 75 Hz assumption
   (filter, interpolation, target-rate handling) — §1.3.
5. **Producer-side bundle filling** — **resolved** (note 71): `secSize`/`secFullSize`/
   `secRemainCount` and the minute analogues, samples per event, oldest-first ordering,
   `version`/`version_type`, `data_type = NORMAL`, and the unconsumed `min*`/`max*` keys are all
   specified in §2.3/§4.3, so the adapter can emit a stock-shaped bundle. (No `versionMap` is
   involved — that key does not exist.)
