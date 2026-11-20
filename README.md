# AnyKey

Transpose whatever your phone is playing — Spotify, YouTube, YT Music, Deezer — so you can play along in any key.

**Status: feasibility spike (Android only).** This build exists to answer three questions on a real phone before building the real app.

## How it works

1. Android 10+ lets an app capture other apps' audio after the user grants a screen-capture permission (`AudioPlaybackCapture`).
2. AnyKey pitch-shifts that audio with a phase vocoder (`app/src/main/java/app/anykey/dsp/PitchShifter.kt`, ~32 ms latency).
3. It plays the result back on the stream you choose (Media, Alarm, Accessibility, Voice call).

iOS is not possible: it has no API for processing other apps' audio.

## Install

1. Open the repo's **Actions** tab → latest **Build APK** run → download the `anykey-debug-apk` artifact (a zip containing `app-debug.apk`).
2. Copy it to the phone and open it (allow "install unknown apps" for your file manager/browser).

Or build locally with Android Studio / `./gradlew assembleDebug`.

## The test (fill in the table)

For each app: play a song, open AnyKey, set +2 semitones, tap **Start transposing**, choose **entire screen** when asked.

**Q0 — Does capture work at all?** Tap **Play test tone**, then **Start transposing**. The *Captured* meter should move and the status should say *Receiving audio ✓*; moving the semitone slider should change the pitch of the second (transposed) copy you hear. If this fails, the problem is the phone/our code, not the music apps.

**Q1 — Does the app allow capture?** Watch the *Captured from other apps* meter. Moving = allowed. Flat while music plays = the app blocks capture.

**Q2 — Can the original be hidden?** Stop, pick **Alarm** as the output, start again, then tap **Mute media**.
- Captured meter still moving **and** you hear only the transposed song → ✅ capture is taken before the volume control; the app concept works as-is.
- Captured meter drops to zero → ❌ muting media also mutes what we capture; we need another approach.
- Repeat with **Accessibility** and **Voice call** outputs (they behave differently on Bluetooth vs. wired vs. speaker).

**Q3 — Is it good enough to play along?** Latency feel, sound quality (vocals, drums), and any dropouts.

| App | Q1 capture allowed? | Q2 original hidden? (which output) | Q3 quality / latency notes |
|---|---|---|---|
| Spotify | | | |
| YouTube | | | |
| YT Music | | | |
| Deezer | | | |
| Chrome (web player) | | | |

Also note your phone model + Android version and whether you used speaker, wired, or Bluetooth headphones (Bluetooth adds its own ~150–250 ms delay).

## What happens next

- **All ✅:** build the real app — nicer UI, quick-settings tile, per-song key memory, a higher-quality shifter (Signalsmith Stretch via NDK).
- **Q1 ❌ for some apps:** those apps opt out of capture; the fallback for them is their web player in Firefox with an extension.
- **Q2 ❌:** look at other routes (output to a different device, or the hardware dongle idea).

## DSP check

The pitch shifter was tested off-device with sine waves, chords and noise: pitch error < 0.05 cents from −24 to +12 semitones, level within ±0.9 dB, ~20× faster than real time on a desktop JVM.
