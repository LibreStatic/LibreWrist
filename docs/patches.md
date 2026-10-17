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
{ "schema": 2, "serial": N, "minAppVersion": 1,
  "inputs":   [ ...copy of apks/inputs.json "inputs" (scripts/catalogue.py sync-inputs) ],
  "families": [ {"id", "package", "markerClass"?}, ... ],       first match wins
  "profiles": { "<name>": {"description", "families": {"<family>": ["<pattern id>", ...]},
                           "inject": {"<family>": ["<injection name>", ...]}} },
  "patterns": { "<id>": {"families": [...] | "*", "title", "description", "optional": false,
                         "optionGroup"?: "<kebab-case group>", "offByDefault"?: true,
                         "ops": [ {"op": "<name>", ...args}, ... ]} },
  "outputs", "installOrder", "grants", "stockAppsToDisable": install section, see below,
  "apps", "strings", "platform": what the app shows and which platform packages it knows (schema 2) }
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
  "stockAppsToDisable": [ {"device", "package", "optional": bool, "reason", "replacedBy"?: "<output id>"} ]
  ```

  - `outputs[].package` is the package of the built APK; `${PKG_PREFIX}` is the only placeholder.
    `from.input` must target the same device as the output and have patterns in `from.profile`.
    `(device, package)` is unique.
  - `installOrder` lists every output exactly once; `then` is a follow-up the app performs after
    that install (`userOnboarding` = the user completes the in-app onboarding).
  - `grants`: closed set of kinds, each with exactly these fields plus the optional
    `"optional": true` (best effort / recommended), `"note"` (one line, <= 300 chars; a technical
    note) and `"why"` (a key of `strings.en`: the plain-language reason shown to the user):

    | `kind` | fields | meaning (adb equivalent) |
    |---|---|---|
    | `permission` | `name` | runtime permission (`pm grant <pkg> <name>`) |
    | `appop` | `op`, `mode` = `allow` | `appops set <pkg> <op> allow` |
    | `notificationListener` | `component` | `cmd notification allow_listener <pkg>/<class>`; either `.Class` (relative to the output package) or a fully qualified class name — a bare class name is rejected because `ComponentName` would not resolve it |
    | `deviceIdleWhitelist` | none | `dumpsys deviceidle whitelist +<pkg>` |
    | `secureSetting` | `key`, `value` | `settings put secure <key> <value>` |
    | `globalSetting` | `key`, `value` | append `value` to the comma-separated list in `settings get global <key>` (foreign entries preserved, then `settings put global <key> '<current>,<value>'`), and force-stop the output so the new value is read at process start |

    Regexes: permission `^[a-zA-Z][\w.]*$`, appop `^[A-Z][A-Z0-9_]*$`, setting key
    `^[a-z][a-z0-9_]*$`, value `^\w[\w.-]{0,63}$` (no leading `-`, which `settings` would read as an option), component `.Class` or `fully.qualified.Class` (no `$`), package
    `^[a-zA-Z]\w*(\.[a-zA-Z]\w*)+$` (after expansion); the `globalSetting` value is a separate,
    narrower set, `^L[a-zA-Z0-9_/$-]{0,127}$` (an ART member-signature prefix as it appears in a
    hidden-API exemption list). The `grants` keys are output ids; an
    output without grants has an empty list. Grants are repeated after every reinstall.
    No grant changes a device-wide user setting: the former optional `secureSetting
    doze_always_on=0` on `shm-watch` was dropped (serial 22), since the ECG does not need it. The
    patcher's revert sets `doze_always_on` back to `1` on a watch where it still reads `0`, and removes our `hidden_api_blacklist_exemptions`
    entry (see the `globalSetting` grant below).
    (The `globalSetting` exemption on `shm-watch`, below, is the single deliberate exception: it
    is a list of hidden-API member prefixes, not a `secure` user preference, and it is appended to,
    never overwritten.)
  - `stockAppsToDisable`: stock system apps that should be disabled on a device
    (`pm disable-user --user 0 <package>`); `optional` apps are offered, not forced. `replacedBy`
    names the output that replaces the stock app (the step is skipped when that output is not built).
  - `outputs[]` may also carry (schema 2, all optional): `start` = `{"receiver": ".Class",
    "action": "<action>", "switches"?: {"<extra>": "<app id>"}}` (the broadcast that starts an
    installed bridge; `${PKG_PREFIX}` allowed in the action; `switches` maps a boolean intent-extra
    name `[a-z][A-Za-z0-9]*` to the id of an `optional` app: the patcher sends `--ez <extra> false`
    only when that app is switched off and offered for the watch, `true` otherwise),
    `companionLink` (the app talks to the companion app), `knownSigner` (may be signed
    by a key other than the catalogue's own), `statusAuthority` (content authority where the app
    publishes its status; `${PKG_PREFIX}` allowed), `singleCopy` (copies under another package
    prefix are leftovers) and `chinaFrom` = `{"input", "profile"}` (only on an output with
    `from.input`: when the app's China mode is active the output is acquired and built from this
    source instead of `from`; the output id, package, install, grants and self-test stay the same.
    The input must exist, have the same `package` and `target` as the `from` input and differ from
    it; the profile must exist and cover the same family as `from.profile`. Samsung Health+ uses it:
    `shealth-phone-cn` + `shealth-cn`, the China build without the Local-only and China account
    patterns).

- **Schema 2 top-level keys** (the app reads these instead of hard-coding them; an engine that does
  not know the schema rejects the file):
  - `apps`: `[{"id", "outputs": [<output id>, ...], "optional": bool, "icon"}]`, the user-facing
    groups, in the order the app shows them. Every output is in exactly one app; `icon` is one of
    `monitor-heart`, `favorite`, `alarm`, `swap`.
  - `strings`: `{"<locale>": {"<key>": "<text>"}}`, locales are BCP-47 tags (`en`, `es`, `id`,
    `zh-CN`, `zh-TW`, ...). `en` is required and holds every key; the other locales only hold keys
    that exist in `en` (the app falls back to `en` for a missing one). Texts are plain single-line
    Unicode (no Android escapes, no control characters). Keys by convention: `app.<id>.title` and
    `app.<id>.summary`, `output.<id>.label` and `.purpose`, `pattern.<id>.description`,
    `inject.<name>.title` and `.description`, `stock.<package>.label` and `.reason`, and the shared
    grant reasons `grant.why.<name>` (referenced from `grants[].why`). The validator requires
    `app.<id>.title`, `app.<id>.summary` and `output.<id>.label` in `en` for every app and output,
    and every `why` to resolve. `scripts/migrate_catalogue_strings.py` produced the first table from
    the app's `strings.xml` files.
  - `platform`: Samsung platform facts, so no package name lives in the app:
    `companionApp` = `{"package", "watchPlugins": [<package>, ...]}` (Galaxy Wearable and one plugin
    per watch family: waterplugin = Watch4/4 Classic/FE, heartplugin = Watch5/5 Pro, watch6plugin =
    Watch6/6 Classic, watch7plugin = Watch7/Ultra, watchuniteplugin = Watch8/8 Classic/Ultra 2025 and
    newer); `healthPlatform` = `{"package", "settingsAction", "settingsComponent" ("pkg/.Class"),
    "titleViewId", "devModeViewId" ("ns:id/name"), "taps": 1..50, "requiredBy": [<output id>, ...]}`
    (the Health Platform service whose developer mode the user enables; `requiredBy` lists the
    outputs that need it); `logPackages` = `{"phone": [...], "watch": [...]}` (packages whose logs
    the app collects); `revertSettings` = `[{"device", "kind": "secureSetting", "key", "default",
    "why"?}]` (settings the revert puts back; the hidden-API exemption it removes is derived from
    the `globalSetting` grants).

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
  patterns are the minimum-age group below, `shm-setup-network-gate`, the sleep-apnea group below,
  `alarm-force-sound-vibrate`, `alarm-hide-launcher`, `alarm-relabel`, `shm-launcher-badge`,
  `shealth-launcher-badge`, `shealth-oobe-local` and `shealth-china-account-gate`.
- **Off-by-default patterns** (`"offByDefault": true`, optional patterns only): the build leaves
  them out unless the user turns them on — the patcher app switches each one off the first time
  it sees it (it remembers the optional ids it already knows, so a catalogue update never undoes
  the user's own choices), and `scripts/build_shealth.sh` skips them unless the environment
  variable named after the id is `1` (`shealth-oobe-local` -> `SHEALTH_OOBE_LOCAL=1`).
  Flip the flag to `false` (or drop it) to make the same pattern ship enabled by default. The
  users are `shealth-oobe-local` and `shealth-china-account-gate` (see "Samsung Health" below).
- **Option groups** (`"optionGroup": "<name>"`, optional patterns only, at least two members):
  the members are always on or off together, across inputs, so a partial skip is an error. The
  groups are `sleep-apnea`: `shm-sleep-apnea-support` and `shm-sleep-countries` (phone SHM) with `watch-apnea-country` and
  `watch-apnea-reeval-not-supported` (watch SHM); and `minimum-age`: `shm-min-age-22-to-16`
  (phone SHM) with `watch-min-age-22-to-16` (watch SHM). Both validators (`superpatcher/patterns.py` and
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
| `resXmlReplaceString` | replace the XML attribute values that are exactly `old` with `new` in the standalone XML resources under `res/` (never the manifest); `expect` counts changed files. The Python engine walks the decoded `res/**/*.xml` (not `values*`); the app engine finds the compiled XML entries by content (chunk header plus the string in the raw bytes), so it never loads resources.arsc |
| `adaptiveIconBadge` | `layers` maps `foreground` / `monochrome` to a list of path specs (`pathData` plus optional `fillColor`, `strokeColor` as `#AARRGGBB`, `strokeWidth`, `strokeLineCap`, `strokeLineJoin`). Every standalone XML resource under `res/` whose root is `adaptive-icon` is a candidate; in each listed layer that is present (a direct child of the root) the content becomes a `layer-list` of two items: the original drawable (its `android:drawable` attribute, or its single inline child, moved into the first item) and a 108x108dp `vector` with one `path` per spec, so a small badge is drawn over the launcher icon. Layers that are absent are skipped; a layer that is neither a drawable attribute nor one child, or that already carries the badge, fails; `expect` counts changed files. Both engines write the values with the types aapt2 gives them (dp dimension, float viewport and stroke width, ARGB colours, enums as integers); the app engine finds the compiled XML by content like `resXmlReplaceString`, so it never loads resources.arsc |
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

`minAppVersion` is 45 because the catalogue uses `outputs[].chinaFrom` and an input with
`obtain.yyb`: apps built before CI run 45 do not know them, so they would build Samsung Health+ only
from the global input and offer no China download. Run 45 is the release that adds both.

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
| 13 | `shm-sleep-countries` *(optional)* | The shared country lookup `util/r.o(country, list)` returns true when it is handed the sleep country list (`util/r.l`, by reference), so the home feature list registers `SleepController` (the Sleep tab, from which apnea onboarding starts) and the sleep content provider passes its country check | Regional check disabled: Samsung's sleep list has 79 countries (no AR, for example); without the tab the watch never gets the onboarding request and apnea stays off |
| 14 | `shm-home-tabs-fallback` | The home feature list (one entry per controller whose per-feature country list holds the saved SIM/network country) gets `BloodPressureController` first when it is missing and `Ecg2Controller` when it holds no ECG controller (`EcgController`, `ECGIhrnController`, `Ecg2Controller`); the home `TabLayout` is hidden instead of being measured with zero tabs | Regional check disabled: with an empty or unlisted country (no SIM, or a country outside every list) the home screen crashed with `ArithmeticException: divide by zero` in the tab indicator's `onMeasure`; since `shm-bp-worldwide` puts BP on every list, the ECG part is what keeps ECG for unlisted countries |
| 15 | `shm-ihrn-countries` | The ECG2 country check (`util/r.r()`) also returns true for the ECG-only country list (CN, MO, NZ, LI, TR, RS, UA, PE), and the home feature list skips `EcgController` when that check is true, so those countries get `Ecg2Controller` (ECG 1.3 + IHRN) instead of `EcgController` (ECG 1.1, no IHRN) | Regional check disabled: with an ECG-only saved country the IHRN controller never starts, so its Data Layer channel is never set up and the IHRN setup item and switch never appear on the ECG card; the IHRN sync and notification settings are gated by the same check |
| 16 | `shm-bp-worldwide` | The shared country lookup `util/r.o(country, list)` returns true when it is handed the BP country list (`util/r.h`, by reference), so every caller of the BP check passes: the home feature list (`BloodPressureController`), the BP sync channel (`rb/a.g()`), the BP notification setting, the watch BP module state sent at onboarding (`fa/c.b()`, `requestModuleDisableState`) and the ECG-only-country texts (`util/r.p()`) | Regional check disabled: BP is offered in every country, including unlisted ones and US (absent from Samsung's BP list) |
| 17 | `shm-provider-callers` | `SleepContentProvider.call` (sleep apnea status) and `BpContentProvider.call` (BP measure flag) accept `${PKG_PREFIX}.shealth` instead of `com.sec.android.app.shealth` | The providers check the exact caller package, so they refused Samsung Health+ ("Not allowed package"); the stock Samsung Health cannot reach them anyway, since it queries the old authorities. Their read permissions stay signature-level, so Samsung Health+ must be signed with the same key |
| 18 | `phone-rebrand-gms` | package -> `com.samsung.android.shealthmonitors` (manifest, permissions, authorities, exact package strings, share URI) | Same package and certificate as the watch app: the GMS Data Layer only delivers between equal AppKeys |
| 19 | `shm-wear-trusted-peers` | the `wear-trusted-peer-packages` meta-data is removed (see the watch table) | Same reason as on the watch |
| 20 | `shm-launcher-badge` *(optional)* | `adaptiveIconBadge`: the adaptive launcher icon gets a small LibreWrist badge at the bottom-right of the foreground and monochrome layers | The patched app is told apart from the stock Samsung Health Monitor in the launcher |

Profile: all of them in `gms`. Verified [V]: the decoded result (smali, manifest, resources) is
identical to the owner-verified `shm-phone-1.4.6.001-stock-mod-gms1` build (24 patched sites),
before `shm-home-tabs-fallback` and `shm-ihrn-countries` (2 more sites each) and `shm-bp-worldwide` (1 more site) were added.

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
| 6b | `watch-min-age-22-to-16` *(optional, group `minimum-age`)* | in the shared date-of-birth check (the method that logs `has date of birth` and calls `DateOfBirth.isPastThanOrEqual`), the feature's minimum age (`w()`: 22, or 18 for apnea 2.0) is overwritten with 16 right after it is read | The watch app runs its own age check against the date of birth synced from the phone and shows "cannot be used by people under 22" on ECG, BP and apnea; the phone pattern alone does not reach it |
| 7 | `watch-ecg-sensor-adapter` | sensor wrapper: type 0x1b (ECG) is always available and registered/unregistered through `EcgAdapter`; types 0x25 (IHRN) and 0x31 (SpO2/apnea) get an availability exemption through `WatchOverrides.sensorAvailability` | A re-signed app cannot reach the privileged ECG sensor; ECG comes from the Health Tracking Service through our own client (see [health-sdk.md](health-sdk.md)) |
| 7b | `watch-sensor-unregister-compat` | the three direct `SemSensorManager.unregisterListener(listener, int)` calls (the sensor wrapper and two in the sensor controller) go through `WatchOverrides.unregisterListener`, which picks the framework's overload by reflection (`(listener, int)` with any return type, else `(listener)`) and logs failures instead of throwing | The One UI 8 Watch framework **denies** `SemSensorManager.unregisterListener` to a re-signed app (see `globalSetting` below): hidden-API enforcement makes the direct call surface as `NoSuchMethodError` (`api=blocked`), and the reflection fallback finds no overload because `getMethods()` also filters hidden members. The apnea version detector (run because of the 0x31 exemption) unregisters after its timeout, and that `NoSuchMethodError` escaped its `catch Exception` and closed the app on open |
| 7c | `watch-bp-attribute-guard` | in the BP calibration path (`sl.g`), the `SemHrSensorAttribute` construction is routed through `WatchOverrides.makeHrSensorAttribute`, which builds it by reflection and returns null on any failure (`LinkageError` included); the following `setType`/`setDuration`/`setAttribute`/`registerListener` calls are skipped when it is null | The same hidden-API denial hits `SemHrSensorAttribute.<init>()V` on those watches; the uncaught `NoSuchMethodError` killed the process on every BP calibration attempt (3 crashes in one log). With the guard SHM takes its normal sensor-error path instead |
| 7d | `watch-bp-sensor-adapter` | in `sl.g` (the BP start path, common to measurement and calibration), the `registerListener(listener, 0x1c)` call of the manual BP sensor is replaced by `BpAdapter.start(listener, 0x1c)` (the int result was already discarded, so the replacement returns void); unregister reaches `BpAdapter.stop` through `WatchOverrides.unregisterListener` (row 7b), which routes type 0x1c before its reflection fallback | A re-signed app cannot register the privileged manual-BP sensor either; the BP data comes from the Health Tracking Service PPG green channel (see [health-tracking-protocol.md](health-tracking-protocol.md) 4.4), repackaged per sample as a `SemBpManualSensorEvent` with the `green_raw` int — the one field the BP consumer (`pl`) reads. The wrapper class `sa2` is not involved: nothing registers 0x1c through it, the registration is direct (unlike the ECG, where the wrapper return value gates the flow). Sensor 24 (HR), registered just before in the same method, is deliberately left alone: its events carry only motion/quality flags for the abort logic and the result HR is computed from the PPG by the native library |
| 7e | `watch-spo2-sensor-adapter` | in the sensor wrapper `sa2`, the three calls of the sleep SpO2 sensor (0x31) that the wrapper forwards to `SemSensorManager` are answered by `SpO2Adapter` instead: `c` (registerListener) -> `SpO2Adapter.register`, `d` (setAttribute) -> `SpO2Adapter.setAttribute`, `e` (unregisterListener) -> `SpO2Adapter.unregister`, each as an `insertAtMethodStart` guard on `type == 0x31` that returns before the original body (the availability exemption of row 7 stays); `WatchOverrides.unregisterListener` routes 0x31 to the same `unregister` | The privileged sleep SpO2 sensor cannot be used by a re-signed app. The adapter records raw red/IR PPG from the Health Tracking Service all night, computes the per-second values itself and answers the version query, the operation-mode command and `getData(start, end)` with `SemSleepWatchSpO2SensorEvent`s. Design, bundle contents, simulation mode and test driver: [spo2-adapter.md](spo2-adapter.md) |
| 7f | `watch-ihrn-sensor-adapter` | in the sensor wrapper `sa2`, `c` (registerListener) and `d` (setAttribute) for the irregular rhythm sensor (0x25) are answered by `IhrnAdapter.register` / `IhrnAdapter.setAttribute` (`insertAtMethodStart` guards on `type == 0x25`, same shape as row 7e); `WatchOverrides.unregisterListener` routes 0x25 to `IhrnAdapter.unregister` (row 7b reaches it); the availability exemption of row 7 stays | The privileged IHRN sensor (`SemSensorManager` type 37) is denied to a re-signed app (`registerListener` fails, SHM's watchdog cancels every sampling). The adapter builds SHM's own IHRN events (1 sample at a time, then 250 per event after the batch-mode attribute) from the green PPG and accelerometer trackers of the Health Tracking Service. Design, contract and open device questions: [ihrn-adapter.md](ihrn-adapter.md) |
| 7g | `watch-ihrn-quiet-services` | the three `startForegroundService` calls that start `IhrnServiceActivity` / `BodyOffDetectServiceActivity` (`tm0`, `p00`, `IhrnAlarmReceiver`) call `WatchOverrides.startService`, which tries `startService` and falls back to `startForegroundService` (remembered per service class) when the system refuses the background start; both services' `onCreate` call `WatchOverrides.startForegroundIfRequired` instead of `startForeground`, which only posts the notification in the fallback case; the channel importance constant in both `onCreate` goes from 4 (high) to 2 (low). Each site is a `replaceInClass` with `count` 1, so the patch fails if an anchor moves | These two services are not UI: they only keep the IHRN sampling alive. Without the `shm-watch` Doze allowlist grant (below) the system refuses background starts and the fallback keeps the old behaviour with a low-importance notification; with it no notification is shown. The real alert (`IhrnAlertActivity`) is not touched |
| 7h | `watch-ihrn-alert-overlay` | manifest: adds `<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>` (a `manifestExpect` with `expect` 0 first, so the patch fails if a new APK already declares it) | The irregular-rhythm alert (`IhrnAlertActivity`) is opened with `startActivity` from a background service, with no notification. The vendor build is exempt from the background activity launch block through `START_ACTIVITIES_FROM_BACKGROUND`, which is privileged and not granted to a re-signed app. With the `SYSTEM_ALERT_WINDOW` app-op allowed (grant below) the app is exempt again, the same BAL exemption the bridge uses on the watch |
| 8 | `watch-apnea-country` | the apnea "supported country" check returns true up front | Regional check disabled (the original is a fixed country list) |
| 9 | `watch-apnea-reeval-not-supported` | in the stored apnea version getter, a persisted `NOT_SUPPORTED` is re-evaluated instead of returned | A value persisted by an earlier build (regional check still on) would be sticky |

The `shm-watch` output also carries one **`globalSetting`** grant (the only device-wide setting any
grant touches): `hidden_api_blacklist_exemptions` gets the value
`Lcom/samsung/android/hardware/sensormanager/` appended.

- **What it does**: Android's ART hidden-API enforcement honours
  `Settings.Global.hidden_api_blacklist_exemptions`, a comma-separated list of member-signature
  **prefixes**; a process started after the write may use the members under those prefixes even
  when they are on the blocked list. Our prefix covers `SemSensorManager`, `SemEcgSensorEvent`,
  `SemHrSensorAttribute`, `SemSensorListener` and the rest of the package.
- **Why it is needed**: on Android 16 / One UI 8 Watch (Watch7/8 and newer, and any watch that
  gets that framework) those `com.samsung.android.hardware.sensormanager` members are
  `api=blocked` for non-system apps. The stock SHM is a preloaded system app and is allow-listed;
  our build is a re-signed `/data/app` user app, so every call it makes into that package
  is denied and surfaces as `NoSuchMethodError` — ECG (`SemEcgSensorEvent.<init>`, also built by
  our `EcgAdapter`), BP calibration (row 7c) and the apnea SpO2 probe (row 7b). See
  [watch-hidden-api-handoff.md](watch-hidden-api-handoff.md).
- **Scope caveat**: the exemption is device-wide, for every app, for that package prefix. It is
  not per-app: the prefix is what keeps it narrow.
- **How the patcher applies it**: it reads the current value and, only when our prefix is not
  already one of the comma-separated entries (an exact-entry comparison, so a different
  `sensormanager`-like entry is not mistaken for ours), writes `<current>,<value>` — or just
  `<value>` when the list is unset. Entries set by other tools are preserved verbatim. The value
  is single-quoted and restricted to `^L[a-zA-Z0-9_/$-]{0,127}$`, so it cannot break out of the
  shell command; a current value whose foreign entries hold a quote or a line break is refused
  (the grant fails) rather than rewritten. After the write the patcher force-stops the twin
  (`am force-stop com.samsung.android.shealthmonitors`), because ART applies the exemption list
  when a process starts. The watch self-test reads it back and reports it as granted when our
  prefix is present among the entries.
- **Revert**: the patcher's "Undo all changes" removes exactly our entry from the list (other
  entries stay) and deletes the setting only when no entries remain, instead of writing an empty
  value. By hand, the same is done with the adb commands in
  [installation.md](installation.md#2-install-order).

Plus two injected smali sets (profile `inject`, merged into one new `smali_classesN` dir
before `apktool b`): `watch-mod` (`watch-mod/build-watch-mod.sh`: `EcgAdapter`, `BpAdapter`,
`WatchOverrides` — `sensorAvailability`, `unregisterListener` and `makeHrSensorAttribute` —,
`UnregisterMethod` and the `shminterop.hsdk` Health Tracking client).

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

The `shm-watch` output also carries a **`deviceIdleWhitelist`** grant (`dumpsys deviceidle whitelist
+<pkg>`, reason `grant.why.battery_shm_watch`, shown in every locale of the catalogue strings). An
app on the Doze allowlist may start background services, which is what lets pattern
`watch-ihrn-quiet-services` start the IHRN services without a foreground-service notification.

It also carries an **`appop` `SYSTEM_ALERT_WINDOW` `allow`** grant (reason
`grant.why.overlay_shm_watch`). Together with pattern `watch-ihrn-alert-overlay` it exempts the app from the
background activity launch block, so the irregular-rhythm alert screen can open while the app is in the background.

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
| 11 | `alarm-semgesture-skip` | `w2/k`, `w2/j`: the `sem_gesture` lookup is skipped (not available to a non-system app) and the `pauseGesture` null path falls through; `w2/k.onStartCommand` starts our own double pinch detector (`Pinch.start`, only when the quick action setting is on), the off-body callback of `w2/j` passes the sensor value to `Pinch.onBody` (detection pauses off the wrist and resumes from a fresh state on it, as stock disconnects and reconnects the gesture), and `onDestroy` stops it right before `super.onDestroy()` (anchored there, not inserted at the method start, so it does not collide with `alarm-service-stop-on-destroy` in the applied-mode check); the manifest declares `uses-native-library lib_algorithm.gesture.samsung.so` (`required=false`) |
| 12 | `alarm-gms-observer-drop` | `CAlarmService.onCreate`: the GMS connection observer is removed (it would stop the service before the player exists) |
| 13 | `alarm-service-stop-on-destroy` | `w2/k.onDestroy` calls `t()` first so sound/vibration is never orphaned |
| 14 | `alarm-force-sound-vibrate` | `CAlarmService.r()`: play mode is always `0x11` (sound + vibrate); the stream is alarm usage |
| 15 | `alarm-link-send` | `o2/b.e()` (sendMessageToAll) body replaced by `Link.send(context, path, data)` |
| 16 | `alarm-hide-launcher` | Removes the `MAIN`/`LAUNCHER` intent-filter of `AlarmMainActivity` (the app only exists to receive phone alarms; the activity itself stays) |
| 17 | `alarm-relabel` | Application and `AlarmMainActivity` label -> new string `interop_app_label`: "Phone alarms" (default), "Alarmas del teléfono" (`values-es`); vendor strings untouched |

Injected smali set (profile `inject`, new `smali_classesN` dir): `alarm-fork` = `Link`,
`LinkActivity`, `LinkReceiver`, `Pinch` and `NativeWrapper` from `shm-bridge/alarm-fork/src` (`javac --release 11`, `d8
--min-api 34`; `java_to_smali` in `scripts/lib/common.sh`).

Double pinch (`alarm-semgesture-skip`): SELinux only lets platform and a few vendor domains find
`sem_gesture_service`, and the re-signed fork runs as `untrusted_app`, so the vendor gesture
manager is unreachable. Instead `Pinch` loads the public native library
`lib_algorithm.gesture.samsung.so` (listed in `/system/etc/public.libraries-gesture.samsung.txt`,
hence the `uses-native-library` entry), feeds it linear acceleration and gyroscope samples and,
on a double pinch (gesture 256, debounced 1.5 s), calls the app's own `SmartGestureEvent`
listener with `QUICKACTION`; the app then snoozes or dismisses as set on the watch
(`setting_gesture_quick_action_alarm_alert`). The library's JNI symbols fix the class name
`com.samsung.android.lib_algorithm.gesture.samsung.NativeWrapper`, so that one own class lives
outside the `shminterop` package (the patcher's `DexInjection` allowlists exactly that type).
The catalogue's `minAppVersion` is 28 because it references `Pinch` and `BpAdapter` (the BP
sensor adapter, watch row 7d), which exist only in patcher builds from CI run 28 on; an older
app would build a fork that crashes when an alarm rings or a BP measurement starts. That run
number is only correct while run 28 is in fact the first release built after both features land
on main: the injected dex ships inside the app bundle, not with the catalogue, so a release cut
from an earlier commit would leave run-28 apps able to accept this catalogue without carrying
the classes it references. If that ever happens, `minAppVersion` must move to the run number of
the first release that does bundle them.
Known limitations: the system-level `pauseGesture` during the alarm is unavailable; verified
on an SM-R920 only, end to end with a phone-synced alarm (Watch7/8 use model types 1/2, untested).

Verified [V]: the decoded result matches the previous owner-verified fork build (same
smali, resources and manifest after normalising the previous package marker). Differences:
patterns 16 and 17 (new), `Link*` (log strings translated to English; d8 numbers the two
`Link` lambdas in the opposite order), and the signing files. Not verified on a device.

## Samsung Health 7.00.6.011

New target (profile `shealth`, input `shealth-phone`): the full Samsung Health phone app,
**renamed** to `${PKG_PREFIX}.shealth` (label "Samsung Health+") and re-signed with the local key,
so it installs next to the stock, Samsung-signed app. Rationale and per-feature analysis:
[samsung-health-survey.md](samsung-health-survey.md).

```bash
scripts/build_shealth.sh                # -> $DIST_DIR/shealth-7.00.6.011-shealth.apk
SHEALTH_OOBE_LOCAL=1 scripts/build_shealth.sh   # include the optional Local-only mode
SHEALTH_CHINA_ACCOUNT_GATE=1 scripts/build_shealth.sh   # include the experimental China account pattern
```

In the patcher app it is opt-out like every optional app (the other is Health Monitor, `shm-phone` +
`shm-watch`, one switch): the "Samsung Health+" switch on the Acquire step controls the input, its patterns
appear in Customize and the output joins the phone install. Only the bridges and, on Wear OS 5, the alarm
fork (`alarm-watch`) are mandatory.

Why two apps: the watch's Samsung Health pins the phone app's certificate
(`wear-trusted-peer-packages` = `com.sec.android.app.shealth` with Samsung's digest) and silently
drops GMS traffic from any other signer, and a re-signed watch build is not possible (it needs
signature|privileged sensor and Health Services permissions). So the **stock** app (from Play)
keeps the watch connection and uploads to the Samsung cloud, and the **patched** copy signs in to
the same account and downloads the history from the cloud, where it computes the phone-side
features the watch model whitelist hides. Cloud identity is signature- and package-agnostic
(survey, section 1). Data in the patched copy lags by the cloud sync cadence (no SPP push on
non-Samsung phones). Both apps declare the same Samsung Account redirect
(`sasdk://samsunghealthhybridweb/login`): signing in to either may show an app chooser; pick the
app that started the sign-in.

| # | Pattern | What it does | Why |
|---|---|---|---|
| 1 | `shealth-res-webp` | renames four drawables whose bytes are WebP/WebP/JPEG but whose stored name ends in `.png` (`element_index_shape`, `coaching_jetlag_index`, `heart_health_sleep_illustration` -> `.webp`; `phr_expanded_chart_image` -> `.jpg`); resources keep name and id | Samsung's pipeline stored them without crunching and aapt2 refuses the mismatched extension, so `apktool b` fails on the stock tree; Android decodes by content, so the rename is invisible at runtime |
| 2 | `shealth-ai-provider-start` | `GenerativeAiProvider.a()` (started from `ExternalSettingsProvider.onCreate`) returns early while `ContextHolder.getContext()` is null | Content providers are created before `Application.onCreate` sets `ContextHolder`; the coroutine `a()` starts reached `SharedPreferences` through the null context and killed the main process at every cold start (`NullPointerException` in `giw.D`). Settings still gets the menu: its `get_menu_list` call starts the same refresh once the application is up |
| 3 | `shealth-feature-dev-ui` | `FeatureManager.isEnabled()` returns true, and the Settings ▸ About entry (`HomeSettingsAboutActivity.L1`) no longer requires Samsung developer mode | Exposes the app's built-in feature-flag list (`FeatureListActivity`): ~400 local toggles over `final_feature_list_pref`, editable at runtime; no server writes those prefs |
| 4 | `shealth-vitality-tile` | default of the `vitality_show_tile_without_capabilities` feature flag -> true | The Energy Score tile and its HRV no-data states otherwise wait for the watch to advertise `tracker_feature.vitality_score`; with the flag the tile renders and the phone-side computation (`VitalityDataGenerateWorker`) feeds it from synced data |
| 5 | `shealth-rename-manifest` | `manifestRebrand` `com.sec.android.app.shealth` -> `${PKG_PREFIX}.shealth` (package, own-namespace permissions and authorities); the 11 `com.samsung.android.app.shealth.*` authorities -> `${PKG_PREFIX}.shealth.app.*`; the 7 custom permissions outside the package namespace -> `${PKG_PREFIX}.shealth.permission.<NAME>` (declarations and the activity/provider attributes using them); application and launcher label -> "Samsung Health+" (literal: the app engine does not load the 175 MB resource table) | Android refuses a second package that declares the same authority or permission with another certificate |
| 6 | `shealth-rename-code` | smali: every exact `"com.sec.android.app.shealth"` const-string, every authority literal (bare and `content://` prefix), the OOBE broadcast permission, the account type `com.samsung.health.auth`, the app-settings URI `package:<pkg>` and the `:remote` process name -> the new names | Keeps the code consistent with the renamed manifest (self package checks, content URIs, sync authority, AccountManager type, permission prompts that open the app settings page, remote-process checks) |
| 7 | `shealth-rename-account-xml` | `resXmlReplaceString`: account type `com.samsung.health.auth` (3 authenticator configs + sync adapter) and sync authority `com.sec.android.app.shealth.datastore` (2 sync adapters) -> the new names | Only one package can register an authenticator type; the sync adapter must point at the renamed datastore provider or cloud sync never runs |
| 8 | `shealth-capability-gates` *(optional)* | every inlined `service_capability_list` check (`getServiceList` + `List.contains`; 3 sites on 7.00.6.011: Vitals `xvx.J0`, Training load `t950`, fitness index `xao`) is forced true, and the per-sleep Vitals device check (`xvx.K0`; `wvo.Q` on the China build) returns `TRUE` | The watch advertises its service list from `model_service_capability.json`, which only whitelists Galaxy Watch8/Ultra-class models, and the patched copy has no watch at all; these features compute on the phone from synced sleep/exercise history. Heart health score, BP trend and hearing stay off: they need a live connected watch node |
| 9 | `shealth-shm-providers` | the `content://com.samsung.android.shealthmonitor.sleep` and `.bp` URIs and the two `uses-permission` entries guarding them (`sleep.READ_SLEEP_APNEA_STATUS`, `bp.READ_MEASURE_FLAG`) -> `com.samsung.android.shealthmonitors.*` | The phone Health Monitor is renamed (`phone-rebrand-gms`), so Samsung Health+ asked a provider that does not exist and showed no Sleep apnea card under Sleep. Pairs with `shm-provider-callers` on the Health Monitor side |
| 10 | `shealth-launcher-badge` *(optional)* | `adaptiveIconBadge`: the adaptive icons (9 on 7.00.6.011: app icon, 4 widget icons, 4 shortcut icons; 12 on the China build, which keeps 3 unused `mipmap-anydpi*/ic_launcher*` copies) get a small LibreWrist badge at the bottom-right of the foreground layer and, for the app icon, of the monochrome (themed) layer | The renamed copy installs next to the stock app and would otherwise look identical in the launcher |
| 11 | `shealth-oobe-local` *(optional, off by default)* | the Samsung Account state getter (`j9v.s0`) reports `LOG_IN`, so the mandatory sign-in stop state right after OOBE never fires | For running the app without a Samsung Account; the first-run wizard itself has no sign-in step, this only removes the gate after it. Off by default: cloud sign-in is expected to work on the re-signed copy (the code has no signature gate); enable it (or flip `offByDefault` in the catalogue) if sign-in does not work on this device |
| 12 | `shealth-china-account-gate` *(optional, off by default, experimental)* | regional check disabled: in the OOBE coroutine that runs right after sign-in (`lcf`, log `Global APK, China SACC, NO Smart Switch`), the result of the account-country check `ozn.Y` (`CountryLookup.getAccountCountryCode == "CN"`) is forced false, so the app no longer opens `HomeAppCloseActivity` with `OOBE_GLOBAL_APK_CHINA_SACC_APP_CLOSE` ("phone must be purchased in the country/region where your Samsung account was created") | The international build closes itself for a Samsung Account registered in mainland China ([#8](https://github.com/LibreStatic/LibreWrist/issues/8)). Only this call site is touched: `ozn.Y` has ~20 other callers that switch China-account features. Experimental: the China/international server split is the build flavor (`fs90.u()`, fixed at compile time), so this build keeps talking to the international servers and cloud sync for a CN account may not work; the real fix is a China build input |

Anchors: semantic method names and literals of the official APK (`isEnabled()Z`, `L1()Z`,
`getGroupVitality`, `vitality_show_tile_without_capabilities`), content selectors in place of
obfuscated names (the Samsung Account state getter is the method that names both
`SamsungAccountManager$State` `LOG_IN` and `LOG_OUT` plus `"getContext(...)"`; the per-sleep Vitals
check is the `(SleepItem, …)Object` method that logs `SHEALTH#Vitals#AvailabilityHelper` and reads
`getSleepTrackDeviceList`), the `getServiceList: size=` log literal of the inlined capability checks,
the four drawable names and one obfuscated name pinned by the input hash: `ozn.Y` (account country is
CN, inside the file holding the `Global APK, China SACC` log). The same patterns apply to the
international 7.00.6.011 and the China 7.00.6.012 build (obfuscated names differ: `j9v.s0` / `q7v.n0`,
`xvx.K0` / `wvo.Q`); the vitality-tile pattern also matches its own patched form (the optional
`const/4` before `at`) so the app engine's applied-mode self-check finds it. A new Samsung Health
version needs the anchors checked; the engines fail instead of guessing.

China build: profile `shealth-cn` builds the same Samsung Health+ from input `shealth-phone-cn`
(7.00.6.012, flavor `chinaFinalRelease`, from 应用宝), selected through the output's `chinaFrom` when
China mode is on. It carries patterns 1-10: the account sign-in is already optional on that flavor
(so no `shealth-oobe-local`), and its China-account check does not exist (so no
`shealth-china-account-gate`). It talks to Samsung's China servers (`*.samsunghealthcn.com`). Survey:
[samsung-health-survey.md](samsung-health-survey.md) section 5.
`SHEALTH_*` env vars work the same: `scripts/build_shealth.sh shealth-cn`.

`renameFile` only changes the extension (`<stem>.<ext>` -> `<stem>.<new ext>`). The Python engine
renames the file in the apktool tree (apktool paths); the app engine resolves the same
`res/<type>[-qual]/<name>` through resources.arsc to the stored path, which a release APK may have
shortened (`res/-0_.png`), renames that zip entry's extension and rewrites the path string in the
table. Not yet verified on a device.

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
