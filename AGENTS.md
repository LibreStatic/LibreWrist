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
- Every push to `main` that touches `patcher-app/`, `watch-mod/`, `shm-bridge/` or `catalogue/` publishes a GitHub release (`.github/workflows/patcher-release.yml`), so pushing such a commit is an upload. The workflow fails before building when the `versionName` was already released.
- Bump `versionName` in `app/build.gradle.kts` in its own commit whenever you commit a change that affects the app (its code, resources or bundled data, including the directories above), right after that change, even when no upload is planned yet. Also bump it before an upload if the current name was already uploaded. Patch (`x.y.Z`) for fixes and small, self-contained changes (including a single small feature), minor (`x.Y.0`) for medium changes (several features, a reworked screen or flow), major (`X.0.0`) for a large overhaul; keep the pre-release suffix (e.g. `-beta`). The `versionCode` must also be higher than the last uploaded one.

## Known issues (`README.md`)

The README has a `## Known issues` section, between "How to check that it works" and "How it
works". It lists real bugs reported on GitHub (`gh issue list --repo LibreStatic/LibreWrist`).

- **Only real bugs.** Leave out questions, usage help and expected behaviour, even when they were
  filed as issues. For example, #1 ("the bridge has no launcher icon") is by design, so it is not
  listed. The `bug` / `confirmed` labels help, but read the thread to decide.
- An issue still being investigated can be listed when the owner labelled it `bug` and the thread
  describes a plausible defect. Mark it `:mag:`.
- One top-level bullet per issue, ordered by issue number. The bullet is only the status emoji, a
  bold one-line summary of what the user sees, and the link
  (`[#N](https://github.com/LibreStatic/LibreWrist/issues/N)`). No prose on that line.
- Details go in nested bullets, one or two sentences each, with a bold label:
  - `**Cause:**` in plain words.
  - `**Status:**` what is confirmed, and what is in progress or still needs a log.
  - `**Fixed in:**` the version that contains the fix and what to rebuild (phone app, watch app,
    or both). Use the latest version that has the full fix. Mention an earlier version only when
    users must avoid it (for example a build that fails).
  - `**Workaround:**` only when there is one.
  - `**Note:**` expected behaviour that users mistake for the bug (keep it to one bullet).
- When one issue covers several separate problems, give each problem its own nested bullet that
  starts with its own status emoji and a bold summary sentence, then the cause, fix and status in
  the same bullet. The top-level emoji is then the least advanced status among them
  (`:mag:` < `:construction:` < `:hourglass_flowing_sand:`). Drop a sub-problem once a user
  confirms its fix, and drop the whole entry when none are left.
- Do not keep the history of the fix (every intermediate version, measurements, dead ends). The
  issue thread has it. Rewrite the entry instead of appending sentences to it.
- The short paragraph under the legend tells users how to export a device log. Keep it in sync
  with the patcher's log viewer.
- Status emojis. Keep the legend line under the intro in sync with them:
  - `:mag:` means the bug is reported and under investigation (cause unknown, usually waiting for
    a device log).
  - `:construction:` means the cause is known and the fix is in progress.
  - `:hourglass_flowing_sand:` means the fix is released and waiting for user confirmation. Here,
    "confirmation" means a user saw the fix working on their device. It never means confirming that
    the bug exists. Word the entry that way: the bug is confirmed, the fix shipped in X, and an
    affected user still needs to report that it works.
- Remove an entry once the issue is closed or a user confirms the fix works.
- **On every request to push**, check the open issues (and any closed since the last update) with
  `gh issue view N --comments` before pushing. Add new real bugs, update statuses and versions, and
  drop resolved entries. Commit README changes in their own commit. If nothing changed, say so
  briefly.
