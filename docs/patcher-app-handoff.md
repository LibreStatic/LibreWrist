# Hand-off: OpenSHM Patcher (on-device app)

Status: design stage, nothing implemented. Written 2026-10-09.

Design source: Design canvas artifact "OpenSHM Patcher"
(https://claude.ai/artifact/FsGUMyaV6CdqrMMmtuTm3X). It has 11 phone artboards at 390×844 and 2
spec boards (Material You and the expressive language). The copy and data in the artboards are
**placeholders**: "Vendor Health 14.1.0.320", `com.example.vendor.health.openshm`, and a mock
patch list. Section 4 maps them to what this repo really builds.

## 1. Goal

Today `scripts/build_all.sh` builds everything on a Linux PC, and ADB installs it by hand
(`docs/installation.md`). This app moves the whole pipeline onto the **phone**:

1. Guide the user to obtain the exact official input APKs and verify them by hash.
2. Patch them on the device with the same patterns as `profiles/gms.json` and
   `profiles/alarm.json`.
3. Sign them with a key kept on the device.
4. Install them on the phone, and on the watch over wireless ADB.
5. Apply the permissions and appops the apps need.
6. Run a self-test.
7. Offer restore and uninstall from Home.

Constraints from `AGENTS.md` still apply:
- No vendor code and no APKs in the repo or inside the app.
- No hard-coded package prefix.
- English for everything that is not user-facing.
- Interoperability vocabulary.
- Personal use only: the app never uploads or shares the APKs it builds.

## 2. Screens (from the canvas, in canvas order)

The canvas says "Step N of 7", but the shape library lists 8 step identities: Profile, Acquire,
Verify, Customize, Build, Install, Watch and Self-test. There are no artboards for step 1
(Profile) or step 3 (Verify). See the owner decisions (§8).

| # | Artboard | What it shows | Behaviour to implement |
|---|---|---|---|
| 0 | Welcome (`Main`) | Hero illustration, title "Your apps, your watch, your way", 3 promises: you download / we patch and install next to current apps / everything can be undone. Buttons: "Get started", "What will change on my devices?". Settings icon. | The "what will change" sheet must list the real changes: the packages installed on each device, the appops and permissions granted, the stock apps disabled. |
| 2 | Acquire | "Get the apps": one card per input APK (name, exact version, target device, chips "APK, not bundle", "nodpi", "Android 11+"), live download progress ("64% · 58 of 91 MB"), "Open on APKMirror", an expander for "More ways to get this app", a verification note, and the footer "Waiting on 2 apps" + Continue. | 4 cards, not 2 (§4.1). Continue stays disabled until every input passes its hash check. |
| 2a | Guided browser (`Browser`) | In-app web view with the page shown as-is. A dark coach-mark card ("2 of 3 · Pick the variant: APK, arm64-v8a, nodpi"). A floating guidance bar with a step indicator and actions: use a file instead, open in my browser, take me back to the right page. | A WebView with download interception. Per-source step hints are matched by URL. The app does not inject into or alter the page (it is "shown as-is"). When a download starts, it is pulled into app storage and hashed. |
| 4 | Customize patches (`Patches`, interactive) | Presets as a segmented button: Recommended / Minimal / Custom. Grouped toggle rows: Required (locked), Connectivity, Interface. "New package name" field with a `.openshm` suffix preview. Summary "N patches on · 2 apps". Build button. | Real patch set and package rules in §4.2–4.3. Today there are no optional patches. |
| 5 | Build (running) | Big progress number (72%), "Phone app · 1 of 2", a step list (Unpack, Check patch fingerprints, Apply N patches, Rename package, Rebuild, Sign, Self-check) using pending/running/passed status shapes. "You can leave. We'll notify you". Show log. | Runs as a foreground service with a progress notification. The steps map to engine phases (§5). The log is the engine report. |
| 5 | Build complete (`BuildDone`) | "Built and signed", one card per output (label, package, size), "Same package name and key on both, so they can talk to each other", "Install on phone". | 5 outputs, not 2 (§4.1). The GMS note is correct and important: the two SHM apps must share package and certificate. |
| 7.4 | Pair watch (`WatchPair`) | Prerequisites "Same Wi-Fi · Developer options · Wireless debugging". Pairing code + host:port, discovered via mDNS ("Watch found on your network"). Checklist: Connect and allow debugging → Install the watch app → Turn off stock watch apps → Turn wireless debugging off. | An in-app ADB client: TLS pairing (SPAKE2) and connect (§5.4). |
| 7.7 | Disable stock apps (`WatchDisable`) | "Connected to [Watch model]", "Watch app installed". A list of stock apps to turn off, each with a reason: required ones are preselected and optional ones are marked "Optional". "Nothing is uninstalled". Buttons "Turn off 1 app" / "Skip for now". | `pm disable-user --user 0`, reversible with `pm enable` (§4.4). |
| 8 | Self-test (`SelfTest`) | Sections Phone / Watch / Phone↔watch link with per-check status and an inline "Fix" action. "Check with you · 1 of 2": manual yes/no questions ("Does the app show your watch as connected?"). Share report. | Real checks in §6. |
| 8 | All set (hero) | "You're all set", "8 of 8 checks passed", status chips Phone / Watch / Link, Done, Share report. | — |
| 9 | Home | Status card ("Everything's working · tested 2 days ago", Test again). Update card ("Update to 14.2.0 · patches are ready for the new version, you'll download 1 app"). Current builds list with per-item actions (rebuild with different patches, restore watch). FAB menu: new build, run self-test, restore watch, uninstall. | The update channel is a signed catalogue (§8). |
| — | Material You | Dynamic color on Android 12+. Fallback indigo palette from seed `#3D5AFE`. Light and dark token tables are in the board script (primary `#4355B9`/`#B9C3FF`, etc.). | Use `dynamicLightColorScheme` / `dynamicDarkColorScheme`, falling back to the board's tokens. |
| — | Expressive language | One Material shape per step, with the step indicator morphing between them. Status = shape + color + icon (pending ring, running cookie, passed green cookie + check, warning triangle, failed red burst + ×). Roboto Flex emphasized type scale; mono for package ids. Spatial springs may overshoot; effects springs may not; with reduced motion, morphs become crossfades. | Material 3 Expressive in Compose (`MaterialShapes`, `MaterialTheme.motionScheme`). Honour `ANIMATOR_DURATION_SCALE = 0`. |

## 3. Proposed stack

- **Kotlin + Jetpack Compose**, Material 3 Expressive. `minSdk` 31 (dynamic color, and the
  wireless-debugging pairing API on the watch side is Android 11+). Single-activity architecture
  with Navigation Compose.
- **Code location:** a new top-level module `patcher-app/` in this repo, using the `shminterop`
  placeholder package like the rest of our code. Follow the global Compose rule: add Compose Driver
  (or an equivalent preview harness) and give every screen a preview fed with fake `UiState`.
- **Patch engine on Android:** a Kotlin port of `superpatcher` (§5). apktool does not run on
  Android.
- **Libraries to evaluate** (check licences before adopting):
  - `com.android.tools.smali:smali` / `baksmali` / `dexlib2`, for dex ⇄ smali;
  - ARSCLib (REAndroid), for binary manifest and `resources.arsc` editing without aapt;
  - `com.android.tools.build:apksig`, for v2/v3 signing;
  - an ADB client with pairing: `libadb-android` (MuntashirAkon) or a minimal own implementation
    of the ADB protocol. These are the same building blocks ReVanced Manager and App Manager use.

## 4. Mapping the design to the real pipeline

### 4.1 Inputs and outputs

Inputs are checked against `apks/SHA256SUMS`, which is the single source of truth. Ship a copy as
an app asset, generated at build time.

| Input | Version | Target | Notes |
|---|---|---|---|
| SHM phone | 1.4.6.001 | phone | — |
| SHM watch | 1.5.1.022 | watch | Pinned version: downloaded from APKMirror or picked as a file. |
| AlarmWatch | 1.3.05.5 | watch | Exact build. The alarm patterns name obfuscated classes directly. Published on APKMirror; the build preinstalled on a watch usually differs and fails the hash. |

| Output | Package | Signed with |
|---|---|---|
| SHM phone (patched) | `com.samsung.android.shealthmonitors` | device key |
| SHM watch (patched + `watch-mod` injected) | `com.samsung.android.shealthmonitors` | the **same** device key |
| Alarm fork | `<prefix>.shmbridge.alarm` | the same key (needed for the `ALARM_LINK` signature permission) |
| Bridge phone | `<prefix>.shmbridge.phone` | the same key |
| Bridge watch | `<prefix>.shmbridge.watch` | the same key |

How the bridge apps get to the device:
- They are our own code, so the patcher can ship them as **prefix-neutral, unsigned** APKs built
  with the `shminterop` placeholder.
- The on-device engine then rewrites the placeholder to the chosen prefix and signs them.
- Rewriting means renaming dex types and replacing the manifest package and its strings, which is
  the same job as `scripts/lib/stage_prefix.py` does on source.

The same rule covers `watch-mod` and the `alarm-fork` injected smali: precompile them at app build
time to smali or dex assets that use the placeholder.

"More ways to get this app" should offer:
- **Choose a file** (SAF).

The patcher never pulls APKs from the watch or the phone. Every input is a pinned version
(SHA-256 + versionCode in `apks/inputs.json`) that the user downloads from APKMirror (guided
browser) or picks as a file; the hash is checked either way.

### 4.2 The real patch catalogue

The mock patches ("Diagnostics hook", "Keep watch link alive", "Hide promo cards", "Compact
dashboard") **do not exist**. The real set is:
- 13 phone patterns, 9 watch patterns and 17 alarm patterns, plus the injections `watch-mod` and
  `alarm-fork` (`docs/patches.md`, in profile order).
- **Every listed pattern is required.** The engine aborts if one does not match, and it never signs
  a partially patched APK.

So for v1, step 4 shows:
- one group per app (Phone SHM / Watch SHM / Alarm fork), each patch with a one-line plain-language
  description and the "Required, always on" lock;
- the presets segmented button hidden, or disabled with only "Recommended";
- the package-prefix field (§4.3).

Keep the screen data-driven so optional patches can appear later. Proposed schema: add an optional
`"optional": true` and a user-facing `title`/`description` per pattern id in the profile JSON.
`shm-min-age-22-to-16` still carries an owner TODO (justification); do not expose it as a
user-facing toggle until that is resolved.

The design's "Diagnostics hook" (a patch that lets the self-test see that patches are active)
is a **new feature request**, not an existing patch. v1 can verify patches without it (§6).

### 4.3 Package naming: the design's "New package name" field is wrong for SHM

- The two SHM apps **must keep** `com.samsung.android.shealthmonitors` on both devices: GMS
  delivers Data Layer messages only between equal AppKeys (package + certificate). The design's
  `com.example.vendor.health` → `.openshm` rename would break phone⇄watch sync.
- What the user may choose is **`PKG_PREFIX`**. It names the bridge apps, the alarm fork, the
  `ALARM_LINK` permission and the injected code packages (`<prefix>/hsdk`, the `watch-mod`
  classes). The default should be something neutral and unique per install, for example a random
  `io.openshm.u<6 hex>`. Never a real person's or company's domain.
- Replace the field with "Package prefix for helper apps" in an advanced section, plus a warning:
  upgrades without data loss need the **same prefix and the same key** as the previous install.
  Lock the field once anything is installed.
- On the watch, the patched SHM coexists with the system SHM. The phone has no stock SHM to coexist
  with on a non-Samsung phone, so the Welcome promise "next to your current apps" applies to the
  watch only. Reword it.

### 4.4 Watch: stock apps to disable

Per `docs/installation.md`:
- The system SHM on the watch keeps running and shows its regional error. Disabling it is
  optional but recommended to avoid duplicate tiles and notifications.
- Any other alarm app with its own Wear companion may show a second UI over the fork's; disabling
  it is optional.

Use `pm disable-user --user 0 <pkg>` and revert with `pm enable <pkg>`. Never uninstall system apps.
Get the stock package ids from the device (`pm list packages`) rather than hard-coding guesses.

### 4.5 Steps the design does not cover yet (add screens)

1. **Stock Galaxy Wearable pairing (prerequisite).** The SHM needs the GMS Data Layer created by
   the stock pairing. The app should detect it, using `NodeClient.getConnectedNodes()` from
   play-services-wearable (visible to any app), and otherwise link to the stock flow.
   - Include the transfer gotchas from `docs/installation.md` as help content.
   - The one fix that is destructive, `pm clear com.google.android.gms`, must **never** be run by
     the app automatically. Show it as information, with a clear warning, for the user to decide.
2. **Health Platform Dev Mode on the watch.** This is a manual step: Settings → Apps → Health
   Platform → tap the title repeatedly. Without it, ECG delivers empty batches. It can reset after
   an unclean reboot. It needs a guided screen with illustrations, and a self-test check (§6).
3. **Phone-side grants for the bridge**: runtime permissions, `ACCESS_RESTRICTED_SETTINGS`,
   `SYSTEM_ALERT_WINDOW` (the BAL exemption) and the notification listener.
   - Settings intents with the user in the loop come first: "Display over other apps" sets
     `SYSTEM_ALERT_WINDOW`, and there is a notification listener settings intent.
   - What Settings cannot do goes through self-ADB (§8).
4. **Watch-side grants after every install.** The appops reset on reinstall. Run them
   automatically right after each watch install, and show them in the WatchPair checklist.

The authoritative grant lists, the install order and the stock apps to disable are data in
`catalogue/catalogue.json` (`grants`, `installOrder`, `stockAppsToDisable`, keyed by the
`outputs` ids); the app reads them from the verified catalogue and never hard-codes them.

## 5. On-device engine (the critical path)

### 5.1 Approach

Port `superpatcher` (`engine.py` ~535 lines, `patterns.py` ~824 lines) to Kotlin with the same
semantics:
- Patterns operate on **smali text** and on the **decoded manifest/resources**.
- Every op asserts what it found, including counts. A miss aborts.
- The ops to port are `op_return_const`, `op_swap_const`, `op_enum_ordinals_zero`,
  `sweep_string_compare_auto_true`, `op_force_const_after_move`, `op_manifest_rebrand`,
  `op_replace_const_string_exact`, `op_force_invoke_result`, `op_return_singleton`,
  `op_insert_in_method`, `op_set_method_body`, `op_manifest_add_query_package`, the class-scoped
  ops, `op_remove_smali_dir`, `manifest_edit` and `op_add_string_resources`.

Phases, which match the Build artboard's step list:
1. **Unpack**: read the APK zip and the dex files.
2. **Check patch fingerprints**: verify the hash against `SHA256SUMS`, then run a dry run of every
   pattern anchor. This is `superpatcher.py analyze`.
3. **Apply patches**:
   - Baksmali **only the classes that patterns touch**. Do not decode the whole app: memory on the
     phone is limited and SHM is about 90 MB. Use the anchors to pick candidate classes, and fall
     back to a streaming scan of the dex string pools.
   - Edit the smali text, re-assemble those classes and write back modified dex files.
   - Add the injected classes as a new `classesN.dex`.
4. **Rename package / prefix**: edit the manifest and resources with ARSCLib. This covers
   `op_manifest_rebrand`, the `ALARM_LINK` permission and authorities, and `interop_app_label`.
   Stage the placeholder prefix on the helper apps.
5. **Rebuild**: write the new zip.
   - Keep `resources.arsc` and `.so` files **uncompressed and 4-byte aligned**, which Android 11+
     requires for targetSdk ≥ 30.
   - Keep native libraries page-aligned, which 16 KB page devices require.
6. **Sign** with apksig, v2 + v3. Drop the old `META-INF` signature files.
7. **Self-check**:
   - re-open the output;
   - verify the signature and the package name;
   - check that every patched site is present, by re-running the anchors in "already applied"
     mode.

### 5.2 Equivalence oracle (acceptance gate for the port)

The Python engine has been verified against owner-tested builds ([V] in `docs/patches.md`). The
Kotlin engine must produce **decoded-equivalent** output to `scripts/build_all.sh` for the same
inputs and prefix: same patched smali, manifest and resources. Signature files and dex ordering may
differ.

Build a JVM test (desktop, not on device) that runs both engines and diffs the apktool-decoded
trees. CI can only run it where the owner provides the input APKs (they are not in the repo). Gate
it with an env var.

### 5.3 Health Tracking client

Nothing to import on device: the client is our own code (`watch-mod/src/shminterop/hsdk`, protocol in
`docs/health-tracking-protocol.md`) and ships inside the precompiled `watch-mod` asset. The inputs
are the three APKs only.

### 5.4 Signing key

- Generate it once on first build: RSA 3072 or EC P-256, with a long validity.
- **Losing the key means the apps cannot be upgraded without uninstalling**, which loses SHM data.
  So:
  - store it in a PKCS#12 file in app-private storage, wrapped by an Android Keystore AES key;
  - offer "Export signing key" (password-protected file via SAF) and "Import key", and nudge the
    user to export after the first successful build;
  - allow importing the PC build's keystore (`$OPENSHM_KEYS_DIR/apk-signing.p12`) so existing installs keep
    upgrading.
- Never log key material. Never send it anywhere.

### 5.5 Installing

- **Phone**: `PackageInstaller` sessions (`REQUEST_INSTALL_PACKAGES`); the user confirms each
  install. Check on Android 13–15 whether session-installed apps get "restricted settings" (this
  affects the accessibility and notification-listener grants). Fall back to ADB grants (§4.5).
- **Watch**: ADB over Wi-Fi.
  - Pair (TLS + SPAKE2 pairing code; discover via the mDNS service `_adb-tls-pairing._tcp`), then
    connect (`_adb-tls-connect._tcp`).
  - Install through `exec:cmd package install-create / install-write / install-commit`, streamed.
  - Keep the ADB RSA key in app storage. Revoking is done on the watch.
  - At the end, offer to turn wireless debugging off: open the watch's settings
    (`settings put global adb_wifi_enabled 0` would cut our own connection, so do it last, or
    guide the user).

### 5.6 Memory and performance

- Process one APK at a time, streaming the zip.
- Bound the baksmali set.
- Run in a foreground service (`dataSync` or `specialUse` FGS type) with a wake lock while it
  runs.
- Measure peak RSS on a mid-range device (target < 512 MB) and record it.

## 6. Self-test checks (real, v1)

| Area | Check | How | Fix action |
|---|---|---|---|
| Phone | Patched SHM installed, expected version, signed with our key | `PackageManager` signing info vs the key cert | Reinstall |
| Phone | Bridge installed and grants present | `PackageManager`, `AppOpsManager` (where readable), notification listener enabled | Open the right Settings screen / re-run grants |
| Phone | Watch node connected over GMS | `NodeClient.getConnectedNodes()` non-empty, `isNearby` | Link to stock pairing help |
| Watch | Patched SHM, alarm fork and bridge installed with our cert | ADB `dumpsys package` / `pm list packages -U` | Reinstall |
| Watch | Stock SHM disabled (if the user chose it) | `pm list packages -d` | `pm disable-user` |
| Watch | Appops and grants applied | `appops get` / `dumpsys package` | Re-run grants |
| Watch | Health Platform Dev Mode on | Find a readable signal (setting or provider), or ask the user (manual check) | Guided screen |
| Link | Bridge RFCOMM link up and `hello` exchanged | Ask the phone bridge for its link state (add an exported, signature-protected status query to the bridge) | Restart bridge |
| Manual | "Does SHM on the phone show the watch as connected?"; "Take an ECG on the watch: does it appear on the phone?" | Yes/No prompts (the design's "Check with you") | Help content |

"All N patches active" needs no diagnostics hook. The engine's self-check already verified them
at build time, and the installed APK's hash can be compared with the built output's hash
(`pm path` + hash on the phone; ADB on the watch).

The **share report** contains only:
- versions and package ids;
- check results;
- the engine log.

It never contains notification content, health data or key material. Same privacy rule as the
bridge.

## 7. Acceptance criteria (v1)

1. On a clean non-Samsung phone, using only the app plus the three official inputs:
   - the user produces the five outputs;
   - the outputs install, and phone ⇄ watch SHM sync works (ECG from the watch shows on the
     phone);
   - a phone alarm rings on the watch through the fork.
2. The Kotlin engine passes the equivalence oracle (§5.2) for phone, watch and alarm with a
   custom prefix.
3. A wrong or modified input is rejected **before** any change, with a message that says which
   file and what was expected.
4. A pattern that does not match aborts the build. Nothing is signed or installed.
5. Restore works:
   - re-enable the stock watch apps;
   - uninstall our apps on both devices;
   - leave GMS and the Bluetooth bond untouched.
6. Upgrading with the same key and prefix keeps SHM data. Export, then import, of the key on a new
   phone install allows the upgrade.
7. Every screen renders in light and dark, with dynamic and fallback color, at font scale 1.0
   and 2.0. Screens have Compose previews with fake state. TalkBack labels match the design's
   aria labels.
8. Reduced motion is honoured: morphs become crossfades.
9. No device action is destructive without explicit confirmation. No factory reset path exists.
   `pm clear` on GMS is never run by the app.

## 8. Owner decisions (2026-10-09)

These answer the open questions of the first draft. The ordered roadmap lives in the project plan;
this section records only the decisions.

1. **Step order**: Galaxy Wearable stock pairing → watch ADB pairing → Acquire. Inputs are
   never pulled from a device: APKMirror or a chosen file only.
2. **Profile and Verify**: Profile (phone + watch model) selects the catalogue profile set; Verify
   stays inline in Acquire, with Continue disabled until every hash passes.
3. **Health SDK input**: none. The Health Tracking client is our own clean-room code
   (`watch-mod/src/shminterop/hsdk`), verified on a watch.
4. **Phone grants**: guided Settings intents first; fallback is self-ADB (Shizuku-like pairing with
   the phone's own wireless debugging, code entered through a notification `RemoteInput`).
5. **Update channel**: a signed declarative catalogue (`catalogue/catalogue.json`, ECDSA P-256)
   hosted in this repo. Data only, never code; the app keeps a bundled baseline, rejects a lower
   `serial` and an unsupported `minAppVersion`.
6. **Optional patches**: none in v1; the presets UI stays hidden while no pattern is optional.
7. **Distribution of the patcher**: still open (non-blocking).
8. **Bridge dead code**: stripped before bundling — TCP transport, relay passthrough, GW
   channel, GMS vendor-identity override and the legacy command protocol are gone. The phone
   bridge exposes a signature-protected status provider for the self-test. Grants, install order
   and stock apps to disable live in the catalogue (`grants`, `installOrder`,
   `stockAppsToDisable`).
9. **Inputs**: `apks/inputs.json` describes each input (package, version, variant, APKMirror slug); `apks/SHA256SUMS` is generated from it.

## 9. Suggested slices

Independent slices can run in parallel. Arrows show dependencies.

1. **Engine core (JVM library, no Android UI)**: the Kotlin port of the ops and patterns, the profile
   loader, SHA256 verification, zip rebuild with alignment, apksig signing, and the equivalence
   oracle test. → *The critical path; start first.*
2. **Prefix staging** (JVM), on top of 1. Oracle against `stage_prefix.py`.
3. **Asset pipeline** (Gradle): build the bridge apps, `watch-mod` and `alarm-fork` with the
   placeholder; generate the patch catalogue and `SHA256SUMS` assets from `profiles/` and
   `apks/SHA256SUMS`.
   Implemented so far (`patcher-app/app/own-code.gradle.kts`, task `:app:ownCodeAssets`, run by every
   variant's asset merge): `assets/owncode/injections/{watch-mod,alarm-fork}.dex` and
   `assets/owncode/apps/bridge-{watch,phone}.apk` (unsigned), all in **placeholder form** (package
   `shminterop`; the engine applies the real prefix on the phone), plus the signed catalogue under
   `assets/catalogue/`. The tasks mirror `watch-mod/build-watch-mod.sh`, `java_to_smali` and
   `build-bridge.sh` (same platform jar, build-tools and javac/d8/aapt2 flags; `ANDROID_HOME`,
   `BUILD_TOOLS_VERSION`, `PLATFORM_API` from the environment). Output is byte-identical to the
   scripts' when both use the same javac version (the app build uses the Gradle JDK, 17). The engine
   (`OwnAppBuilder`, `DexInjection`) stages them on the phone: smali rename (including d8 lambda
   names and the `hashCode` keys of `switch` over renamed strings; staging fails if a key would stay
   stale), manifest and `resources.arsc` string pools, optional `knownSigner` for the phone bridge's
   status permission, then v2+v3 signing and a self-check. `OracleTest` compares all five staged
   outputs with `build_all.sh` (`SHM_ORACLE_OWNCODE`, `SHM_ORACLE_PREFIX`).
4. **ADB client** (Android library): pairing, connect, shell, streamed install. Test against an
   emulator, then the watch.
5. **App shell + design system**: theme (dynamic color + fallback tokens), expressive shapes,
   status components, step indicator, Compose previews for every artboard with fake state.
   Independent of 1–4.
6. **Flows**: Acquire + guided browser, Customize, Build (FGS + notifications), Install phone,
   Watch (pair, install, grants, disable), Self-test, Home/restore. Depends on 1–5.
7. **New screens** from §4.5 (stock pairing check, Health Platform Dev Mode, phone grants).

## 10. Rules carried over

- Never factory reset any device. Destructive commands only on the owner's explicit decision.
- Device testing happens on the owner's devices with their authorization. Check host memory before
  launching emulators.
- No vendor code, smali, dex or APKs committed. Test inputs come from `apks/` (git-ignored).
- Commits are authored by the owner. No AI attribution trailers.
