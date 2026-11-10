# Set Finder for Android — offline preview

A native Android interface around the existing Techno ID fingerprint engine.
The Python matcher is packaged with Chaquopy, NumPy and SciPy. Audio decoding
uses Android's MediaExtractor/MediaCodec, not a remote service or desktop FFmpeg.

## Try it

1. Install the preview APK on Android 8.0 or newer (ARM64 or x86-64).
2. Tap **Try generated-audio demo**. It adds two generated reference tracks and
   scans a generated mix containing both, overlapping and at different speeds.
   These are synthetic fixtures, not copyrighted catalogue recordings or a
   real-techno benchmark. The two demo entries can be removed in Manage library.
3. Import your own full reference tracks with **Add reference tracks**.
4. Choose a DJ set, set the starting time and a 5–180-second scan length, then
   tap **Find candidates**. Start with an excerpt whose tracks you know.
5. Results show candidate names, positions in the full set, corresponding
   reference positions and evidence labels. Single-window leads are hidden
   until explicitly expanded. Export the latest report as JSON if desired.

This is a sideloadable **debug-signed development build**, not a Play Store
release. No commercial recognition catalogue is included. It cannot name a
recording which has not been indexed. Fingerprints are not Shazam-compatible.

## Privacy and storage

- The manifest has **no Internet, microphone or broad storage permission**.
- Android's document picker grants access to selected files only. A cloud-backed
  document provider may download those files itself; choose local files to keep
  the whole workflow offline.
- The app retains a private SQLite fingerprint index, reference filenames and
  the latest result. PCM decoding files are temporary cache files, deleted when
  a job finishes (Android can also clear the cache). Original audio is unchanged.
- System cloud backup is disabled. Uninstalling removes the app's local library.
- Notifications and a time-limited wake lock support foreground analysis with
  the display off. Cancellation is checked during decoding and between matcher
  hypotheses; an individual fingerprint operation may finish before stopping.

## Preview limits

- Reference tracks: up to 15 minutes each. Queries: up to 180 seconds at a time.
  Large libraries have not been profiled on physical phones.
- Android-supported audio codecs only; MP3, common AAC/M4A, FLAC and PCM WAV are
  typical choices. Codec/container support can vary by phone.
- Optional pitch-linked speed search: 0.96 to 1.04 in 0.01 steps, with full-band
  and high-pass views. **Key-locked time stretching is not supported on Android
  in this preview**. The desktop CLI retains its FFmpeg-based key-lock search.
- Heuristic candidate thresholds, not probabilities. Shared percussion, loops,
  heavy processing, missing references and intermediate speeds can cause false
  leads or misses. Overlapping windows are correlated evidence.
- Python 3.10 / NumPy 1.23.3 / SciPy 1.8.1 are pinned to available Android wheels.
  This preview is not certified for devices configured with 16-KB memory pages;
  some bundled scientific libraries are older native binaries. A store-ready
  build needs modernized wheels and physical-device compatibility testing.

## Build

Requirements: JDK 17, Android SDK platform 35, Python 3.10, and network access to
download public Gradle/Python dependencies. No credentials are needed.

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

For a smaller ARM64-only phone APK (the delivered preview), use
`./gradlew -PphoneOnly :app:assembleDebug`.

Set the SDK location in `local.properties` (`sdk.dir=/your/sdk`) or standard
Android tooling configuration. If Python 3.10 is not on PATH, set
`SETFINDER_BUILD_PYTHON` to the Python 3.10 executable. Build scripts copy only
`techno_id.py` and specified vendored source/license files from the parent
project. They do not include private audio, local databases or desktop environments.

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Verification

Host-side adapter check, from the parent project with its dependencies installed:

```sh
python android/test_bridge.py
python validate.py
```

The adapter test covers decoded PCM, resampling from 48 kHz, overlapping
pitch-shifted matches, full-set timestamp offsets, unknown abstention,
cancellation, duplicate imports, deletion and desktop database compatibility.

The instrumented `OfflineTest` exercises bundled WAV files through Android's
decoder, Python and SQLite, checks seeked excerpt boundaries, and asserts the
absence of Internet permission. Run on a connected emulator or device:

```sh
./gradlew connectedDebugAndroidTest
```

Passing host tests alone does not establish that the APK works on a phone.
See the separately delivered verification notes for what was actually run.

## Attribution

audfprint: Dan Ellis / Columbia University / Google, MIT license; exact pinned
revision and license are under `../vendor`. Chaquopy is MIT; Python uses the
PSF license; NumPy and SciPy are BSD-licensed; psutil is BSD-3-Clause. Their
native dependencies include OpenBLAS (BSD-3-Clause), LLVM libc++ (Apache-2.0
with LLVM exceptions) and GCC runtime libraries (GPL with runtime exception).
Third-party notices are included in the app assets and source distribution.
