# SHM Bridge — phone app + Wear OS companion

Our own pair of apps that mirror **notifications, alarms and call state** between an Android phone
and a Galaxy Watch over **one direct RFCOMM session** (no PC, no Wi-Fi). Everything else
(SHM data, ECG/BP) travels over the GMS Data Layer created by the stock Galaxy Wearable pairing and
is not handled here: see [../docs/architecture.md](../docs/architecture.md).

```
┌──────────────────────────┐   RFCOMM (UUID 8b6d5c1e-…)   ┌──────────────────────────┐
│ Phone: SHM Bridge        │ ◄─────────────────────────── │ Watch: SHM Bridge        │
│  BridgeLink (RFCOMM      │   /__bridge__/* frames       │  RfcommLink + FrameMux   │
│  server) + CommsHub      │ ────────────────────────────►│  BridgeHub               │
│  StatusProvider          │                              │  ⇄ Alarm fork (intents)  │
└──────────────────────────┘                              └──────────────────────────┘
```

No GMS, no Gradle: plain Java built with `javac` + `d8` + `aapt2` + `apksigner`
(`scripts/build-bridge.sh`).

The package prefix `shminterop` is a marker: builds rewrite it to `PKG_PREFIX`
(`config.env`, see `scripts/lib/stage_prefix.py`). The examples in this README use the default.

## Build

```bash
./shm-bridge/scripts/build-bridge.sh   # writes $DIST_DIR/shm-bridge-watch.apk and -phone.apk
BRIDGE_ONLY=phone ./shm-bridge/scripts/build-bridge.sh   # only one of them (watch|phone)
```

It signs with the local keystore (`KEYSTORE` in `config.env`, generated if missing).
`versionCode` / `versionName` come from each `AndroidManifest.xml` (watch 16 / 1.15, phone 10 / 1.9).
Optional `PATCHER_CERT_SHA256`: see "Status provider" below.

## Install and grants

Install with `adb install -r` (same key and `PKG_PREFIX` as the previous install to upgrade in
place). **The authoritative list of permissions, appops, notification listeners and exemptions
is the `grants` section of [`catalogue/catalogue.json`](../catalogue/catalogue.json)** (format in
[../docs/patches.md](../docs/patches.md)); the installer app reads it from there. They must be
repeated after every reinstall. The equivalent manual commands, derived from the catalogue:

```bash
# Watch bridge (output "bridge-watch")
W=shminterop.shmbridge.watch
for p in POST_NOTIFICATIONS BLUETOOTH_CONNECT READ_PHONE_STATE ANSWER_PHONE_CALLS; do
  adb -s $WATCH shell pm grant $W android.permission.$p; done
for op in SCHEDULE_EXACT_ALARM USE_FULL_SCREEN_INTENT SYSTEM_ALERT_WINDOW MANAGE_ONGOING_CALLS; do
  adb -s $WATCH shell appops set $W $op allow; done
adb -s $WATCH shell cmd notification allow_listener $W/$W.SamsungAlarmListener
adb -s $WATCH shell dumpsys deviceidle whitelist +$W

# Alarm fork (output "alarm-fork")
F=shminterop.shmbridge.alarm
adb -s $WATCH shell pm grant $F android.permission.POST_NOTIFICATIONS
adb -s $WATCH shell appops set $F USE_FULL_SCREEN_INTENT allow
adb -s $WATCH shell dumpsys deviceidle whitelist +$F

# Phone bridge (output "bridge-phone")
P=shminterop.shmbridge.phone
for p in ANSWER_PHONE_CALLS READ_PHONE_STATE POST_NOTIFICATIONS BLUETOOTH_CONNECT; do
  adb -s $PHONE shell pm grant --user 0 $P android.permission.$p; done
for op in ACCESS_RESTRICTED_SETTINGS SYSTEM_ALERT_WINDOW; do
  adb -s $PHONE shell appops set --user 0 $P $op allow; done
adb -s $PHONE shell cmd notification allow_listener $P/$P.BridgeNotificationListener 0
adb -s $PHONE shell cmd deviceidle whitelist +$P
adb -s $PHONE shell am start-foreground-service --user 0 -n $P/.CommsService
```

Neither bridge has a launcher entry (the icon only shows in app lists; Android 10+ may show a
generic "App info" entry on the phone, which is expected). A freshly installed app is in the
stopped state and receives no `BOOT_COMPLETED` until something starts it once, so start each
bridge after installing it (the patcher does this):

```sh
adb -s $WATCH shell am broadcast -a shminterop.shmbridge.START -n $W/.BridgeAlarmReceiver -f 0x20
adb -s $PHONE shell am broadcast --user 0 -a shminterop.shmbridge.START -n $P/.CommsBootReceiver -f 0x20
```

The watch START may carry the boolean extra `--ez alarms false`, which turns alarm sync off
entirely (no fork ring, no fallback ringer, no local alarm, nothing written to Samsung's Alarm
app; `alarm/stop` is still honoured). The patcher sends it when the user leaves the alarm fork out
(and `--ez alarms true` otherwise); without the extra the setting is unchanged (default on).

The watch link starts on that action, after boot and after an upgrade (`BridgeAlarmReceiver`,
which also re-arms the mirrored alarm), and when `am start -n $W/.MainActivity` opens the status
screen. The phone service starts on the same three triggers (`CommsBootReceiver`).

### Companion mode (watch, no notification)

When the phone is associated with the watch bridge in the watch's Companion Device Manager, the
system binds `LinkPresenceService` while the phone is connected over Bluetooth. That binding keeps
the process alive (perceptible, never frozen, also in Doze), so the link runs without
`BridgeService` and without its ongoing notification (`CompanionLink`). Without an association the
bridge falls back to `BridgeService` (a `connectedDevice` foreground service whose notification has
its own channel, "Phone connection", which the user can turn off). Needs Android 13+ on the watch.

The patcher creates the association after installing the watch bridge and removes it when it
reverts the setup. By hand, once the bridge has linked to the phone once (it records the phone's
Bluetooth address, readable only by the shell):

```sh
adb -s $WATCH shell content query --uri content://$W.link          # phone_mac=..., companion=0|1
adb -s $WATCH shell cmd companiondevice associate 0 $W <phone_mac>
adb -s $WATCH shell am broadcast -a shminterop.shmbridge.START -n $W/.BridgeAlarmReceiver -f 0x20
adb -s $WATCH shell cmd companiondevice list 0
adb -s $WATCH shell cmd companiondevice disassociate 0 $W <phone_mac>   # back to notification mode
```

Updating an associated bridge (`adb install -r`) makes the Companion Device Manager drop its user
Doze exemption a few seconds later (seen on a Galaxy Watch with Wear OS 5; an unassociated package
keeps it, associating does not remove it, and `REQUEST_COMPANION_RUN_IN_BACKGROUND` does not
prevent it). The patcher puts it back after the link step; by hand, run
`adb -s $WATCH shell cmd deviceidle whitelist +$W` again after the update.

## Communications channel `/__bridge__/` (watch)

Phone notifications, alarms and calls mirrored on the watch. The protocol is documented in the
comments of `FrameMux`, `BridgeHub` and `NotifCodec` and in
[../docs/architecture.md](../docs/architecture.md).

- `RfcommLink` (kept alive by the companion binding or by `BridgeService`) connects to the phone's
  RFCOMM server (it tries the bonded devices, reconnects with back-off) and drives `FrameMux`
  (pure Java): it parses `[int len][short pathLen][path][data]` frames in both directions.
  `/__bridge__/*` frames go to `BridgeHub` (in-process); any other frame is dropped (logged once
  per session, path length only). All RFCOMM writes are complete frames under a single lock, an
  own `/__bridge__/ping` keepalive is emitted after 2.5 s of inactivity, and an absurd `len`
  (< 2 or > 16 MB) or `pathLen` means desynchronization and closes the session.
- `BridgeNotifier` (tag `br:<id>`, channels `br_<pkg>` DEFAULT and `br_<pkg>_hi` HIGH for
  the msg/call/alarm categories), `AlarmMirror` + `AlarmRinger` + `RingActivity` (one managed
  `setAlarmClock` at `t + 5 s`, suppressed if `alarm/ring` arrived from the phone),
  `CallMirror` (`CATEGORY_CALL` notification with Answer/Reject).
- Log: tag `SHMBridgeComms`, only path/id/package/lengths.
- Host-side tests of the mux: `watch/test/run-tests.sh`.

Debugging (receiver protected by `android.permission.DUMP`: only shell and the app itself):

```bash
R="-n shminterop.shmbridge.watch/.BridgeActionReceiver --include-stopped-packages"
# inject a frame as if it came from the phone
adb shell 'am broadcast '"$R"' -a shminterop.shmbridge.DEBUG_RECV --es path /__bridge__/notif/post --es json "{\"id\":\"t1\",\"pkg\":\"com.example\",\"app\":\"Example\",\"title\":\"Test\",\"text\":\"Hello\",\"cat\":\"msg\",\"actions\":[{\"i\":0,\"label\":\"Reply\",\"reply\":true,\"choices\":[\"Ok\"]}],\"dismissible\":true}"'
# send a frame to the phone
adb shell 'am broadcast '"$R"' -a shminterop.shmbridge.DEBUG_SEND --es path /__bridge__/hello --es json "{\"v\":1,\"role\":\"watch\",\"caps\":[]}"'
adb logcat -s SHMBridgeComms
```

## Communications channel `/__bridge__/` (phone)

Phone side in `phone/src/shminterop/shmbridge/phone/`: `CommsService` (`specialUse` FGS;
link `BridgeLink`, ping `/__bridge__/ping` every 2.5 s, alarm/next and call/state),
`BridgeNotificationListener` + `NotifCodec` (notif/*, alarm/ring|stop), `CommsHub` (dispatch
and watch actions), `StatusProvider`, `CommsDebugReceiver`, `CommsBootReceiver`, and a minimal
`MainActivity` that starts the service and shows the same state as the provider.

`BridgeLink` opens an RFCOMM server on the phone with the service UUID. The watch's `RfcommLink`
connects by itself and its `FrameMux` delivers the `/__bridge__/*` frames. The phone ignores
frames outside the channel. SHM does not use this channel: it goes through the native GMS Data
Layer. A link counts as up after the first frame received from the watch.

Mirrored capabilities (`caps`), default `alarm`: with the watch paired through Galaxy
Wearable, notifications and calls arrive natively and mirroring them would duplicate.
Configuration (prefs `comms`, applied immediately):

```bash
P=shminterop.shmbridge.phone; r() { adb -s <phone-serial> "$@"; }
r shell am broadcast --user 0 -n $P/.CommsDebugReceiver -a shminterop.shmbridge.DEBUG_SEND \
  --es path config --es caps alarm            # or notif,alarm,call
r logcat -s SHMBridgeComms                    # only ids, packages, paths and lengths
# status / inject a frame as if it came from the watch / send one to the watch
r shell am broadcast --user 0 -n $P/.CommsDebugReceiver -a shminterop.shmbridge.DEBUG_SEND --es path status
r shell am broadcast --user 0 -n $P/.CommsDebugReceiver -a shminterop.shmbridge.DEBUG_SEND \
  --es path /__bridge__/notif/dismiss --es json "'{\"id\":\"<id>\"}'" --ez inject true
```

## Status provider (phone)

`<prefix>.shmbridge.phone.status` (`StatusProvider`, exported) gives a read-only, single-row view of
the bridge state for the patcher app. `query` checks the caller itself: it must hold the signature
permission `<prefix>.shmbridge.permission.STATUS` (declared by the phone bridge) or be signed with a
certificate listed in that permission's `knownCerts` (see below). There is no
`android:readPermission`, because the patcher app cannot request a permission whose name carries the
per-user prefix. Other callers get a `SecurityException`; insert/update/delete throw
`UnsupportedOperationException`. It never creates the hub nor starts the service or the link:
without a hub it reports zeros and empty strings.

| Column | Meaning |
|---|---|
| `service_running` | 0/1: `CommsService` is running |
| `link_up` | 0/1: RFCOMM session up and the watch has sent a frame (`BridgeLink.isUp()`) |
| `hello` | 0/1: a watch `/__bridge__/hello` was received on the current link |
| `peer_caps` | csv of the capabilities in that hello (`notif,alarm,call` subset), `""` if none |
| `caps` | csv of the phone's own mirrored capabilities |
| `last_rx_age_ms` | ms since the last frame received from the watch, `-1` if never |
| `version_code` | the phone bridge `versionCode` |

```bash
adb shell content query --uri content://shminterop.shmbridge.phone.status   # as the shell it needs the permission
```

**Patcher app signer.** The patcher app is signed with a different key than the bridge, so a plain
`signature` permission would not match it. Set `PATCHER_CERT_SHA256` (64 hex characters, colons
optional; `config.env`) when building: `build-bridge.sh` then declares the permission as
`signature|knownSigner` with `android:knownCerts="<digest>"` and also writes the digest to the
`<meta-data android:name="<prefix>.shmbridge.PATCHER_CERT">` inside the `<provider>` (empty in the
source manifest). `StatusProvider` reads that meta-data from its own `ProviderInfo` and lets in a caller
when `PackageManager.hasSigningCertificate(uid, digest, CERT_INPUT_SHA256)` holds (API 28+; the
`knownCerts` getter is a hidden API); the patcher app's on-device builds do the same two edits. Empty
(default) keeps plain `signature` and an empty meta-data, so only the bridge's own signature and the
STATUS permission holders get in. The meta-data check also works on API 29-30, where `knownSigner`
is ignored (the phone `minSdk` is 29).

## Alarm Fork (`shminterop.shmbridge.alarm`)

A fork of the Samsung Alarm app (AlarmWatch 1.3.05.5) packaged as
`shminterop.shmbridge.alarm`; it coexists with the system app. It replaces the Wear data
layer (GMS Wearable) with local intents to the bridge, using Samsung's native UI
(CAlarmAlertActivity) when a phone alarm rings. The fork is produced by the patch pipeline
from the official APK; this repo only contains its source classes under `alarm-fork/src` (see
`alarm-fork/README.md`).

**Priority chain** (in `AlarmMirror`): **AlarmFork** -> **AlarmRinger**. If the fork is
installed and the watch bridge has `SYSTEM_ALERT_WINDOW` (appop), alarms use Samsung's native UI.
Otherwise `AlarmRinger` rings as a fallback. The local alarm from `onFire` (link down) also uses
the fork when available.

Both packages (`watch` and `alarm`) are signed with the same local keystore (`KEYSTORE` in
`config.env`, generated if missing) and protected by the signature permission
`shminterop.shmbridge.permission.ALARM_LINK` (declared in the bridge, used by the fork).

## Known limitations

- With the watch paired through Galaxy Wearable, mirroring notifications and calls duplicates the
  native ones: that is why only `alarm` is mirrored by default.
- The `knownSigner` flag of the STATUS permission is ignored on Android 10 (API 29-30); the provider's
  own `PATCHER_CERT` check does not depend on it.

## Layout

```
shm-bridge/
├── watch/            # Wear OS companion (Java, no dependencies)
│   ├── AndroidManifest.xml
│   └── src/shminterop/shmbridge/watch/
│       ├── MainActivity.java     # minimal status screen + start the link
│       ├── CompanionLink.java, LinkPresenceService.java # companion mode (no notification)
│       ├── BridgeService.java    # FGS (connectedDevice): fallback host of the RFCOMM link
│       ├── LinkInfoProvider.java # shell-only link facts (phone_mac) for the installer
│       ├── RfcommLink.java       # RFCOMM client session with the phone + FrameMux
│       ├── FrameMux.java         # frame codec / session loop: RFCOMM <-> BridgeHub (pure Java)
│       ├── BridgeHub.java        # /__bridge__/ channel: dispatch, hello, send()
│       ├── BridgeNotifier.java   # mirrored notifications
│       ├── BridgeActionReceiver.java # actions/dismissals -> phone, DEBUG_SEND/DEBUG_RECV
│       ├── AlarmMirror.java, AlarmRinger.java, BridgeAlarmReceiver.java, RingActivity.java
│       ├── SamsungAlarm.java, SamsungAlarmListener.java # Samsung Alarm app integration
│       ├── AlarmFork.java        # integration with the Samsung Alarm app fork
│       ├── AlarmForkReceiver.java # receives dismiss/snooze from the fork
│       └── CallMirror.java, CallActivity.java, CallWatchService.java # incoming call (no audio)
│   └── test/                     # host-side FrameMux tests (run-tests.sh)
├── phone/            # phone app (Java, no dependencies)
│   ├── AndroidManifest.xml
│   └── src/shminterop/shmbridge/phone/
│       ├── MainActivity.java     # minimal status screen (same data as StatusProvider)
│       ├── CommsService.java     # FGS: link, alarm watcher, call watcher
│       ├── BridgeLink.java       # RFCOMM server + framing + keepalive
│       ├── CommsHub.java         # dispatch, state, capabilities, peer hello tracking
│       ├── StatusProvider.java   # read-only status ContentProvider (signature permission)
│       ├── BridgeNotificationListener.java, NotifCodec.java, CallNotice.java
│       └── CommsBootReceiver.java, CommsDebugReceiver.java
├── alarm-fork/       # source classes of the alarm fork (own short README)
│   └── src/shminterop/shmbridge/alarm/
│       ├── Link.java             # transport: /c_alarm/* in, /w_alarm/* -> bridge
│       ├── LinkReceiver.java     # BroadcastReceiver (stopped/muted/snoozeChanged)
│       └── LinkActivity.java     # invisible activity to reach the foreground (started)
└── scripts/
    ├── build-bridge.sh           # Gradle-free build (javac+d8+aapt2+apksigner)
    └── gen-call-notice-strings.py # generates the call_notice / alarm_label / bridge_strings resources
```
