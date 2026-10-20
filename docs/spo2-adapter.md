# SpO2Adapter — sleep (apnea) SpO2 on a re-signed watch SHM

`shminterop.spo2.SpO2Adapter` answers every use of the sleep SpO2 sensor (type 0x31 = 49,
`SLEEP_WATCH_SPO2`) in the patched watch SHM. A re-signed SHM cannot use that sensor through
`SemSensorManager` (privileged), so the patch (`watch-spo2-sensor-adapter`, see
[patches.md](patches.md) row 7e) intercepts the calls in the sensor wrapper `sa2` and the adapter
answers them from raw red/IR PPG delivered by the Health Tracking Service (our clean-room client
`shminterop.hsdk`, [health-tracking-protocol.md](health-tracking-protocol.md) 4.4). This document
records the decisions that [spo2-calibration.md](spo2-calibration.md) 7.3 left open.

## 1. What is intercepted

| Wrapper call | Adapter entry | Behaviour |
|---|---|---|
| `sa2.c` registerListener | `register(listener, 49)` | remember the listener, return true |
| `sa2.d` setAttribute | `setAttribute(listener, 49, attribute)` | parse the command, always return true |
| `sa2.e` / `WatchOverrides.unregisterListener` | `unregister(listener, 49)` | forget the listener; if it is the recording listener, stop the PPG tracker |

`sa2.a` (isAvailable) stays forced to true by `WatchOverrides.sensorAvailability`; `sa2.b`
(isFeatureSupported) has a result that is only logged.

The command is read from `attribute.getAttribute(49)`: a Bundle with `cmd` (an `EnumSet` of the
vendor `Cmd` enum, read by `name()`), `startTimestamp` / `endTimestamp` (epoch ms, GET_DATA),
`versionType` (GET_VERSION) and `mode` (SET_OPERATION_MODE, an `OperationMode` enum). The
adapter never throws and returns true for unknown commands too. Events are always delivered to
the listener passed to that `setAttribute` call, asynchronously on a daemon thread. The vendor
enums are looked up by class and constant name through reflection.

## 2. Decisions (spec 7.3)

1. **Injection contract**: the sa2 hooks above; events are `SemSleepWatchSpO2SensorEvent(49, Bundle)`.
2. **Per-second policy**: the windowed ratio-of-ratios of [spo2-calibration.md](spo2-calibration.md)
   2.1, re-run at every wall-clock second over the trailing 8 s (see 3).
3. **V1 only.** `versionMap` holds key 1 only (never key 2), so SHM selects the V1 evaluator, which
   needs per-second values only. `dcRatioList` / `accPowerList` are sent as `short[]` zeros of the
   chunk length (V1 ignores them, V2 null-checks them); their real encoding is undefined
   (spo2-calibration 4.4), so no values are invented.
4. **No resampling.** The estimator works in wall-clock seconds on the HTS ~100 Hz stream; window and
   filter lengths are counted in samples at a nominal 100 Hz. R is a ratio of two channels filtered
   identically, so a small deviation of the true rate only stretches the window slightly.
5. **Bundle filling** (the rules the SHM consumers `ua`, `yb`/`zb`, `ac` enforce):
   - VERSION: `data_type` = `DataType.VERSION`, `version_type` = the requested `VersionType`,
     `version` = `int[3]` (calculation 1.11.1, signal check 1.0.0), `versionMap` = `{1: version}`.
   - NORMAL: `secFullSize` = size of the whole answer, `secSize` = chunk length, `secRemainCount` =
     entries still to come after this chunk, `secSpO2List` (int[]), `secTimeList` (long[] epoch ms),
     `dcRatioList` / `accPowerList` zeros. The consumer completes when the accumulated count equals
     `secFullSize` exactly.
   - EMPTY: `data_type` only, for an empty or invalid window (`end <= start`).
   - GET_DATA is a dense 1 Hz grid, one entry per second from floor(start) to floor(end), both
     included, value 0 (invalid second) where nothing was recorded, at most 86400 entries
     (start-anchored), in chunks of at most 600 entries delivered sequentially, the first at once
     (SHM waits 5 s for the first event). A VERSION event is never sent to the monitoring listener.
   - The V1 evaluation needs at least 3600 per-second values (1 h) or the night is ignored, and at
     least 10800 for a sufficient result. Values are 0 (invalid) or 70..100.

## 3. Estimator

`shminterop.spo2.Spo2Estimator` (pure Java, streaming) per sample (timestamp, red, IR, ok):

- DC = trailing mean of the last round(1.5 fs) samples per channel; AC = (x - DC) smoothed by a
  9-sample trailing mean.
- At every whole-second boundary of the sample timestamps, over the trailing 8 s window:
  R = (std(AC_red) / mean(red)) / (std(AC_ir) / mean(ir)).
- Gates (a failure yields 0): all samples of the window have both channel statuses normal;
  Pearson(AC_red, AC_ir) >= 0.7; IR perfusion 100 std(AC_ir) / mean(ir) within [0.02, 20];
  R within [0.08, 1.84].
- SpO2 = (int) clamp(109.6912003 - 20.3647995 R - 2.1715099812 R^2, 70, 100).
- Warm-up: nothing is emitted until the 8 s window is full. A gap of more than 2 s resets the
  filters. fs is a fixed nominal 100 (samples per second), output is bucketed by the wall-clock
  second of the timestamps (the reference script emits every round(fs) samples instead; the two
  differ only in which window ends where).

It reproduces the reference `per_second` of the validation script. On the on-wrist session
`s173708` both give a median of 98 % (stock one-shot result: 97 %).

### Channel orientation (`SWAP_RED_IR = true`)

On the owner's SM-R920 (ADI sensor path, see spo2-calibration 7.1) the channel the Health Tracking
Service labels IR behaves as red and vice versa. As labelled, R is about 1.75 at a stock-measured
97 %, a value no physiology explains; swapped, R is about 0.52, which maps to 98 %. Evidence: the
on-wrist dataset of 20 s probes (`GREEN,RED,IR` at ~100 Hz, each followed by the stock one-shot
result). The adapter therefore feeds the labelled-IR channel as red and the labelled-red channel as
IR, behind the named constant `SpO2Adapter.SWAP_RED_IR`. The median R is logged once a minute
(tag `SpO2Adapter`, `stats:` lines, which also print the current `swap` value) so a wrong
orientation is visible in logcat.

`SWAP_RED_IR` is only the default. Each recording checks it on its own: the adapter collects the
ratio R of the first 120 seconds for which the estimator reports a finite value (also seconds
that failed a gate and got 0). If the median is above 1.2 the orientation is wrong (a wrong
orientation gives about 1 / R_true, 1.75 against 0.52 on the SM-R920; 1.2 would be about 82 %
SpO2 sustained at rest, which is not physiological), so the adapter flips it, starts a fresh
estimator and logs `orientation: median R <x> over 120 s, swapping red/IR (swap=<new>)`. The
choice is written to `<filesDir>/shminterop-spo2/orientation` (`swap=true` or `swap=false`) and is
the starting orientation of later nights (`SWAP_RED_IR` when the file is missing or unreadable).
The decision is taken at most once per recording; with a median of 1.2 or less it logs
`orientation: median R <x>, keeping swap=<cur>` and writes nothing. Seconds already stored are not
rewritten.

## 4. Recording

SET_OPERATION_MODE starts a PPG tracker (`PPG_ON_DEMAND`, channels `RED` and `IR` only) tied to the
calling listener and feeds the estimator; the per-second values go to an in-memory store. A
watchdog (5 s tick) re-arms the tracker if no batch arrived for 15 s while recording. The store is
appended to `<filesDir>/shminterop-spo2/night.bin` (fixed 9-byte records: big-endian long second
in epoch ms + one value byte, no header), flushed at least every 60 s and on stop, so a process
restart mid-night does not lose the night. Unregistering the recording listener stops the tracker
but keeps the data for the GET_DATA that follows.

A new SET_OPERATION_MODE clears the store and the file, with one refinement: if the newest stored
second is less than 6 hours old (`RESUME_MS`) the night is resumed instead. GET_DATA only returns
seconds inside the requested window, so keeping older seconds never pollutes an answer; clearing
only bounds the file, and a new night always starts more than 6 h after the last recorded second
of the previous one. A brief awakening (AWAKE, GET_DATA, unregister, then SLEEPING again) therefore
keeps the earlier part of the night. Do not run it together with the BP flow: both use the same
`PPG_ON_DEMAND` tracker of the service.

Battery: the red and IR LEDs run at ~100 Hz all night while recording.

### Wake lock

While recording the adapter holds a `PARTIAL_WAKE_LOCK` (tag `shminterop:spo2`, not reference
counted, 12 h timeout as a safety net), acquired in `startRecording` and released on stop. The
watchdog executor needs the CPU awake during Doze. The SHM twin already holds `WAKE_LOCK`. A
failure to get the lock is logged, never thrown.

### Auto-resume after a process restart

On start the adapter writes the marker file `<filesDir>/shminterop-spo2/recording`; it is deleted
when the recording stops. In a new process, on the first entry (`register` or `setAttribute`), if
the marker exists and the newest stored second is less than 30 minutes old (`AUTO_RESUME_MS`), the
adapter records again without a listener and logs `start: auto-resuming after a process restart`.
A stale marker is deleted; with no marker nothing is resumed. The next listener that calls
`register` adopts the recorder role (its `unregister` then stops the recording). A listener that
sends GET_VERSION gives the adopted role up again, so the version detector SHM runs at app open
(register, GET_VERSION, unregister) does not stop a resumed night. SET_OPERATION_MODE ties the
recording to its sender as usual, and GET_DATA is answered normally. A GET_DATA while no listener
owns the recording (the restarted process's first call is the morning request) also stops it, as
nothing would unregister it otherwise.

### Fallback start

SHM's sleep start (`cc.start()`) registers a listener and then sends SET_OPERATION_MODE, but skips
the latter when its stored apnea version is not V1/V2 ("Skipping operation mode: final apnea
version not ready"). So 5 s (`FALLBACK_START_MS`) after each `register` a one-shot check runs: if
that listener is still registered, nothing is recording and the listener has sent neither
SET_OPERATION_MODE, GET_VERSION nor GET_DATA since registering, the adapter starts recording tied
to it and logs `start: no SET_OPERATION_MODE <n> s after register; recording anyway`. The version
detector (GET_VERSION within about 2 s, then unregister) never triggers it.

### GET_DATA coverage log

The answer is the dense 1 Hz grid of the window with 0 for every second without a valid value;
holes are never filled, because SHM itself treats 0 as an invalid second and its 70 % threshold
is what guards the result against an unreliable night. The log line reports the share of non-zero
seconds, e.g. `GET_DATA a..b -> 3 event(s), coverage 64.2% (SHM needs 70%)`, so a rejected night
can be told apart from a recording that did not run.

## 5. Simulation mode (end-to-end test without sleeping)

Set the system property `debug.shminterop.spo2_sim` to `<hours>[:<eventsPerHour>]`, for example
`adb shell setprop debug.shminterop.spo2_sim 5:20`. The property is read (through `getprop`) at
every GET_DATA; while it is set, the answer ignores the recorded data and the requested start and
is a deterministic synthetic night (`SyntheticNight`, fixed seed) of hours x 3600 seconds ending at
`endTimestamp`: baseline 96-97 % with small noise and `eventsPerHour` (default 10) desaturation
events per hour, each a drop of 4-8 points over 15-25 s and a recovery over 10-15 s, clamped to
70..100. Every use is logged as `SIMULATION`. Recording keeps running normally. Clear it with
`adb shell setprop debug.shminterop.spo2_sim ""`.

## 6. Test-only sleep/wake driver

Samsung's Health Services build has no synthetic sleep, and the apnea service of SHM is guarded by
a signature permission, so adb cannot start it; the SHM process itself can (same uid).
`ApneaTestDriver` is started on the first adapter entry call, or on the first sensor-availability
check (`WatchOverrides.sensorAvailability`, which SHM runs at start-up). If the system property
`debug.shminterop.apnea_drive` is empty at that moment nothing happens at all (no thread). Otherwise
a daemon thread polls it every 3 s and on each CHANGE of the value (the first value read counts):

- `sleep` sends sleep state SLEEPING (0), `wake` sends AWAKE (1), anything else is ignored (logged).
- Sending is what the app's own forwarder does (smali `c72`): an `Intent` for the class
  `com.samsung.android.shealthmonitor.apnea.control.ApneaMonitoringService` (loaded by name through
  the application class loader), action `com.samsung.android.shealthmonitor.apnea.whs.activity`,
  int extra `sleep_state`, long extra `sleep_time`, `startForegroundService`.
- `sleep_time` is now, except for SLEEPING while the simulation of section 5 is on: then it is now
  minus the simulated night's length, so the sleep window matches the synthetic data. The apnea
  library ignores sleep shorter than 1 h.

Set the property before the SHM process starts, e.g.
`adb shell setprop debug.shminterop.apnea_drive sleep`, later `... wake`. The same value twice does
nothing; set another value in between to repeat a transition. Test-only: inert unless the
property is set.

Things that get in the way of a run:

- Android refuses `startForegroundService` from a background app
  (`ForegroundServiceStartNotAllowedException` in the driver's log), so keep SHM open on the watch
  when the property changes.
- Health Services re-delivers its last real sleep state when SHM's listener reconnects; a stale
  AWAKE can arrive right after the driver's SLEEPING.
- After a night is evaluated the library closes the day: it asks for a 7.5 h timer
  (`millisecTimeToRequest = 27000000, ignoreNextSleepDetection = true`) and logs
  `Apnea1Manager not yet` for every sleep onset until it expires. A second run the same day needs
  that wait.

## 7. Tests

`watch-mod/test/run-tests.sh` covers the estimator (known R, every gate,
bucketing, gap), the synthetic night, the answer chunking and the store, the
adapter flow with doubles (including the 6 h resume, auto-resume, fallback start, orientation
check, wake lock and coverage), the driver
logic and the red/IR parsing. The host tool `shminterop.spo2.Spo2CsvCheck <probe.csv>` runs the
estimator with the adapter's orientation over a probe CSV, to compare with
`analyze.py --swap`.
