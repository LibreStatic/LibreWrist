# AGENTS.md — shm-interop

## What this is

Personal interoperability between a Galaxy Watch and a non-Samsung Android phone, on devices the
owner owns: patches to the official Samsung Health Monitor (SHM) APKs plus our own code (ECG adapter
on top of Health Tracking, alarm bridge over direct RFCOMM). See `README.md` and `docs/`.

## Repository rules

- **English only** for everything that is not user-facing: docs, code comments, commit messages,
  build/log/error messages, test names, script usage text. User-facing app strings (UI resources,
  notification texts) are the only exception.
- **No vendor code and no APKs in the repo.** Official APKs go in `apks/` (git-ignored, checked
  against `apks/SHA256SUMS`); the Health Tracking client (`watch-mod/src/shminterop/hsdk`) is our own
  clean-room code written from `docs/health-tracking-protocol.md`; never copy vendor SDK classes into
  the repo. Never commit smali/dex/jar/aar/apk files or decompiled
  trees.
- **No hard-coded package prefix.** Our code uses the placeholder `shminterop`; builds rewrite it to
  `PKG_PREFIX` (`scripts/lib/stage_prefix.py`, `scripts/lib/config.py`). New code must use the
  placeholder, never a real prefix.
- Configuration only through `config.env` / environment variables. No machine-specific absolute
  paths.
- Build data and temporary files go in `WORK_DIR` (default `~/.cache/shm-interop`), never in `/tmp`,
  `/dev/shm` or the repo. apktool needs a lot of RAM: the scripts serialize it with a lock.
- Never commit keystores, keys or tokens (`keys/` is ignored).
- Patches: each one is a pattern anchored to the official APK (`superpatcher/patterns.py`) and the
  order lives in `profiles/gms`. Adding a patch = pattern + profile entry + note in
  `docs/patches.md`. If an anchor stops matching with a new APK version, the engine must fail, not
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
python3 -m unittest discover -s superpatcher -p 'test_*.py'
```
