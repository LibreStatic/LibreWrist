# SHM Bridge — phone app + Wear OS companion

A portable mechanism to use the Samsung Health Monitor (SHM) feature set of a Galaxy Watch
(verified on the author's own watch, One UI 8 / Android 16) from **any Android phone**,
based on the permission and access-path audit of that watch:

- **F2 — exported activities without a permission**: stock SHM exposes `EcgMainActivity`,
  `BpMainActivity`, `BpMeasureRunActivity` and `MainActivity`. They can be launched with
  `startActivity` from another app, with no root and no mod.
- **BAL exemption (F2+)**: One UI blocks background `startActivity`; this is solved with the
  `SYSTEM_ALERT_WINDOW` appop (`BAL_ALLOW_SAW_PERMISSION` exemption, verified on the device).
- **Dangerous permissions**: `BODY_SENSORS` / `READ_ADDITIONAL_HEALTH_DATA` and the Health
  Connect ones are granted with `pm grant` over ADB.
- **Result capture**: an `AccessibilityService` that reads the SHM result screen (for BP/ECG,
  which do not go through Health Connect; on this watch Health Connect is not even present).

## Architecture

```
┌──────────────────┐   TCP/LAN (token)   ┌──────────────────────────┐
│  Android phone   │ ──────────────────► │  Galaxy Watch            │
│  SHM Bridge      │  ECG/BP/SYNC/LAST   │  SHM Bridge (companion)  │
│  (shminterop.    │ ◄────────────────── │   • launches exported    │
│   shmbridge.phone│   DATA (results)    │     SHM activities       │
└──────────────────┘                     │   • measurement wake lock│
                                         │   • SHM screen capture   │
                                         │   • reads Health Connect │
                                         │     (if present)         │
                                         └──────────────────────────┘
                                                        │ startActivity
                                                        ▼
                                         ┌──────────────────────────┐
                                         │ stock SHM 1.5.1.022      │
                                         │ (ECG / BP / measurement) │
                                         └──────────────────────────┘
```

No GMS, no Gradle: plain Java built with `javac` + `d8` + `aapt2` + `apksigner`
(`scripts/build-bridge.sh`). Text protocol over a socket with a pairing token.

The package prefix `shminterop` is a marker: builds rewrite it to `PKG_PREFIX`
(`config.env`, see `scripts/lib/stage_prefix.py`). The examples in this README use the default.

> **Supported transport: direct RFCOMM phone <-> watch** (default `bt`). The code keeps the
> `tcp` transport and the `/__gw__/` channel (`GwHub`), which target a PC gateway that is
> **not included** in this repo; they are not part of the supported flow.

## Build

```bash
./shm-bridge/scripts/build-bridge.sh   # writes $DIST_DIR/shm-bridge-watch.apk and -phone.apk
BRIDGE_ONLY=phone ./shm-bridge/scripts/build-bridge.sh   # only one of them (watch|phone)
```

It needs the play-services-wearable AAR (`scripts/fetch_deps.sh`) and signs with the local
keystore (`KEYSTORE` in `config.env`, generated if missing). `versionCode` / `versionName`
come from each `AndroidManifest.xml`.

## Install (watch)

```bash
adb install -r dist/shm-bridge-watch.apk

# permissions
adb shell pm grant shminterop.shmbridge.watch android.permission.POST_NOTIFICATIONS
for p in READ_HEART_RATE READ_RESTING_HEART_RATE READ_HEART_RATE_VARIABILITY \
         READ_OXYGEN_SATURATION READ_BLOOD_PRESSURE READ_SLEEP READ_STEPS \
         READ_BODY_TEMPERATURE READ_RESPIRATORY_RATE; do
  adb shell pm grant shminterop.shmbridge.watch android.permission.health.$p
done

# BAL exemption + restricted settings + screen capture
adb shell appops set shminterop.shmbridge.watch SYSTEM_ALERT_WINDOW allow
adb shell appops set shminterop.shmbridge.watch ACCESS_RESTRICTED_SETTINGS allow
adb shell settings put secure enabled_accessibility_services \
    shminterop.shmbridge.watch/.ShmCaptureService
adb shell settings put secure accessibility_enabled 1
adb shell settings put secure doze_always_on 0   # recommended for measurements

# start (shows IP + token on screen and in logcat)
adb shell am start -n shminterop.shmbridge.watch/.MainActivity
```

> Note: **every reinstall resets** accessibility and the appops; repeat the block above.

## Communications channel `/__bridge__/` (watch)

Phone notifications, alarms and calls mirrored on the watch over the same direct RFCOMM
session. The protocol is documented in the comments of `FrameMux`, `BridgeHub` and `NotifCodec`.

- `BtRelayClient` uses `FrameMux` (pure Java): it parses `[int len][short pathLen][path][data]`
  frames in both directions. `/__bridge__/*` frames go to `BridgeHub` (in-process); everything
  else is passed byte for byte to the SHM at `127.0.0.1:8773`. All RFCOMM writes are complete
  frames under a single lock. The 8773 side is optional: without SHM the session stays alive
  for the channel, an own heartbeat is emitted after 2.5 s of inactivity and the 8773 is
  retried in the background. An absurd `len` (< 2 or > 16 MB) means desynchronization and
  closes the session.
- `BridgeNotifier` (tag `br:<id>`, channels `br_<pkg>` DEFAULT and `br_<pkg>_hi` HIGH for
  the msg/call/alarm categories), `AlarmMirror` + `AlarmRinger` + `RingActivity` (one managed
  `setAlarmClock` at `t + 5 s`, suppressed if `alarm/ring` arrived from the phone),
  `CallMirror` (`CATEGORY_CALL` notification with Answer/Reject).
- Log: tag `SHMBridgeComms`, only path/id/package/lengths.
- Host-side tests of the mux: `watch/test/run-tests.sh`.

Permissions/appops after every install (they reset on reinstall):

```bash
W=shminterop.shmbridge.watch
adb shell pm grant $W android.permission.POST_NOTIFICATIONS
adb shell appops set $W SCHEDULE_EXACT_ALARM allow     # USE_EXACT_ALARM is already in the manifest
adb shell appops set $W USE_FULL_SCREEN_INTENT allow   # alarm screen (Android 14+)
adb shell appops set $W SYSTEM_ALERT_WINDOW allow      # BAL exemption (opens RingActivity with the screen on)
adb shell dumpsys deviceidle whitelist +$W             # battery exemption (Doze)
```

Debugging (receiver protected by `android.permission.DUMP`: only shell and the app itself):

```bash
R="-n shminterop.shmbridge.watch/.BridgeActionReceiver --include-stopped-packages"
# inject a frame as if it came from the phone
adb shell 'am broadcast '"$R"' -a shminterop.shmbridge.DEBUG_RECV --es path /__bridge__/notif/post --es json "{\"id\":\"t1\",\"pkg\":\"com.example\",\"app\":\"Example\",\"title\":\"Test\",\"text\":\"Hello\",\"cat\":\"msg\",\"actions\":[{\"i\":0,\"label\":\"Reply\",\"reply\":true,\"choices\":[\"Ok\"]}],\"dismissible\":true}"'
# send a frame to the phone
adb shell 'am broadcast '"$R"' -a shminterop.shmbridge.DEBUG_SEND --es path /__bridge__/hello --es json "{\"v\":1,\"role\":\"watch\",\"caps\":[]}"'
adb logcat -s SHMBridgeComms
```

## `/__gw__/` channel and GwHub (phase 2: Galaxy Wearable / heartplugin)

A second in-process hub of the watch bridge: the `/__gw__/*` frames of the RFCOMM session are
served by `GwHub` (same pattern as `BridgeHub`), which answers over the same channel. It is
the watch side of phase 2: heartplugin talks the GMS Data Layer with the watch through the
bridge instead of through GMS. It targets a PC gateway that is not included in this repo;
with direct RFCOMM the phone ignores these frames.

- `FrameMux` routes three channels: `/__bridge__/*` -> `BridgeHub`, `/__gw__/*` -> `GwHub`
  (prefix already stripped, `Listener.onGwFrame`), everything else byte for byte to the SHM's
  8773; SHM heartbeats are untouched. `sendGw(path, data)` requires a leading slash and rejects
  already-prefixed paths; on the RFCOMM it goes out as `/__gw__/` + GMS path (intentional
  double slash, removed by the phone's GW client).
- **What `GwHub` answers** (payloads = serialized GMS DataMap, `GwDataMap` encoder; mirror
  store of the DataClient in `GwData`):
  - **Battery**: DataItems `wear://watch/c_devicecare/battery/base_info` and
    `wear://watch/w_module_sec_watch_setting/battery_changed` (host `watch`, as required by the
    heartplugin Settings WLS filter) on session open, on BatteryInfoProvider requests
    (`c_battery/syncBatteryInfo`, `c_battery/refreshBatteryInfo`,
    `c_devicecare/battery/{base_info,sync,refresh}`) and on every `ACTION_BATTERY_CHANGED`
    (5 s anti-spam).
  - **RAM**: `c_ram/requestRamInfo` -> message `/c_ram/resultRamInfo` + DataItem
    `wear://watch/c_devicecare/memory/info` (`mem_total`/`mem_available`/`memory_used`).
  - **State**: DataItem `wear://watch/w_module_sec_watch_setting/state` (own extension, no
    verified stock consumer) with `Build.*`, settings readable without a permission, volumes,
    brightness and next wake-up.
  - **Mirror store `/__d__/*`**: put/del update it; `all` answers `all-r` with the snapshot
    (the direction is fixed: the phone requests it once the link is verified).
- **What it does not answer** (documented, not improvised): the real application of the
  watch settings (psm, dnd, advanced, show_on_phone, wrist_detection) is done by gws
  (`sharedUserId=android.uid.system`, cannot be forked) and there is no public API; those
  messages are logged and left silent. Same for any path without a handler: `sendMessage`
  does not require a reply, so silence is the documented answer.
- Log: tag `SHMBridgeGw`, only paths/uris and lengths.
- Host-side tests: `watch/test/run-tests.sh` compiles `FrameMux`+`GwData`+`GwDataMap` (pure
  Java) and runs `FrameMuxTest` and `GwMuxTest`: GW routing with the prefix stripped and never
  to the 8773, `sendGw` guards, GW+bridge+SHM interleaving without breaking framing, exact
  put/del/all/all-r framing, codec and DataMap vectors with unknown-field skipping.

## Install (phone)

Side-load `dist/shm-bridge-phone.apk` on the phone. Enter the watch IP and the token (shown
by the watch app when opened). Buttons: ECG, BP measure, BP menu, SHM home, Sync (Health
Connect) and Last result (watch screen capture).

## Verified state on the author's own watch (One UI 8)

| Function | State |
|---|---|
| Launch ECG/BP from the phone through the companion (background, without touching the watch) | OK: `BAL_ALLOW_SAW_PERMISSION`, result=2 (TASK_TO_FRONT) |
| Token-protected TCP bridge on the LAN | OK |
| Measurement wake lock (screen on for 15 s) | OK |
| Result screen capture (accessibility) | OK: bound service + located window; in real use the watch must be on the wrist (off-wrist the system returns to the watchface after ~10 s and aborts the capture) |
| Health Connect on the watch | Not present on this firmware (`android.software.health_connect` = false); the SYNC code is kept for watches that have it |

## Known limitations

- Off-wrist, the watch returns to the watchface: for the full flow (measurement + capture)
  it has to be worn. Without a wrist the trigger still works but the measurement does not
  progress.
- BP requires a prior calibration (done once against a cuff); ECG requires touching the
  crown. Both are unavoidable physical interactions; the bridge only orchestrates.
- ECG/BP do not travel through Health Connect from SHM: the value is read from the screen
  (accessibility) or seen directly on the watch. The capture returns the raw text of the
  active SHM window.
- The token travels in clear over the LAN (useful for testing); for prolonged use switch to
  TLS or bind the port to the Bluetooth/Companion link.

## Alarm Fork (`shminterop.shmbridge.alarm`)

A fork of the Samsung Alarm app (AlarmWatch 1.3.05.9) packaged as
`shminterop.shmbridge.alarm`; it coexists with the system app. It replaces the Wear data
layer (GMS Wearable) with local intents to the bridge, using Samsung's native UI
(CAlarmAlertActivity) when a phone alarm rings. The fork is produced by the patch pipeline
from the official APK; this repo only contains its source classes under `alarm-fork/src` (see
`alarm-fork/README.md`).

**Priority chain** (in `AlarmMirror`): **AlarmFork** -> **AlarmRinger**. If the fork is
installed and has `SYSTEM_ALERT_WINDOW` (appop), alarms use Samsung's native UI. Otherwise
`AlarmRinger` rings as a fallback. The local alarm from `onFire` (link down) also uses the
fork when available.

Both packages (`watch` and `alarm`) are signed with the same local keystore (`KEYSTORE` in
`config.env`, generated if missing) and protected by the signature permission
`shminterop.shmbridge.permission.ALARM_LINK` (declared in the bridge, used by the fork).

## Layout

```
shm-bridge/
├── watch/            # Wear OS companion (Java, no dependencies)
│   ├── AndroidManifest.xml
│   ├── res/xml/accessibility_service_config.xml
│   └── src/shminterop/shmbridge/watch/
│       ├── MainActivity.java     # watch UI + direct SHM launch
│       ├── BridgeService.java    # FGS: TCP socket, token, wake lock, notification fallback
│       ├── HcReader.java         # Health Connect reader (optional, depends on firmware)
│       ├── ShmCaptureService.java# accessibility: captures the SHM result screen
│       ├── BtRelayClient.java    # RFCOMM session with the phone + FrameMux
│       ├── FrameMux.java         # frame demux: SHM 8773 <-> RFCOMM <-> BridgeHub/GwHub (pure Java)
│       ├── BridgeHub.java        # /__bridge__/ channel: dispatch, hello, send()
│       ├── GwHub.java            # /__gw__/ channel: battery/RAM/state for heartplugin, mirror store
│       ├── GwData.java           # DataClient /__d__/* codec and store (pure Java)
│       ├── GwDataMap.java        # GMS DataMap encoder (protobuf, scalar tags)
│       ├── BridgeNotifier.java   # mirrored notifications
│       ├── BridgeActionReceiver.java # actions/dismissals -> phone, DEBUG_SEND/DEBUG_RECV
│       ├── AlarmMirror.java, AlarmRinger.java, BridgeAlarmReceiver.java, RingActivity.java
│       ├── AlarmFork.java        # integration with the Samsung Alarm app fork
│       ├── AlarmForkReceiver.java # receives dismiss/snooze from the fork
│       └── CallMirror.java       # incoming call (no audio)
│   └── test/                     # host-side FrameMux tests (run-tests.sh)
├── phone/            # phone app (Java; the Wearable API classes come from the AAR)
│   ├── src/shminterop/...
│   └── stubs/        # compile-only stubs (Task/Tasks/...), never dexed
├── alarm-fork/       # source classes of the alarm fork (own short README)
│   └── src/shminterop/shmbridge/alarm/
│       ├── Link.java             # transport: /c_alarm/* in, /w_alarm/* -> bridge
│       ├── LinkReceiver.java     # BroadcastReceiver (stopped/muted/snoozeChanged)
│       └── LinkActivity.java     # invisible activity to reach the foreground (started)
└── scripts/
    ├── build-bridge.sh           # Gradle-free build (javac+d8+aapt2+apksigner)
    └── gen-call-notice-strings.py # generates the call_notice / alarm_label resources
```

## Protocol commands (TCP transport)

`AUTH <token>` -> `OK` · `PING` -> `PONG` · `ECG` / `BP` / `BPRUN` / `MAIN` ->
`OK <activity>` (or `OK-NOTIF <activity>` without BAL exemption: tap the notification) ·
`SYNC` -> `DATA {Health Connect json}` · `LAST` -> `DATA <SHM screen text>` · `BYE`.

## Communications channel `/__bridge__/` (phone)

Phone side in `phone/src/shminterop/shmbridge/phone/`: `CommsService` (`specialUse` FGS;
link `BridgeLink`, ping `/__bridge__/ping` every 2.5 s, alarm/next and call/state),
`BridgeNotificationListener` + `NotifCodec` (notif/*, alarm/ring|stop), `CommsHub` (dispatch
and watch actions), `CommsDebugReceiver`, `CommsBootReceiver`. The app does **not** start
`BtServer`/`BtRelayServer` by itself (BT autostart sits behind the `bt_autostart` pref, off).

**Transport (since 1.5-rfcomm, default `bt`): no PC.** `BridgeLink` opens an RFCOMM server on
the phone with the service UUID. The watch's `BtRelayClient` tries the bonded devices,
connects by itself, and its `FrameMux` delivers the `/__bridge__/*` frames. The phone ignores
plain heartbeats and `/__gw__/*`. SHM does not use this channel: it goes through the native
GMS Data Layer.

Mirrored capabilities (`caps`), default `alarm`: with the watch paired through Galaxy
Wearable, notifications and calls arrive natively and mirroring them would duplicate.
Configuration (prefs `comms`; the transport is applied on process restart):

```bash
r shell am broadcast --user 0 -n $P/.CommsDebugReceiver -a shminterop.shmbridge.DEBUG_SEND \
  --es path config --es caps alarm            # or notif,alarm,call
r shell am broadcast --user 0 -n $P/.CommsDebugReceiver -a shminterop.shmbridge.DEBUG_SEND \
  --es path config --es transport bt          # only supported transport (tcp: see above)
```

```bash
P=shminterop.shmbridge.phone; r() { adb -s <phone-serial> "$@"; }
BRIDGE_ONLY=phone shm-bridge/scripts/build-bridge.sh
r install -r --user 0 dist/shm-bridge-phone.apk
r shell appops set --user 0 $P ACCESS_RESTRICTED_SETTINGS allow
r shell appops set --user 0 $P SYSTEM_ALERT_WINDOW allow      # BAL exemption (notif/open, alarms)
for x in ANSWER_PHONE_CALLS READ_PHONE_STATE POST_NOTIFICATIONS; do
  r shell pm grant --user 0 $P android.permission.$x; done
r shell cmd notification allow_listener $P/$P.BridgeNotificationListener 0
r shell am start-foreground-service --user 0 -n $P/.CommsService
r logcat -s SHMBridgeComms                                     # only ids, packages, paths and lengths
# status / inject a frame as if it came from the watch / send one to the watch
r shell am broadcast --user 0 -n $P/.CommsDebugReceiver -a shminterop.shmbridge.DEBUG_SEND --es path status
r shell am broadcast --user 0 -n $P/.CommsDebugReceiver -a shminterop.shmbridge.DEBUG_SEND \
  --es path /__bridge__/notif/dismiss --es json "'{\"id\":\"<id>\"}'" --ez inject true
```
