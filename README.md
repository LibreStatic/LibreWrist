# OpenSHM

Personal interoperability between a Galaxy Watch and a **non-Samsung** Android phone. With it, the
Samsung Health Monitor (SHM) app can measure ECG, blood pressure and irregular heart rhythm
notifications (IHRN) on the watch and sync them with the phone. It also includes our own bridge for
alarms, notifications and calls.

This repository **contains no vendor code and no APKs**. It holds:

- the patches, written as patterns anchored to the official APKs;
- our own code;
- the tools that rebuild everything from the official APKs, which each user obtains on their own.

The resulting APKs are signed with the user's own key. They are for personal use and are not
redistributed.

**Unlike Dante's version, this compatibility layer DOES work on modern One UI Watch releases. It is
verified on One UI 8 Watch (Wear OS 6, Android 16 / API 36).** Older One UI Watch releases have not
been tested.

| Tested setup | Version |
|---|---|
| Watch | Galaxy Watch5 Pro (SM-R920), One UI 8 Watch, firmware `R920XXS2DZG3` (security patch 2026-08-05) |
| Health Platform on the watch | 1.7.00.05 |
| Samsung Health Monitor | 1.5.1.022 (watch), 1.4.6.001 (phone) |
| Phone | Motorola razr fold (non-Samsung) |

## Getting started: the patcher app

The easiest way is **OpenSHM Patcher** (`patcher-app/`). This Android app does the whole job on the
phone itself; no PC is needed. Download the latest APK from the
[Releases](https://github.com/LibreStatic/OpenSHM/releases) page and install it on the phone.

The app guides you through these steps:

1. **Pairing**: the stock Galaxy Wearable pairing, then a Wireless debugging (ADB) pairing with the
   watch.
2. **Inputs**: getting the official APKs (from APKMirror, a file, or pulled from your own watch or
   phone). Each one is checked against the hashes in the catalogue.
3. **Build**: patching, signing and verifying five APKs with a key that is created on, and stays on,
   your phone:
   - SHM for the phone and for the watch;
   - the phone and watch bridges;
   - the Alarm app fork.
4. **Install on the phone**: installing the apps and granting their permissions.
5. **Install on the watch**: installing the apps over Wi-Fi, granting their permissions, turning on
   Health Platform developer mode, and turning off the stock watch SHM. Nothing is uninstalled.
6. **Self-test**: automatic checks, followed by a few questions about what you see on the devices.

The patches come from a **signed catalogue** (`catalogue/`), and the app can update it from this
repository. The catalogue is data only: the app checks its ECDSA signature and never runs code from
it.

Export the signing key from the app after the first build. Upgrading the installed apps without
losing data requires the **same key and the same package prefix**.

## How it works

1. The watch is paired with the phone through the stock Galaxy Wearable flow, which creates the GMS
   Data Layer between them.
2. Both patched SHM apps share one package (`com.samsung.android.shealthmonitors`) and one signing
   certificate. GMS needs both to deliver messages between the two apps.
3. On the watch, our ECG adapter talks to the Health Tracking service through a clean-room client
   ([protocol](docs/health-tracking-protocol.md)). The service only accepts it while Health
   Platform developer mode is on.
4. The bridge talks to the watch directly over RFCOMM, phone ⇄ watch.

Everything runs on the two devices. Details are in [docs/architecture.md](docs/architecture.md).

## Repository layout

| Path | What it is |
|---|---|
| `patcher-app/` | The OpenSHM Patcher Android app, a Gradle build with three modules: `:engine` (pure JVM patch engine), `:adb` (ADB client) and `:app` (Compose UI). Third-party licences are in [patcher-app/THIRD_PARTY.md](patcher-app/THIRD_PARTY.md). |
| `catalogue/` | The signed patch catalogue. `catalogue.json` describes the inputs, profiles and patterns as data; `catalogue.json.sig` and `catalogue.pub` are its signature and public key. Details in [docs/patches.md](docs/patches.md). |
| `superpatcher/`, `scripts/superpatcher.py` | The Python patch engine. It interprets the same catalogue as the app. |
| `watch-mod/` | Our own code injected into the watch SHM: the ECG adapter, the Health Tracking client and the vendor overrides. |
| `shm-bridge/` | Our own bridge apps (phone ⇄ watch over direct RFCOMM) and the fork of the watch Alarm app. |
| `scripts/` | The PC build (`build_all.sh`, `build_shm_*.sh`, `fetch_deps.sh`), catalogue tooling (`catalogue.py`), and shared helpers in `scripts/lib/`. |
| `apks/` | Where the official input APKs go for PC builds. They are not versioned; see [apks/README.md](apks/README.md) and `apks/inputs.json`. |
| `docs/` | [Architecture](docs/architecture.md), [installation runbook](docs/installation.md), [patches](docs/patches.md), [Health Tracking client](docs/health-sdk.md) and [protocol](docs/health-tracking-protocol.md). |

## Building on a PC

The original shell pipeline is still available, and it produces the same five APKs:

```bash
scripts/fetch_deps.sh                 # apktool into third_party/
# copy the official APKs into apks/ with the names listed in apks/SHA256SUMS
PKG_PREFIX=com.example scripts/build_all.sh
```

All configuration lives in [`config.env`](config.env) and can be overridden through environment
variables: package prefix, SDK, keystore and directories. Our source code uses the package
placeholder `shminterop`, and builds rewrite it to the chosen `PKG_PREFIX`. Manual installation with
ADB, pairing, and known gotchas are covered in [docs/installation.md](docs/installation.md).

### Building the patcher app

The launcher `patcher-app/gradlew` is a jar-free script, since the repository carries no jars. Run
it with a JDK 17 and the Android SDK:

```bash
JAVA_HOME=/path/to/jdk17 patcher-app/gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug
```

Builds are signed with `patcher.p12` and `patcher.pass` from `OPENSHM_KEYS_DIR` (key alias
`PATCHER_KEY_ALIAS`, default `patcher`). Without them, builds fall back to the SDK debug key. The
published releases are built by `.github/workflows/patcher-release.yml` and signed with the
LibreStatic release key.

## Tests

```bash
python3 -m unittest discover -s superpatcher -p 'test_*.py'
python3 -m unittest discover -s scripts -p 'test_*.py'
shm-bridge/watch/test/run-tests.sh
watch-mod/test/run-tests.sh
JAVA_HOME=/path/to/jdk17 patcher-app/gradlew :engine:test :app:testDebugUnitTest
```

## Scope

Dropped on purpose:

- the PC relay or gateway, replaced by native GMS and direct RFCOMM;
- the Galaxy Wearable proxy, which never worked.

Never needed:

- a factory reset;
- a Samsung phone.

## Licence

Our own code is released under the [MIT Licence](LICENSE). The MIT licence does not cover:

- Samsung's apps and the APKs built from them, which remain under their owners' terms;
- the third-party libraries shipped in the patcher app, which keep their own licences (see
  [patcher-app/THIRD_PARTY.md](patcher-app/THIRD_PARTY.md)).

This is an independent project. It is not affiliated with Samsung or Google.
