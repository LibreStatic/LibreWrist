# Installation (runbook)

Everything below assumes devices you own, with ADB debugging enabled (Wi-Fi debugging on the
watch). `$P` = phone ADB serial, `$W` = watch ADB serial, `<prefix>` = the `PKG_PREFIX` used for the
build.

## 0. Build

```bash
scripts/fetch_deps.sh
# put the official APKs in apks/ with the names and hashes listed in apks/SHA256SUMS
PKG_PREFIX=com.example scripts/build_all.sh
```

Outputs in `dist/`: phone SHM, watch SHM, phone bridge, watch bridge and the Alarm fork. Both SHM
apps are signed with the same key (`KEYSTORE` in `config.env`); GMS requires that to deliver
messages between them.

## 1. Pair with the stock flow

The SHM needs the GMS Data Layer between phone and watch, which is created by the **stock** Galaxy
Wearable pairing or transfer (unmodified; Galaxy Wearable and its watch plugin are used as is).

Known gotchas when transferring a previously used watch:

- **The wizard stalls at `GoogleAssistantStep`**: the phone's GMS keeps an old Wear configuration
  with the same NodeId as the watch. Reinstalling the companion does not remove it. What worked:
  `adb -s $P shell pm clear com.google.android.gms` (**destructive**: wipes every Wear
  configuration and the Play services state on the phone; decide beforehand). The Bluetooth bond
  survives (the BT stack keeps it, not GMS).
- **AUTH_FAILURE on the first retry** after the phone forgets the bond: expected; the second attempt
  pairs.
- **Do not accept `ERROR_RESET` or `ERROR_UNPAIR`** from the old wizard if they show up: its OK
  unbonds only on the phone side and leaves the watch in an intermediate state.
- **Do not press Cancel while the watch is in the final step** (`phone_switching_status=7`): let
  the transport connect on its own.
- **Watch faces/Tiles stuck loading** after the transfer: the watch plugin started with stale
  state. Force-stop Galaxy Wearable and its plugin (`am force-stop com.samsung.android.heartplugin`)
  and reopen.

Check: `adb -s $P shell dumpsys activity service com.google.android.gms/.wearable.service.WearableService`
must show the watch configuration with `IsConnected=true`.

## 2. Install order

1. **Phone SHM**: `adb -s $P install -r dist/<shm-phone>.apk`. Complete the ECG/BP onboarding on
   the phone.
2. **Watch SHM**: `adb -s $W install -r dist/<shm-watch>.apk`, then its grants from the catalogue
   (`appops set com.samsung.android.shealthmonitors SCHEDULE_EXACT_ALARM allow`).
   It installs next to the system SHM (different package); the system one keeps showing the
   regional error and is not used.
3. **Watch bridge** and **Alarm fork** on the watch; **phone bridge** on the phone. Permissions,
   appops, notification listeners and exemptions to grant: the `grants` section of
   `catalogue/catalogue.json` (per output; format in [patches.md](patches.md)), summarized in
   [shm-bridge/README.md](../shm-bridge/README.md). The `installOrder` section of the catalogue
   gives the order above; `stockAppsToDisable` lists the optional stock apps to disable.

To upgrade without losing data: same key and same `PKG_PREFIX` as the previous install.

## 3. Health Platform developer mode (watch)

The watch SHM measures ECG through the Health Tracking SDK. Without Health Platform developer
mode, the service lets you create the tracker but **delivers no data** (empty batches with the
finger on the sensor).

Watch → Settings → Apps → Health Platform → tap the "Health Platform" title several times until
**`[Dev mode]`** appears under it. It can reset after an unclean reboot: if ECG stops receiving
data, check this first.

## 4. Verify

- Phone SHM: the BP card does not stay on "Connecting"; the watch node shows as connected.
- ECG on the watch: take a measurement and confirm the result shows up on the phone.
- Bridge: the watch logs the RFCOMM connection to the phone and the `hello` exchange; a phone
  alarm rings on the watch with the fork UI, and dismiss/snooze on the watch acts on the phone
  alarm.

**Another alarm app with its own Wear companion** may show a second UI on top of the fork's when an
alarm rings. If that is a problem, disable it on the watch only:
`adb -s $W shell pm disable-user --user 0 <package>` (revert with `pm enable <package>`).
