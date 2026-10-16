# IHRN adapter (irregular heart rhythm on a re-signed watch app)

`IhrnAdapter` (`watch-mod/src/shminterop/ihrn/`) answers the irregular heart rhythm (IHRN) sensor
of the modified Samsung Health Monitor (SHM) watch app, 1.5.1.022. The stock app reads it as
`SemSensorManager` type 0x25 (37, `TYPE_WEAR_PPG_IHRN`), which is privileged: a re-signed user app
gets "NO SSENSOR permission 37" from `registerListener`, and SHM's watchdog cancels every sampling.
The adapter feeds SHM's unchanged IHRN pipeline from the public Health Tracking Service (HTS)
instead. Patches: `watch-ihrn-sensor-adapter` and `watch-ihrn-quiet-services`, see
[patches.md](patches.md). The HTS client is described in
[health-tracking-protocol.md](health-tracking-protocol.md) and [health-sdk.md](health-sdk.md).

The adapter never invents data. Samples it cannot trust are delivered marked as compromised, so
SHM's own analysis asks for a new window; SHM's thresholds and results are not touched (the alert
still needs 5 irregular windows in 24 h, the stock rule).

## SHM side of the contract (read from the decoded 1.5.1.022 smali)

| Step | SHM code | What the adapter does |
|---|---|---|
| start | `rm0.b()`: `sa2.c(listener, 0x25)`, one-shot 5 s watchdog (`n8`, cancelled by the first event of any kind), then `sa2.d` with `BatchMode.ONE` | `register` starts a session and both HTS trackers; mode is single-sample |
| single samples | `qm0.onEventChanged`: `count == 1` while not in batch mode; `hn0` takes the first stable sample (`wrist && !motion`) and calls `rm0.a()` | one event per paired sample, `count` 1, `data_sequence` -1 (not read in this path) |
| batch switch | `rm0.a()`: `sa2.d(listener, 0x25, attribute with BatchMode.MAX)` | `setAttribute` with `batch_mode = MAX` switches to batch mode |
| batches | `count == 250` only; any other count is counted and at 4500 SHM aborts. `kn0` requires `data_sequence` of consecutive events to be +1 (a first event of -1 means "not provided"); 1500 samples (6 events) feed the native analysis | events of exactly 250 samples, `data_sequence` 0, 1, 2 ... per session |
| stop | `rm0.c()`: `sa2.e` = `SemSensorManager.unregisterListener`, rewritten by `watch-sensor-unregister-compat` to `WatchOverrides.unregisterListener` | routes type 0x25 to `IhrnAdapter.unregister`: stops the session and both trackers, drops pending samples |

`SemPpgIhrnSensorEvent(int type, Bundle)` is Bundle-backed. Keys and types (getters in the decoded
class) and what the adapter writes:

| Key | Type | Value |
|---|---|---|
| `event_type` | Serializable enum `SemPpgIhrnSensorParam$EventType` | `NORMAL` (anything else is "invalid type" in `qm0`) |
| `count` | int | 1 or 250 |
| `timestamp` | long | epoch ms of the first sample |
| `systick`, `systick_array` | long, long[] | epoch ms of the first sample, epoch ms of each sample |
| `led_green` | long[] | converted green value (below) |
| `acc_x`, `acc_y`, `acc_z` | int[] | raw accelerometer counts; SHM multiplies by 0.0023950561 (= 9.81 / 4096) |
| `agc` | boolean[] | compromised-sample flag (below) |
| `ecg_mode` | boolean[] | false |
| `wrist`, `motion` | boolean[] | see below |
| `mode` | int[] | zeros |
| `data_sequence` | int | -1 for single events; per session counter for batch events |
| `accuracy` | Serializable enum `SemPpgIhrnSensorParam$Accuracy` | `NORMAL` |

The enums are resolved by reflection (`Class.forName`), like in `SpO2Adapter`. The event
constructor is covered by the existing `hidden_api_blacklist_exemptions` grant; a `LinkageError`
is logged once.

The native analysis gates that matter here: the sample period is 40 ms (25 Hz); `agc` true on any
sample of a window makes it return "resample"; `led_green` outside [25000, 4106923] is masked as
saturated; a window is stable when `wrist && !motion`, and a non-stable first sample means
resample/retry.

## HTS side

Trackers `PPG_GREEN` and `ACCELEROMETER` are registered together on one connection
(`GreenAccelTracker`, own entries per appId and tracker type, distinct from `PPG_ON_DEMAND` of
the BP and SpO2 adapters; stopping one never unsets the other). Both read the same sensor-hub
stream (Sem type 26, 40 ms period), so green and accelerometer samples of one hub event carry
identical epoch-ms timestamps. `PPG_GREEN` values: green int, status int (0 = normal, -999 =
error, 500 = accuracy unsupported). `ACCELEROMETER` values: x, y, z raw ints. The hub batches up
to 300 samples, so the adapter sends `flush` (binder code 4, allowed for both types) every 1 s
while a session runs; this beats SHM's 5 s watchdog.

Warm-up: on the SM-R920 the first `PPG_GREEN` sample of a session (about 1 s after register)
carries a non-normal status and the mid-scale value 2^21 (2097152). Measured on the wrist on
2026-10-15: `single: green=2097152 wrist=false`, then SHM logged "not stable" and skipped the
window, on every run. SHM judges stability from the first single event only, while the vendor
hub does not report that warm-up sample as a measurement. So in single-sample mode the adapter does not deliver off-wrist
samples until the first on-wrist one, for at most `WARMUP_MAX_MS` (3 s) after register; after
that, samples go out as they are, so a watch that is really off the wrist still reports
`wrist = false`. Batch mode is not affected.

## Conversions

| Field | Rule |
|---|---|
| `led_green` | `GREEN_SIGN * green + GREEN_OFFSET`, with sign +1 and offset 0 (identity). Device probe on the SM-R920 (on wrist): 2,065,899 to 2,408,311, positive, inside the native range |
| `acc_*` | pass-through counts (4096 counts per g measured: median magnitude 4095) |
| `wrist` | green status == 0 |
| `motion` | standard deviation of the accelerometer magnitude over the trailing 25 samples above `MOTION_STD_COUNTS` (300 counts). A still wrist measured 50 to 200 counts, normal arm movement 250 to 1500. Needs at least 5 samples. Marking motion only skips the analysis of that window |
| `agc` | the compromised-sample flag, see below |

## Compromised-window lever

`agc = true` is the only lever on a window: SHM's native analysis returns "resample" for it. It
is set on a sample when:

1. the timestamp gap to the previous sample is above 3 periods (120 ms; the hub timestamps jitter
   between 16 and 74 ms on the SM-R920): dropped samples. Late arrival is not a gap: after a
   stall (screen off or Doze) the hub delivers one burst of up to 300 samples, which is forwarded
   in order with the original timestamps;
2. no accelerometer sample of the same timestamp arrived within 2 s (pairing failed; the green
   sample then goes out with the last accelerometer values seen);
3. the green value changed by more than 25 % (`LEVEL_STEP_RATIO`) from the previous valid
   sample, which looks like a gain change rather than signal;
4. the green status is not 0, which also sets `wrist = false`.

Green samples with a repeated or older timestamp are dropped, not reused.

## Session and threading

Each `register` creates a session with its own single delivery thread; the HTS callbacks (binder
threads) only enqueue onto it. A session that is no longer the current one (replaced by a new
`register`, or stopped) delivers nothing, so late callbacks of an old session never reach the
next. Switching from batch back to single mode drops a half-filled batch. A debug line per
250-sample window logs the green min/median/max, the median accelerometer magnitude, the
effective rate, the gaps and the number of compromised samples (tag `ShmInterop`).

## Open device questions

Answered by the first device probe (SM-R920, 60 s, on wrist): green range and sign, accelerometer
scale, rate (25 Hz, median 40 ms), first delivery about 1.1 s after registration, identical
timestamps of green and accelerometer, status always 0 on the wrist, one 12 s stall with the
screen off. Still open:

- Whether the type-26 green stream has the same optical front-end tuning as the IHRN input of the
  native analysis (amplitude and DC level of the pulse), so that windows are not rejected for
  signal quality. The first on-wrist run should compare the per-window log lines with SHM's
  "stable" decisions.
- Behaviour with the screen off for a whole window (late bursts) and across Doze.
- Coexistence of the PPG_GREEN/ACCELEROMETER registration with `PPG_ON_DEMAND` (BP and SpO2
  adapters) on the same optical front-end when two measurements overlap.
- Whether the service ever refuses `flush` for these types on other models.
- A real alert on the device. It cannot be forced without an irregular rhythm; the launch path is
  covered by the grant described in "Alert screen" below.

## Alert screen

The batch path of SHM's IHRN alert (`ym0`) starts `IhrnAlertActivity` with a plain
`Context.startActivity` (no notification, no full-screen intent), from a background service. The
vendor build is exempt from the background activity launch block through
`START_ACTIVITIES_FROM_BACKGROUND`, which is privileged and not granted to a re-signed app.
Pattern `watch-ihrn-alert-overlay` declares `SYSTEM_ALERT_WINDOW`, and the `shm-watch` output
grants it as an app-op (`appops set <pkg> SYSTEM_ALERT_WINDOW allow`). That is the same background
activity launch exemption the bridge uses for its alarm and call screens on the watch.

## Quiet services

SHM starts `IhrnServiceActivity` and `BodyOffDetectServiceActivity` (both are `Service`s) with
`startForegroundService` from three places (`tm0`, `p00`, `IhrnAlarmReceiver`), and each posts a
notification on channel `shealthmonitor.ihrn.notification.channel` in `onCreate`. Pattern
`watch-ihrn-quiet-services` starts them with `WatchOverrides.startService` (plain `startService`;
on `IllegalStateException` it falls back to `startForegroundService` and remembers that per
class) and makes `onCreate` call `startForeground` only in the fallback case. With the new
`deviceIdleWhitelist` grant on `shm-watch` the plain start is allowed, so no notification is
shown; in the fallback the channel importance is low (2) instead of high (4). An existing channel
keeps its saved importance unless the new value is lower.
