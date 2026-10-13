# apks/ — official input APKs

This directory holds the **official, unmodified** APKs that the patch pipeline takes as
input. They are not stored in the repo (`apks/*.apk` is gitignored); each user provides them.

`inputs.json` is the canonical description of every input: file name, SHA-256, package,
`versionName`/`versionCode`, `minSdk`/`targetSdk`, ABIs, variant (`nodpi`, plain APK, not a
bundle), target device, and where to obtain it (APKMirror app page, or a file you already have).
`SHA256SUMS` is generated from it (`scripts/gen_sha256sums.py`; `--check` fails if it is stale,
`--verify-apks` also compares the package and version of the APKs present here). The build
scripts check each file against `SHA256SUMS` before use.

| File | Package | Version (code) | Variant | Device | How to obtain it |
|---|---|---|---|---|---|
| `shm-phone-1.4.6.001.apk` | `com.samsung.android.shealthmonitor` | 1.4.6.001 (1406001) | arm64-v8a + armeabi-v7a, nodpi, minSdk 31 | phone | APKMirror "Samsung Health Monitor" (`samsung-electronics-co-ltd/samsung-health-monitor-2`). |
| `shm-watch-1.5.1.022.apk` | `com.samsung.android.shealthmonitor` | 1.5.1.022 (1501022) | armeabi-v7a, nodpi, minSdk 30 | watch | APKMirror "Samsung Health Monitor (Wear OS)" (`samsung-electronics-co-ltd/samsung-health-monitor-wear-os`). |
| `alarm-watch-1.3.05.5.apk` | `com.samsung.android.watch.alarm` | 1.3.05.5 (130505000) | no native code, nodpi, minSdk 34 | watch | APKMirror "Alarm (Wear OS)", `samsung-electronics-co-ltd/alarm-wear-os`, release `alarm-wear-os-1-3-05-5-release`. |
| `shealth-7.00.6.011.apk` | `com.sec.android.app.shealth` | 7.00.6.011 (7006011) | arm64-v8a + armeabi-v7a, nodpi, minSdk 29 | phone | APKMirror "Samsung Health" (`samsung-electronics-co-ltd/s-health`), release `samsung-health-7-00-6-011-release`. |

Inputs are pinned versions (SHA-256 + versionCode); the patcher never pulls APKs from a device. Use the
stock APK, not a patched copy (a patched build has a different package or signature and fails the hash).

After copying the files here, verify them:

```bash
cd apks && sha256sum -c SHA256SUMS
```

Only the versions listed in `inputs.json` are supported; a different build will fail the
check on purpose, because the patch patterns are written against those exact versions.
To support a new version, update its entry in `inputs.json`, run
`scripts/gen_sha256sums.py`, and fix any pattern whose anchor no longer matches.
Never commit these files.
