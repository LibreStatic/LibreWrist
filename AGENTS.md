# AGENTS.md — shm-interop

## What this is

Personal interoperability between a Galaxy Watch and a non-Samsung Android phone, on devices the
owner owns: patches to the official Samsung Health Monitor (SHM) APKs plus our own code (ECG adapter
on top of Health Tracking, alarm bridge over direct RFCOMM). See `README.md` and `docs/`.

## Repository rules

- **English only** for everything that is not user-facing: docs, code comments, commit messages,
  build/log/error messages, test names, script usage text. User-facing app strings (UI resources,
  notification texts) are the only exception.
- **No vendor code and no APKs in the repo.** Official APKs go in `apks/` (git-ignored, described
  by `apks/inputs.json`, from which `apks/SHA256SUMS` is generated); the Health Tracking client
  (`watch-mod/src/shminterop/hsdk`) is our own clean-room code written from
  `docs/health-tracking-protocol.md`; never copy vendor SDK classes into the repo. Never commit
  smali/dex/jar/aar/apk files or decompiled trees.
- **No hard-coded package prefix.** Our code uses the placeholder `shminterop`; builds rewrite it to
  `PKG_PREFIX` (`scripts/lib/stage_prefix.py`, `scripts/lib/config.py`). New code must use the
  placeholder, never a real prefix.
- Configuration only through `config.env` / environment variables. No machine-specific absolute
  paths.
- Build data and temporary files go in `WORK_DIR` (default `~/.cache/shm-interop`), never in `/tmp`,
  `/dev/shm` or the repo. apktool needs a lot of RAM: the scripts serialize it with a lock.
- Never commit keystores, keys or tokens. Keys live outside the repo in `OPENSHM_KEYS_DIR`
  (default `~/.local/share/openshm/keys`, see `config.env`); `keys/` stays ignored.
- Patches: each one is a pattern anchored to the official APK, written as DATA in
  `catalogue/catalogue.json` (the engine, `superpatcher/engine.py` + `patterns.py`, interprets named
  ops; the catalogue never contains code) and the order lives in its `profiles`. Adding a patch =
  catalogue pattern + profile entry + note in `docs/patches.md`, then `scripts/catalogue.py check`
  (and `sign`). If an anchor stops matching with a new APK version, the engine must fail, not
  continue silently.
- Wording: interoperability vocabulary (*vendor override*, *regional check disabled*, *BAL
  exemption*), not offensive-security vocabulary.
- Devices: never factory reset. Destructive commands (e.g. `pm clear` on GMS) only on the owner's
  explicit decision.
- Out of scope (dropped on purpose): the PC relay/gateway and the Galaxy Wearable proxy. Do not
  reintroduce them without the owner asking.

## Device-free tests

```bash
shm-bridge/watch/test/run-tests.sh
watch-mod/test/run-tests.sh
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 -m unittest discover -s superpatcher -p 'test_*.py'
```

## Patcher app (`patcher-app/`)

Gradle multi-module build (`:engine` pure JVM, `:adb` Android library, `:app` Compose app). The
launcher `patcher-app/gradlew` is a jar-free shell script (the repo carries no jars); run it with a
JDK 17 (`JAVA_HOME=<jdk17> patcher-app/gradlew :engine:test`). Every shipped library needs a row
in `patcher-app/THIRD_PARTY.md` before it is added. The app's own code lives in `io.openshm.patcher`;
code injected into vendor APKs still uses the `shminterop` placeholder.
To render a screen without an emulator (Compose Driver, dev-only, needs a JDK 21+): `JAVA_HOME=<jdk21+> patcher-app/tools/compose-driver/compose-driver.sh start WelcomeDriver`, then `tree` (semantics) and `shot <name>`, then `stop`; see `patcher-app/tools/compose-driver/README.md`.

## Versioning (for `patcher-app/`)

- Every uploaded build has a `versionName` that no earlier upload used. This applies to every Play track (internal, closed, open, production) and to GitHub releases. Two builds with the same name are never allowed, not even a rebuild, a hotfix or a re-upload after a rejected or broken build. A new `versionCode` alone is not enough. Before uploading, check the release list for the name. If the name is already taken, bump the version again.
- Bump `versionName` in `app/build.gradle.kts` in its own commit whenever you commit a change that affects the app (its code, resources or bundled data), right after that change, even when no upload is planned yet. Also bump it before an upload if the current name was already uploaded. Patch (`x.y.Z`) for fixes and small, self-contained changes (including a single small feature), minor (`x.Y.0`) for medium changes (several features, a reworked screen or flow), major (`X.0.0`) for a large overhaul; keep the pre-release suffix (e.g. `-beta`). The `versionCode` must also be higher than the last uploaded one.
