# Health Tracking Service binder protocol (ECG subset)

Clean-room interface specification for a minimal watch-side client of the on-watch Health Tracking
Service (package `com.samsung.android.service.health`, "Health Platform"). It covers only what
`watch-mod/src/shminterop/ecg/EcgAdapter.java` needs today: bind, capability query, create the ECG
tracker, register a listener, receive ECG batches, receive errors, unregister, unbind.

This document describes interface facts only (component names, permission names, descriptor
strings, transaction codes, wire layouts and observable behaviour). It contains no vendor code.

Sources: static analysis of the service APK (versionCode 170005001, versionName 1.7.00.05,
targetSdk 36) as installed on the owner's watch, cross-checked against a reference client that
embeds the vendor client library (library version string 1.4.01.00) and against a `dumpsys` capture
of the owner's watch. Nothing here was verified on a device; see "Open questions".

---

## 1. What the current adapter uses

| Feature used by `EcgAdapter` | Wire-level equivalent (this document) |
|---|---|
| connect / `onConnectionSuccess` / `onConnectionFailed` / `onConnectionEnded` | `bindService` + `ServiceConnection` callbacks (section 2, 5) |
| capability check "ECG_ON_DEMAND supported" | transaction 5 on the service interface, look for the string `ECG` (section 3.3) |
| create ECG_ON_DEMAND tracker | transaction 1 with tracker type string `ECG` (section 3.3) |
| `setEventListener` | transaction 2, passing our own listener binder (section 3.3) |
| `onDataReceived(List<DataPoint>)` with `ECG_MV`, `LEAD_OFF` | listener transaction 1 (section 4) |
| `onError(TrackerError)` | listener transaction 2 (section 6) |
| `onFlushCompleted` | listener transaction 3 (never sent for ECG; section 3.4) |
| `unsetEventListener` | transaction 3 (section 3.3) |
| `disconnectService` | `unbindService` (section 7) |

The vendor enum value `ECG_ON_DEMAND` does not exist on the wire. Both the client-side `ECG` and
`ECG_ON_DEMAND` enum values map to the single wire string `ECG`.

---

## 2. Binding

### 2.1 Target

| Item | Value |
|---|---|
| Package | `com.samsung.android.service.health` |
| Service component | `com.samsung.android.service.health/.tracking.requesthandler.WearTrackerService` |
| Intent action | `com.samsung.android.service.health.wear.sts.ACTION_SERVICE` |
| Exported | yes; no `android:permission` attribute on the service (any app can bind) |
| Foreground type | `health` (the service promotes itself to foreground in `onCreate`) |
| Bind call used by the reference client | implicit Intent with the action above, `setPackage("com.samsung.android.service.health")`, flags = `BIND_AUTO_CREATE` (value 1) |

An explicit component Intent works equally well; the action is the documented entry point.

The client app must be able to see the service package (API 30+ package visibility). The client
manifest needs a `<queries>` entry for package `com.samsung.android.service.health` (or an
equivalent intent query), otherwise `bindService` returns false.

### 2.2 Version gate (client-side only)

The service does not exchange a version on the wire. The vendor client library reads the service
package `versionCode` before binding and refuses to bind if it is too old: the embedded copy in the
service APK requires at least 150407000, the newer reference client requires at least 160503000.
The installed service is 170005001. A clean client may perform the same optional check; the wire
protocol in this document is identical for all these versions as far as statically observable.

### 2.3 `onBind` behaviour

- The service returns its binder only after its internal "SDK policy" store has finished
  initialising. `onCreate` waits up to about 2 seconds for that. If initialisation has not
  completed (or failed), `onBind` returns null and the client receives `onNullBinding` instead of
  `onServiceConnected`.
- Android caches the `onBind` result per Intent for the lifetime of the service process. After a
  null binding, rebinding will keep yielding null until the service is destroyed; the client must
  fully unbind and try again later.
- When the last client unbinds, the service stops its foreground state and calls `stopSelf`.

### 2.4 Permissions required by the client

No permission is needed to bind. Permissions are enforced when the listener is registered
(transaction 2) and again before every data delivery, using `Context.enforcePermission` against the
binder caller's pid/uid captured at registration time.

Which permission applies to tracker type `ECG` depends on the target SDK of the package named by the
`appId` string (section 3.2) and the watch OS level:

| Condition | Permission required for `ECG` |
|---|---|
| appId package `targetSdkVersion >= 36` AND device `SDK_INT >= 36` | `com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA` (protection level: dangerous, runtime grant) |
| otherwise | `android.permission.BODY_SENSORS` (dangerous, runtime grant) |

If the appId package cannot be found by the service, its target SDK is treated as unknown and the
non-granular rule (`BODY_SENSORS`) applies.

The client must declare the relevant permission(s) in its manifest and hold the runtime grant. The
`dumpsys` capture shows the SHM watch app (targetSdk 36) with both permissions granted.

### 2.5 Caller checks performed by the service ("SDK policy")

On every listener registration (transaction 2), after the permission check passes, the service
decides whether the caller is allowed to use the tracker:

1. **Developer mode on** (Health Platform setting, see section 8): the policy check is skipped and
   the registration proceeds.
2. **Developer mode off**:
   - If the appId is on a server-provided blocklist cached locally, the registration is refused.
   - Otherwise the service looks up a cached policy for the appId (fetching it from a Samsung
     server when not cached, endpoint path `sdp/policies` with query `serviceId=<appId>`). The
     registration is allowed only if all of the following hold: the appId package's signing
     certificate matches one of the credentials in that policy; the policy has not expired; the
     policy contains an entry whose name equals the tracker type in lower case (`ecg`), whose SDK
     field equals `T`, and whose access type equals 1.
   - On refusal, or on any error during the lookup (including no network), the service sends
     `onThrowException(1)` (SDK_POLICY_ERROR) to the listener and adds the appId to a pending
     provisioning queue that is retried later in the background.

Consequence for this project: a re-signed client (for example the patched SHM) will not match any
server credential, so it needs developer mode enabled on the watch, or it receives SDK_POLICY_ERROR.

The policy decision is asynchronous: transaction 2 returns before the decision is made, and the
error (if any) arrives later on the listener.

---

## 3. Service interface

### 3.1 Descriptor and conventions

| Item | Value |
|---|---|
| Interface descriptor | `com.samsung.android.service.health.tracking.IClientEventListener` |
| Implemented by | the binder returned from `onBind` |

Note the confusing name: the interface implemented by the service is called
`IClientEventListener`; the callback interface implemented by the client is called
`IServiceEventListener` (section 4).

Conventions for every transaction on this interface:

- Request parcel starts with `writeInterfaceToken(descriptor)`. The service enforces the token for
  codes 1..16777215.
- All transactions are two-way (flags = 0, not oneway).
- Reply parcel starts with the standard exception header. Read it with `readException()`; on
  success it is a single int 0. If the service-side method throws a runtime exception (for example a
  null `appId` or tracker type string), the reply carries that exception and `readException()`
  rethrows it.
- Strings are written with `Parcel.writeString` (UTF-16, length-prefixed; null is length -1).
- Transaction code `1598968902` (`INTERFACE_TRANSACTION`) returns the descriptor string.

### 3.2 The `appId` argument

Every call except code 5 starts with an `appId` string. The vendor client always passes the calling
app's own package name (`Context.getPackageName()`). The service uses this string as the client
identity for: per-client tracker bookkeeping, target-SDK lookup for the permission rule, the SDK
policy lookup and signature match, and the pid/uid record used for later per-batch permission checks.
The client must pass its own package name. It must be non-null.

### 3.3 Transactions used

| Code | Name | Request (after interface token, in order) | Reply (after exception header) |
|---|---|---|---|
| 5 | getSupportedHealthTrackerTypes | (nothing) | string list: int count (-1 = null), then count strings |
| 1 | createHealthTracker | String appId; String trackerType; typed object TrackerUserProfile (int 0 = null); String exerciseType (nullable) | (nothing) |
| 2 | setEventListener | String appId; String trackerType; strong binder of the client listener (section 4) | (nothing) |
| 3 | unsetEventListener | String appId; String trackerType | (nothing) |
| 4 | flush | String appId; String trackerType | (nothing) |

For ECG, use these exact values:

| Field | Value for ECG |
|---|---|
| trackerType | `ECG` |
| TrackerUserProfile | absent: write int 0 (null marker) and nothing else |
| exerciseType | null (`writeString(null)`) |

TrackerUserProfile layout, only for completeness (not used for ECG): int 1 (non-null marker), then
float height, float weight, int gender, int age.

Other codes exist on this interface but are not needed for ECG: 6 setExerciseState (String appId,
String trackerType, String state), 7 setExerciseData (String appId, String trackerType, String
dataType, float array, long array), 8 setPpgType (String appId, String trackerType, string list).

#### Code 5: getSupportedHealthTrackerTypes

Executed synchronously on the binder thread. Returns the wire tracker type strings supported by the
watch's sensor hub. The service builds it from available sensor types; sensor type 27 maps to `ECG`.
Possible strings: `HEART_RATE`, `ECG`, `PPG_GREEN`, `ACCELEROMETER`, `PPG_IR`, `PPG_RED`, `SPO2`,
`BIA`, `SWEAT_LOSS`, `SKIN_TEMPERATURE`, `SKIN_TEMPERATURE_CONTINUOUS`, `PPG_CONTINUOUS`,
`PPG_ON_DEMAND`, `EDA_CONTINUOUS`, `MFBIA_ON_DEMAND`. ECG is usable when the list contains `ECG`.

The vendor client caches this list for the process lifetime and refuses to create a tracker whose
type is not in it. A clean client should do the same check once per connection.

#### Code 1: createHealthTracker

Asynchronous on the service side: the binder call only enqueues a request on the service's single
request-handler thread and returns. Processing creates (or replaces) the per-appId, per-tracker-type
entry. No permission or policy check happens here. No callback is produced.

It must precede code 2 for the same appId and tracker type. If code 2 arrives without a prior
create, the service silently does nothing (no data, no error), apart from the permission and policy
checks described for code 2.

#### Code 2: setEventListener

Order of events on the service side:

1. Synchronously, on the binder thread: permission check (section 2.4) against the caller's pid/uid.
   - On failure (permission missing, or no permission mapping for the type) the service calls
     `onThrowException(0)` (PERMISSION_ERROR) on the supplied listener, still inside this
     transaction (nested call back into the client; see section 7), and returns normally.
   - On success it records the caller's pid/uid for this appId and enqueues the registration.
2. Asynchronously, on the request-handler thread: developer-mode / SDK policy check (section 2.5).
   On refusal: `onThrowException(1)`. On success: the listener is attached to the tracker and the
   ECG sensor is started if it was not already running for another client.

The reply of code 2 only means "accepted for processing". There is no positive acknowledgement; the
first data batch (or an error) is the only signal.

The listener argument is written with `writeStrongBinder(listenerBinder)` (equivalently
`writeStrongInterface`). Must be non-null.

#### Code 3: unsetEventListener

Asynchronous on the service side (enqueued). Detaches this appId's listener for that tracker type.
When no client at all remains registered for the tracker type, the service stops the sensor.
Unsetting something not registered is a no-op. No callback is produced.

### 3.4 Flush (code 4) and ECG

Do not use flush for ECG. The vendor client refuses flush for ECG locally (it only allows it for
`ACCELEROMETER`, `PPG_GREEN`, `HEART_RATE`, `SKIN_TEMPERATURE_CONTINUOUS`, `PPG_CONTINUOUS`,
`EDA_CONTINUOUS`), and the service's ECG tracker has no flush implementation, so `onFlushCompleted`
is never sent for ECG.

---

## 4. Client listener interface

| Item | Value |
|---|---|
| Interface descriptor | `com.samsung.android.service.health.tracking.IServiceEventListener` |
| Implemented by | a `Binder` object created by the client and passed in code 2 |

The client binder should attach the descriptor (so `INTERFACE_TRANSACTION` is answered), call
`enforceInterface(descriptor)` on incoming data for codes 1..16777215, and write
`writeNoException()` (int 0) into the reply after handling. All three calls are two-way: the service
thread waits for the reply.

| Code | Name | Request (after interface token) | Reply |
|---|---|---|---|
| 1 | onDataUpdated | typed list of ServiceDataPoint (layout below) | exception header only |
| 2 | onThrowException | int errorCode (section 6) | exception header only |
| 3 | onFlushCompleted | (nothing) | exception header only |

If the client throws or its process is dead, the service gets a `RemoteException` on delivery and
then removes the client's tracker entry (equivalent to an unset); see section 7.

### 4.1 onDataUpdated parcel layout

The payload is a typed list (the `createTypedArrayList` format) of ServiceDataPoint objects. Each
ServiceDataPoint contains a timestamp and a generic list (the `writeList` / `readList` format) of
ServiceValue Parcelables. Because the inner list is written with `Parcel.writeList`, each element
carries the generic value header and the Parcelable class name.

Top level, after the interface token:

| # | Type | Meaning |
|---|---|---|
| 1 | int | N = number of data points; -1 means null list |
| 2 | repeated N times | one ServiceDataPoint entry (below) |

ServiceDataPoint entry:

| # | Type | Meaning |
|---|---|---|
| a | int | non-null marker: 0 = null element (no further fields), non-zero = present |
| b | long | timestamp, milliseconds since the Unix epoch (wall clock, see 4.3) |
| c | int | M = number of values; -1 means null list |
| d | repeated M times | one generic-list element (below) |

Generic-list element holding a ServiceValue (the standard `Parcel.writeValue` encoding of a
Parcelable):

| # | Type | Meaning |
|---|---|---|
| i | int | value type tag; 4 (`VAL_PARCELABLE`) for ServiceValue |
| ii | int | byte length of the following Parcelable payload (items iii..vi). Present only when the writer runs on Android 13 / API 33 or later, which is the case on current watches. |
| iii | String | Parcelable class name, `com.samsung.android.service.health.tracking.data.ServiceValue` |
| iv | int | integer field |
| v | float | float field |
| vi | long | long field (always 0 for ECG) |

Implementation guidance for a clean client: parse this layout manually with `readInt`, `readLong`,
`readString`, `readFloat`. Do not rely on `Parcel.readList` / `readValue`, because those would need a
Parcelable class with the vendor's exact class name on the client's class loader. Use the length
field (ii) to skip unknown or unexpected elements robustly: record the data position after reading
it and continue at that position plus the length. Since writer and reader run on the same watch,
the length field's presence can be decided from `Build.VERSION.SDK_INT >= 33`. A defensive parser
may also check that, after reading the tag 4 and the length, the next item is the expected class
name string.

### 4.2 ECG value mapping

Each ECG data point carries exactly 6 ServiceValues. Positions:

| Pos | Integer field | Float field | Vendor library key |
|---|---|---|---|
| 0 | ECG ADC count (offset-removed) | ECG voltage in mV (`ECG_MV`) | ECG_MV = float of pos 0 |
| 1 | PPG green ADC count (`PPG_GREEN`) | unused (0) | PPG_GREEN = int of pos 1 |
| 2 | lead-off status (`LEAD_OFF`) | unused (0) | LEAD_OFF = int of pos 2 |
| 3 | sequence number (`SEQUENCE`) | unused (0) | SEQUENCE = int of pos 3 |
| 4 | max threshold, ADC counts | max threshold in mV (`MAX_THRESHOLD_MV`) | MAX_THRESHOLD_MV = float of pos 4 |
| 5 | min threshold, ADC counts | min threshold in mV (`MIN_THRESHOLD_MV`) | MIN_THRESHOLD_MV = float of pos 5 |

The vendor library exposes only the keys in the last column (ECG_MV float, PPG_GREEN int,
LEAD_OFF int, SEQUENCE int, MAX/MIN_THRESHOLD_MV float) plus the timestamp. The raw ADC count in
pos 0 and the threshold ADC counts in pos 4/5 are present on the wire but hidden by the library.

Per-sample validity inside one batch (important):

| Data point index in batch | pos 0 | pos 1 (PPG green) | pos 2 (lead-off) | pos 3 (sequence) | pos 4/5 (thresholds) |
|---|---|---|---|---|---|
| 0 | valid | first PPG sample | valid | valid | valid |
| 5 | valid | second PPG sample | -1 | -1 | int -1, float -1.0 |
| any other | valid | -1 | -1 | -1 | int -1, float -1.0 |

So lead-off, sequence and thresholds are per batch, carried only by the first data point. A
consumer must take them from index 0 and must treat -1 in other points as "not present", not as a
status value. (The current `EcgAdapter` takes the lead value from the last point of the batch,
which by this layout is always -1.)

Value semantics:

- **ECG mV (pos 0 float)**: produced by the sensor hub's parsed-data path. If the watch's sensor
  framework does not support the parsed-data feature for sensor 27, the service falls back to a
  legacy path where the float is the constant -9999.0 for every sample and only the ADC count is
  meaningful. Treat -9999.0 as "mV unavailable".
- **ECG ADC (pos 0 int)**: parsed path: raw sample minus the per-event ADC offset reported by the
  sensor; legacy path: raw sample minus 2097152 (0x200000).
- **Lead-off (pos 2, index 0)**: parsed path: 0 = electrode contact, 5 = lead off. Legacy path:
  the raw lead status byte from the sensor event (the adapter's comment documents 0 = finger,
  5 = no contact).
- **Sequence (pos 3, index 0)**: parsed path: the sensor event's pattern counter; legacy path: low
  8 bits of the sensor's sequence word. Treat as an 8-bit wrapping counter.
- **Thresholds (pos 4/5, index 0)**: parsed path: ADC threshold minus offset, and the sensor's
  positive/negative thresholds in mV. Legacy path: ADC values minus 0x200000, and 0.0 mV.
- **PPG green (pos 1)**: ADC count minus offset; at most two samples per batch (index 0 and 5).

### 4.3 Batching, timing and ordering

- One `onDataUpdated` call per sensor event from the sensor hub. N = the number of ECG samples in
  that event (parsed path: the event's ECG count; legacy path: 10 if the event's count field equals
  1, otherwise 5). The current adapter observes and expects batches that are multiples of 10.
- All data points in one batch share the same timestamp: wall-clock time (`currentTimeMillis`) at
  which the service received the sensor event. It is not monotonic, not per sample, and includes
  delivery jitter. Per-sample times must be derived from the sample rate.
- Sample rate is not configured by the service (it registers the sensor with defaults). The adapter
  assumes 500 Hz; see open questions.
- Data points within a batch are in sample order. Batches are produced on a single
  per-sensor worker thread in the service and each delivery is a blocking two-way call, so batch N+1
  is not sent before the client has replied to batch N. On the client, consecutive batches may
  arrive on different binder threads; ordering is preserved only if the client finishes handling
  (or enqueues in order) before returning from the call.
- If the client is slow, it delays ECG delivery for every client of the sensor. Handlers must return
  quickly (copy, enqueue, return).

---

## 5. Connect sequence

1. `bindService(intent, connection, BIND_AUTO_CREATE)` with the intent from section 2.1. If it
   returns false, the service is missing or not visible: report failure.
2. Wait for `onServiceConnected(name, binder)`. Treat `onNullBinding` (service policy store not ready,
   section 2.3) and `onBindingDied` as connection failures. The vendor library uses a 20 second
   overall timeout for the bind step.
3. Transaction 5. If the returned list does not contain `ECG`, stop (unsupported).
4. Transaction 1 with (packageName, `ECG`, null profile marker 0, null exerciseType).
5. Transaction 2 with (packageName, `ECG`, listener binder). Issue it after code 1 has returned on
   the same thread; the service processes both in arrival order.
6. Wait for either the first `onDataUpdated` or an `onThrowException`. There is no other success
   signal. The vendor library performs steps 3 to 5 inside its "connection success" callback.

There is no handshake, version exchange, session token or client SDK version field on the wire.

---

## 6. Errors

### 6.1 Errors delivered by the service (listener code 2, int argument)

| Code | Name | When |
|---|---|---|
| 0 | PERMISSION_ERROR | permission check failed at registration (sent synchronously inside code 2), or permission check failed before a data delivery (sent instead of that batch, once per batch, while the registration stays in place) |
| 1 | SDK_POLICY_ERROR | developer mode off and the caller is blocklisted, has no matching policy, has an expired policy, does not match the policy's signing credentials, or the policy lookup failed (for example no network) |

The vendor library maps 0 to PERMISSION_ERROR and any other value to SDK_POLICY_ERROR. A clean
client should do the same.

After SDK_POLICY_ERROR the listener is not attached; the client must register again (code 2) after
the cause is fixed (for example after enabling developer mode). After a per-batch
PERMISSION_ERROR the registration remains; data resumes when the permission is granted again.

### 6.2 Connection-level failures (client side, not on the wire)

| Situation | Vendor library behaviour (for reference) |
|---|---|
| service package not installed | connection failed, code 0 (PACKAGE_NOT_INSTALLED) |
| service versionCode below the library minimum | connection failed, code 1 (OLD_PLATFORM_VERSION) |
| `bindService` false, timeout, `onNullBinding`, `onBindingDied` | connection failed, code -1 |
| transaction throws `RemoteException` / `DeadObjectException` | swallowed by the library |

A clean client defines its own error reporting; these codes have no meaning to the service.

---

## 7. Lifecycle and threading

- **Service side**: requests 1, 2 (second half), 3, 4, 6, 7, 8 are queued to one dedicated
  "request handler" thread and processed in FIFO order. Code 5 and the permission part of code 2 run
  on the incoming binder thread.
- **Client callbacks** arrive on the client's binder thread pool. The one exception: a
  PERMISSION_ERROR raised during code 2 is a nested call made while the client's code 2 transaction
  is still in progress, so it is delivered on the same client thread that issued code 2, before
  `transact` returns. Do not hold a non-reentrant lock across the code 2 call if the error handler
  needs that lock.
- **Teardown order**: (1) code 3 `unsetEventListener(packageName, "ECG")`; (2) `unbindService`.
  Unbinding without unsetting leaves the registration in the service until the next delivery fails
  with `RemoteException`, at which point the service removes it; if the service process stays alive
  and the client process stays alive, the sensor keeps running. The vendor library calls its
  "connection ended" callback synchronously from its disconnect method; there is no service-side
  acknowledgement.
- **Client process death**: detected by the service on the next delivery attempt
  (`RemoteException`), which removes the client's ECG entry and stops the sensor if no other client
  remains. The service does not use `linkToDeath` on the listener.
- **Service death or restart**: the client gets `onServiceDisconnected` (and, with
  `BIND_AUTO_CREATE`, a later `onServiceConnected` when the system restarts it). All service state is
  in memory, so trackers and listeners are lost. The vendor library does not re-arm anything; a
  clean client should, on reconnection, repeat steps 3 to 5 of section 5 if it is still running.
  `linkToDeath` on the service binder gives earlier notice.
- **Multiple clients**: registrations are keyed by (appId, tracker type). A second create for the
  same appId and type replaces the previous entry. The sensor runs while at least one (appId, type)
  registration exists.

---

## 8. Health Platform developer mode

- It is a boolean feature flag inside the service, key `common_developer_mode`, default off, kept in
  the service's own preferences. It is read on every listener registration (code 2); no restart is
  needed after changing it.
- When on, the SDK policy check (section 2.5) is skipped entirely. The permission check (section 2.4)
  is not affected.
- It is toggled from the Health Platform settings screen (exported activity, action
  `com.samsung.android.service.health.intent.action.START_SETTINGS_ACTIVITY`) by tapping one specific
  element of that screen 10 times, each tap within 10 seconds of the previous one. Each 10-tap
  sequence flips the flag. Turning it off also turns off the dependent "feature mode" flag.
- On change the service sends a package-scoped broadcast to itself,
  `com.samsung.android.service.health.intent.action.SETTINGS_CHANGED_DEV_MODE`, with boolean extra
  `is_enabled`. Other apps cannot receive it, so a client cannot observe the flag directly. The only
  observable effect for a client is the absence of SDK_POLICY_ERROR.

---

## 9. Open questions / unverified

1. **Writer-side length prefix**: confirm on the device that each ServiceValue element in
   `onDataUpdated` has the API 33+ length int after the type tag 4 (expected on the watch's OS
   level). The parser should validate the class-name string to detect a mismatch.
2. **Sample rate**: the ECG rate delivered through this service (assumed 500 Hz by the adapter) and
   the typical N per batch on the owner's watch (parsed path). Not set by the service; depends on the
   sensor hub default.
3. **Parsed vs legacy path**: whether the owner's watch reports the parsed-data feature for sensor
   27 (real mV values) or uses the legacy path (mV = -9999.0). The adapter's logged values
   (DC-coupled, about -142 to -31 mV) suggest the parsed path.
4. **PPG at index 5 with short batches**: in the parsed path, if a batch has more than 5 samples but
   the sensor reports only one PPG sample, the service may fail to build that batch. Check on device
   whether batches are ever missing.
5. **Lead-off semantics in practice**: confirm 0 / 5 for contact / no contact at index 0, and how
   often lead-off changes are reported.
6. **Developer mode element**: which element of the Health Platform settings screen receives the
   10 taps (believed to be a title or version label). Confirm the dev-mode state on the owner's
   watch (log tag shows "isDeveloperMode" on each registration).
7. **Policy outcome for the patched SHM**: confirm that without developer mode the patched,
   re-signed SHM gets SDK_POLICY_ERROR (signature mismatch), and whether the unmodified SHM package
   name has a server policy at all.
8. **`onBind` null after boot**: how often the policy store is not ready within 2 seconds after the
   service is created (cold start, no network), producing `onNullBinding`, and whether a retry after
   a few seconds then succeeds without killing the service process.
9. **Per-batch permission checks**: the service checks permissions against the pid/uid recorded at
   registration. If the client process restarts and registers with a new pid, the record is
   overwritten; confirm no stale-pid failure occurs after reconnect.
10. **Concurrent use with the stock ECG path**: whether the native SHM ECG sensor client and this
    service can hold sensor 27 at the same time, and what the second registrant observes.
