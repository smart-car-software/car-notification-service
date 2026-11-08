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
  `WheelNavigator` (key codes → actions per card type; `forCard` is the routing table).
  Keep decision logic there.
- No diagnostics in the release (v0.4.0 dropped the key-log view, the wheel trace, "Send logs"
  and the on-unit journal): `DebugLog` is logcat only. Bring a diagnostic back from git history
  (v0.3.0) when a new wheel question needs the car's logs.
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
- A list scrolls with the volume roller (200024 / 200025) only — the user's requirement. One click
  is one `onKeyPressed` line (confirmed in the car). The volume step itself is made by
  `ecarx.xsf.inputservice` as `AudioManager.adjustSuggestedStreamVolume(USE_DEFAULT_STREAM_TYPE)`,
  which `MediaSessionService` hands to the default volume session (the trace showed Spotify's) — so
  for a list `MediaKeyCatcher.captureVolume` puts the held session on remote playback and takes
  the step (confirmed in v0.2.0). That does not stop the volume: ECarX's MediaSessionService still
  passes the adjustment on, and `com.android.car` (its AudioPolicy volume callback) sets the
  stream — `VolumeGuard` puts it back after each step. The session's steps are what scrolls a list
  (key log roller lines are ignored then): the key log once went quiet in the car while "connected".
  One detent can give 2–3 steps within a few ms — `WheelNavigator.isRollerBounce` folds them.
- Music in the car came from Spotify and from the phone over Bluetooth (A2DP session).
- For ⏮ ⏭ ⏯ `ecarx.xsf.inputservice` does three things: `ecarx.intent.action.media_*_pressed/
  released` broadcasts, a key event into media-session dispatch (that is what `MediaKeyCatcher`
  takes), and a plain (not ordered — cannot be aborted) `android.intent.action.MEDIA_BUTTON`
  broadcast. The ECarX media centre receives the last one and, when its own source holds the
  focus (`KeyManager … isLossFocus(): false`), sends it over AVRCP to the phone — the "music
  switched to Bluetooth" the driver saw. It also does so with Spotify holding the focus when
  Bluetooth was its last source (`keyMode: 2`) — head unit behaviour, popup or not. Taking a
  transient audio focus while a card is up does not help (tried in v0.3.0: the Bluetooth sink
  takes it straight back). Left as is — the driver does not use Bluetooth music. Next step if it
  ever matters: list the MEDIA_BUTTON receivers (`cmd package query-receivers`, `dumpsys activity
  broadcasts`) to see whether the media centre's could be switched off as root for a card.
- The cluster card is laid out in px against that one panel (display #2, 460 px wide, 288 px down).
