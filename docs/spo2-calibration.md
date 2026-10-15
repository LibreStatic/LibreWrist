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
- All SHM-watch smali consumption facts (note 12; read-only single session). **The interface
  the adapter must satisfy (§2.3, §4.3, §5.3) inherits this caveat.**
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
- Input per sample: `{irRaw, redRaw}` raw ADC ints (**unscaled**; their numeric full-scale is
  [N] — §7.2) [V]; accelerometer XYZ in g — the Java side scales each raw axis by
  0.0023950562 = 9.81/4095.9375 [V+C]; `redLOffset` u16 and `externalHr` u8 [V]. The event
  source is the dedicated manual-SpO2 sensor (`SemSpo2ManualSensorEvent`), **not**
  PPG_ON_DEMAND [V note 20 §2]. The algorithm's internal sample-rate constant is **75 Hz** [V+C].
- Alternative ADI path: if the sensor-service version ≥ 0.27 and the sensor reports feature 0x23
  `fresh_hrm_dualized`, HTS uses `sensorListenerADI` and never instantiates SpO2LibManager — the
  sensor hub then delivers already-computed (status, spo2, hr, accuracy) [V note 20 §1]. Which
  path a given physical watch takes at runtime is a device-side property (§7.1).

### 1.2 Path B — HM overnight (sleep-apnea) SpO2

- Chain: the sensor hub (nanohub stack; hub MCU image `lpd.core.bin` on the R920's S5E5515 SoC
  [V note 31 §5]) produces ~4 Hz per-sample SpO2 estimates quantized to 5 bits, 4 per second
  (4 Hz is the wrapper's input contract; the true hub rate is unverified) [V note 40 §2–§3; I rate].
  The vendor post-processing wrapper `lib_hmspo2wrap.sensor.samsung.so` (version 1.11.01)
  aggregates them into per-second SpO2 and per-minute anchors [V note 40]. A Samsung
  sensor-manager service (SSM — not present in the analyzed trees; loader role *inferred*)
  assembles the `SemSleepWatchSpO2SensorEvent` bundle (event type 0x31 = 49) carrying
  `secSpO2List`, `secTimeList`, `dcRatioList`, `accPowerList`, … [V keys; I loader]. The SHM
  watch APK (`com.samsung.android.shealthmonitor` 1.5.1.022) pulls it with `getData(start, end)`
  and feeds the apnea flow [V note 12].
- SHM fork [V note 12]: **V1** (`Apnea1Manager`) evaluates per-second values locally (values
  only); **V2** (`Apnea2Manager`) persists per-sample `SleepRaw(timestamp, spO2:B, dcRatio:S,
  accPower:S)` into Room and syncs raw sessions to a Samsung phone via ApneaSyncManager
  (transport below that not traced).
- The hub's own 4 Hz estimator (upstream of the wrapper) is **not present in any analyzed
  binary**; `lpd.core.bin` was extracted but not analyzed (hub phase closed "not-needed")
  [V notes 31, 40 §8; phase summary]. Whether it shares the libspo2 mapping cannot be determined
  from the analyzed material [I].

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
  but their internals are [N] (§1.4, §7.2). An adapter can implement the exact mapping/voting
  contract on top of its own pre-processing; it cannot guarantee stock-identical beat
  acceptance from this material.
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

**Structurally described but NOT reproducible bit-exactly from the notes** (all [N] items,
collected in §7.2): the adaptive filter bank (no update equations, step sizes, tap counts,
steering rule); z-score normalization (no constants); the motion metric (no formula, axes or
−4 threshold); beat detection (no detection rule or channel); the template/calmness machinery
(no window selection, resample method, threshold, template init/update); the FIR/IIR/Kaiser tap
values (not enumerated; IIR chain position unpinned); the internal PPG HR path (SDFT untraced).

---

## 2. Algorithm

### 2.1 On-demand mapping (`libspo2.so`, APK build ver01.2824) — step by step

Read this together with §1.4: steps 4c, 5, 6 and 11 are **structure-verified but not
reproducible bit-exactly**; the notes do not contain their internals.

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
   - **4b. FIR band-pass** [C structure; tap values [N]]: transposed direct-form FIR,
     **150 taps** (the `0x96` length argument is pinned). Two coefficient tables exist,
     `fir_filter_f_for_spo2` (0xa1d0) and `fir_filter_for_spo2` (0xa5a8), each spanning 151 f32
     slots to the next symbol — the 151st slot's role is [N] (possibly padding). **The actual
     tap values are not recorded in the notes** and the table↔channel assignment conflicts
     *within the source material*: note 20 says "one per channel", while note 21's call sites
     show `fir_filter_for_spo2` passed at **both** main-pipeline channel calls (0x13c2c/0x13c3e)
     and `fir_filter_f_for_spo2` passed to a `fir_bpf` call **inside the adaptive-bank
     function** (0x15920) — §7.2.
   - **4c. Adaptive notch, normalization, motion metric** [V structure only; internals [N]]:
     an accelerometer-steered adaptive band-stop (LMS-style recursion; center frequency clamped
     to bounds derived from accelerometer activity; bound table `an_init_bound_for_spo2`,
     24-or-25 slots — the consumer count is unaudited, so even the table indexing is uncertain);
     online z-score normalization (EMA mean/variance — no update rule or constants recorded);
     a motion-variance metric from a 50-sample accelerometer-magnitude ring with min/max clamps
     (no formula, axes or threshold recorded). Four parameter islands near init ({0.35, 0.0,
     8.5, 1.2}, {20000.0, 5e8, 0.3, 0.5}, {25.0, 0.9, 0.8, 0.8}, {0.8, 0.8, 0.5, −100.0}) are
     motion/HR-side parameters with unresolved roles. Note 21 explicitly did not audit this
     ~2.7 KB function — §7.2.
   - **4d. IIR band-pass** [tables C; position [N]]: 6th-order, coefficient tables confirmed at
     0xa198/0xa1b4 (§3.1). Its position in the per-sample chain is **not pinned** — no consuming
     call site is recorded in the notes; treat it as exported infrastructure, not a specified
     pipeline stage.
5. **Beat segmentation** (from sample 77) [C gates; detection rule [N]]: the filtered channels
   are pushed into 630-sample (8.4 s) beat buffers (index guards at 0x274); the filtered value
   is read at index `i + 0x99` (FIR group-delay compensation). Interval validation compares
   beat-to-beat distances: > **286** samples (≈ 3.8 s at 75 Hz) flags "no beat recently" (287
   being the strict side of ≤); a second gate pair at **210/211** (≈ 2.8 s) works the same way;
   a variability gate of **105** samples (≈ 1.4 s) applies to |Δinterval|. Which channel the
   detection runs on and the peak criterion are [N]. Waveform range-quality bits: flags 0x40 /
   0x20 from range checks against floats at file 0x3e8c–0x3e94 (values [N]), bit 0x10 NaN path,
   +0x80 when the comparisons pass; their effect on beat acceptance is untraced — §7.2.
6. **Artifact rejection** [C structure; internals [N]]: a window of the beat waveform (located
   via a 300-entry timestamp ring) is linearly detrended, min-shifted, max-normalized, resampled
   to 64 points via the library's `interpolate_array` helper (method not further specified) and
   Pearson-correlated against the running template (`calc_pearson_coef_and_index`; plain
   Pearson r); the coefficient is stored at state+0x14d8. `judge_result_flag` compares
   consecutive coefficients: more than 3 alternations beyond a threshold (mod-4 ring counter,
   `gStFlagJudgeParam`) → internal status −52; the motion metric over threshold → −4. The
   window boundaries inside the 630-sample buffer, the threshold values, the exact
   "alternation" definition, and the template's initialization/update rule are [N] — §7.2.
7. **Per accepted beat — ratio of ratios** [C]:
   - AC per side = the **sum of the two per-beat segments of that channel's FIR-filtered
     signal**, made absolute after summation: `AC_A = |M[0x00] + M[0x04]|` (IR),
     `AC_B = |M[0x08] + M[0x0c]|` (red). The segment boundaries within the 630-sample buffer
     are [N].
   - `DC_A = M[0x18]`, `DC_B = M[0x1c]`: the per-channel DC-filter (4a) state values; the
     sample at which they are read is [N].
   - Perfusion-like indices per side = `100·|AC|/DC` (exposed via `get_perfusion_index`).
   - `R = (AC_red / DC_red) / (AC_ir / DC_ir)` [C — proven channel order].
   - Accept iff `R ≤ limit`; limit = **1.84** on both `redLOffset` selections in this build
     (slot M+0x3c vs M+0x50 chosen by `redLOffset < threshold`). On reject, the internal R slot
     (M+0x74) and the internal DC-ratio slot (M+0x2c, which holds DC_B/DC_A on accept) are
     zeroed. On accept (caller's "beat valid" argument ≥ 1): M+0x74 = R, M+0x2c = DC_B/DC_A.
   - The **0.08 lower bound** is verified enforced for the secondary stream (out-of-bounds →
     zeroed at 0x14bd2); whether the beat path enforces `R ≥ 0.08` is [N] — §7.2.
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
9. **Two estimate streams, same polynomial** [C; second source only loosely defined]:
   - Stream 1's R source is the **per-beat slot M+0x74** (written per accepted beat, step 7);
     its results land at M+0x7c and are classified with pools 0x447c/0x4480 (94.0/90.0) into
     bands ≥94 / [90,94) / < 90.
   - Stream 2's R source is **M+0x98, "a ratio of two channel-statistic floats"** maintained in
     `.bss` tables (pool words point near 0x20fc0/0x224aa) — the notes contain no closer
     definition of those statistics [N]. It is bounded by the same constants (1.84 / 0.08,
     selected by the same redLOffset test) and **zeroed when out of bounds**. Its results land
     at M+0x9c and are classified with pools 0x4c2c/0x4c30 (94.0/90.0) into bands ≥94 /
     [90,94) / [70,90), with < 70 dropped.
   - Both streams are mapped by the same polynomial and the same coefficient sets (the two
     `FUN_00016760` call sites, flags 1 and 2). A dedup guard skips accumulation "when R is
     unchanged since the previous beat" — the exact comparison predicate (equality vs
     tolerance) and whether it also freezes the band counters are [N].
10. **Band voting** [C gates; two assignments [N]]:
    - The **reported** selector fires with gates: share(≥ 94) ≥ **5 %** → mean(≥ 94); else
      share(≥ 90) ≥ **30 %** → mean(hi+mid); else share(< 90) ≥ **60 %** → mean(lo); else **0**
      (no value). It writes the `get_spo2` slots of the results struct at 0x28D28 (+0x4 flag /
      +0x2c value). The share denominator is a total counter in that struct (+0x1c).
    - The **secondary** selector fires with gates **8 % / 30 % / 70 %**, else no valid flag
      (the getter then reports −1.0). It writes the `get_template_spo2` slots (+0x0 / +0x20).
    - [N] (a) which selector consumes which accumulator's counters: note 20 labels the reported
      selector "direct path" (its path 0 = the M+0x98 stream), while note 21's block layout
      places the reported selector immediately after the M+0x7c (per-beat) accumulator — the
      two readings conflict; see §7.2. The gates and the reported-value semantics are confirmed
      either way. (b) The firing schedule (when the vote re-evaluates as beats accumulate) is
      not pinned; the spo2 byte reported at COMPLETE is the mean from the most recent firing
      [D].
11. **HR fusion** [C rule; internal path not implementable]: the reported HR is the current
    `externalHr` sample when ≠ 0, otherwise the **latched** previous HR; when that value is
    < 1 and the sample count ≥ 2000 (i.e. after sample 1999), the internal PPG HR (latched into
    state+0xc whenever `get_hr()` produced > 0) is used instead, if ≥ 1; otherwise 0. Exactly
    what enters the latch (external samples only, or also the internal HR) is [N]. The internal
    PPG HR path (SDFT/beat machinery; only the acos→bpm multiplier −5.264523029 is pinned) is
    not specified in the notes — with `externalHr = 0` the stock HR ≥ 1 decision gate cannot be
    evaluated from this document (§1.4, §7.2).
12. **Output packing**: {status, spo2, hr, hr-source} byte-packed into the result struct;
    `funcr` spreads the 4 bytes into the Java `int[4]` (§4.1). The spo2 value is the
    char-truncation of the band-voted mean [C].
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
  attribute) is sent only when the version is ready; otherwise skipped.
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
  unregisterListener(listener, type). Attribute bundle: `Cmd` ∈ {FLUSH, GET_DATA, GET_VERSION,
  SET_OPERATION_MODE}; keys `mode` (value 1 or 2), `startTimestamp`/`endTimestamp` (longs),
  `versionType` (2 = CALCULATION_ALGO_VERSION, 4 = SIGNALCHECK_ALGO_VERSION); all delivered via
  setAttribute(0x31, bundle). Events arrive at `SemSensorListener.onEventChanged`; SHM gates on
  type 0x31 and reads the bundle keys of §4.3.
- **Producer-side semantics are [N]**: the relations among `secFullSize` / `secSize` /
  `secRemainCount`, samples per event and event ordering are service-side bookkeeping absent
  from the analyzed trees; SHM's data-flow handling of `data_type = VERSION` is not described
  (the VERSION/"SQC" query flow is a separate h8 path that falls back to `versionMap`); how a
  producer should fill `version` / `versionMap` / `version_type` and the unconsumed keys is
  unknown — §7.3.
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
Offsets below are **file offsets in `libspo2.so`** (APK build) unless stated; each was
byte-verified in note 21 §A [C].

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
| sample rate | 75.0 Hz | `.data` vaddr 0xe004 = file 0xd004 (`0x42960000`); the code also computes (75/60)·28 from it at 0x13d1a — quantity unexplained in the notes (§7.2) |
| DC filter α | 0.98 | file 0x3850 (`0x3f7ae148`) |
| FIR band-pass | 150 taps (length argument `0x96`, pinned); two 151-slot float tables `fir_filter_f_for_spo2` 0xa1d0 and `fir_filter_for_spo2` 0xa5a8 — 151st-slot role [N]; **tap values [N]** (not recorded in any note); table↔channel assignment conflicting (§2.1 step 4b, §7.2) | [C structure] |
| IIR band-pass (infrastructure) | b = [0.1044481, 0, −0.3133443, 0, 0.3133443, 0, −0.1044481] @0xa198; a = [1.0, −3.3464386, 4.5369792, −3.4548595, 1.7421415, −0.5275742, 0.0499856] @0xa1b4; chain position [N] | **corrected values** [C note 21 §C2] |
| adaptive-notch bounds | 24 floats (24th slot may be padding; consumer count unaudited): 0.9659258 0.9876884 0.9426415 0.9781476 0.9135454 0.9588197 0.8633956 0.9335804 0.7996846 0.9092361 0.7253744 0.8553643 0.6414496 0.7901550 0.5490228 0.7144727 0.4493190 0.6293204 0.3436597 0.5358268 0.2840154 0.4352311 0.3090170 0.9876884 @0xa804 | **corrected values** [C note 21 §C1] |
| Kaiser window table | 95 floats `compansate_kaiser_for_spo2` @0xa42c; only first/last recorded (0.9640617 / 0.99558); role consistent-by-layout, consuming instruction [N] | [C layout] |
| an2bpm constant | −5.264523029 @0x2a48 — the acos→bpm multiplier of `an2bpm_for_spo2` | [C] |
| timeout floats | 94.0 / 90.0 @0x7be4 / 0x7be8 — predicate vestigial (both branches accept a positive value; HR < 1 is what fails) | [C] |
| accelerometer scale | 0.0023950562 g/LSB = 9.81/4095.9375 | HTS Java side (smali word `0x3b1cf660`), not in the library; verified in the analyzed HTS APK copy only — not compared across models' APKs [C scope-limited] |
| window counts | warm-up 150; decision 3000; HR fallback after 1999; motion re-report 200; motion give-up 999; log cadence 100; accel ring 50; beat buffer 630; beat gates 286/287, 210/211, 105; template resample 64; FIR 150 taps | all [C] |

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
| 3 | hr-source / aux — role confirmed (which HR source was used); **its possible values are never enumerated** in the notes [N] (§7.2) |

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

- Event: `SemSleepWatchSpO2SensorEvent`, type **0x31 (49)**. Keys: `secSpO2List` (int[]),
  `secTimeList` (long[]), `dcRatioList` (short[]), `accPowerList` (short[]), `secFullSize`,
  `secRemainCount`, `secSize`, `timestamps` (long[]), `version` (int[]), `versionMap`
  (HashMap), `version_type`, `data_type` (enum NORMAL=0 / EMPTY=1 / VERSION=2), plus unconsumed
  `minSize`, `minFullSize`, `minRemainCount`, `minTimeList` (long[]), `maximumSpO2List` /
  `minimumSpO2List` (int[]).
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
- **Producer-side semantics are [N]** (§2.3): relations among `secFullSize` / `secSize` /
  `secRemainCount`, samples per event, event ordering, `data_type = VERSION` handling in the
  data flow, `version`/`versionMap`/`version_type` filling, and expectations for the unconsumed
  `min*` / `maximumSpO2List` / `minimumSpO2List` keys (their "per-minute aggregates" reading is
  an unverified guess) — §7.3.

### 4.4 `dcRatio` and `accPower` — status: not defined in any analyzed binary

- **No definition, no computation, no fixed-point scale, range or sentinel can be stated from
  the analyzed material.** In `lib_hmspo2wrap` they appear in no function, no bitfield, no
  constant (all 12 algorithm functions decompiled and traced; the library writes exactly the
  five outputs of §4.2 plus one scalar) [V note 40 §7]. The libspo2 pipeline's outputs (status /
  spo2 / hr / aux and the getter set) contain nothing by these names either [V notes 20/21].
- What is verified (consumer side) [V notes 12, 40 §6]: `dcRatioList` / `accPowerList` are
  `short[]`, one entry per second, parallel to `secSpO2List`; they exist **only** in the
  sleep-watch event (the continuous SpO2 event lacks them). SHM reads them with no bounds (only
  the batch min), stores `SleepRaw.dcRatio:S` / `SleepRaw.accPower:S`, persists via Room
  (Apnea2Manager) and ships them into vendor sync. They pass through as opaque shorts.
- Origin: upstream of both the SHM APK and the wrapper — the SSM service bundle builder and/or
  the sensor hub. Since the wrapper's input word carries only the 5-bit SpO2 code + 2 flag
  bits, dcRatio/accPower must travel in a different field or stream than the wrapper's sample
  array (plausibly the EHT entry payloads; the wrapper exports `decode_eht_entry`, which has
  no internal callers — grammar per note 41: entries with bit 31 set carry a kind in bits 24–27
  of the first word, kind 0 → type 1 / size 4, kinds 1 and 3 → payload-word count in bits
  16–23, type 2; otherwise the type-1 header is effectively two words, the payload-word count
  living in bits 24–31 of the *second* word) [C note 41].
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
   SSM version and the `fresh_hrm_dualized` (feature 0x23) answer [notes 20 §8, 12]. Until
   settled, it is unknown whether stock even runs the algorithm of §2.1 on this watch.
2. **Actual delivery rate of `SemSpo2ManualSensorEvent`** as configured by the sensor service —
   the library assumes 75 Hz; a different true rate rescales every time constant [note 20 §8].
3. **Whether the hub's 4 Hz overnight estimator shares the libspo2 mapping** — requires hub
   firmware (`lpd.core.bin`) analysis or device capture; not determinable from the analyzed
   userspace binaries [note 40 §5].
4. **dcRatio / accPower production and encoding** — producing component, scale/offset/sentinel
   of the shorts, and the stream they travel in; only a device capture of the SSM sleep-watch
   SpO2 event settles it [note 40 §7].
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

### 7.2 Binary-analysis leftovers ([N] items — each blocks bit-exact reproduction of the named step; resolvable by re-reading the byte-identical binaries, no device needed)

1. **Adaptive filter bank** (§2.1 step 4c): LMS update equation, step size(s), tap count, the
   rule steering the center frequency from the accelerometer and the bound-table indexing
   (including the 24-vs-25-slot consumer count of `an_init_bound_for_spo2`).
2. **Z-score normalization**: update rule and constants (§2.1 step 4c).
3. **Motion metric**: formula, which accelerometer axes, the 50-sample ring details, and the
   threshold that yields status −4 (§2.1 steps 4c, 5.1).
4. **`judge_result_flag` thresholds** (stack-passed floats) and the waveform range floats at
   file 0x3e8c–0x3e94; the effect of the 0x40/0x20/0x10/0x80 quality bits on beat acceptance.
5. **Beat detection**: which channel, what peak criterion; the segment boundaries of the two
   per-beat sums that feed AC_A / AC_B (§2.1 steps 5, 7).
6. **Template/calmness machinery**: window selection inside the 630-sample buffer (300-entry
   timestamp ring), detrend/resample method, the Pearson threshold, the exact "alternation"
   definition, and the running template's initialization/update rule (§2.1 step 6).
7. **Filter tables**: the 151+151 FIR tap values, the 151st-slot role, the table↔channel
   assignment (note 20's "one per channel" vs note 21's call-site evidence, §2.1 step 4b), the
   IIR's chain position, and the Kaiser table's consuming instruction + remaining 93 values.
8. **Stream↔source association** (§2.1 steps 9–10): which selector consumes which accumulator —
   note 20's labeling (reported selector = "direct" = the M+0x98 statistic stream) vs note 21's
   block locality (reported selector follows the M+0x7c per-beat accumulator). Also the actual
   definition of the M+0x98 "ratio of two channel-statistic floats". The reported-value gates
   are confirmed either way; the R source feeding them is not.
9. **Dedup guard**: exact comparison predicate (equality vs tolerance) and whether it freezes
   the band counters (§2.1 step 9).
10. **Vote firing schedule**: when the selectors re-evaluate as beats accumulate (§2.1 step 10).
11. **Beat-path R lower bound**: whether `R ≥ 0.08` is enforced on the per-beat stream
    (verified only for the M+0x98 stream) (§2.1 step 7).
12. **hr-source byte** (out[3]): enumeration of its possible values (§4.1).
13. **PPG HR path**: SDFT bin→bpm and the `an2bpm_for_spo2` caller chain — with `externalHr = 0`
    the HR ≥ 1 gate at sample 3000 cannot be evaluated as stock does (§2.1 step 11).
14. **External-HR latch**: exactly what enters the latch (external samples only, or also the
    internal HR) (§2.1 step 11).
15. **(75/60)·28**: what quantity the 0x13d1a computation represents (§3.1).
16. **ADC full-scale** of `irRaw` / `redRaw` — needed to judge whether an arbitrary source's
    counts share the constants' assumed scaling (§1.1).
17. **Independent confirmation of the remaining single-session datasheets**: the SHM smali
    claims (note 12 — the basis of the interface) and note 22's two-table constants (§0.3).
    (The wrapper datasheet, note 40, was subsequently confirmed by note 41 — §0.1.)

### 7.3 Design decisions this material cannot make (must be settled before implementation; none is answerable from the analyzed binaries alone)

1. **Injection contract** (which interface the SpO2Adapter must expose so that SHM's
   `getData(start, end)` + listener registration on type 0x31 is satisfied): the observable
   SHM-side surface is in §2.3; the answering side (the vendor sensor-manager service) is
   absent from every analyzed tree, and no note describes an injection point. Requires SSM-side
   analysis or device work plus an OpenSHM design decision.
2. **Per-second emission**: which value constitutes "the SpO2 of second t". The specified
   algorithm produces one band-voted mean per 40 s session (plus status updates); the stock
   per-second stream originates in the unrecoverable hub estimator (§1.3). Candidate policies
   (latest voted mean, per-beat estimates, re-running the mapping per second) are adapter
   designs with no stock counterpart in this material.
3. **V1 vs V2 target, and the dcRatio/accPower encoding**: V1 needs per-second values only;
   V2 additionally requires two shorts per second that the adapter must invent (non-stock by
   construction, §4.4). Owner/design decision.
4. **Resampling method** for feeding a ~100 Hz PPG_ON_DEMAND stream into the 75 Hz assumption
   (filter, interpolation, target-rate handling) — §1.3.
5. **Producer-side bundle filling**: `secSize`/`secFullSize`/`secRemainCount` values and their
   relations, samples per event, event ordering, `version`/`versionMap`/`version_type`, the
   unconsumed keys, and `data_type` usage — constrained by §2.3/§4.3 and settleable only
   against a device (§7.1) plus this design decision.
