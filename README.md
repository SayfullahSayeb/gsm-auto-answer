# GSM Auto-Answer

An Android call attendant for SIM calls. It answers matching incoming calls, plays a
recorded message to the caller, loops hold music, and then opens a timed window where you
can unmute and talk or let the call end automatically.

Built with Kotlin and Material 3 for Android 10 and newer.

## Features

- **Automatic answering** for all SIMs or one selected SIM, with an optional answer delay.
- **Caller message and hold music** decoded locally from MP3, M4A, OGG or WAV files.
- **Unmute window** after the message. Press Unmute to talk, or the call ends on timeout.
- **Call screen** with caller details, mute, unmute, end call and live captions.
- **Device check** that inspects the phone's audio policy, mixer and HAL, optionally with
  Shizuku, and runs a live call test.
- **Light, dark and system appearance** with a blue-on-white telecom design.
- **No internet permission.** Settings, audio and logs stay on the device.

## How a call flows

1. A matching SIM call arrives.
2. The app answers it and mutes the microphone.
3. The custom message plays once to the caller.
4. Hold music loops while the Unmute window counts down.
5. Press **Unmute** to talk, or the call is ended when the window expires.

If caller audio cannot be verified, auto-answer stays blocked and the call keeps ringing.
The app never routes the message or hold music to the loudspeaker.

## Requirements

- Android 10 (API 29) or newer with a SIM and telephony service.
- The app must be set as the **default phone app** for auto-answer and the in-call screen.
- The device firmware must expose a telephony uplink audio path. The app checks the audio
  policy, vendor mixer and HAL before injecting audio and fails closed when the route is
  not available.
- **Shizuku** (optional) enables a deeper, read-only vendor scan and one-tap setup. It is
  not required for normal use.

Caller-audio support is a firmware property, not an app setting. Some ROMs expose a route
in the framework but the vendor HAL or DSP still drops the audio. Run the live call test
from a second phone before relying on the app; that test is the only proof.

## Build

Requirements:

- JDK 17 or newer
- Android SDK Platform 36 and Build Tools
- A connected device or emulator with telephony for runtime testing

```bash
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

```bash
./gradlew test
```

Unit tests cover the call-flow state machine, SIM policy, capability parsing,
policy-tree selection and the audio resampler.

For a release build, use your own signing key. Never commit keystores or
`local.properties` to the repository.

## Permissions

| Permission | Purpose |
| --- | --- |
| `CALL_PHONE`, `ANSWER_PHONE_CALLS` | Place and answer calls from the in-call UI |
| `READ_PHONE_STATE` | Read SIM and call state |
| `MANAGE_OWN_CALLS` | Register with the telecom framework |
| `POST_NOTIFICATIONS` | Incoming-call and session notifications |
| `USE_FULL_SCREEN_INTENT` | Show the call screen over the lock screen |
| `WAKE_LOCK`, `VIBRATE` | Keep the session alive and alert on calls |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Avoid call interruptions |
| `MODIFY_PHONE_STATE`, `MODIFY_AUDIO_ROUTING` | Used only when the firmware grants these protected permissions; a normal install cannot obtain them |

Shizuku access, when granted by the user, is used for a read-only vendor scan and for
applying the phone-app role, runtime permissions, battery exemption and full-screen alert
setting. The Shizuku setup action asks for confirmation first.

## Device check

The **Device check** tab reports whether the ROM exposes a telephony uplink and whether the
vendor mixer and HAL contain a matching route. The optional Shizuku deep scan reads vendor
audio configuration files that are not visible to a normal app.

The **Live call test** arms a short session. Call the phone from another phone; the caller
hears a test tone, then hold music. Answer **Yes** only if the caller actually heard it.
The verdict and the live result are used to decide whether auto-answer may run.

## Limitations

- Caller-side audio depends on the device firmware and cannot be guaranteed by the app.
- Call waiting is not supported; a second incoming call is declined during a session.
- Outgoing and manually answered calls are never injected with caller audio.
- The app is not a full replacement for a carrier-grade PBX. It is a local call attendant
  for a single device.

## Privacy

The app has no `INTERNET` permission, no analytics, no ads and no accounts. All data stays
in the app's private storage and is removed when the app is uninstalled. See
[PRIVACY.md](PRIVACY.md) for details.

## Project structure

```
app/src/main/java/com/gsmautoanswer/app/
  audio/        audio cache, playback, capability probe
  compat/       device compatibility scanner
  core/         call-flow state machine
  data/         settings and SIM policy
  diagnostics/  setup checks and session log
  shizuku/      optional Shizuku integration
  telecom/      InCallService and call session manager
  ui/           main shell, call UI, device check, settings
```

## License

MIT. See [LICENSE](LICENSE).
