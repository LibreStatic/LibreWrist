# Patches

Everything is applied by `scripts/superpatcher.py` following an **ordered** profile
(`profiles/gms.json`). Each pattern is anchored to strings or signatures that exist in the
official APK (log strings, semantic method names, pref keys, SDK call signatures), not to
obfuscated names, so it survives renames between builds. A pattern listed in the profile that
does not match aborts the build (every listed pattern is implicitly required): an APK missing a
patch is never signed.

Official inputs (hash-checked against `apks/SHA256SUMS`): SHM phone **1.4.6.001** and
SHM watch **1.5.1.022**. The SHM profile is `gms` (native GMS Data Layer between watch
and phone, no PC); `alarm` builds the Alarm app fork (see below). The PC relay variant was dropped.

```bash
python3 scripts/superpatcher.py analyze <official.apk>            # what the profile would patch
scripts/build_shm_phone.sh        # -> $DIST_DIR/shm-phone-1.4.6.001-gms.apk
scripts/build_shm_watch.sh        # -> $DIST_DIR/shm-watch-1.5.1.022-gms.apk
```

The vocabulary is interoperability vocabulary: *vendor override*, *regional check disabled*,
*exemption*. All changes are applied to local copies of the owner's own APKs, re-signed with a
local key, never redistributed.

## SHM phone 1.4.6.001

Application order (the `order` column is the order in `profiles/gms.json`).

| # | Pattern id | What it does | Why it is needed |
|---|---|---|---|
| 1 | `generic-manufacturer-check` | `"samsung".contains/equals` manufacturer checks in Samsung code (androidx/google excluded) become auto-true | The phone is not a Samsung device |
| 2 | `generic-vendor-check` | `SsdkVendorCheck`: `"Samsung"` -> `"Android"`, compare auto-true | The Samsung SDK vendor check |
| 3 | `shm-min-age-22-to-16` | `MIN_AGE` 22 -> 16 in `util/c` | TODO(owner): document the personal-use justification |
| 4 | `shm-supported-type-ordinals` | Rejection ordinals (MCC/CSC/ALL/NO_SIM) of `CommonConstants$SupportedType` collapse to 0 | Regional check disabled |
| 5 | `shm-region-supportedtype` | Region check returning `SupportedType` always returns `.a` (supported) | Regional check disabled |
| 6 | `shm-region-countrylist` | Boolean country check (list containing `"BH"`) returns true | Regional check disabled |
| 7 | `shm-root-packagevalidator` | `PackageValidator` and certificate-validation methods return false | The re-signed app no longer matches the Samsung certificate |
| 8 | `shm-force-update-response` | `AppVersionCheckResponse`: failed/updateNeeded false; non-null version name/code | The watch answer may come without versionName; the NPE kills the process mid-handshake |
| 9 | `shm-force-update-pref` | Method reading the required-version pref returns false | No forced-update gate |
| 10 | `shm-main-gates` | `checkSystem`/`updateVersionCheck` -> void; `isScreenLockEnabled` -> success | System / version / screen-lock gates |
| 11 | `shm-setup-network-gate` | `checkNoNetworkError` -> void | No SIM/region gate in setup |
| 12 | `shm-sleep-apnea-support` | `isPhoneSupportSleepApnea` forced true (const after move-result) | Regional check disabled |
| 13 | `phone-rebrand-gms` | package -> `com.samsung.android.shealthmonitors` (manifest, permissions, authorities, exact package strings, share URI) | Same package and certificate as the watch app: the GMS Data Layer only delivers between equal AppKeys |

Profile: all of them in `gms`. Verified [V]: the decoded result (smali, manifest, resources) is
identical to the owner-verified `shm-phone-1.4.6.001-stock-mod-gms1` build (24 patched sites).

## SHM watch 1.5.1.022

| # | Pattern id | What it does | Why it is needed |
|---|---|---|---|
| 1 | `generic-manufacturer-check` | as in the phone (one site, `y71`) | Manufacturer check |
| 2 | `watch-rebrand-coexist` | package -> `com.samsung.android.shealthmonitors` in the manifest (package, permissions, authorities) and exact package strings in code (the tile package) | Coexists with the system SHM and shares the phone's AppKey |
| 3 | `watch-queries-health-service` | manifest `<queries>` declares `com.samsung.android.service.health` | Package visibility for the Health Tracking Service |
| 4 | `watch-companion-vendor` | the `"samsung"` companion match is forced true; the denied-READ_COMPANION_INFO path emits TRUE | Companion vendor exemption for re-signed apps |
| 5 | `watch-alarm-system-app` | the reflective lookup `setExactAlarmForSystemApp` is renamed to a non-existent name | A re-signed app is not a system app; the SecurityException kills the process from the IHRN off-body detector; the stock `setAlarmClock` path is used instead |
| 6 | `watch-mobile-installed` | the "phone app installed" getter (pref `application_installed_in_mobile`) returns true | Opens the ECG capability gate |
| 7 | `watch-ecg-sensor-adapter` | sensor wrapper: type 0x1b (ECG) is always available and registered/unregistered through `EcgAdapter`; types 0x25 (IHRN) and 0x31 (SpO2/apnea) get an availability exemption through `WatchOverrides.sensorAvailability` | A re-signed app cannot reach the privileged ECG sensor; ECG comes from the Health Tracking Service through our own client (see [health-sdk.md](health-sdk.md)) |
| 8 | `watch-apnea-country` | the apnea "supported country" check returns true up front | Regional check disabled (the original is a fixed country list) |
| 9 | `watch-apnea-reeval-not-supported` | in the stored apnea version getter, a persisted `NOT_SUPPORTED` is re-evaluated instead of returned | A value persisted by an earlier build (regional check still on) would be sticky |

Plus two injected smali sets (profile `inject`, merged into one new `smali_classesN` dir
before `apktool b`): `watch-mod` (`watch-mod/build-watch-mod.sh`: `EcgAdapter`,
`WatchOverrides` and the `shminterop.hsdk` Health Tracking client).

Verified [V], by decoding both final APKs and comparing with `shm-watch-mod-gms-v32-signed`
(owner-verified on device, normalising the previous package marker to `shminterop`):
manifest, all patched stock smali and the imported SDK are equivalent. Accepted differences:

- The v32 build still contained the PC relay (dex, `RelayService`, INTERNET/FGS permissions) and
  call sites for `dq1` (BT gate) and `hu1.Y` (open-URI); not present here.
- Log/identifier text translated: the apnea log line and the `setExactAlarmForSystemApp__...`
  placeholder name (no behaviour change).
- `WatchOverrides` is reduced to `sensorAvailability`.
- `EcgAdapter` is identical; its three inner classes differ only in register allocation
  (different d8 build).
- Two SDK classes the previous tree referenced as `Ln90$a;` / `Lxp$a;` (inner classes of
  closure members, missed by its rename regex because of `$`) are now renamed and included.
- Method order inside SDK smali files differs (dex sorts by type name).

## Alarm fork (AlarmWatch 1.3.05.9)

Official input `alarm-watch-1.3.05.9.apk` (`com.samsung.android.watch.alarm`), profile
`profiles/alarm.json`, build `scripts/build_alarm_fork.sh` -> `$DIST_DIR/alarm-fork-1.3.05.9.apk`.
The fork receives the phone's alarms from the SHM bridge (direct intents to `LinkActivity` /
`LinkReceiver`, signature permission `<prefix>.shmbridge.permission.ALARM_LINK`) instead of the
GMS Data Layer. Unlike the SHM families, this input is one exact build, so the patterns name
the obfuscated classes of 1.3.05.9 directly and every edit asserts what it found (counts
included): another build aborts instead of being patched by a heuristic. `<prefix>` is
`PKG_PREFIX`.

| # | Pattern id | What it does |
|---|---|---|
| 1 | `alarm-verify-symbols` | The members `Link` reflects on exist: `o2.b.a(String)`, `o2.c.f()`, `k0.b.a(Context)` / `c(Intent)`, `AlarmSyncService.m:J` |
| 2 | `alarm-rebrand-manifest` | Manifest package, dynamic-receiver permission and the two provider authorities -> `<prefix>.shmbridge.alarm` |
| 3 | `alarm-rebrand-code` | Content URI (`u2/b`) and the exact quoted package string in `c0/c` (3), `J0/e` (1), `e2/i` (2), `Z1/b` (1) |
| 4 | `alarm-permissions-prune` | Removes 15 privileged/system `uses-permission` and the 4 vendor permission declarations |
| 5 | `alarm-notification-permissions` | Adds `POST_NOTIFICATIONS` and `USE_FULL_SCREEN_INTENT` |
| 6 | `alarm-queries-libs` | Removes `<queries>` and the `wear-sdk` / window / appfunctions libraries; `com.google.android.wearable` becomes optional |
| 7 | `alarm-components-prune` | Removes 16 GMS / tile / complication / backup / bixby / appfunctions components and 9 application meta-data entries |
| 8 | `alarm-provider-open` | `AlarmProvider` without the vendor read/write permissions |
| 9 | `alarm-link-components` | Adds the `ALARM_LINK` signature permission, `LinkActivity` (no UI) and `LinkReceiver` (action `<prefix>.shmbridge.alarm.IN`), both protected by that permission |
| 10 | `alarm-appfunctions-drop` | Removes the `appfunctions_aggregated_deps` smali tree |
| 11 | `alarm-semgesture-skip` | `w2/k`, `w2/j`: the `sem_gesture` lookup is skipped (not available to a non-system app) and the `pauseGesture` null path falls through |
| 12 | `alarm-gms-observer-drop` | `CAlarmService.onCreate`: the GMS connection observer is removed (it would stop the service before the player exists) |
| 13 | `alarm-service-stop-on-destroy` | `w2/k.onDestroy` calls `t()` first so sound/vibration is never orphaned |
| 14 | `alarm-force-sound-vibrate` | `CAlarmService.r()`: play mode is always `0x11` (sound + vibrate); the stream is alarm usage |
| 15 | `alarm-link-send` | `o2/b.e()` (sendMessageToAll) body replaced by `Link.send(context, path, data)` |
| 16 | `alarm-hide-launcher` | Removes the `MAIN`/`LAUNCHER` intent-filter of `AlarmMainActivity` (the app only exists to receive phone alarms; the activity itself stays) |
| 17 | `alarm-relabel` | Application and `AlarmMainActivity` label -> new string `interop_app_label`: "Phone alarms" (default), "Alarmas del teléfono" (`values-es`); vendor strings untouched |

Injected smali set (profile `inject`, new `smali_classesN` dir): `alarm-fork` = `Link`,
`LinkActivity`, `LinkReceiver` from `shm-bridge/alarm-fork/src` (`javac --release 11`, `d8
--min-api 34`; `java_to_smali` in `scripts/lib/common.sh`).

Verified [V]: the decoded result matches the previous owner-verified fork build (same
smali, resources and manifest after normalising the previous package marker). Differences:
patterns 16 and 17 (new), `Link*` (log strings translated to English; d8 numbers the two
`Link` lambdas in the opposite order), and the signing files. Not verified on a device.

## Excluded and why

- **Galaxy Wearable / heartplugin / Samsung Health families** (`wearable-*`, `hp-*`): the
  Galaxy Wearable proxy never worked; the final setup uses the stock apps.
- **PC relay** (`phone-relay-transport`, `phone-relay-channel-stream`, `watch-relay-transport`,
  BT-gate and open-URI overrides, RelayService and INTERNET/FGS manifest
  entries): the variant was dropped, only `gms` ships.
- **Old per-version diffs** (`patches/` for SHM 1.4.1.006): superseded by the anchored patterns.
- **`shm-watch-apnea-country`** (old, disabled, polarity unverified): replaced by
  `watch-apnea-country`.
- `shm-min-age-22-to-16` and `generic-vendor-check` are not listed for the watch: they do not
  match in 1.5.1.022 (a listed pattern that matches nothing aborts).

## Open doubts

- [C] `watch-mobile-installed`, `watch-apnea-reeval-not-supported` and `watch-alarm-system-app`
  were in the verified build; each one's individual necessity was not re-tested on a device.
- [C] The INTERNET/FGS permissions of the old watch build were relay-only and are not
  re-added; the gms setup works without them in theory but this build was not re-run on a device.
