# Health Tracking client (watch ECG)

## What it is

The watch has a *Health Tracking Service* (`com.samsung.android.service.health`, "Health Platform").
A normal app holding the normal health permissions can ask it for the ECG tracker. A re-signed SHM
cannot reach the privileged ECG sensor path (SSENSOR) directly, so the patched watch app routes
sensor type 0x1b through `watch-mod/src/shminterop/ecg/EcgAdapter.java`, which repackages the
service's ECG batches as the raw `SemEcgSensorEvent` the SHM pipeline expects (see the class comment
for the data layout, the baseline filter and the ordering constraints).

The client is our own code in `watch-mod/src/shminterop/hsdk/`, written from the interface
specification in [health-tracking-protocol.md](health-tracking-protocol.md). It uses raw
`IBinder.transact` and `Parcel`; no vendor classes, no runtime SDK, nothing to import at build time.

## What the client does

- `HealthClient`: binds the service (implicit action + package, `BIND_AUTO_CREATE`), treats
  `bindService == false`, `onNullBinding`, `onBindingDied` and a 20 s timeout as connection failure,
  watches the service binder with `linkToDeath`, and reports service loss and reconnection.
- `EcgTracker`: on every (re)connection checks the capability list for `ECG` (code 5), creates the
  tracker (code 1) and registers its own listener binder (code 2); on stop it unsets (code 3) and
  then unbinds.
- `ServiceProtocol`: pure-Java request encoding and batch parsing behind `WireReader`/`WireWriter`
  (device implementation `ParcelWire`), so it is covered by host tests (`watch-mod/test/run-tests.sh`).
- `EcgBatch`/`EcgSample`: lead-off, sequence and thresholds come only from data point index 0;
  `-9999.0` mV is reported as `NaN` (unavailable).
- `HealthError`: wire code 0 is `PERMISSION_ERROR`, anything else `SDK_POLICY_ERROR`.

Verified on a Galaxy Watch (SM-R920) on 2026-10-09: tracker armed without `SDK_POLICY_ERROR`, lead-off
reported by the SHM pipeline, a full ECG classified and synced to the phone. Remaining open questions
are listed in the protocol document.

## Permissions

For the ECG tracker the service enforces `android.permission.BODY_SENSORS`, or
`com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA` when the calling
package targets SDK 36 on an SDK 36 device (the SHM watch app does). Both are runtime permissions
that must be granted. Missing permission is reported as `PERMISSION_ERROR`.

## Health Platform developer mode gate

The service checks a server-provided policy for the calling package and signing certificate. A
re-signed app does not match, so the service reports `SDK_POLICY_ERROR` unless the Health Platform
developer mode is on. It is read on every registration (no restart needed), is toggled on the watch
from the Health Platform settings screen by tapping one element of that screen 10 times in a row
(each tap within 10 seconds of the previous one), and cannot be observed by a client other than by
the absence of `SDK_POLICY_ERROR`. See section 8 of the protocol document. This is a manual step
on the watch.

Everything runs between the phone and the watch; no PC is involved.
