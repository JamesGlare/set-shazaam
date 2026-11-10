# Preview verification — 5 November 2026

## Passed

- Android Gradle build of the app and Android instrumentation-test APK.
- ARM64-only phone APK built with compressed native libraries: 46,858,443 bytes.
- APK v2 signature verification with Android SDK apksigner. This is a debug-signed
  development preview, not a production release.
- APK permission inspection: foreground service, data-sync foreground service,
  wake lock and notifications only. No Internet or microphone permission.
- Host integration test of the mobile Python adapter: 48-kHz PCM resampling,
  two overlapping reference IDs at different pitch-linked speeds, full-set
  timestamp offsets, unknown abstention, cancellation, duplicate handling,
  reference deletion and desktop SQLite compatibility.
- Existing desktop synthetic validation: overlapping IDs, reference timestamps,
  unknown/silence abstention, key-lock transform, high-pass EQ and indexing
  semantics. Desktop functionality was preserved.

## Not verified

- Android runtime and UI behavior. An Android 35 emulator was installed and
  started without hardware acceleration, but system initialization did not
  complete. Package installation failed inside Android's storage/package
  services (PackageManagerInternal was null), before application execution.
  The instrumentation test therefore did not run. The emulator was stopped.
- No physical phone installation, real-techno benchmark, background endurance,
  large-library performance or 16-KB memory-page compatibility test was completed.

## First phone check

Install the ARM64 preview APK and choose **Try generated-audio demo**.
Expected supported candidates: demo-reference-0.wav (approximately 0–38 seconds
of the mix, at 1.04x) and demo-reference-1.wav (approximately 20–60 seconds, at
0.98x). Exact evidence boundaries vary slightly. Weak single-window leads are
hidden by default. Then test a known real excerpt against its actual references.

If installation or launch fails, record the visible error and Android version.
Do not treat a successful build or these host tests as proof of on-phone behavior.
