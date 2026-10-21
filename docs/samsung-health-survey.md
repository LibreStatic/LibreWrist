# Samsung Health survey (account sign-in, side-by-side build, gated features)

Status: **survey + implemented phone patches** (owner approved the follow-up). The patches live
in the catalogue (profile `shealth`, see [patches.md](patches.md) "Samsung Health 7.00.6.011")
and `scripts/build_shealth.sh` produces a statically verified, signed build
(`dist/shealth-7.00.6.011-shealth.apk`) — **not installed on any device yet**. The watch-side
follow-up (Vascular Load on the GW5P) was surveyed and is a **hardware no-go** — see
[shealth-watch-survey.md](shealth-watch-survey.md). Data lives in `$WORK_DIR/shealth-survey/`
and `$WORK_DIR/shealth-watch-survey/` (APKs, decoded trees, evidence logs). Nothing from the
APKs is in the repo; these notes reference class/method names only.

Method: jadx 1.5.6 per-dex (`-j 4 -Xmx8g`; whole-APK mode OOMs even at 10g) for readable sources,
plus a full apktool 3.0.3 decode (`d -r`) for lossless smali greps. One dex (`classes8`) defeats
jadx at any heap size (dies at the same class ~45%); its packages are still covered by smali.

Obfuscated-name map used throughout (from this build): `cln` = `OOBEManager`, `b11` =
`AppStateManager`, `j9v` = `SamsungAccountManager`, `l220` = `TermsOfUseManager2`,
`ozn` = build-flavor/country utils, `m9v` = SA-app intent dispatcher, `aa1` = sasdk web flow,
`dwc` = GMS wearable helper, `tf80` = WearOsNodeStatusHelper.

## 1. Samsung Account sign-in and cloud sync after re-signing

### What the app actually sends

There is **no signature and no package name in any cloud request**. Every cloud-facing surface
identifies with the embedded `client_id` `1y90e30264` plus Samsung-Account-issued tokens and
device hashes:

| Surface | Endpoint | Identity material | Where computed |
|---|---|---|---|
| SA web sign-in (embedded sasdk) | account.samsung.com via Chrome Custom Tab | `client_id`, `redirect_uri=sasdk://samsunghealthhybridweb/login`, PKCE `code_verifier` | `aa1.r/t`; scheme declared in manifest |
| SA app sign-in (when SA app installed) | intents `com.msc.action.samsungaccount.SIGNIN_POPUP` etc. | extras `client_id`, `mypackage="com.sec.android.app.shealth"` | `m9v.e/f/c` (literals) |
| Token exchange | `POST /auth/oauth2/token` (account.samsung.com) | auth code + `client_id` + verifier, done in-app | `SamsungAccountRequest.getTokenByAuthCode` |
| Health backend JWT | `https://api.samsungknowledge.com/knowledge-ws/v1.3/auth/access_token` | `Authorization: Bearer base64("at=<SA token>,su=<url>")`, query `pvid`/`svid` | `jwt/a.java`, `jwt/v2/HealthIdJwtApi` |
| Health data server | `https://shealth-api.samsunghealth.com` | `app_id=1y90e30264`/`X-SC-App-ID`, `x-sc-uid`, `x-sc-access-token`; `User-Agent=model;build;healthsdk=7.00.6.011`; `ai`=SHA-256(android_id), `sg`=SHA-256(SA userId) | `DataServerInterface`, `HeaderUtil`, `FingerPrintUtil.getHash` |
| SCloud/SCSP | `ers.samsungcloud.com`, `api.samsungcloud.com` | `x-sc-app-id=1y90e30264`, `x-sc-cdid/x-sc-pdid`=PBKDF2(Android ID), SA access token | `scsp/framework/core/identity/api/IdentityHeader` |
| SMP push | `com.sec.spp.push` (SPP agent, Samsung-ROM only) | `sppAppId=12fb0a5b3859f81c` literal | `SmpInitializerImpl`, `SppInterface.register` |
| AI assistant | `https://api.samsunghealthai.com` | `X-SA-GUID`, SA access token, session token | `aiassistant/data/remote/interceptor/HttpHeaderType` |

The embedded SA SDK (`com.osp.app.signin.sasdk.*`) contains **no certificate digest computation**
(no `MessageDigest`, no `GET_SIGNATURES`; the only `getPackageInfo` is an exists-check in
`browser/BrowserUtil`). All cert hashing in the app (`FingerPrintUtil.getFingerPrint`) verifies
*other* apps (SDK-policy callers, easyMover, the watch service) — never itself.

### The user-override scheme: spoof package+signature towards the cloud

The survey says the cheaper scheme is enough: **there is nothing to spoof**. The cloud never
sees the APK's package name or signing certificate — identity is `client_id` (a build-time
constant) + SA tokens bound to the user account + device hashes (Android ID, SA GUID). A
re-signed APK (any package name) presents the same credentials. The two places where the package
name crosses a trust boundary are local, not cloud:

- **SA app handoff** (`m9v`): passes `mypackage="com.sec.android.app.shealth"` in intents to
  `com.osp.app.signin`. Whether the SA app validates the caller is decided *in the SA app*, not
  here — and on a non-Samsung phone the SA app is absent, so `DataConfig` flips
  `SAMSUNG_ACCOUNT_CLIENT=false` and the app uses the **embedded Custom-Tab flow**, which is
  fully self-contained (the redirect scheme is a static literal, not derived from the package).
  On a **Samsung** phone the SA app is present, so the app took the handoff path and failed
  (issue #19: the SA app refuses the renamed, re-signed copy and answers that a Samsung account
  already exists). Pattern `shealth-samsung-account-absent` makes the app behave as if the SA
  app were absent on every phone: the package check in `DataConfig`, the `AccountManagerProvider`
  metadata checks, the embedded SDK's SA-presence checks and the stored account type
  (`pref_health_account_type`) are all neutralised.
- **SakAttestation** (`SakAttestation` sets `AttestationParameterSpec.setPackageName(...)`,
  literal): a renamed app attesting the stock name produces a key-attestation chain that doesn't
  match its UID package. Only matters if/when a server-side flow uses that attestation.

### Verdict for Q1

- **Sign-in and sync are signature-agnostic** in the code. A re-signed copy — stock name or
  renamed — presents the same credentials via the embedded sasdk flow.
- **Earlier "stall" was not caused by re-signing.** The owner's earlier real-device attempt that
  stalled at "Iniciar sesión" ran the **stock, Samsung-signed** app (see
  `shm-remake2/docs/protocol-gaps-2026-10-03.md`): Chrome delivered the
  `sasdk://samsunghealthhybridweb/login` redirect to `ResponseReceiverActivity`, which closed
  without processing it. Closing the Custom Tab, removing Samsung Health from recents and
  retrying completed the sign-in. It is a flaky Custom-Tab hand-off, not a signature gate, so
  re-signing is expected to sign in the same way; the Local-only OOBE patch still ships **off by
  default** (`offByDefault`) as the fallback if the re-signed build fails on device.
- The syncadapter declares accountType `com.osp.app.signin` (OS-level SA account) against
  authority `datastore`; without the SA app the app falls back to its own authenticator
  (`com.samsung.health.auth`, `syncadapter_samsung_mobile_web.xml`).
- Push (SPP) simply never registers on a non-Samsung phone (`SMP_5002 SPP is not installed`) —
  expected, non-blocking.

**Empirical test (needs the owner, disposable AVD, no owner devices):** install the unmodified,
re-signed APK; run OOBE sign-in with a throwaway SA account; watch three hops: (1) Custom Tab
returns to `sasdk://samsunghealthhybridweb/login` with an auth code; (2)
`POST /auth/oauth2/token` succeeds; (3) a `X-SC-App-ID 1y90e30264` request to
`api.samsungcloud.com` succeeds. All three passing = sign-in + sync confirmed working.

## 2. Side-by-side build (renamed copy next to stock)

### What the rename touches (static findings)

- Manifest: 28 `<provider>` authorities; **16 derive from `com.sec.android.app.shealth.*`, 12
  from `com.samsung.android.app.shealth.*`** (they do *not* follow the package name and need an
  explicit map), plus 8 custom `<permission>`s (some `signature`) and custom actions.
  The existing `manifestRebrand` op (`superpatcher/engine.py:723`) covers the package attribute
  and `com.sec...`-prefixed authorities/permissions only — it must be extended with a
  data-driven authority map (and custom actions) for this APK. A side-by-side install fails on
  duplicate authorities/permissions otherwise.
- Code: **254 occurrences of the literal `"com.sec.android.app.shealth"` across 167 files**.
  Classification from the watch-link analysis:
  - *self-addressing, must be rewritten*: ~40 `intent.setPackage(...)` sites (BootReceiver,
    widgets, syncmanager dispatch in `message.a.i` and `connectivity.b.a`), `setClassName` calls
    (`TrackerSensorCommonIntentService`, pedometer/Bixby services, sport cards),
    `CallerIdentity.isOtherApp`, `DataRequestAdapter` self-exemptions and blob channels,
    `HealthDataStore` platform binding, the wearable identity JSON (`ml80`, `qcn`, `wj80`), and
    `WearableMessageLibConstants.SHEALTH_PACKAGENAME` (which also feeds
    `WearableMessageLibSignatureCheckerLib.checkSignature` — 5 embedded Samsung certs).
  - *must stay*: literals addressed to other apps (`com.samsung.android.wear.shealth`,
    `watchmanager`, `shealthmonitor.*`, `com.samsung.android.service.health`), and the
    `com.samsung.android.app.shealth.*` *class-namespace* strings used as protocol endpoint
    names (`provider`, `wearable.syncmanager`) and intent actions.

### The blocker: the watch

The watch link is **GMS Wear OS data-layer only** (no SAP/SAAgent in this APK). Two listeners
(`WearableListenerService` on `wear://*/samsung_health/...`); the watch app
(`com.samsung.android.wear.shealth`) is paired to the phone app via the manifest meta-data
`wear-trusted-peer-packages` and the watch side's own pairing — both anchored to the stock
package name. Routing itself is URI/node-based, but the app's internal fan-out re-addresses
payloads with `setPackage("com.sec.android.app.shealth")`, and the phone advertises itself to
the watch in manager-capability JSON using that literal.

**Verdict: a renamed copy next to the living stock app will not receive watch health data.**
GMS delivers per trusted-peer pairing (stock), and even intercepted payloads would be re-routed
into the stock app by the internal `setPackage` fan-out. Disabling stock (`pm disable-user`)
removes the contention on the phone side, but the *watch-side* pairing still targets the stock
package + Samsung certificate; bridging that would mean patching the watch APK too (bigger
effort, not surveyed here).

Options, in the order they should be considered:

1. **Patched copy keeps the stock package name; the stock app is removed** (owner's explicit
   decision at install time; it is a user app on the Motorola, uninstallable normally). This is
   the chosen path. Watch pairing, signature checks, attestation and all self-addressing stay
   coherent because the package name is unchanged. Loses: the stock app's own UI/cloud usage.
2. **Rename with full literal rewrite**: viable for cloud/UI experiments and Health-Connect-only
   usage, but watch-data features stay dead until the watch side is handled. Effort below.
3. True side-by-side (stock alive + renamed copy with watch data): **no-go** on phone-side
   evidence alone.

## 3. OOBE without an account

- **The wizard itself has no sign-in step.** `res/res/navigation/oobe_nav_graph.xml`:
  Welcome → Consent (terms/PP/PS + optional AUTOSYNC/AGE consents) → permissions →
  `OOBEManager.done` (`cln.y0().w0(activity)`).
- Persistence: SharedPreferences **`home_state_sharedpreferences`** (main process) /
  `home_state_sharedpreferences_remote` (`:remote`), key **`home_state_oobe_checked=true`**
  (+ `oobe_done_time`, `is_new_user`). Startup walks `AppStateManager$StateType`
  (DpError, MultiUser, HSUpgrade, ChinaMinorAge, **OOBE**, LockMode, **SamsungAccount**, ...).
- **The account gate sits right after OOBE**: `j9v` (`SamsungAccountManager`).`t0()` —
  `s0()` returns `LOG_OUT` when `SamsungAccountUtils.isDeviceSignInSamsungAccount` is false; on
  non-China builds that is a stop state that launches `HomeSaSignInActivity`
  (action `SA_MANDATORY_GUIDE`) on the first dashboard launch, and back-press closes the app.
  **The China build flavor short-circuits exactly this stop state** (`ozn.X()`).
- Later re-checks: every activity via `BaseActivity`/`b11` → `j9v.t0`; SA change →
  `clearApplicationUserData()` (wipes OOBE state); SA country change re-runs the wizard
  (`l220.O0()`, action `HOME_OOBE_REONBOARDING_BY_SA_CC`); server terms change re-runs agree.
- Minimal patch: make `j9v.s0()` report signed-in (or the `t0` tail accept `LOG_OUT` on
  non-China) — one point, keeps terms/consents intact. Optionally pre-seed
  `home_state_oobe_checked` to skip the wizard entirely. Account-dependent features (sync,
  backup, AI) then degrade individually instead of bouncing to sign-in.

## 4. Gated features

### How gating works in general

- **No tracker is gated on "is a Samsung phone".** `SsdkVendorCheck.isSamsungDevice()` exists but
  only guards the S-Cover SDK; `SemFloatingFeature` only gates the AI-suggestion/Now-Brief path
  (below). The non-Samsung phone itself is not the gate for the surveyed trackers.
- Feature flags resolve **locally**: `config/FeatureManager` → `config/Feature` values from
  SharedPreferences `final_feature_list_pref` (prod) over compile-time defaults in
  `config/FeatureGroup`. **No server writes these prefs.** A built-in override UI exists:
  `config/FeatureListActivity` (manifest `exported=false`), reachable from
  `HomeSettingsAboutActivity.L1()` when `SdkDevPolicyControl.isDeveloperModeOn` **and**
  `FeatureManager.isEnabled()` (`Build.TYPE == "eng"` **or** a `FeatureSettingOn` file in
  `/sdcard/Download/SamsungHealth/`). Under scoped storage (targetSdk 36) that marker file is
  almost certainly unreadable by the app — the practical unlock is a one-line patch to
  `isEnabled()`.
- The real per-feature gate for tracker tiles is the **`tracker_feature` capability JSON the
  connected watch advertises** over the wearable channel (persisted in the
  `com.samsung.health.device_profile` Health-Data row). Watch-model allowlists
  (`WearOsNodeStatusHelper$SupportDeviceForWearOs`, `AutoExerciseUtil$SupportDeviceList`) are used
  for device *registration*, not for tracker gating.
- Several tiles register behind `Common_watch8_trackers` (`HServiceFactory.java:279-308`) — and
  that key **defaults to `true`** (`FeatureGroup.java:343`, `.setDev(true)`), so antioxidant,
  vascular-load, training-planner and heart-health HServices are already registered in this build.

### Per-feature table

| Feature | Effective gate | Data source | Classification | Single flip to force UI |
|---|---|---|---|---|
| Energy Score / Vitals ("vitality") | watch capability `tracker_feature.vitality_score` → `hrv_support` (`ns70.d`, `c370.isDeviceSupportVitality`), latched into `VITALITY_IS_HRV_SUPPORTED_*` prefs | `com.samsung.shealth.vitality_score`, **also computed phone-side** (`VitalityDataGenerateWorker`) from synced HRV/sleep/activity | hybrid: tile gate watch-advertised; score computation patchable | FeatureList `vitality_show_tile_without_capabilities` = true |
| Antioxidant Index | registered behind `Common_watch8_trackers` (default true) + Labs pref `SETTING_LAB_ENABLE_ANTI_OXIDANT` (default on); no watch capability read for the tile | live measurement **runs on the watch** (`AntioxidantWearableMessage`, MESSAGE_ID `tracker.antioxidant`), stored as `com.samsung.health.antioxidant` | watch-app-dependent (measurement) | none needed (already on); data empty unless watch answers |
| Vascular Load | same registration (default on) + Labs pref; **visibility: SA account country ≠ US** (`f73.d()` → `BpTrendCountryUtil.d()`, `ozn.h0()`); watch capability `vascular_load_feature` version ≥ 1000 && support (`ye80.checkMapCapability`) | watch computes from MAP/bioactive; `com.samsung.shealth.mean_arterial_pressure`, `vascular_load_raw_data` (two-way synced) | hybrid: account gate local; data watch-side | `BpTrendCountryUtil.d()` patch if account is US; otherwise none |
| Daily cardio load ("training_load") | **none** — `HServiceFactory.java:308` registers unconditionally | watch sends `training_load.*` rows (two-way; goal is phone→watch writable) | watch-app-dependent for data only | none needed |
| Fitness Index ("pfi") | **none** — registered unconditionally | `com.samsung.shealth.physical_fitness_score` (no producer in this APK) + server content (`PFIContentNetworkManager`) | hybrid: score from watch/sync, content server | none needed for UI |
| Bedtime guidance / sleep coaching | program registered unconditionally; watch program sync via `tracker_feature.common_program_sync` + `program_spec`; AI-suggestion publication gated by `ContextualInsightCapabilityProvider.isAvailable` | bedtime window computed **phone-side** (`OptimalBedtimeData`) from sleep sessions; missions two-way synced; coaching visuals from `api.samsungknowledge.com` (`sleep_coaching_server` flag) | hybrid: bedtime math local, missions watch, content server | none for the tile |
| Running coach / sport | watch capabilities only: `auto_activity_recognition.supported_types.running` (`tcw.p()`), `sport.max_hr_auto_update`, `sport.remote_control_version` | watch exercise data | watch-app-dependent | mirror prefs are writable but secondary; needs the watch to advertise |
| AI insights / AI brief / Healix | `sdk/aisuggestion/.../Rune`: needs `SEM_PLATFORM_INT ≥ 160000` + `SEC_FLOATING_FEATURE_FRAMEWORK_SUPPORT_PERSONALIZED_DATA_CORE` (One UI / Samsung platform) — **OS-level, not patchable in this APK**; LLM backends are server (`insight.samsunghealth.com`, `api.samsunghealthai.com`) | server LLMs + on-device doc publication | server + Samsung-platform-gated | no single flip; server calls themselves are signature-agnostic |

### The "gated to newer watches" scenario

Where Samsung restricted a feature to newer watch *generations*, the gate is the `tracker_feature`
JSON the watch-side Samsung Health advertises — that logic lives on the **watch APK**, not here.
Two phone-side levers still exist:

1. The capability mirror/latch prefs and `vitality_show_tile_without_capabilities`-style flags
   force the UI on; and
2. for features whose raw inputs sync anyway (HRV, sleep sessions, MAP), the phone has its own
   computation paths (`VitalityDataGenerateWorker`, `OptimalBedtimeData`) — the score can be
   produced locally even when the watch never advertises the capability. Features whose
   *measurement* must run on the watch (antioxidant) need the watch-side app to cooperate; that
   is a separate project (watch APK not surveyed).

## 5. China build 7.00.6.012 (issue #8)

Why it matters: the international build closes itself after sign-in with a Samsung Account
registered in mainland China (`lcf`, log `"Global APK, China SACC, NO Smart Switch"` →
`HomeAppCloseActivity`, `OOBE_GLOBAL_APK_CHINA_SACC_APP_CLOSE`) when the build flavor
(`fs90.u()`) is not China and the account country (`ozn.Y`) is `CN`. The optional
`shealth-china-account-gate` pattern skips that check, but the server split is fixed by the
flavor at build time, so the international build keeps talking to the international servers
while a CN account's data lives on Samsung's China infrastructure. The China build is the real fix.

Source: not on APKMirror (the 7.00.6.012 release page is 404). 应用宝 (YingYongBao) serves it as a
single APK (no splits): flavor `chinaFinalRelease`, sha256
`b40e9bc5e927db43dadbe5d5c41d2c99d7f7787c44c6d5bb664bceb5686e77ca`, 365,359,180 bytes. Decoded
with apktool into `$WORK_DIR/shealth-cn-survey/tree`.

Findings (non-Samsung phone, re-signed, renamed):
- **Flavor check**: `wr50.Y()` (flavor contains `chinaFinal`), 51 call sites; it selects the
  `*.samsunghealthcn.com` servers, and sasdk uses `account.samsung.cn`.
- **Account optional**: `q7v` is `SamsungAccountManager` here; `q7v.q0` skips the
  `LOG_OUT` stop state on China, so the `shealth-oobe-local` pattern is redundant (left out of the
  `shealth-cn` profile) and so is the China account gate (the check does not exist on this flavor).
- **Same sasdk `CLIENT_ID`** as 011 (`1y90e30264`), so sign-in behaves like the international
  build after re-signing (see §1).
- **ChinaMinorAge** reads `Settings.Secure` `minors_mode`, which a non-Samsung ROM does not have:
  no block.
- No signature, manufacturer or CSC gates were found beyond what 011 has.

Anchors: every `shealth` pattern applies to both builds once the obfuscated names are replaced
with content selectors (checked with `superpatcher.py analyze` on both APKs):

| Pattern | 011 site | 012 site | Selector change |
|---|---|---|---|
| `shealth-ai-provider-start` | `GenerativeAiProvider.a()` (lambda `up1`) | same (lambda `lp1`) | `bodyAll` without the lambda class |
| `shealth-vitality-tile` | `FeatureGroup.getGroupVitality` (factory `gz6.g`) | same (factory `rt.g`) | `insertInMethodAtMatch` on the literal, any registers and factory class |
| `shealth-capability-gates` step 2 | `xvx.K0(SleepItem, gc5)` | `wvo.Q(SleepItem, …)` | no file; signature shape + `getSleepTrackDeviceList(` |
| `shealth-launcher-badge` | 9 icons | 12 icons (3 extra unused `mipmap-anydpi*/ic_launcher*`) | `expect: atLeastOne` |
| `shealth-oobe-local` | `j9v.s0()La11;` | `q7v.n0()Lw01;` | no file; body has `LOG_IN`/`LOG_OUT` + `"getContext(...)"` |

Not verified yet: sign-in and cloud sync with a China account on the patched build (we have no
CN account; it needs the reporter or a CN account on a test device).

## Go / no-go and effort

| Part | Verdict | Rough effort (owner-time excluded) |
|---|---|---|
| Sign-in + cloud on re-signed copy | **Expected go** — code is signature-agnostic; the earlier stall was the stock app's flaky Custom-Tab redirect (retry fixes it) | on-device test of the patcher-built APK |
| OOBE account-less | **Go** — implemented, off by default | built; on-device validation pending |
| Feature flags UI + local gates | **Go** — implemented (`isEnabled` + `L1` patches, vitality default) | built; on-device validation pending |
| Vascular Load / Antioxidant on the GW5P | **No-go — hardware**: the watch app ships both features but its sensorhub lacks the capabilities (`initVascularLoadSensorCapability available=false`, `initAdvancedGlycationEndproduct… false`); see [shealth-watch-survey.md](shealth-watch-survey.md) | none; do not build watch patches for this |
| Training load / Fitness Index / bedtime tiles | **Already ungated or flag-flippable** (defaults on) | verification on device only |
| AI brief / Now-Brief publication | **No-go in this APK** | gated by Samsung platform (One UI 7 `SemFloatingFeature`), OS-level |
| Side-by-side with stock alive + watch data | **No-go** | watch-side pairing makes it a watch-APK project |
| Renamed build (cloud-only / HC-only) | Conditional go | authority map op + literal rewrite op in the engine, ~1 week incl. integration tests |

Recommended path: patched copy with the **stock package name, stock app removed** (owner's
call at install time), SA sign-in kept **if** the re-test passes, otherwise the Local-only
patch flipped to default-on. Most tracker tiles light up from their defaults; the GW5P stays
stock (no watch patch — nothing to unlock there). The rename path is only worth building if the
owner explicitly wants the stock app alive simultaneously and accepts losing watch data on the
copy.

## Explicitly not done

- Nothing is installed on any device; the built APK passed static verification only (badging,
  res parity, arsc references, signature). On-device validation is the next step.
- The empirical sign-in test on the re-signed build has not been run yet (the earlier stall was
  on the stock app and cleared on retry; protocol above).
- The GW5P keeps the stock watch app — no watch build exists, and per
  [shealth-watch-survey.md](shealth-watch-survey.md) none is planned (hardware no-go for
  Vascular Load / Antioxidant).
- Nothing is committed yet.
