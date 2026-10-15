# LibreWrist

> LibreWrist was formerly called OpenSHM (some identifiers, such as the `io.openshm` package, keep that name).

Personal interoperability between a Galaxy Watch and a **non-Samsung** Android phone. With it, the
Samsung Health Monitor (SHM) app can measure ECG, blood pressure and irregular heart rhythm
notifications (IHRN) on the watch and sync them with the phone. It also includes our own bridge for
alarms, notifications and calls.

This repository **contains no vendor code and no APKs**. It holds:

- the patches, written as patterns anchored to the official APKs;
- our own code;
- the tools that rebuild everything from the official APKs, which each user obtains on their own.

The resulting APKs are signed with the user's own key. They are for personal use and are not
redistributed.

**Unlike Dante's version, this compatibility layer DOES work on modern One UI Watch releases. It is
verified on One UI 8 Watch (Wear OS 6, Android 16 / API 36).** Older One UI Watch releases have not
been tested.

| Tested setup | Version |
|---|---|
| Watch | Galaxy Watch5 Pro (SM-R920), One UI 8 Watch, firmware `R920XXS2DZG3` (security patch 2026-08-05) |
| Health Platform on the watch | 1.7.00.05 |
| Samsung Health Monitor | 1.5.1.022 (watch), 1.4.6.001 (phone) |
| Samsung Health (built as Samsung Health+, optional) | 7.00.6.011 (phone) |
| Phone | Motorola razr fold (non-Samsung) |

The patcher recognizes the watch plugin of every Galaxy Watch family, and includes a fix for the
watch SHM closing on open on the Galaxy Watch8. The patcher UI is available in English and 17 other languages, and
the bridge texts into the same 88 locales as the call notice.

## Quick start video

Prefer watching to reading? This 90-second video shows the whole setup, step by step. The screens
use sample data. Click it to open the full-quality MP4.

[![LibreWrist quick start](docs/media/quick-start.gif)](docs/media/quick-start.mp4)

## Getting started: the patcher app

The easiest way is **LibreWrist** (`patcher-app/`). This Android app does the whole job on the
phone itself; no PC is needed. Download the latest APK from the
[Releases](https://github.com/LibreStatic/LibreWrist/releases) page and install it on the phone.

The app guides you through these steps:

1. **Pairing**: the stock Galaxy Wearable pairing, then a Wireless debugging (ADB) pairing with the
   watch.
2. **Inputs**: getting the official APKs (from APKMirror, a file, or pulled from your own watch or
   phone). Each one is checked against the hashes in the catalogue.
3. **Build**: patching, signing and verifying up to six APKs (Health Monitor and Samsung Health+ are optional) with a key that is created on, and stays on,
   your phone:
   - SHM for the phone and for the watch;
   - the phone and watch bridges;
   - the Alarm app fork;
   - **Samsung Health+**, optional and on by default (see below).
4. **Install on the phone**: installing the apps and granting their permissions.
5. **Install on the watch**: installing the apps over Wi-Fi, granting their permissions, turning on
   Health Platform developer mode, and turning off the stock watch SHM. Nothing is uninstalled.
6. **Self-test**: automatic checks, followed by a few questions about what you see on the devices.

The patches come from a **signed catalogue** (`catalogue/`), and the app can update it from this
repository. The catalogue is data only: the app checks its ECDSA signature and never runs code from
it.

Export the signing key from the app after the first build (the export and import forms open as
dialogs; the app also offers an automatic backup right after the first build). Upgrading the installed apps without
losing data requires the **same key and the same package prefix**.

## Walkthrough

This section shows the patcher step by step, from the first screen to the end. The screenshots come
from an emulator with **made-up data**: the addresses, versions, sizes and package prefix are
examples, so your screens will show your own values.

### 1. Welcome

Open LibreWrist. **What will change on my devices?** lists every app that will be installed on
the phone and on the watch, and what each one is allowed to do. Tap **Get started**.

<p>
  <img src="docs/images/walkthrough/01-welcome.png" width="260" alt="Welcome screen">
  <img src="docs/images/walkthrough/02-what-will-change.png" width="260" alt="What will change sheet">
</p>

### 2. Pick your watch

Choose the Wear OS version of your watch. If the watch is already reachable, the patcher checks the
version for you. This decides which official apps you need: Health Monitor, plus the Alarm app on
Wear OS 5 or newer.

<img src="docs/images/walkthrough/03-watch-profile.png" width="260" alt="Watch profile">

### 3. Pair the watch with Galaxy Wearable

Pair the watch the usual way, with Galaxy Wearable and the watch plugin from the Play Store. All
three checks must be ticked before you continue. If pairing fails, **Trouble pairing?** has the
known fixes.

<img src="docs/images/walkthrough/04-wearable-pairing.png" width="260" alt="Galaxy Wearable pairing">

### 4. Connect to the watch over Wi-Fi

The patcher installs apps on the watch over Wireless debugging. Put the phone and the watch on the
same Wi-Fi network, then follow the guide on screen:

1. On the watch, open **Settings → About watch → Software information**.
2. Tap **Software version** until developer mode turns on.
3. In **Developer options**, turn on **Wireless debugging**, allow it, then choose **Pair new
   device**.

The patcher finds the watch on the network by itself. Type the 6-digit code shown on the watch and
tap **Pair**.

<p>
  <img src="docs/images/walkthrough/05-watch-adb-pairing.png" width="260" alt="Connect to the watch">
  <img src="docs/images/walkthrough/06-wireless-debugging-guide.png" width="260" alt="Wireless debugging guide">
  <img src="docs/images/walkthrough/08-pairing-code.png" width="260" alt="Pairing code entered">
</p>

### 5. Bring the official apps

You get the official Samsung APKs yourself; the patcher never ships them. For each app, either:

- tap **Open on APKMirror**, and a guided browser shows which variant to pick (APK, not bundle,
  `nodpi`) and catches the download;
- or, from the menu next to that button, pick a file you already have, or pull the app from your
  own watch or phone.

Every file is checked against the catalogue's checksum. Continue unlocks once all of them match.

<p>
  <img src="docs/images/walkthrough/09-official-apps.png" width="260" alt="Official apps">
  <img src="docs/images/walkthrough/10-guided-download.png" width="260" alt="Guided download">
</p>

### 6. Review the patches and build

The required patches are locked; the others can be switched off. The defaults are recommended. Tap
**Build**. The build runs in the background with a notification, so you can leave the app. When it
finishes, all five apps are patched and signed with your own key. The app then offers to back up
that key: **do it**, because you will need it for future updates.

Later builds only patch again the apps whose inputs or patches changed. The others are reused, and
**Built and signed** marks them as *Unchanged*.

<p>
  <img src="docs/images/walkthrough/11-patches.png" width="260" alt="Patches">
  <img src="docs/images/walkthrough/12-building.png" width="260" alt="Building">
  <img src="docs/images/walkthrough/13-built.png" width="260" alt="Built and signed">
</p>

### 7. Install on the phone

Tap **Start installing** and confirm each Android install prompt. The patcher then checks the
permissions each app needs. Any missing one has an **Open settings** button, where you switch it on
yourself.

Open the patched Samsung Health Monitor once and finish its setup.

<p>
  <img src="docs/images/walkthrough/14-install-phone.png" width="260" alt="Install on the phone">
  <img src="docs/images/walkthrough/15-phone-access.png" width="260" alt="Phone permissions">
</p>

#### The easy way: let the patcher grant everything (Wireless debugging on this phone)

Some permissions are hard to find in Settings, and on Android 13 or newer some of them are blocked
as *restricted settings*. The patcher can switch them all on for you. To do that, it connects to
**this same phone** through Wireless debugging, a built-in Android developer feature.

This sounds technical, but it is a one-time setup of a few taps. Read the whole list once before
you start, because you will move between the patcher, Settings and the notification panel.

> You need Wi-Fi for this: any network works, even without internet. Mobile data is not enough.
> Nothing here changes your data, and you can turn Wireless debugging and Developer options off
> again when you are done.

**Step A: tap the button in the patcher.** Scroll down to **Grant everything at once** and tap
**Grant everything with Wireless debugging**. The card shows the steps that follow. The patcher
keeps waiting in the background and opens Developer options for you. If they are not on yet, do
step B first.

<img src="docs/images/walkthrough/14a-phone-adb-offer.png" width="260" alt="Grant everything with Wireless debugging">

**Step B: turn on Developer options** (only the first time).

1. Open **Settings → About phone**. On some phones it is under *Settings → System → About phone*.
2. Scroll to the bottom and tap **Build number** seven times in a row. On some phones it is inside
   *Software information*.
3. After a few taps Android counts down (*"You are now 1 step away from being a developer"*).
   If you have a screen lock, it asks for your PIN at the end. Then it says *"You are now a
   developer!"*.

<p>
  <img src="docs/images/walkthrough/14b-phone-build-number.png" width="260" alt="Build number in About phone">
  <img src="docs/images/walkthrough/14c-phone-developer-toast.png" width="260" alt="Developer countdown">
</p>

**Step C: turn on Wireless debugging.**

1. Go to **Settings → System → Developer options**. The patcher normally opens it for you, scrolled
   to the right place.
2. Find **Wireless debugging** in the *Debugging* section and tap its **name**, not only the
   switch, to open its page.
3. Turn on **Use wireless debugging**. Android asks *"Allow wireless debugging on this network?"*:
   tap **Allow**.

<p>
  <img src="docs/images/walkthrough/14d-phone-developer-options.png" width="260" alt="Developer options">
  <img src="docs/images/walkthrough/14e-phone-allow-network.png" width="260" alt="Allow wireless debugging on this network">
</p>

**Step D: get a pairing code.** On the Wireless debugging page, tap **Pair device with pairing
code**. Android shows a six-digit **Wi-Fi pairing code**. Leave this window open: closing it cancels
the code.

<p>
  <img src="docs/images/walkthrough/14f-phone-wireless-debugging.png" width="260" alt="Wireless debugging page">
  <img src="docs/images/walkthrough/14g-phone-pairing-code.png" width="260" alt="Wi-Fi pairing code">
</p>

**Step E: give the code to the patcher.** Without closing the code window:

1. Swipe down from the top of the screen to open the notification panel.
2. Find the LibreWrist notification **Enter the pairing code** and tap **Enter code**.
3. Type the six digits shown on the code window and tap the send arrow.

You can also go back to the patcher and type the code there, but the notification is easier,
because it keeps the code window on screen.

<p>
  <img src="docs/images/walkthrough/14h-phone-notification.png" width="260" alt="Enter the pairing code notification">
  <img src="docs/images/walkthrough/14i-phone-notification-code.png" width="260" alt="Typing the code in the notification">
</p>

**Step F: done.** The code window closes by itself. The patcher connects and switches on every
missing permission. Go back to the patcher: it shows **All set** and the permission list turns
green. *LibreWrist* now appears under **Paired devices** in Wireless debugging. The pairing is
remembered, so next time the patcher reconnects without a new code, as long as Wireless debugging is
on.

<img src="docs/images/walkthrough/14j-phone-adb-done.png" width="260" alt="Pairing done">

**If something goes wrong:**

- *The notification never asks for a code*: check that Wireless debugging is on and that the phone
  is on Wi-Fi. Also check that notifications are allowed for LibreWrist.
- *"Wrong code"*: codes expire when the window closes. Tap **Pair device with pairing code**
  again and use the new code.
- *Wireless debugging turns itself off*: Android turns it off when you change Wi-Fi networks. Turn
  it on again; you will not need a new code.

### 8. Install on the watch

The watch apps are installed over Wi-Fi, in order: Health Monitor, the bridge, then phone alarms.
Their permissions are granted automatically. Then:

- **Health Platform developer mode**: tap **Turn it on for me** and keep the watch unlocked and
  nearby; or do it by hand, as shown on screen. Without it, the watch will not take ECG
  measurements with the patched app.
- **Earlier copies**: watch bridge and alarm copies left by an earlier package prefix are removed
  first, since they would compete with the current bridge for the link. The phone install step does
  the same with the phone bridge (Android asks you to confirm). Samsung Health+ is never touched.
- **Phone bridge link**: the bridge is registered as a companion app of the phone on the watch, so
  it stays connected without an ongoing notification.
- **Turn off the stock app**: the stock Health Monitor is disabled, not uninstalled, so it does not
  compete with the patched one for the sensor. Apps that are already off are marked as done.

<p>
  <img src="docs/images/walkthrough/16-install-watch.png" width="260" alt="Install on the watch">
  <img src="docs/images/walkthrough/17-developer-mode.png" width="260" alt="Health Platform developer mode">
  <img src="docs/images/walkthrough/18-disable-stock.png" width="260" alt="Turn off the stock app">
</p>

### 9. Self-test

The patcher runs these checks:

- it confirms what is installed on both devices, with which signature;
- it confirms that permissions are granted;
- it checks that no bridge copy from an earlier package prefix is left on either device;
- it checks that the bridge link between the phone and the watch is up.

It then asks you a few questions about what you see on the devices. When every check passes, you
are done. **Share report** produces a report with versions and results only, which is useful if
something fails.

<p>
  <img src="docs/images/walkthrough/19-self-test.png" width="260" alt="Self-test">
  <img src="docs/images/walkthrough/20-all-set.png" width="260" alt="All set">
</p>

### 10. Home

From then on, the app opens on **Home**. It shows:

- the state of the last test, with **Test again**;
- the apps you built;
- an update card when a newer catalogue supports newer official apps.

Each app row has two buttons:

- **Rebuild** (*Change patches and rebuild*) opens the patches focused on that app, then runs a
  normal build. Apps that did not change are reused, and the install steps only cover the apps that
  were built again.
- **Restore** removes only that app. For the watch Health Monitor, it also turns the stock app back
  on and resets the watch settings the patcher changed.

**Restore watch** in the Home menu undoes only the watch side. A restored app leaves Home until you
install it again.

<img src="docs/images/walkthrough/21-home.png" width="260" alt="Home">

#### Updates of the patcher itself

Release builds of the patcher look for a newer official release on GitHub when they start and about
every 12 hours in the background. When there is one, the app shows an update dialog (and a
notification if the background check found it first); tapping **Update** downloads the APK, checks
its SHA-256 against the release's `SHA256SUMS`, checks that it is this app signed with the same key,
and then asks Android to install it. The app closes while it updates; open it again afterwards. Only
official releases signed with the same key can update the app.

GitHub is asked at most once an hour; in between, the app offers the last build it found each time
it opens. **Later** shows it again on the next opening; ticking **Don't show this version again**
hides that build (dialog and notification) until a newer one comes out. **Check now** always shows it.

To turn this off, open **Settings** (the gear on the first screen, or the Home menu) and switch off
**Check for updates automatically**; that stops both the startup check and the background one.
**Check now** in the same screen still works. Local builds without an update repository never check.

#### Device logs

When a paired watch answers over ADB, Welcome and All set show a **View device logs** link. It
streams logcat from the watch and from the phone (through the phone's own Wireless debugging),
filtered to the patched apps and the system lines that name them. A foreground service keeps
capturing in the background, and **Export** writes both logs into one `.txt` file, handy for bug
reports. Each tab shows whether its device is connected; tap the broken link icon for help, and it
reconnects by itself once the device shows up on the network again.

#### Undo all changes

Welcome, All set and the Home menu offer **Undo all changes**.
It looks for every LibreWrist app on both devices (under the current package prefix and any earlier one
it finds) and only acts on what is actually there:

- on the watch, it removes the bridge's companion association, uninstalls our apps, turns the stock
  Health Monitor back on if it was disabled, and restores `doze_always_on` to 1 if it reads 0;
- on the phone, it uninstalls our apps (Android asks you to confirm each one).

System apps are never touched, and one failing step does not stop the rest. An unreachable watch can
be retried or skipped; the result is then marked as partial. After a complete revert the package
prefix is unlocked and the stored builds are cleared. The last step offers to uninstall LibreWrist
Patcher itself.

### Optional apps

The catalogue declares the apps (`apps` in `catalogue/catalogue.json`, with their texts and icons), so the list
below follows it. On the **Official apps** step each optional app has its own switch, all on by default (opt-out):

- **Samsung Health Monitor** (phone and watch together, one switch);
- **Samsung Health+** (below).

Always included, with no switch: our two bridge apps (phone and watch) and, on Wear OS 5 and newer, the
phone-alarm fork. With Health Monitor off, the Health Platform developer-mode and stock-app steps are
passed over, since they only matter for it.

#### Samsung Health+

The Samsung Health switch adds **Samsung Health** as an extra input. The patcher
builds it as **Samsung Health+**: renamed to `<prefix>.shealth` and signed with your key, so it
installs **next to** the stock Samsung Health from the Play Store instead of replacing it.

- The watch only trusts the Samsung-signed phone app, so the **stock app stays** and keeps syncing
  the watch and uploading to the Samsung cloud.
- Samsung Health+ signs in to the same Samsung account, downloads the history, and unlocks the
  phone-side features hidden on non-Samsung phones (for example the Energy Score tile).
- Its patches appear on the patches step. Optional ones add a launcher badge (also available for
  SHM) and a local-only mode, which is off by default.

Turn the switch off if you do not want it. On a PC, `scripts/build_shealth.sh` builds the same target.

### What to expect when you are done

- **On the watch**, the patched Samsung Health Monitor replaces the stock one in the app list. It
  offers ECG and blood pressure, including the BP calibration. Irregular heart rhythm notifications
  can be turned on from the phone app.
- **On the phone**, Samsung Health Monitor finds the watch as a compatible device. Readings taken
  on the watch appear there and can be exported as PDF.
- **The bridge** sends phone notifications, incoming calls (answer or decline from the watch) and
  phone alarms to the watch over Bluetooth, with no Samsung phone involved. After installing it, the
  patcher associates the phone with the watch bridge in the watch's Companion Device Manager, so the
  link stays alive (also in Doze and power saving) **without a permanent notification**. Without the
  association it falls back to a foreground service with a "Phone connection" notification, which
  has its own channel so you can hide it without muting alarms or calls.
- **Phone alarms** ringing on the watch can be snoozed or dismissed with a double pinch, if the
  quick action is on in the watch's alarm settings.
- Nothing stock was uninstalled. Galaxy Wearable keeps managing the watch as before.

### How to check that it works

1. **ECG**:
   - On the watch, open Samsung Health Monitor, choose **ECG**, rest your arm on a table and hold a
     fingertip on the top button for 30 seconds.
   - The watch shows a result (for example *Sinus rhythm*). Within a minute, the same reading shows
     up in Samsung Health Monitor on the phone.
2. **Blood pressure**:
   - In the phone app, start a calibration with a cuff monitor, taking three readings. The watch
     completes the calibration.
   - After that, measure from the watch. The result syncs to the phone the same way as ECG.
3. **Bridge**:
   - Send yourself a message, or call the phone: the notification or the call shows on the watch.
   - Set an alarm on the phone a couple of minutes ahead: it rings on the watch. With the watch on
     your wrist, a double pinch snoozes or dismisses it.
4. **If something does not arrive**, run **Test again** from Home and share the report. Most
   problems are one of these:
   - Health Platform developer mode turned off on the watch;
   - a missing permission;
   - the watch disconnected from Galaxy Wearable.

## Known issues

These come from user reports. Each one links to its GitHub issue, where progress is tracked.

:construction: fix in progress · :hourglass_flowing_sand: fix released, waiting for user confirmation

- :construction: **Irregular heart rhythm notifications fire too often, and they cannot be cleared from the
  watch** ([#5](https://github.com/LibreStatic/LibreWrist/issues/5)). The vendor software denies
  the feature access to the sensor, the sensor returns negative values, and Samsung Health Monitor
  treats each failed reading as an alert. These are false alarms, not a health finding. A custom
  sensor translation layer is in progress. Until it ships, turn irregular heart rhythm
  notifications off in the phone app.
- :construction: **Sleep apnea detection is not available yet**
  ([#3](https://github.com/LibreStatic/LibreWrist/issues/3)). It needs calibration patterns baked
  into Samsung's firmware, which are being extracted and tested against a custom driver. The same
  applies to a persistent SpO2 unlock. The irregular heartbeat toggle from that report works since
  0.4.0, and feature level 1.3 is the highest one available, so there is nothing newer to upgrade
  to.
- :hourglass_flowing_sand: **Blood pressure calibration stuck at 0% with the PPG LEDs off**
  ([#2](https://github.com/LibreStatic/LibreWrist/issues/2)), reported on a Galaxy Watch4 Classic
  (SM-R890) with One UI Watch 8 and patcher 0.3.7. The bug is confirmed and a fix shipped in
  0.3.8: update the patcher and run the patches again. We are still waiting for an affected user to
  report that calibration now completes on their watch.

## How it works

1. The watch is paired with the phone through the stock Galaxy Wearable flow, which creates the GMS
   Data Layer between them.
2. Both patched SHM apps share one package (`com.samsung.android.shealthmonitors`) and one signing
   certificate. GMS needs both to deliver messages between the two apps.
3. On the watch, our ECG adapter talks to the Health Tracking service through a clean-room client
   ([protocol](docs/health-tracking-protocol.md)). The service only accepts it while Health
   Platform developer mode is on.
4. The bridge talks to the watch directly over RFCOMM, phone ⇄ watch.

Everything runs on the two devices. Details are in [docs/architecture.md](docs/architecture.md).

## Repository layout

| Path | What it is |
|---|---|
| `patcher-app/` | The LibreWrist Android app, a Gradle build with three modules: `:engine` (pure JVM patch engine), `:adb` (ADB client) and `:app` (Compose UI). Third-party licences are in [patcher-app/THIRD_PARTY.md](patcher-app/THIRD_PARTY.md). |
| `catalogue/` | The signed patch catalogue. `catalogue.json` describes the inputs, profiles and patterns as data; `catalogue.json.sig` and `catalogue.pub` are its signature and public key. Details in [docs/patches.md](docs/patches.md). |
| `superpatcher/`, `scripts/superpatcher.py` | The Python patch engine. It interprets the same catalogue as the app. |
| `watch-mod/` | Our own code injected into the watch SHM: the ECG and BP adapters, the Health Tracking client and the vendor overrides. |
| `shm-bridge/` | Our own bridge apps (phone ⇄ watch over direct RFCOMM) and the fork of the watch Alarm app. |
| `scripts/` | The PC build (`build_all.sh`, `build_shm_*.sh`, `build_shealth.sh`, `fetch_deps.sh`), catalogue tooling (`catalogue.py`), and shared helpers in `scripts/lib/`. |
| `apks/` | Where the official input APKs go for PC builds. They are not versioned; see [apks/README.md](apks/README.md) and `apks/inputs.json`. |
| `docs/` | [Architecture](docs/architecture.md), [installation runbook](docs/installation.md), [patches](docs/patches.md), [Health Tracking client](docs/health-sdk.md) and [protocol](docs/health-tracking-protocol.md), plus research notes on Samsung Health ([phone survey](docs/samsung-health-survey.md), [watch survey](docs/shealth-watch-survey.md)). |

## Building on a PC

The original shell pipeline is still available, and it produces the same five APKs:

```bash
scripts/fetch_deps.sh                 # apktool into third_party/
# copy the official APKs into apks/ with the names listed in apks/SHA256SUMS
PKG_PREFIX=com.example scripts/build_all.sh
```

All configuration lives in [`config.env`](config.env) and can be overridden through environment
variables: package prefix, SDK, keystore and directories. Our source code uses the package
placeholder `shminterop`, and builds rewrite it to the chosen `PKG_PREFIX`. Manual installation with
ADB, pairing, and known gotchas are covered in [docs/installation.md](docs/installation.md).

### Building the patcher app

The launcher `patcher-app/gradlew` is a jar-free script, since the repository carries no jars. Run
it with a JDK 17 and the Android SDK:

```bash
JAVA_HOME=/path/to/jdk17 patcher-app/gradlew :engine:test :app:testDebugUnitTest :app:assembleDebug
```

Builds are signed with `patcher.p12` and `patcher.pass` from `OPENSHM_KEYS_DIR` (key alias
`PATCHER_KEY_ALIAS`, default `patcher`). Without them, builds fall back to the SDK debug key. The
published releases are built by `.github/workflows/patcher-release.yml` and signed with the
LibreStatic release key.

## Tests

```bash
python3 -m unittest discover -s superpatcher -p 'test_*.py'
python3 -m unittest discover -s scripts -p 'test_*.py'
shm-bridge/watch/test/run-tests.sh
watch-mod/test/run-tests.sh
JAVA_HOME=/path/to/jdk17 patcher-app/gradlew :engine:test :app:testDebugUnitTest
```

## Scope

Dropped on purpose:

- the PC relay or gateway, replaced by native GMS and direct RFCOMM;
- the Galaxy Wearable proxy, which never worked.

Never needed:

- a factory reset;
- a Samsung phone.

## Licence

Our own code is released under the [MIT Licence](LICENSE). The MIT licence does not cover:

- Samsung's apps and the APKs built from them, which remain under their owners' terms;
- the third-party libraries shipped in the patcher app, which keep their own licences (see
  [patcher-app/THIRD_PARTY.md](patcher-app/THIRD_PARTY.md)).

This is an independent project. It is not affiliated with Samsung or Google.
