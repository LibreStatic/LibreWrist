# Samsung Health (Wear OS) survey — Vascular Load on the Galaxy Watch5 Pro

Status: **survey** (no catalogue or build changes yet). Question: can the phone-side Samsung
Health patches (see [samsung-health-survey.md](samsung-health-survey.md)) be completed with a
watch-side patch so Vascular Load works on the owner's Galaxy Watch5 Pro (SM-R920), where
Samsung gates it to the Watch8 generation?

## Input

`com.samsung.android.wear.shealth` **7.00.0.131** (versionCode 7000131, minSdk 35, targetSdk 37,
3 dex, arm64+armv7, 141 MB), pulled read-only over wireless ADB from the owner's watch
(`$WORK_DIR/shealth-watch-survey/shealth-watch-7.00.0.131.apk`, sha256 `19ba5da4…f3a07`; decoded
tree in `decoded/`). Same 7.00 generation as the phone build: **the complete Vascular Load
implementation ships in this APK** — `app/vascularload/` (activity), `tracker/vascularload/`
(data/domain/monitor, 36 classes), `tile/prototile/vascularload/`, even a dev
`TestVascularLoadActivity`. It is switched off by rollout state, not by absence of code.

## The phone↔watch contract (from the phone survey, confirmed on this tree)

- The watch advertises a capability JSON containing `tracker_feature`; the phone reads
  `tracker_feature.vascular_load_feature` and requires **`version >= 1000`** and
  **`vascular_load_support == true`** (`ye80.checkMapCapability`; log tag
  `SHEALTH#VLWearManagerRepositoryImpl`). The phone hides the tile otherwise (plus a
  Samsung-account-country ≠ US check, satisfied for this owner).
- The phone sends its own capability blob (`xg80.a()`, keys `antioxidant`, `vascular_load_feature`
  `{version:1002, vascular_load_support:true}`, `training_load_feature`, `vitals_feature`, …) —
  on the phone side these are already hard-coded on (see the phone survey).

## The watch-side gate chain

1. **`mg7`** (init coroutine, runs at app process start) probes Samsung private sensors with
   `nsb.a(type, ctx)` = `SemSensorManager("sem_sensor").isAvailable(type)` (cached) and writes
   one pref per sensor: `initSrbrSleepAnalyzerSensorCapability` (0x11…), `initMovementSensorCapability`
   (0x38), `initRespirationSensorCapability` (0x37), `initAdvancedGlycationEndproductSensorCapability`,
   and **`initVascularLoadSensorCapability`**: writes pref **`sensor_map_support`** =
   `isAvailable(0x45)` (sensor type 69). This is the **only writer**; `p4c.C` reads it with
   default **false** (`SharedPreferences.getBoolean(key, false)`).
2. **`zte.<clinit>`** builds the advertised VL capability as
   `{version: 1002 (0x3ea), vascular_load_support: p4c.C("sensor_map_support")}` — exactly the
   shape `ye80` demands. `a47`/`oj0` build a sibling `{version: 1000, sensor_map_support,
   heart_health_support: sensor_map_support && lg9.a() >= 90000}` blob.
3. **`rue.a()`** (VL tracker availability) = `sensor_map_support && tracker.vascular_load
   registered (tub) && !tj0.a()`; `tj0` is the analogous BP-trend check. The tracker id itself
   (`lub`, id `"45"`) is registered unconditionally — no gate there.
4. Nothing else in the VL chain checks the watch model; the generation gate is this flag.

So: **one boolean pref, `sensor_map_support`, turns the whole family on or off**, and on this
watch it can only be false (nothing writes it unless the probe passes; the owner sees no VL).

## The open question: sensor type 0x45 on the GW5P — **answered: not available**

`isAvailable(0x45)` asks the sensorhub (`sem_sensor` service) for a private capability. This was
settled empirically on 2026-10-12: with the owner's OK, the watch app was restarted while
`logcat` streamed, and `SHW - SensorCapabilityInitializer` printed the whole suite:

```
[initSrbrSleepAnalyzerSensorCapability]         available=true
[initMovementSensorCapability]                  available=true
[initRespirationSensorCapability]               available=true
[initAdvancedGlycationEndproductSensorCapability] false
[initVascularLoadSensorCapability]              available=false
```

The GW5P's sensorhub **does not expose capability 0x45 (69)** — Vascular Load's MAP algo — nor
the advanced-glycation one. This is a hardware/firmware gate (older sensor generation), not a
rollout switch: the pref is written `false` because the probe genuinely fails. Forcing
`sensor_map_support=true` would only turn on UI with no data behind it. Raw capture:
`$WORK_DIR/shealth-watch-survey/init-logcat.txt`.

Related dependency noted for completeness: Vascular Load derives from blood-pressure (MAP) data,
which on Samsung Health requires the watch's **BP feature to be calibrated** — moot given the
probe result.

## Re-sign / pairing work for a watch build

- **No `wear-trusted-peer-packages` in the watch manifest**, but the watch still pins the phone:
  GMS's remote peer map (`dumpsys activity service
  com.google.android.gms/.wearable.service.WearableService` on the phone) lists the watch app
  `com.samsung.android.wear.shealth` (cert `9ca5170f…`) with the trusted peer
  `AppKey[com.sec.android.app.shealth, 9741a0f3…]`, the stock phone certificate (see "Outcome"
  below). `r7c` embeds the same five Samsung certificates as the phone's checker, but its only
  consumer is `HealthDataProvider` (validating watch-side callers), unaffected by our phone
  certificate. The literal `com.sec.android.app.shealth` appears in data rows (default peer
  package), not as a security gate. **Phone side needs one pattern though**: our phone build
  still carries the vendor meta-data pinning `com.samsung.android.wear.shealth` + the *stock*
  watch certificate digest; once the watch app is re-signed that pin must go — a
  `shealth-wear-trusted-peers` pattern (drop the meta-data, mirroring `shm-wear-trusted-peers`)
  in the `shealth` profile.
- Watch `res/` is clean: **no extension/magic mismatches** (0 of ~1800 files), so the
  `renameFile` op is not needed on this target. 3 dex; apktool decodes cleanly (480 MB tree).
- The app is preloaded on the watch (uid 10086, system partition). Installing a re-signed copy
  needs the same treatment the owner already applies to SHM watch: remove the stock copy for
  user 0, install ours, keep a backup of the original APK for rollback. `stockAppsToDisable`
  semantics may need the uninstall variant for this package (to be decided at build time).
- Watch-internal self-signature checks: the phone's copy of this checker is legacy/inert
  (Tizen-era nonce flow); whether the watch build needs the equivalent neutralized is part of the
  build phase (same class of check as `shm-watch` already solves).

## Patch points (data-driven candidates, watch family `shealth-watch`)

| Candidate | Site | Effect |
|---|---|---|
| A (preferred, 1 site) | `mg7.initVascularLoadSensorCapability`: replace the `nsb.a(0x45, ctx)` result with `const/4 v3, 0x1` before the `p4c.b0` write | Every reader (zte capability, rue availability, a47/oj0 blob, tj0 BP-trend) sees support naturally, exactly like a passing probe |
| B (equivalent, 2+ sites) | `zte.<clinit>` const true + `rue.a()` first term true | Same effect, more sites |

Vendor-override wording for the note: *sensor capability probe overridden*; the probe result is
replaced for the MAP/vascular-load capability only (`nsb.a` itself stays untouched — other
sensors keep their real answers).

## Go / no-go

**No-go for Vascular Load on the GW5P.** The implementation ships in the watch app and the gate
is a single pref, but the pref is the honest answer of a sensor probe: the sensorhub lacks
capability 0x45. Patching it would yield an empty feature; the only remaining route would be
computing a vascular-load score ourselves on the phone from synced BP data — new-feature
development, not a vendor-override, and BP calibration itself may not run without a Samsung
phone. Antioxidant Index is equally dead on this hardware (`initAdvancedGlycationEndproduct…`
false). The watch survey stands as the record of why; no watch build is planned.

## Outcome: two phone apps, no watch build

Testing the re-signed phone build against the paired watch showed that the watch side only trusts
the Samsung-signed phone app (certificate digest `9741a0…`): a phone copy signed with our key
stops syncing with the watch. Since no watch build is planned, the `shealth-wear-trusted-peers`
pattern above is not needed either. The shipped design (see `docs/patches.md`, Samsung Health)
keeps the stock Samsung Health from Play for the watch and installs the patched copy under its
own package, `${PKG_PREFIX}.shealth`, which reads the same data from the Samsung cloud.
