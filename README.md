# Health Devices — standard Bluetooth health devices → Android Health Connect

A small native Android app that receives measurements from a **standard
Bluetooth LE health device** in the background — without the user opening
anything — and writes them to **Health Connect**.

Today it speaks the **Blood Pressure Service (0x1810)**: any monitor that
implements the Bluetooth SIG profile works. Tested with the **Beurer BM81**.
The code is laid out so that the other standard profiles (weight scale
0x181D, thermometer 0x1809, glucose 0x1808, pulse oximeter 0x1822) are one
class each (see [Adding another profile](#adding-another-profile)).

- **Language:** Kotlin, Jetpack Compose, the androidx Health Connect client.
- **No Google Play services**: runs on GrapheneOS and any Android 12+ phone.
  On Android 14+ Health Connect is part of the system; on 12 and 13 the
  Health Connect app must be installed (the app detects this and says so).
- **Nothing leaves the phone.** The app has no network permission.
- Repository: `retog/health-devices`. Package: `com.farewellutopia.healthdevices`.

---

## How it works, in one paragraph

A blood pressure monitor wakes after each measurement, advertises for about
30 s, accepts one connection and sends **all** readings it holds as
indications of the Blood Pressure Measurement characteristic (0x2A35), then
disconnects. Health Devices registers a **system-level BLE scan** for the
paired device (a `PendingIntent` scan, which survives the app process). When
the device advertises, Android wakes the app's receiver, which starts a
**short-lived foreground service**: it connects, subscribes to 0x2A35,
collects the indications until the device hangs up, parses them, decides
which are new, writes them to Health Connect, posts one notification
("128/79 mmHg, pulse 64 — 1 new reading"), and stops.

## Pairing

1. Open the app. Grant the nearby-devices (Bluetooth) permission, the
   notification permission (Android 13+), and the Health Connect write
   permissions for blood pressure and heart rate (its own dialog).
2. **Take a measurement** so the monitor advertises; tap **Scan**; tap the
   device when it appears (the scan is filtered on the 0x1810 service, so
   only blood pressure monitors are listed).
3. The app connects and subscribes. If the device requires bonding, Android
   shows its own **pairing dialog** (the Beurer BM81 shows a six-digit
   passkey on its display); Android keeps the bond from then on.
4. The device sends what it holds. The app now asks two things:
   - **Device clock is set to:** UTC or **local time** (the phone's zone;
     the default). See [The time setting](#the-time-setting).
   - **Readings already on the device:** import them now, or only new
     readings from now on. This sets the import watermark once.
5. Done: the background scan is registered. The status screen shows the
   device, its last reception and everything the phone must allow.

The app can hold several devices. **Forget device** removes it from the app
only; the Bluetooth bond stays until it is removed in the system's Bluetooth
settings (an app cannot remove a bond on modern Android).

## The time setting

The Bluetooth profile sends the measurement's date-time as the device's own
wall clock, **without a zone**. To turn it into an instant, the app has to
know how the device's clock is set, and asks per device: **UTC** or **local
time** (the phone's zone, with summer time handled by the phone's zone
rules). The resulting instant and the matching zone offset go into Health
Connect, so the record shows the wall-clock time the user saw on the device.

A time stamp the device does not send, or one that is implausible (year
before 2020 — the typical "clock never set" value), is replaced by the
**time of reception**; such a record's client id ends in `-rx` and the
notification says "device clock not usable, time of reception used".

Changing the setting later applies to **future readings only**; earlier
records are not rewritten.

## Background behaviour and its limits

- The scan is registered with the system (`BluetoothLeScanner.startScan`
  with a `PendingIntent`, low-power mode, aggressive matching, first-match
  callback), filtered on the paired addresses plus the profile service UUID.
  It is registered again on **boot**, when **Bluetooth turns on**, after an
  **app update**, and whenever the app's screen opens. Registration is
  idempotent (stop, then start, under one PendingIntent).
- The reception runs in a foreground service of type `connectedDevice` with
  a visible "Reading <device>…" notification, for at most 45 s. A GATT
  failure (the Android stack's status 133 and friends) is retried once after
  2 s. One session at a time; the same device is not read twice within a
  minute.
- **What stops it:** a *force-stop* of the app (from app info, or Samsung's
  "sleeping apps" / "deep sleeping apps") cancels the scan until the app is
  opened again. Doze alone does not stop a hardware-filtered scan, but
  aggressive battery managers may; the status screen has a button to the
  system's **battery optimisation** list — allow Health Devices there,
  especially on Samsung.
- If Bluetooth is off or a permission is missing, a notification on the
  *Problems* channel says what to fix.

## Health Connect data

For every reading:

| Health Connect record | Fields | `clientRecordId` |
|---|---|---|
| `BloodPressureRecord` | systolic, diastolic (mmHg; kPa is converted), time, zone offset | `bp:<address>:<epochSeconds>` |
| `HeartRateRecord` | one sample (the pulse) at the reading's instant | `hr:<address>:<epochSeconds>` |

Both carry `clientRecordVersion` 1, recording method *automatically
recorded*, and a `Device` with the manufacturer and model read from the
Device Information Service (0x180A). Health Connect **upserts** by client
record id within an app, so a device that re-sends its whole memory never
produces duplicates. In addition the app keeps a local **watermark** per
device (the newest device time stamp imported) and skips older readings
without touching Health Connect; readings without a usable time stamp are
remembered by content so a re-send is recognised.

Mean arterial pressure, the user id and the measurement status bits (body
movement, cuff fit, irregular pulse, pulse range, position) have no place
in Health Connect. The notification mentions an irregular pulse and the
other flags when set; everything stays in the raw frame in the log.

## Privacy

The app has **no network permission**: nothing leaves the phone. It writes
to Health Connect only what is listed above; the last 20 receptions, with
their raw frames (hex), are kept on the device for support and shown on the
status screen.

## Building and installing

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # JVM unit tests of the pure logic
./gradlew installDebug           # or: adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requirements: **JDK 17** and an **Android SDK** with platform 35. Versions:
AGP 8.7.2, Gradle 8.11.1, Kotlin 2.0.21, Compose BOM 2024.10.01, Health
Connect client 1.1.0-beta01 (the same toolchain as `retinue-os/ara-android`).

CI (`.github/workflows/ci.yml`) assembles the debug APK and runs the unit
tests on every pull request and push to `main`; the APK is attached to the
run. It is signed with the runner's debug key, so to install it over a
nightly build (or an earlier branch APK) uninstall that build first.

## Nightly builds

`.github/workflows/nightly.yml` builds a release APK every night at 02:30
UTC if `main` changed since the last successful run, and publishes it as a
pre-release tagged `nightly-YYYY-MM-DD`. Runs started by hand (Actions →
Nightly APK build → Run workflow) are tagged `nightly-YYYY-MM-DD-X1`, `-X2`,
… for that day. Install from the [releases page](../../releases) or with
[Obtainium](https://github.com/ImranR98/Obtainium) pointed at this
repository (enable pre-releases).

Nightly APKs are signed with the nightly key, so each installs over the
previous one. To install one over a build signed with a different key,
uninstall that build first.

The key is not in the repository but in two repository secrets; until they
are set, the workflow stops with an error. Create the keystore once, with
Java's `keytool`:

```bash
keytool -genkeypair -keystore nightly.jks -storetype PKCS12 -alias health-devices-nightly \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Health Devices Nightly"
```

or, without Java, with OpenSSL (or macOS's LibreSSL), which makes an
equivalent keystore:

```bash
openssl req -x509 -newkey rsa:4096 -sha256 -days 10000 -nodes \
  -subj "/CN=Health Devices Nightly" -keyout key.pem -out cert.pem
openssl pkcs12 -export -inkey key.pem -in cert.pem -name health-devices-nightly -out nightly.jks
rm key.pem cert.pem
```

Then put the keystore and its password (the one `keytool` or `openssl`
asked for) into the two secrets:

```bash
base64 < nightly.jks | gh secret set NIGHTLY_KEYSTORE_BASE64 --repo retog/health-devices
gh secret set NIGHTLY_KEYSTORE_PASSWORD --repo retog/health-devices
```

Keep `nightly.jks` and its password somewhere safe outside the repository:
a nightly signed with a new key does not install over the earlier ones.

The workflow finds the key's alias by itself: it uses the repository
variable `NIGHTLY_KEY_ALIAS` when that is set (`gh variable set
NIGHTLY_KEY_ALIAS --repo retog/health-devices`), otherwise the keystore's only private key (`keytool
-list`). A keystore with several private keys needs the variable.

To sign a build locally as the workflow does, set `NIGHTLY_KEYSTORE_FILE`
(the path of `nightly.jks`), `NIGHTLY_KEYSTORE_PASSWORD` and
`NIGHTLY_KEY_ALIAS` (the alias above, e.g. `health-devices-nightly`), then
run `./gradlew assembleRelease -Pnightly`. Without `-Pnightly` a release
build stays unsigned.

## Architecture

```
com.farewellutopia.healthdevices
├── profiles/            DeviceProfile: service UUID, characteristic, decode → Reading,
│   │                    records → Health Connect records, summary; Profiles = the registry
│   └── bloodpressure/   Sfloat, BloodPressureParser (0x2A35), BloodPressureProfile
├── ble/                 Uuids, ScanPlan (which filters), BackgroundScan (the PendingIntent scan),
│                        ReadStateMachine (pure), GattSession (the Android driver), receivers
├── time/                DeviceTime: naive device clock → instant + offset; the watermark form
├── importer/            ImportLogic (pure: dedupe, watermark, receive-time fallback), Importer
├── hc/                  HealthConnectAccess: availability check, lazy client, permissions, insert
├── data/                PairedDevice, Reception, ScanState; DeviceStore (DataStore + JSON)
├── service/             ReadingService (foreground, type connectedDevice), Notifications
└── ui/                  MainActivity, PairingScreen, StatusScreen, RationaleActivity
```

The read of one device is a pure state machine (`ReadStateMachine`): the
GATT callbacks become events, the machine returns actions, `GattSession`
executes them. Callbacks are delivered on a handler thread of the session's
own; nothing of the Bluetooth stack runs on the main thread, and the
session closes the connection however it ends (including cancellation when
the service is destroyed).

### Adding another profile

1. Implement `DeviceProfile` in `profiles/<name>/`: the service and
   characteristic UUIDs, whether it is indicated or notified, `decode`
   (the characteristic's layout per the Bluetooth specification — reuse
   `Sfloat` and `BloodPressureParser.dateTime`), `records` (the Health
   Connect records; use `RecordKeys.key` for the ids), and `summary`.
2. Add it to `Profiles.ALL` and the write permission to
   `HealthConnectAccess.WRITE_PERMISSIONS` and the manifest.
3. Unit-test the parser with synthetic frames (see `app/src/test`).

The scan (all profile services), the pairing flow, the GATT session, the
time setting, the dedupe and the service need no change.

## Licence

MIT, see `LICENSE`.
