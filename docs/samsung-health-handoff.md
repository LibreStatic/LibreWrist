# Hand-off: Samsung Health patches — state and remaining work

For the model taking over: read this, then `docs/patches.md` § "Samsung Health 7.00.6.011",
then the two surveys. Everything below is verified against the sources listed; do not re-derive.
(This file was the survey-phase hand-off; its questions are all answered in the documents above.)

Status: **phone patches implemented and the build verified statically; watch survey closed
(hardware no-go); nothing installed on any device; nothing committed.**

## Documents

- `docs/patches.md` — catalogue format, the `offByDefault` flag, and the Samsung Health pattern
  table (anchors, rationale, build command).
- `docs/samsung-health-survey.md` — phone APK analysis: cloud identity (signature-agnostic),
  OOBE, feature gates, go/no-go.
- `docs/shealth-watch-survey.md` — watch APK analysis: why Vascular Load / Antioxidant are a
  hardware no-go on the GW5P (sensor capability probe fails), with log evidence.

## What exists (implemented in this phase)

- **Input**: `apks/shealth-7.00.6.011.apk` (stock Samsung Health phone 7.00.6.011, hash-pinned
  in `apks/inputs.json` + `apks/SHA256SUMS`, row in `apks/README.md`).
- **Catalogue** (serial 18, signed, `minAppVersion` 7): family `shealth-phone` (package
  `com.sec.android.app.shealth`), profile `shealth`, output `shealth-phone`, installOrder entry.
  Patterns, in profile order:
  1. `shealth-res-webp` — `renameFile` ×4 (four drawables carry WebP/JPEG bytes under a `.png`
     name; aapt2 refuses them, so `apktool b` failed without this).
  2. `shealth-feature-dev-ui` — `FeatureManager.isEnabled()` → true;
     `HomeSettingsAboutActivity.L1()` no longer requires Samsung developer mode. Exposes the
     in-app feature-flag list (Settings ▸ About, ~400 runtime toggles over
     `final_feature_list_pref`).
  3. `shealth-vitality-tile` — default of feature key `vitality_show_tile_without_capabilities`
     → true (Energy Score tile does not wait for the watch capability advertisement).
  4. `shealth-oobe-local` — **optional, `offByDefault: true`**:
     `SamsungAccountManager.s0` → always `LOG_IN`, so the mandatory sign-in stop state right
     after OOBE never fires (account-less local mode).
- **Engine/schema**: new `offByDefault` pattern key validated in both engines
  (`superpatcher/patterns.py`, patcher-app `engine/.../Catalogue.kt`); the patcher app switches
  each off-by-default pattern off the first time it sees it and remembers the optional ids it
  knows, so catalogue updates never undo the user's choices (`app/.../data/SetupSession.kt`,
  `seedOffByDefault`). New op `renameFile` (only the extension changes): Python renames the file
  in the apktool tree; the app engine only checks the arguments, because it never recompiles
  resources (and Samsung Health's 175 MB resources.arsc would not fit a phone's heap through
  ARSCLib). `OracleTest` maps the renamed files back before comparing.
- **Patcher app**: switch "Samsung Health (optional)" on the Acquire step, on by default
  (opt-out; `SessionState.samsungHealth`, persisted as `includeSamsungHealth`; `inputIds`/`outputIds`
  add `shealth-phone`). The input is the APKMirror release `samsung-health-7-00-6-011-release`
  (`samsung-electronics-co-ltd/s-health`); its zip entries match the Galaxy Store install, only the
  APK signing block differs. The watch app 7.00.0.131 is on APKMirror too
  (`samsung-health-wear-os/samsung-health-wear-os-7-00-0-131-release`) but is not patched. The stock app is removed through the
  existing signature-conflict reinstall flow (the user confirms). Strings in all 18 locales.
- **Build script**: `scripts/build_shealth.sh` — skips every `offByDefault` pattern of the profile
  unless the env var named after its id is `1` (`SHEALTH_OOBE_LOCAL=1`).
- **Output**: `dist/shealth-7.00.6.011-shealth.apk` — stock package, OpenSHM signature.
- **Analysis data** (never in the repo): `$WORK_DIR/shealth-survey/` — phone APK, `res/`,
  per-dex jadx trees `java-perdex/classes{,2..8}/sources`, lossless apktool smali `smali/`,
  driver `run_jadx_perdex.sh`; `$WORK_DIR/shealth-watch-survey/` — watch APK 7.00.0.131,
  `decoded/`, and `init-logcat.txt` (the sensor-probe evidence).

## Owner decisions (settled; do not re-litigate)

1. **Renamed package, two apps** (supersedes the earlier "stock package name" decision). The
   watch only syncs with the Samsung-signed phone app, so the stock Samsung Health from Play stays
   installed for the watch, and the patched copy installs next to it as `${PKG_PREFIX}.shealth`
   ("Samsung Health+"), signed in to the same Samsung Account and fed from the Samsung cloud
   (data lags by the cloud sync cadence). Both apps handle the sign-in redirect, so Android shows
   a chooser at sign-in. The soft gates (Vitals, Training load, fitness index) are forced on by
   `shealth-capability-gates`.
2. **Local-only OOBE patch stays off by default.** The earlier "Iniciar sesión" stall happened
   on the **stock** app (flaky Custom-Tab redirect to `ResponseReceiverActivity`; closing the
   tab, clearing Samsung Health from recents and retrying fixed it), so it says nothing about
   re-signing. If the re-signed build's sign-in fails on device, the user switches the optional
   pattern on in the patcher's Customize step (or `SHEALTH_OOBE_LOCAL=1` for the script build).
3. **No watch build.** Vascular Load and Antioxidant on the GW5P are hardware no-gos: the watch
   app (7.00.0.131) ships them fully implemented, but `SensorCapabilityInitializer` logs
   `initVascularLoadSensorCapability available=false` and
   `initAdvancedGlycationEndproductSensorCapability false` — the sensorhub lacks the
   capabilities. Forcing the pref would produce empty UI. The GW5P stays stock; the phone's
   `wear-trusted-peer-packages` meta-data (pinning the stock watch certificate) stays valid —
   no `shealth-wear-trusted-peers` pattern is needed.
4. **AI Brief / Now Brief**: no-go (gated by Samsung platform `SemFloatingFeature`, OS-level).
5. The owner plans to validate the findings later with a full GLM 5.3 pass — the surveys plus
   the evidence trees are the inputs for that.

## Verified vs not verified

Verified (trust it): catalogue `scripts/catalogue.py check` + `verify` (serial 15); python suites
(`scripts` 26 tests, `superpatcher` 58 tests); patcher-app `:engine:test` and `:app` compile
(JDK 17: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk`); the build pipeline end-to-end; the output
APK statically (badging package/version, 19,258 res entries identical to stock, arsc references
the renamed drawables, old names absent, `apksigner` v2/v3 with the OpenSHM cert).

Not verified (the remaining work): everything on-device —

1. **Sign-in**: run the AVD protocol in `docs/samsung-health-survey.md` § 1 (disposable AVD, the
   owner types credentials, watch the three hops). Never the owner's devices, never
   `emulator-5554`/`emulator-5570`.
2. **Install**: remove stock Samsung Health (owner's explicit decision), install `dist/…apk`,
   let the GW5P pair (no watch patch; pairing should just work — same package, GMS data layer).
3. **Feature checks**: Settings ▸ About shows the Feature list (patch 2); Energy Score tile
   renders without the watch capability (patch 3); VL/antioxidant tiles absent (expected);
   OOBE completes without an account bounce on a `SHEALTH_OOBE_LOCAL=1` build (patch 4).
4. **Watch data flow**: health data arrives on the phone build with stock removed. Analysis says
   the live fan-out (`message.a.i`) is self-addressed and safe; the only signature-gated fan-out
   (`WearableMessageLibBroadcastManager.broadcastData`) is the legacy Tizen-era nonce flow,
   inert on Wear OS. If data does not flow, that legacy check is the first suspect (force true
   in a follow-up pattern).

## Working notes

- **Obfuscated-name map (phone 7.00.6.011)**: `cln`=OOBEManager, `b11`=AppStateManager,
  `j9v`=SamsungAccountManager, `l220`=TermsOfUseManager2, `m9v`=SA-app intent dispatcher,
  `aa1`=sasdk web flow, `e9v`=SA availability, `ozn`=build flavor/country, `dwc`=GMS wearable
  helper, `tf80`=WearOsNodeStatusHelper, `ye80`=VL capability check, `xg80`=phone capability
  advertisement, `f73`=BpTrendCountryUtil (VL visibility ≠ US account).
- **Obfuscated-name map (watch 7.00.0.131)**: `mg7`=SensorCapabilityInitializer,
  `p4c`=prefs flag helper (C read default-false, b0 write), `zte`=VL capability blob
  (1002), `a47`/`oj0`=map/heart-health blob (1000), `nsb`=SemSensorManager.isAvailable
  (cached), `rue`/`tj0`=VL/BP-trend tracker availability, `tub`=tracker registry,
  `lub`=tracker id enum, `r7c`=embedded Samsung cert set (used by HealthDataProvider only),
  `lg9`=heart-health version.
- **Trees**: phone jadx per-dex is near-complete but `classes8` defeats jadx at any heap (use the
  lossless `smali/` for that dex); the whole-APK jadx run in `java/sources` has the best
  cross-references for ~38% of classes. Watch: full smali in `decoded/` (3 dex, no jadx run yet
  — names are largely unobfuscated under `com/samsung/android/wear/shealth/`).
- **Shared worktree**: another (human-driven) session has uncommitted work in this repo
  (`watch-sensor-unregister-compat` pattern + watch-mod/WearableChecks/translations). Do not
  revert it; the catalogue is shared state — after any edit run `scripts/catalogue.py check`
  and re-sign (`scripts/catalogue.py sign`, bumps the serial). Keep catalogue edits through
  `scripts/catalogue.py` (`load_raw`/`write_catalogue`) so the canonical formatting holds;
  `minAppVersion` is 7: older patcher builds cannot parse `offByDefault`/`renameFile`.
- **Constraints that still bind** (repo rules + the original hand-off): English in
  docs/code/commits; catalogue patches are data, never code; nothing from the APKs in the repo
  (no smali/dex/decompiled snippets — class/method references only); survey data stays in
  `$WORK_DIR`; read-only on the owner's devices (the watch restart + logcat capture for the
  sensor probe was owner-approved); never type the owner's Samsung credentials; commit only when
  the owner asks (as FacuM, no AI attribution); configuration only via `config.env`.
