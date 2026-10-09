# Architecture and protocol

## Components

```
watch: patched SHM   ⇄ GMS Data Layer (stock pairing) ⇄ phone: patched SHM
watch: bridge (BtRelayClient/FrameMux) ⇄ direct RFCOMM ⇄ phone: bridge (BridgeLink, transport `bt`)
watch: Alarm fork ⇄ local intents ⇄ watch bridge
```

Everything runs between the phone and the watch; no PC is involved.

- **Watch SHM** (`com.samsung.android.shealthmonitors`): the official app, renamed so it can live
  next to the system one, with the vendor/regional checks disabled and ECG measured through the
  Health Tracking client (`watch-mod/src/shminterop/ecg/EcgAdapter.java`), because a re-signed app has
  no access to the proprietary sensor path. See [patches.md](patches.md) and
  [health-sdk.md](health-sdk.md) (Health Tracking client).
- **Phone SHM**: the official app with the same package and the same signing certificate as the
  watch one (GMS only delivers messages between matching "AppKeys": package + certificate).
- **Bridge** (`shm-bridge/`): our own apps that mirror notifications, alarms and call state. On the
  watch, alarms ring with the native UI thanks to the fork of the Alarm app
  (`shm-bridge/alarm-fork/`), driven by local intents from the bridge.

## Framing

Frames: `[int len][short pathLen][UTF-8 path][data]`, big-endian,
`len = 2 + pathLen + data.length`. Heartbeat: empty `/__bridge__/ping` frame.

The watch `FrameMux` routes by prefix: `/__bridge__/*` to the bridge, `/__gw__/*` to the GW channel
(no counterpart, see below). Forwarding every other frame to the SHM (`:8773`) belonged to the PC
variant and is inactive: the SHM uses GMS.

## Bridge transport

The phone `BridgeLink` has pluggable transports:
- `bt` (default): RFCOMM server on the relay UUID; the watch `BtRelayClient` probes the bonded
  devices and connects. 15 s no-traffic watchdog (RFCOMM has no read timeout). No PC needed.
- `tcp`: `127.0.0.1:8775`, towards a PC gateway that is **not included** (dropped variant); the
  code is still there but unused.

The configuration is changed through the bridge debug receiver (`path=config`); see
`shm-bridge/README.md`.

## `/__bridge__/` channel — messages (UTF-8 JSON, ≤ 64 KB per frame)

When the link opens, each side sends `/__bridge__/hello`
`{"v":1,"role":"watch|phone","caps":[...]}`; idempotent state (`notif/sync`, `alarm/next`) is
resent in full after every `hello`. With GMS active, Galaxy Wearable already mirrors notifications
and calls natively, so by default the phone only announces `caps=["alarm"]`.

### Notifications
- Phone → watch: `notif/post` (stable id = 16 hex chars of the SHA-1 of the StatusBarNotification
  key; a `post` with the same id updates), `notif/remove`, `notif/sync` (authoritative id list).
- Watch → phone: `notif/dismiss`, `notif/action`, `notif/reply`, `notif/open` (the last one needs
  the BAL exemption on the phone: appop `SYSTEM_ALERT_WINDOW allow`).

### Alarms
- Phone → watch: `alarm/next` `{"t":<epoch ms>|null}` (the watch schedules its own alarm at
  `t + 5 s` that rings even if the link is down), `alarm/ring`, `alarm/stop`.
- Watch → phone: `alarm/snooze`, `alarm/dismiss` (the phone tries the Clock app notification
  action, then `ACTION_SNOOZE_ALARM`/`ACTION_DISMISS_ALARM`, and finally `cancelNotification`).
- UI priority on the watch: Alarm fork → `AlarmRinger` (our own fallback).
- Fork ⇄ bridge (local intents, protected by the signature permission
  `<prefix>.shmbridge.permission.ALARM_LINK`): `/c_alarm/started` (startActivity to
  `LinkActivity`), `/c_alarm/stopped|muted|snoozeChanged` (broadcast to `LinkReceiver`), and
  `/w_alarm/dismiss|snooze` back to the bridge.

### Calls
- Phone → watch `call/state` `{"s":"ringing|offhook|idle"}`; watch → phone `call/answer`,
  `call/reject`. With HFP connected between both devices the system handles audio and UI; our own
  screen is the fallback without HFP.

### Privacy
Notification bodies, names and numbers are never logged: only id, package, path and lengths.

## `/__gw__/` channel and DataClient

The watch bridge (`GwHub`/`GwData`) also implements a `/__gw__/` channel with a mirrored
DataClient (`/__d__/put|del|all|all-r`), meant to let Galaxy Wearable talk to the watch without
GMS. **The gateway and the phone side of that channel (patched heartplugin) are not included**: it
never worked (GMS node/capability queries were still not redirected), and the verified setup uses
stock Galaxy Wearable paired through GMS. The code stays in the watch bridge because it is part of
the verified build and is harmless without a counterpart.

## Device-free tests

- `shm-bridge/watch/test/run-tests.sh` — watch mux.
