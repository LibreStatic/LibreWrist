# apks/ — official input APKs

This directory holds the **official, unmodified** APKs that the patch pipeline takes as
input. They are not stored in the repo (`apks/*.apk` is gitignored); each user provides them
from their own devices. `SHA256SUMS` lists the exact builds the pipeline was verified
against, and the build scripts check each file against it before use.

| File | What it is | How to obtain it |
|---|---|---|
| `shm-phone-1.4.6.001.apk` | Samsung Health Monitor, phone side, 1.4.6.001 | Install it from the store on your phone, then extract the base APK (`adb shell pm path <package>` + `adb pull`). |
| `shm-watch-1.5.1.022.apk` | Samsung Health Monitor, watch side, 1.5.1.022 | Extract it from your own watch: `adb shell pm path <package>` + `adb pull <path>`. |
| `alarm-watch-1.3.05.9.apk` | Samsung Alarm app of the watch, 1.3.05.9 (input of the alarm fork) | Same extraction from your own watch. |

After copying the files here, verify them:

```bash
cd apks && sha256sum -c SHA256SUMS
```

Only the versions listed in `SHA256SUMS` are supported; a different build will fail the
check on purpose, because the patch patterns are written against those exact versions.
Never commit these files.
