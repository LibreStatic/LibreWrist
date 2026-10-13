# Patches

The patches are **data**: `catalogue/catalogue.json` (format below) is interpreted by
`scripts/superpatcher.py` (`superpatcher/patterns.py` + the named ops of `superpatcher/engine.py`),
following an **ordered** profile (`gms`, `alarm`) stored in the same file. The catalogue never
contains code, so another engine (the Android app) can interpret the very same file; it is
published and signed (see "Catalogue" below). Each pattern is anchored to strings or signatures
that exist in the official APK (log strings, semantic method names, pref keys, SDK call
signatures), not to obfuscated names, so it survives renames between builds. A pattern listed in
the profile that does not match aborts the build (every listed pattern is implicitly required): an
APK missing a patch is never signed.

Official inputs (hash-checked against `apks/SHA256SUMS`): SHM phone **1.4.6.001** and
SHM watch **1.5.1.022**. The SHM profile is `gms` (native GMS Data Layer between watch
and phone, no PC); `alarm` builds the Alarm app fork (see below). The PC relay variant was dropped.

```bash
python3 scripts/superpatcher.py analyze <official.apk>            # what the profile would patch
scripts/build_shm_phone.sh        # -> $DIST_DIR/shm-phone-1.4.6.001-gms.apk
scripts/build_shm_watch.sh        # -> $DIST_DIR/shm-watch-1.5.1.022-gms.apk
```

## Catalogue

`catalogue/catalogue.json` (indent 2, key order preserved; `scripts/catalogue.py` writes it):

```
{ "schema": 1, "serial": N, "minAppVersion": 1,
  "inputs":   [ ...copy of apks/inputs.json "inputs" (scripts/catalogue.py sync-inputs) ],
  "families": [ {"id", "package", "markerClass"?}, ... ],       first match wins
  "profiles": { "<name>": {"description", "families": {"<family>": ["<pattern id>", ...]},
                           "inject": {"<family>": ["<injection name>", ...]}} },
  "patterns": { "<id>": {"families": [...] | "*", "title", "description", "optional": false,
                         "optionGroup"?: "<kebab-case group>",
                         "ops": [ {"op": "<name>", ...args}, ... ]} },
  "outputs", "installOrder", "grants", "stockAppsToDisable": install section, see below }
```

- **Install section** (top level, same file): what to install and how, for the app that drives
  the install. It is data too, and every value that could reach an `adb shell` command line is
  matched against a strict whitelist regex by `validate_catalogue` (unknown kinds or fields are
  rejected):

  ```
  "outputs":      [ {"id", "device": "phone"|"watch", "package",
                     "from": {"input": "<inputs id>", "profile": "<profile>"}      (patched APK)
                           | {"ownApp": "<repo-relative dir>"} } ],               (our own app)
  "installOrder": [ {"output": "<output id>", "then"?: "userOnboarding"} ],   every output once
  "grants":       { "<output id>": [ <grant>, ... ] },
  "stockAppsToDisable": [ {"device", "package", "optional": bool, "reason"} ]
  ```

  - `outputs[].package` is the package of the built APK; `${PKG_PREFIX}` is the only placeholder.
    `from.input` must target the same device as the output and have patterns in `from.profile`.
    `(device, package)` is unique.
  - `installOrder` lists every output exactly once; `then` is a follow-up the app performs after
    that install (`userOnboarding` = the user completes the in-app onboarding).
  - `grants`: closed set of kinds, each with exactly these fields plus the optional
    `"optional": true` (best effort / recommended) and `"note"` (one line, <= 300 chars):

    | `kind` | fields | meaning (adb equivalent) |
    |---|---|---|
    | `permission` | `name` | runtime permission (`pm grant <pkg> <name>`) |
    | `appop` | `op`, `mode` = `allow` | `appops set <pkg> <op> allow` |
    | `notificationListener` | `component` | `cmd notification allow_listener <pkg>/<class>`; either `.Class` (relative to the output package) or a fully qualified class name — a bare class name is rejected because `ComponentName` would not resolve it |
    | `deviceIdleWhitelist` | none | `dumpsys deviceidle whitelist +<pkg>` |
    | `secureSetting` | `key`, `value` | `settings put secure <key> <value>` |

    Regexes: permission `^[a-zA-Z][\w.]*$`, appop `^[A-Z][A-Z0-9_]*$`, setting key
    `^[a-z][a-z0-9_]*$`, value `^\w[\w.-]{0,63}$` (no leading `-`, which `settings` would read as an option), component `.Class` or `fully.qualified.Class` (no `$`), package
    `^[a-zA-Z]\w*(\.[a-zA-Z]\w*)+$` (after expansion). The `grants` keys are output ids; an
    output without grants has an empty list. Grants are repeated after every reinstall.
    No grant changes a device-wide user setting: the former optional `secureSetting
    doze_always_on=0` on `shm-watch` was dropped (serial 22), since the ECG does not need it. The
    patcher's revert sets `doze_always_on` back to `1` on a watch where it still reads `0`.
  - `stockAppsToDisable`: stock system apps that should be disabled on a device
    (`pm disable-user --user 0 <package>`); `optional` apps are offered, not forced.

- **Family detection**: the first rule whose `package` equals the manifest `package` and, if
  `markerClass` is set (a class type without `L`/`;`), whose class exists in any `smali*` dir.
- **Profile**: an ORDERED list of pattern ids per family. `inject` lists the names of the extra
  smali sets the build merges in (ids only: that code ships separately, never in the catalogue).
- **Pattern**: `ops` run in order; the logs of all steps are concatenated. A pattern whose
  combined log is empty fails the build (unless `optional`), and so does any step that raises.
- **Optional patterns** (`"optional": true`): the patcher app lets the user switch these off in
  its Customize step; all of them are enabled by default and every other pattern is required. A
  disabled pattern is simply not run (`PatchRequest.disabledPatterns` in the Kotlin engine,
  `--skip <id>` in `scripts/superpatcher.py`); asking to skip a required or unknown id is an error,
  and the patterns that do run keep the "anchor must match, otherwise abort" rule. The optional
  patterns are `shm-min-age-22-to-16`, `shm-setup-network-gate`, the sleep-apnea group below, and
  `alarm-force-sound-vibrate`, `alarm-hide-launcher`, `alarm-relabel`.
- **Option groups** (`"optionGroup": "<name>"`, optional patterns only, at least two members):
  the members are always on or off together, across inputs, so a partial skip is an error. The
  only group is `sleep-apnea`: `shm-sleep-apnea-support` (phone SHM) with `watch-apnea-country` and
  `watch-apnea-reeval-not-supported` (watch SHM). Both validators (`superpatcher/patterns.py` and
  `Catalogue.kt`) accept and type-check the field.
- **Ops are named and documented** in `superpatcher/engine.py` (`OPS` registry; the docstring of
  each `op_*` function is its semantics contract, also for the Kotlin port). An unknown op, an
  unknown or missing argument, or a wrong argument type is a hard error. JSON argument names are
  camelCase (`fileRegex`); the catalogue has no expressions, lambdas or code of any kind.
- **Regex dialect**: regex arguments use Python `re` syntax. Keep to the subset that maps
  one-to-one onto `java.util.regex`: named groups are written `(?P<name>...)` and back-references
  `(?P=name)`, which a JVM interpreter rewrites to `(?<name>...)` and `\k<name>`. No
  Python-only constructs (conditional groups, `(?L)`, `(?u)`).
- **Counts are asserted** with `expect`: an exact integer, `"atLeastOne"` (the default of the
  smali ops) or `"any"` (no assertion; used on purpose where a candidate may not exist in every
  build). The structural manifest ops add `"eachListed"` (every listed name matched exactly once
  and nothing else). Class-targeted ops (`replaceInClass`, ...) assert their own counts.
- **Multi-line text** (smali snippets): wherever an op takes `text`, a string or a list of
  strings joined with `"\n"`. One array element per line; end the list with `""` for a trailing
  newline, and use `""` elements for blank lines.
- **Placeholders**: string values may contain `${PKG_PREFIX}` (e.g. `com.example`) and
  `${PKG_PATH}` (`com/example`), expanded from `config.env` before the op runs. Nothing else is
  templated (except the `{{capture}}` variables of `insertInMethodAtMatch`).

Ops (one line each; the full contract is the docstring):

| Op | What it does |
|---|---|
| `insertAtMethodStart` | insert smali after `.locals` of every selected method (selector: `fileRegex`, `fileAll`, `fileAny`, `sigRegex`, `sigExclude`, `bodyAll`, `bodyNone`); optional `needRegs`, `tail` |
| `returnSingleton` | same selector; the method returns `sget-object <its return type>-><field>` |
| `setMethodBody` | same selector; replace the whole method body |
| `insertInMethodAtMatch` | same selector; regex with named captures (and enum field lookups) + `{{var}}` template inserted at group `at` |
| `swapConst`, `enumOrdinalsZero` | text swap in files matching `fileRegex`; enum ordinal constants to 0 |
| `sweepStringCompareAutoTrue` | comparisons against a string constant become "value compared with itself" |
| `forceConstAfterMove`, `forceInvokeResult` | force a `const/4` after a `move-result` (before an anchor / after a given invoke) |
| `replaceConstStringExact`, `replaceAllInFilesWithAnchor` | exact `const-string` replacement; replace text in files that contain an anchor |
| `verifyClassContains`, `replaceInClass`, `replaceClassMethod`, `insertInClassMethod`, `removeSmaliDir` | class-targeted ops for pinned builds (every one asserts what it found) |
| `addStringResources` | add a `<string>` to `res/values*/strings.xml` |
| `defineResourceEntry` | define `type/name` at a resource `id` (`0x` + 8 hex digits) with value `@null` in the default config; fails if the type id does not match, the slot is defined in any config, or the name exists |
| `manifestRebrand`, `manifestAddQueryPackage` | text edits of `AndroidManifest.xml` (exact semantics in the docstrings) |
| `manifestExpect`, `manifestRemove`, `manifestSetAttr`, `manifestRemoveAttr`, `manifestAddChild` | structural manifest edits: select elements (`path`, `attr`, `attrIn`, `simpleNameIn`, `parentAttr`, `hasChild`), assert the count, then act |

**Adding a patch** = a pattern entry in `catalogue/catalogue.json` + its id in the profile (order
matters) + a note in this file. Then `python3 scripts/catalogue.py check` (it validates every op
and argument, the cross references and that every pattern is used by a profile), run the tests,
and sign (`scripts/catalogue.py sign`). If an anchor stops matching with a new APK version the
engine fails, it never continues silently.

**Signing**: `scripts/catalogue.py genkey` once (ECDSA P-256 key at `$CATALOGUE_KEY`, public key
`catalogue/catalogue.pub`), then `scripts/catalogue.py sign` after every change (it increments
`serial` and writes `catalogue/catalogue.json.sig`, a DER ECDSA/SHA-256 signature; verify with
`scripts/catalogue.py verify`).

**Publishing a catalogue update** (no app release needed): change the catalogue, `scripts/catalogue.py
check`, `scripts/catalogue.py sign` (bumps `serial`), commit `catalogue.json` and `catalogue.json.sig`
and push. A patcher app built with `CATALOGUE_URL` set (config.env; an https base URL of the raw
`catalogue/` directory, exported to Gradle) checks `<CATALOGUE_URL>/catalogue.json` and `.sig` when
Home is shown, at most once a day, or on demand from the Home menu. It accepts a download only if
the signature verifies with the *bundled* `catalogue.pub` (the key is never downloaded), the serial
is newer than the highest ever accepted, and `minAppVersion` fits (raise `minAppVersion` when an
update needs new engine ops). Accepted files are kept in the app's private storage and used from
the next time Home is shown with no build running, or the next start; a bundled catalogue with a
higher serial (app update) replaces a stored one. Home offers an "update" only when the new serial
changes an input hash or the patches of the user's last build; nothing is downloaded automatically
(the user fetches changed inputs on the Acquire screen, then rebuilds). Never rotate the key this way:
a new key needs a new app release.

**Regression oracle** for engine changes: build everything before and after with the same
`PKG_PREFIX`, then `scripts/oracle_decoded_diff.sh <distBefore> <distAfter>` decodes both with
apktool and must report no difference.

The vocabulary is interoperability vocabulary: *vendor override*, *regional check disabled*,
*exemption*. All changes are applied to local copies of the owner's own APKs, re-signed with a
local key, never redistributed.

## SHM phone 1.4.6.001

Application order (the `#` column is the order in the `gms` profile of the catalogue).

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
| 14 | `shm-wear-trusted-peers` | the `wear-trusted-peer-packages` meta-data is removed (see the watch table) | Same reason as on the watch |

Profile: all of them in `gms`. Verified [V]: the decoded result (smali, manifest, resources) is
identical to the owner-verified `shm-phone-1.4.6.001-stock-mod-gms1` build (24 patched sites).

## SHM watch 1.5.1.022

| # | Pattern id | What it does | Why it is needed |
|---|---|---|---|
| 1 | `generic-manufacturer-check` | as in the phone (one site, `y71`) | Manufacturer check |
| 2 | `watch-rebrand-coexist` | package -> `com.samsung.android.shealthmonitors` in the manifest (package, permissions, authorities) and exact package strings in code (the tile package) | Coexists with the system SHM and shares the phone's AppKey |
| 2b | `shm-wear-trusted-peers` | the `wear-trusted-peer-packages` meta-data (stock package + vendor certificate) is removed; same pattern on the phone | With it, the watch's Data Layer did not publish the patched app's `capability_ecg`, so the phone SHM found no compatible watch; without it the two patched apps match by package and certificate |
| 2c | `watch-gms-resources-anchor` | `defineResourceEntry` defines `anim` `0x7f010000` (`openshm_gms_resources_anchor`, `@null`) | The vendor build stripped the first resource ID but kept a resource table named `com.samsung.android.shealthmonitor` (no `s`), different from the manifest package. Google Play services derives an app's resources package from that first ID and, when it is missing, falls back to the manifest package, finds no `android_wear_capabilities` array and registers no Wear capabilities, so the phone reported no compatible watch |
| 3 | `watch-queries-health-service` | manifest `<queries>` declares `com.samsung.android.service.health` | Package visibility for the Health Tracking Service |
| 4 | `watch-companion-vendor` | the `"samsung"` companion match is forced true; the denied-READ_COMPANION_INFO path emits TRUE | Companion vendor exemption for re-signed apps |
| 5 | `watch-alarm-system-app` | the reflective lookup `setExactAlarmForSystemApp` is renamed to a non-existent name | A re-signed app is not a system app; the SecurityException kills the process from the IHRN off-body detector; the stock `setAlarmClock` path is used instead |
| 6 | `watch-mobile-installed` | the "phone app installed" getter (pref `application_installed_in_mobile`) returns true | Opens the ECG capability gate |
| 7 | `watch-ecg-sensor-adapter` | sensor wrapper: type 0x1b (ECG) is always available and registered/unregistered through `EcgAdapter`; types 0x25 (IHRN) and 0x31 (SpO2/apnea) get an availability exemption through `WatchOverrides.sensorAvailability` | A re-signed app cannot reach the privileged ECG sensor; ECG comes from the Health Tracking Service through our own client (see [health-sdk.md](health-sdk.md)) |
| 7b | `watch-sensor-unregister-compat` | the three direct `SemSensorManager.unregisterListener(listener, int)` calls (the sensor wrapper and two in the sensor controller) go through `WatchOverrides.unregisterListener`, which picks the framework's overload by reflection (`(listener, int)` with any return type, else `(listener)`) and logs failures instead of throwing | The Galaxy Watch8 framework has no `unregisterListener(listener, int)V`; the apnea version detector (run because of the 0x31 exemption) unregisters after its timeout, and the `NoSuchMethodError` escaped its `catch Exception` and closed the app on open |
| 8 | `watch-apnea-country` | the apnea "supported country" check returns true up front | Regional check disabled (the original is a fixed country list) |
| 9 | `watch-apnea-reeval-not-supported` | in the stored apnea version getter, a persisted `NOT_SUPPORTED` is re-evaluated instead of returned | A value persisted by an earlier build (regional check still on) would be sticky |

Plus two injected smali sets (profile `inject`, merged into one new `smali_classesN` dir
before `apktool b`): `watch-mod` (`watch-mod/build-watch-mod.sh`: `EcgAdapter`,
`WatchOverrides`, `UnregisterMethod` and the `shminterop.hsdk` Health Tracking client).

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

## Alarm fork (AlarmWatch 1.3.05.5)

Official input `alarm-watch-1.3.05.5.apk` (`com.samsung.android.watch.alarm`), profile
`alarm` of the catalogue, build `scripts/build_alarm_fork.sh` -> `$DIST_DIR/alarm-fork-1.3.05.5.apk`.
The fork receives the phone's alarms from the SHM bridge (direct intents to `LinkActivity` /
`LinkReceiver`, signature permission `<prefix>.shmbridge.permission.ALARM_LINK`) instead of the
GMS Data Layer. Unlike the SHM families, this input is one exact build, so the patterns name
the obfuscated classes of 1.3.05.5 directly and every edit asserts what it found (counts
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
