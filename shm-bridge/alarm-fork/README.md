# Alarm Fork (source classes)

The alarm fork (`shminterop.shmbridge.alarm`) is **not built from this directory**. It is
produced by the patch pipeline (profile `alarm` of `catalogue/catalogue.json`,
`scripts/build_alarm_fork.sh`) from the official `alarm-watch-1.3.05.5` APK, which the user downloads from APKMirror (the copy preinstalled on a watch is usually another build and fails the hash) (see `apks/README.md`).
The patch list is documented in [docs/patches.md](../../docs/patches.md#alarm-fork-1305-9).
No vendor code is stored in this repo.

`src/shminterop/shmbridge/alarm/` holds the classes that the pipeline adds to the fork:

- `Link` — transport that replaces the Wear data layer: `/c_alarm/*` messages come in,
  `/w_alarm/*` messages go out as broadcasts to the watch bridge.
- `LinkReceiver` — `BroadcastReceiver` for the bridge's events (stopped/muted/snoozeChanged).
- `LinkActivity` — invisible activity used to reach the foreground (started).
- `Pinch` — double pinch detector: feeds the sensors to the public device library
  `lib_algorithm.gesture.samsung.so` and calls the app's gesture listener (the vendor gesture
  manager is not reachable for the re-signed fork).
- `NativeWrapper` (`src/com/samsung/android/lib_algorithm/gesture/samsung/`) — our clean-room JNI
  binding for that library; its class name is fixed by the library's exported symbols.

Bridge and fork talk through broadcasts protected by the signature permission
`shminterop.shmbridge.permission.ALARM_LINK` (declared by the watch bridge, used by the fork),
so both packages must be signed with the same local key. The package marker `shminterop` is
rewritten to `PKG_PREFIX` at build time.
