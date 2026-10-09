# apks/ — official input APKs

This directory holds the **official, unmodified** APKs that the patch pipeline takes as
input. They are not stored in the repo (`apks/*.apk` is gitignored); each user provides them.

`inputs.json` is the canonical description of every input: file name, SHA-256, package,
`versionName`/`versionCode`, `minSdk`/`targetSdk`, ABIs, variant (`nodpi`, plain APK, not a
bundle), target device, and where to obtain it (APKMirror app page, or pull it from a device).
`SHA256SUMS` is generated from it (`scripts/gen_sha256sums.py`; `--check` fails if it is stale,
`--verify-apks` also compares the package and version of the APKs present here). The build
scripts check each file against `SHA256SUMS` before use.

| File | Package | Version (code) | Variant | Device | How to obtain it |
|---|---|---|---|---|---|
| `shm-phone-1.4.6.001.apk` | `com.samsung.android.shealthmonitor` | 1.4.6.001 (1406001) | arm64-v8a + armeabi-v7a, nodpi, minSdk 31 | phone | APKMirror "Samsung Health Monitor" (`samsung-electronics-co-ltd/samsung-health-monitor-2`), or pull it from your phone. |
| `shm-watch-1.5.1.022.apk` | `com.samsung.android.shealthmonitor` | 1.5.1.022 (1501022) | armeabi-v7a, nodpi, minSdk 30 | watch | APKMirror "Samsung Health Monitor (Wear OS)" (`samsung-electronics-co-ltd/samsung-health-monitor-wear-os`), or pull it from your watch. |
| `alarm-watch-1.3.05.9.apk` | `com.samsung.android.watch.alarm` | 1.3.05.9 (130509000) | no native code, nodpi, minSdk 34 | watch | Pull it from your watch (APKMirror "Alarm (Wear OS)", `samsung-electronics-co-ltd/alarm-wear-os`, may not list this exact build). |

Pulling from a device: `adb shell pm path <package>` + `adb pull <path>`. Pull the stock app,
not a patched copy (a patched build has a different package or signature and fails the hash).

After copying the files here, verify them:

```bash
cd apks && sha256sum -c SHA256SUMS
```

Only the versions listed in `inputs.json` are supported; a different build will fail the
check on purpose, because the patch patterns are written against those exact versions.
To support a new version, update its entry in `inputs.json`, run
`scripts/gen_sha256sums.py`, and fix any pattern whose anchor no longer matches.
Never commit these files.
