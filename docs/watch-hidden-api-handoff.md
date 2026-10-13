# Hand-off: watch SHM sensor calls denied by hidden-API enforcement (Galaxy Watch7/Watch8, Android 16)

Status: diagnosed from one user log; **implemented in code (catalogue serial 28, `minAppVersion` 27: older patcher builds cannot parse `globalSetting`), pending the
on-device verification of §5**. The `globalSetting` grant (Option A), the BP attribute guard
(pattern `watch-bp-attribute-guard`, catalogue row 7c), the one-time `EcgAdapter` hidden-API log
line and the corrected Watch8 explanation are all in place; whether Samsung's One UI 8 Watch
build honours the exemption, and whether the sensor service then accepts the BP/apnea
registrations, is still open (§8). This document is self-contained: it is meant for an
agent/model that picks the work up without the original conversation.

## 1. Report

A Galaxy Watch7 user (watch `SM-L300`, Android 16 / One UI 8 Watch, `oneUiVersion=80000`,
`sdk_version=36`) on patcher **0.3.3**, catalogue serial **25**, watch SHM twin
`com.samsung.android.shealthmonitors` (patched SHM 1.5.1.022) reports three problems:

1. **ECG**: the measurement screen shows *"Can't open the app, restart the app or reboot your
   watch and try again"*.
2. **Blood pressure**: calibration starts, stops after a few seconds, and says *"follow the
   instructions in Samsung Health Monitor to calibrate your watch"*.
3. **Sleep apnea**: shows the "not supported" screen, which the user reads as a region lock.

The exported log (patcher "device logs", `openshm-logs-20261013-183613.txt`) has the watch logcat
only; the phone section is empty (`not connected`). The log is not in the repo (it contains a
device id); ask the owner for it if needed.

## 2. Root cause (one cause for all three)

Every call the watch twin makes into Samsung's sensor framework classes is **denied by ART
hidden-API enforcement**. ART logs it explicitly, and then the call surfaces in Java as
`NoSuchMethodError`:

```
E shealthmonitors: hiddenapi: Accessing hidden method
  Lcom/samsung/android/hardware/sensormanager/SemSensorManager;->registerListener(Lcom/samsung/android/hardware/sensormanager/SemSensorListener;I)I
  (runtime_flags=0, domain=platform, api=blocked) from /data/app/.../com.samsung.android.shealthmonitors-.../base.apk!classes2.dex (domain=app) using linking: denied
```

Denied members seen in the log (count = occurrences in ~40 minutes):

| Count | Member | Caller |
|------:|--------|--------|
| 69 | `SemEcgSensorEvent.<init>(ILandroid/os/Bundle;)V` | **our** `EcgAdapter` (watch-mod) |
| 36 | `SemSensorManager.isAvailable(I)Z` | vendor sensor wrapper |
| 32 | `SemSensorManager.registerListener(SemSensorListener, I)I` | vendor (apnea SpO2 probe, BP) |
| 16 | `SemSensorManager.isFeatureSupported(I, String)Z` | vendor (apnea DC_RATIO check) |
| 3 crashes | `SemHrSensorAttribute.<init>()V` | vendor BP calibration (`sl.g`) |

Why it happens:

- On Android 16 / One UI 8 Watch these `com.samsung.android.hardware.sensormanager` members are on
  the **blocked** hidden-API list (`api=blocked`), not on an allowed/unsupported list.
- ART exempts an app from enforcement only if it is platform-signed, or a system/updated-system
  app that is allow-listed (`usesNonSdkApi` / sysconfig `hidden-api-whitelist`). The stock SHM is a
  preloaded system app on these watches, so it is exempt. **Our twin is a renamed, re-signed
  `/data/app` user app, so it is not.** (Older watch frameworks evidently did not block these
  members, which is why the same build works elsewhere.)
- Reflection does not help: hidden members are also filtered out of `getMethods()`. That is why our
  own diagnostic line reads `ShmInterop: sensor: unregisterListener overload = none`.

### How each symptom follows

**ECG** — `EcgAdapter.start` connects to the Health Tracking service fine (`EcgAdapter: tracker
armed`), but every repackaged batch fails when it builds the event:
`EcgAdapter: deliver failed: java.lang.NoSuchMethodError: No direct method <init>(ILandroid/os/Bundle;)V in class ...SemEcgSensorEvent`.
No event reaches the SHM pipeline, its 1 s watchdog expires (`EcgSensorImpl: sensor watch dog is
expired` → `SENSOR_NOT_WORKING`) and SHM shows `EcgInvalidSensorErrorFragment` = the "Can't open
the app…" dialog. Note the keepalive synthetic events fail the same way (they use the same
constructor).

**Blood pressure** — calibration (`BpCalibrationActivity`) builds a `SemHrSensorAttribute` when the
intro overlay animation ends (`MeasurementIntroOverlayView.fadeOutAndRemove` → `mk` → `lk` →
`tk.f` → `sl.g`). The constructor is denied, the `NoSuchMethodError` is not caught and **the
process dies** (`FATAL EXCEPTION: main`, 3 times: 18:00:11, 18:00:49, 18:02:55). The activity
restarts in a fresh process and shows the generic "calibrate your watch" message. BP calibration
has never succeeded on this watch (`BpUtil: calibrationSuccessTime : 0`).

**Sleep apnea** — not the regional check (our `watch-apnea-country` patch is in place and the
menu is visible: `ApneaHomeSelector: Apnea menu visible for device capability: VERSION_1_AND_2`).
The SpO2 sensor version probe fails:
`ApneaSensorVersionDetector: Exception during version request: java.lang.NoSuchMethodError: No virtual method registerListener(...)` →
`ApneaVersionManager: refreshSensorVersion: detected NOT_SUPPORTED` → SHM opens
`ApneaNotSupportGuideActivity`, which the user perceives as a region lock. Our availability
exemption for type 0x31 (`WatchOverrides.sensorAvailability`) only hides the failed
`isAvailable`; the subsequent `registerListener` is denied too. (Secondary: `Phone runtime is
UNKNOWN` also appears because the phone was not reachable in this capture; re-check once the
sensor side works.)

Other side effects of the same cause: `SemSensorManager.unregisterListener` cannot be resolved, so
`WatchOverrides.unregisterListener` is a no-op (listeners are never unregistered; harmless now
because registration is denied too). IHRN (0x25) and the SpO2 availability checks are all denied.

## 3. Does it apply to the Galaxy Watch8?

**Almost certainly yes.** The Watch8 ships with the same Android 16 / One UI 8 Watch framework.
The earlier Watch8 report (crash on open with
`NoSuchMethodError: SemSensorManager.unregisterListener(SemSensorListener,int)V`, fixed in
catalogue serial 13 by `watch-sensor-unregister-compat`) was diagnosed as "the overload is missing
from the framework". In light of this log it was very likely **the same hidden-API denial**, not a
missing overload: `NoSuchMethodError` is exactly how a denied hidden member surfaces, and the
reflection fallback finds nothing for the same reason. That fix only stopped the crash; on Watch8
ECG, BP and apnea should fail exactly like here. Every watch updated to One UI 8 Watch (Watch7,
Ultra, probably Watch6/5/4 when they get it) is expected to be affected.

To confirm on a Watch8, ask for a log and look for `hiddenapi: Accessing hidden method
...sensormanager... denied` lines.

The class comment of `WatchOverrides` ("That overload is missing from some watch frameworks
(Galaxy Watch8)") and the related row in `docs/patches.md` (7b) should be corrected once the fix
is confirmed.

## 4. Fix options

### Option A (recommended first): hidden-API exemption via a global setting, applied by the patcher over ADB

Android honours two `Settings.Global` keys, writable by the `shell` user (which the patcher's ADB
session is), and applied by zygote to processes started afterwards:

- `hidden_api_blacklist_exemptions` — a list of member-signature **prefixes** exempt from
  enforcement for all apps. Scoped value for us:
  `Lcom/samsung/android/hardware/sensormanager/`
  (covers `SemSensorManager`, `SemEcgSensorEvent`, `SemHrSensorAttribute`, `SemSensorListener`…).
  Multiple prefixes are separated by `,` (the framework splits on commas); the existing value must
  be preserved and appended to, not overwritten.
- `hidden_api_policy` — global enforcement policy (`1` = allow + warn). Broader; only a fallback if
  the exemption list does not work on Samsung's build.

Why this first: one change fixes ECG, BP and apnea (and makes the unregister reflection work
again), needs no smali changes, no vendor code, and the patcher already has an ADB shell to the
watch. It is reversible (`settings delete global hidden_api_blacklist_exemptions`) and persists
across reboots.

Risks / unknowns (must be verified on a device, see §5):
- Samsung could ignore or reset these keys on user builds. Not known yet.
- It is device-wide (all apps get access to that package's hidden members). The prefix keeps the
  scope to the Samsung sensor-manager package; mention it in the UI/docs text.
- A watch reset / Samsung update might clear it; the patcher's self-test should read it back and
  offer to re-apply.
- The SHM process must be restarted after setting it (`am force-stop
  com.samsung.android.shealthmonitors`), since exemptions are applied at process start.
- Even with access granted, the sensor service itself may still reject a non-system caller for
  some types (we saw this for ECG type 27 on an older watch — "SSENSOR" rejection, `registerListener`
  returned -1 — which is why `EcgAdapter` exists). ECG is unaffected (it goes through Health
  Tracking and only needs the event constructor); BP (HR/PPG attribute) and apnea (SpO2 0x31)
  may still fail at the service level. If they do, that is a new, separate problem.

### Option B: in-process exemption from our own code

Call `dalvik.system.VMRuntime.setHiddenApiExemptions(["Lcom/samsung/android/hardware/sensormanager/"])`
from the twin's `Application.onCreate` via a hook. Direct and meta-reflection paths are closed
since Android 11; the known working approach (as in the open-source "AndroidHiddenApiBypass"
library) relies on `sun.misc.Unsafe`/`MethodHandle` internals and may break on any Android
release. Only consider it if Option A is not honoured on Samsung watches. It would need a
clean-room implementation or a third-party library with a `THIRD_PARTY.md` row, plus a catalogue
hook in the twin's Application class.

### Option C: stop depending on hidden members (per feature)

- **ECG only**: avoid constructing `SemEcgSensorEvent` — patch the SHM ECG parser (`pl` in
  1.5.1.022) to read our `int[27]` through a `shminterop` accessor instead of the event object.
  Moderate smali work, ECG only.
- **BP / apnea**: these need real PPG/SpO2 sensor streams from `SemSensorManager`; replacing them
  with Health Tracking data is new-feature development (calibration algorithm inputs, apnea SpO2
  SQC version map) and likely infeasible. At minimum, catch the `NoSuchMethodError` in the BP path
  so the app shows an error instead of crashing.

Recommendation: verify Option A by hand first (§5). If it works, implement it in the patcher
(§6). Independently, harden the BP crash and fix the misleading Watch8 comment.

## 5. Manual verification (do this before writing code)

Ask the reporter (or the owner on a One UI 8 watch) to run, with the watch connected over ADB:

```sh
adb shell settings get global hidden_api_blacklist_exemptions   # note the old value
adb shell settings put global hidden_api_blacklist_exemptions 'Lcom/samsung/android/hardware/sensormanager/'
adb shell am force-stop com.samsung.android.shealthmonitors
# then try ECG, BP calibration and sleep apnea, and export logs from the patcher
```

Expected if it works: no more `hiddenapi: ... denied` lines for `sensormanager`; ECG produces a
trace (`EcgAdapter` without `deliver failed`); `ShmInterop: sensor: unregisterListener overload =`
shows a real method; apnea log shows a resolved sensor version map; no BP crash.

If the denial lines persist, try the broad policy as a diagnostic only:

```sh
adb shell settings put global hidden_api_policy 1
adb shell am force-stop com.samsung.android.shealthmonitors
```

To revert: `adb shell settings delete global hidden_api_blacklist_exemptions` (or restore the old
value) and `adb shell settings delete global hidden_api_policy`.

Report back which variant worked (if any), and whether BP/apnea then fail further down (service
rejection, `registerListener` returning a negative value).

## 6. Implementation plan (once §5 confirms Option A)

1. **Model**: add a global-setting grant next to `Grant.SecureSetting` (engine `Catalogue.kt`,
   `GrantCommandBuilder`, `GrantApplier`, `GrantChecker`). Requirements:
   - append-to-list semantics for `hidden_api_blacklist_exemptions` (read current value, add our
     prefix if absent, keep the rest; comma-separated); the read-back counts it as granted when the
     prefix is present among the entries;
   - the value contains `/` (and other entries may contain `;`); the current setting-value
     character check may reject it; extend the allow-list narrowly and keep the shell quoting safe (single-quoted, no
     user-controlled input);
   - after applying, force-stop the watch twin so the exemption takes effect.
2. **Catalogue**: add the grant to the watch SHM twin entry in `catalogue/catalogue.json` (grants
   are data, like the existing ones), `scripts/catalogue.py check` and `sign`, bump the serial.
   Apply it only to watches where it is needed, or unconditionally (harmless on frameworks where
   the members are not blocked) — decide; unconditional is simpler.
3. **Self-test**: show the read-back in the watch self-test, with a "re-apply" action.
4. **Docs**: `docs/patches.md` (grant + why), `docs/installation.md` (what the patcher changes on
   the watch, how to revert), correct the Watch8 explanation in `WatchOverrides.java` and
   `docs/patches.md` row 7b.
5. **Hardening (independent of the above)**: catalogue pattern so the BP path's
   `SemHrSensorAttribute` construction failure does not kill the process (wrap via a
   `WatchOverrides` helper that catches `LinkageError` and makes SHM take its normal
   sensor-error path). Also make `EcgAdapter` log the hidden-API cause once instead of on every
   batch, so future logs are readable.
6. **Versioning**: patch-level bump of the patcher (`x.y.Z`) in its own commit, per `AGENTS.md`.

Acceptance criteria:
- On a One UI 8 watch, after the patcher's watch install, ECG records, BP calibration does not
  crash, apnea reaches the setup flow (or a clearly different, service-level error is documented).
- Re-running the watch self-test reports the exemption as present; removing it by hand makes the
  self-test report it missing.
- Existing exemption entries set by other tools are preserved.
- Device-free tests pass (commands below) plus new unit tests for the global-setting grant
  (command building, append semantics, read-back parsing).

Validation commands:

```sh
JAVA_HOME=<jdk17> patcher-app/gradlew :engine:test :adb:test :app:testDebugUnitTest
watch-mod/test/run-tests.sh
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 -m unittest discover -s superpatcher -p 'test_*.py'
scripts/catalogue.py check
```

## 7. Relevant files

- `watch-mod/src/shminterop/ecg/EcgAdapter.java` — builds `SemEcgSensorEvent` (denied constructor).
- `watch-mod/stubs/com/samsung/android/hardware/sensormanager/SemEcgSensorEvent.java` — compile stub.
- `watch-mod/src/shminterop/watch/WatchOverrides.java`, `UnregisterMethod.java` — availability
  exemption and unregister reflection (comment about Watch8 is likely wrong).
- `catalogue/catalogue.json` — watch patterns (`watch-ecg-sensor-adapter`,
  `watch-sensor-unregister-compat`, `watch-apnea-country`, `watch-apnea-reeval-not-supported`) and
  per-app grants.
- `docs/patches.md` — rows 7, 7b, 8, 9 (watch SHM).
- `patcher-app/adb/src/main/kotlin/io/openshm/patcher/adb/GrantChecker.kt` (+ `GrantApplier`,
  `GrantCommandBuilder`) — grant apply/read-back; today only `settings ... secure`.
- `patcher-app/engine/src/main/kotlin/io/openshm/patcher/engine/Catalogue.kt` — `Grant` model.
- `patcher-app/app/src/main/kotlin/io/openshm/patcher/device/GrantMapping.kt`,
  `ui/screens/selftest/SelfTestRunner.kt`, `ui/screens/install/InstallSteps.kt` — where grants are
  applied and shown.

## 8. Open questions

- Does Samsung's One UI 8 Watch build honour `hidden_api_blacklist_exemptions` /
  `hidden_api_policy` from `shell`? (§5 answers it.)
- With access granted, does the sensor service accept BP (HR attribute) and SpO2 (0x31)
  registrations from a non-system app, or does it reject them like ECG type 27 on older watches?
- Is the stock SHM on these watches really a system app (the reason it is exempt)? Check with
  `adb shell pm list packages -s | grep shealthmonitor` and `dumpsys package com.samsung.android.shealthmonitor`
  (`flags=[ SYSTEM ... ]`); useful for the docs, not needed for the fix.
