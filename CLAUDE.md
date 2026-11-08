# CLAUDE.md

## What this is

Android app for a Geely Monjaro head unit (KX11, Android 9, arm64): the single place where popups
("cards") are shown on the driver's cluster and, as a touchable fallback, on the head unit. Client
apps (barrier-opener first) send an explicit broadcast; `NotificationService` queues the cards, shows
one at a time, reads the steering wheel and sends the answer back through the client's
`PendingIntent`. The wire contract is `NotifyContract` and `docs/API.md` — keep them in step, clients
copy the values rather than link against this app.

Pure Java, `minSdk 28`, `targetSdkVersion 30` on purpose (from 31 the request receiver could not
start the foreground service from the background), Java 21. GPL-3.0: the wheel and cluster code
came from barrier-opener (a fork of DezzK/barrier-opener); every file carries the GPL header.

## Commands

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

No Android SDK on the Pi that runs the agents: CI (`.github/workflows/ci.yml`) is the build.
Tag `v*` → `release.yml` → GitHub Release with `car-notification-service-<tag>.apk`, signed with
`keystore.jks` from the `KEYSTORE_BASE64` / `KEY_PASSWORD` repo secrets (a PKCS12 file; alias `key0`).

## Architecture

- Pure, tested: `Card` (request model, validation, defaults), `CardQueue` (FIFO, same id updates in
  place, expiry), `CardLayout` (list window, when the head unit must show the card too),
  `WheelNavigator` (key codes → actions per card type; `forCard` is the routing table), `LogPayload`.
  Keep decision logic there.
- `NotificationService` owns everything a card needs while one is up: the overlay windows
  (`ClusterDisplay`, `CardView`), the key log (`WheelKeyLog`), the held media keys
  (`MediaKeyCatcher`), `VolumeGuard`, the chime. It stops itself a minute after the queue empties.
- Exactly one reply per request with a `reply` PendingIntent, whatever happens (answered, timeout,
  expired, cancelled, invalid, unavailable, service destroyed).

## Wheel constraints (learned in the car, don't undo)

- Wheel keys reach Android by no ordinary route; only the ECarX `InputImpl` log carries them, read as
  root through the unit's own adbd (`LocalAdb`). The left joystick never reaches Android.
- While `MediaKeyCatcher` holds the media keys, ⏮ ⏭ ⏯ arrive by both routes: first in the log, then
  80–240 ms later in the session as `KEYCODE_MEDIA_*` (seen in the car with v0.1.0; barrier-opener
  once saw them vanish from the log). Both are read, `WheelNavigator.isEcho` folds doubles — across
  cards too, or the echo of the press that closed one card answers the next. For the same reason
  the media keys are released only `MEDIA_LINGER_MS` after the last card: released at once, the
  answering ⏯ reached the player and paused or started the music.
- A list scrolls with the volume roller (200024 / 200025) only — the user's requirement. The roller
  cannot be taken: the volume changes anyway. `VolumeGuard` puts the Android media volume back as a
  stopgap; `VolumeTrace` is the diagnostic to find a real way. Whether one roller click is one log
  line is not yet confirmed in the car.
- The cluster card is laid out in px against that one panel (display #2, 460 px wide, 288 px down).
