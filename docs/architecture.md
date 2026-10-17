# Architecture and protocol

## Components

```
watch: patched SHM   ⇄ GMS Data Layer (stock pairing) ⇄ phone: patched SHM
watch: bridge (RfcommLink/FrameMux) ⇄ direct RFCOMM ⇄ phone: bridge (BridgeLink)
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

Both ends only handle `/__bridge__/*` frames; any other frame is dropped (the SHM does not use this
link: it goes through the GMS Data Layer). A framing violation (`len` < 2 or > 16 MB, `pathLen` >
`len - 2`) closes the session.

## Bridge transport

Direct RFCOMM, no PC: the phone `BridgeLink` is the RFCOMM server (UUID
`8b6d5c1e-3a24-4c0d-9f1b-6a7e2d5c4b3b`); the watch `RfcommLink` probes the bonded devices and
connects, and its `FrameMux` carries the session. 15 s no-traffic watchdog on the phone (RFCOMM has
no read timeout); both ends send their own `/__bridge__/ping` keepalive.

What keeps the watch side alive: when the phone is associated with the watch bridge in the watch's
Companion Device Manager (the patcher does it over adb after installing the bridge, with the phone
address the bridge records when its first session opens and exposes to the shell through
`<prefix>.shmbridge.watch.link`), the system binds the bridge's `CompanionDeviceService` while the
phone is connected, and the process stays perceptible without a notification. Without an
association the link runs in a `connectedDevice` foreground service with an ongoing notification.
Updating an associated bridge makes the Companion Device Manager drop its Doze exemption, so the
patcher applies it again after the link step.
The patcher's revert removes the association before uninstalling the bridge.

The only configuration is the mirrored capabilities (`caps`), changed through the bridge debug
receiver (`path=config`); see `shm-bridge/README.md`.

## Status provider (phone)

The phone bridge exposes a read-only `ContentProvider`, `<prefix>.shmbridge.phone.status`, open to
callers that hold the signature permission `<prefix>.shmbridge.permission.STATUS` or are signed with
the certificate whose SHA-256 is in the provider's `<prefix>.shmbridge.PATCHER_CERT` meta-data (the
patcher app's, set with `PATCHER_CERT_SHA256` by `build-bridge.sh` and by the on-device engine); the
provider checks this in code with `hasSigningCertificate`. One row: service running, link up, watch
`hello` received, peer and own capabilities, ms since the last received frame and the bridge
`versionCode`. It never creates the hub or starts the link. Columns and details:
[shm-bridge/README.md](../shm-bridge/README.md).

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
  `t + 5 s` that rings even if the link is down; `t` is null unless the next alarm clock belongs
  to the Clock app, since other apps' `setAlarmClock()` never post a ring), `alarm/ring`,
  `alarm/stop`.
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

## Acquiring input APKs (patcher app)

The Acquire step verifies every input against the catalogue sha256 (the only trust anchor) whether it
comes from a picked file or a download. Downloads come from the guided browser: one WebView
(`ApkMirrorWebView`), one coach UI (`GuidedBrowserHost`) and one `RedirectChain`, all parameterized by a
`SiteGuide` (`data/SiteGuide.kt`, pure JVM): start URL, navigation allow-list, coach step, download
verdict, which hosts may serve the file, and `normalizeDownloadUrl`. `InputSource` (`ApkMirror`, `Yyb`)
maps a navigation argument (`Route.guidedBrowser(inputId, source)`) to its guide, and `InputApp.sources()`
lists the sources an input is on, primary first (the first one is the main button on the card, the rest
go in the "more ways" menu).

- `ApkMirrorGuide`: https `apkmirror.com` pages, 3-step coach, redirect hop to its CDN.
- `QqGuide` (YingYongBao, opt-in per input with `obtain.yyb`): start page
  `https://a.app.qq.com/o/simple.jsp?pkgname=<package>`, pages on `a.app.qq.com` / `sj.qq.com` only.
  The page links straight to `imtt[N].dd.qq.com/.../<32 hex>.apk` over http; that link is upgraded to https
  (the CDN serves the same file) and accepted only for a whole-file name, never `_offset_` partial
  mirrors or `microdown.myapp.com`. No redirect hops, no cookies. 2-step coach; no version parsing, the
  sha256 decides.

Both end in `AcquireViewModel.downloadFrom` -> `InputStore.download` over `ApkDownloader` (https only).

## Device-free tests

- `shm-bridge/watch/test/run-tests.sh` — watch mux.
