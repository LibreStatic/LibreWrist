# shm-interop

Personal interoperability between a Galaxy Watch and a **non-Samsung** Android phone, so that the
Samsung Health Monitor (SHM) app can measure ECG/BP/IHRN on the watch and sync with the phone, plus
our own bridge for alarms, notifications and calls.

This repository **contains no vendor code and no APKs**: only the patches (as patterns anchored to
the official APK), our own code and the scripts that rebuild everything from the official APKs each
user downloads from APKMirror. The resulting APKs are signed with a local key and are for
personal use; they are not redistributed.

Tested with: Galaxy Watch5 Pro (SM-R920, Wear OS API 36) and a Motorola foldable phone.

## Contents

| Path | What it is |
|---|---|
| `superpatcher/`, `scripts/superpatcher.py`, `catalogue/` | Patch engine (named ops) and the signed patch catalogue (`catalogue/catalogue.json`: the patterns for the official APKs as data, plus the profiles with their application order). Details in [docs/patches.md](docs/patches.md). |
| `watch-mod/` | Our own code injected into the watch SHM (ECG adapter on top of Health Tracking, vendor overrides). |
| `shm-bridge/` | Our own bridge apps (phone ⇄ watch over direct RFCOMM) and the fork of the watch Alarm app. |
| `scripts/` | Builds (`build_all.sh`, `build_shm_*.sh`, `fetch_deps.sh`) and shared helpers in `scripts/lib/`. |
| `apks/` | Where the official input APKs go (not versioned; see [apks/README.md](apks/README.md) and `apks/SHA256SUMS`). |
| `docs/` | [Architecture and protocol](docs/architecture.md), [installation](docs/installation.md), [patches](docs/patches.md), [Health Tracking client](docs/health-sdk.md). |

## How it works

The watch is paired with the phone through the stock Galaxy Wearable flow, so the GMS Data Layer
exists between them. Both patched SHM apps share the package
(`com.samsung.android.shealthmonitors`) and the signing certificate, which GMS requires to deliver
messages between them. The bridge talks over direct RFCOMM phone ⇄ watch. Everything runs on the two
devices; no PC is needed. Details in [docs/architecture.md](docs/architecture.md).

Dropped and not included: the PC relay/gateway (replaced by native GMS and direct RFCOMM) and the
Galaxy Wearable proxy (it never worked).

## Quick start

```bash
scripts/fetch_deps.sh                 # apktool into third_party/
# copy the official APKs into apks/ with the names listed in apks/SHA256SUMS
PKG_PREFIX=com.example scripts/build_all.sh
```

All configuration (package prefix, SDK, keystore, directories) lives in [`config.env`](config.env)
and can be overridden through environment variables. Our source code uses the package placeholder
`shminterop`; builds rewrite it to the chosen `PKG_PREFIX`.

To upgrade already installed apps without losing data, sign with the **same** key and the **same**
prefix as the previous install (`KEYSTORE`, `KEY_ALIAS`, `KS_PASS_FILE`, `PKG_PREFIX`).

Step-by-step installation, pairing and known gotchas are in
[docs/installation.md](docs/installation.md).
