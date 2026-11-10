# Techno ID — local experimental DJ-mix matcher

A working CLI prototype, **not a demonstrated Shazam replacement**. It searches
audio you supply against reference recordings you supply. No audio is uploaded,
no API credentials are needed, and no commercial catalogue is included.

## Android app

The [Set Finder Android preview](android/README.md) wraps this same offline
matcher in a native UI. See [verification notes](android/VERIFICATION.md) for
completed checks and remaining device-testing limitations. The bundled demo
audio is synthetic; no private reference library is included.

## Quick start

Requires Python 3.11+ on Linux, macOS or Windows. The imageio-ffmpeg dependency
provides FFmpeg on supported platforms; an external FFmpeg installation is not
normally necessary.

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python techno_id.py index /path/to/reference-tracks --db data/catalogue.sqlite
.venv/bin/python techno_id.py match /path/to/dj-set.mp3 \
  --db data/catalogue.sqlite --out data/set-result.json
```

On Windows use `python` and `.venv\Scripts\python.exe` instead. The reference
folder is scanned recursively. WAV, FLAC, MP3, M4A, AIFF, OGG and Opus are accepted.
Results are JSON plus a standalone HTML timeline at the same output stem. Open
the HTML file in a browser; it has no remote dependencies. Source files are never
modified. Unchanged files are skipped; reindexing a changed file replaces that
file's fingerprints atomically. Removed source files remain indexed; start a new
database to rebuild a catalogue without them.

Example deeper search:

```bash
.venv/bin/python techno_id.py match mix.wav --db data/catalogue.sqlite \
  --out data/mix.json --speeds 0.96,0.97,0.98,0.99,1,1.01,1.02,1.03,1.04 \
  --modes vinyl,keylock --bands full,high --window 20 --hop 10
```

`--speeds` means the hypothesised deck speed relative to the source recording.
For example, 1.04 is a track playing 4% faster. The engine applies the inverse
transformation to the query. `vinyl` changes pitch and tempo together; `keylock`
changes tempo while preserving pitch. This is a **discrete search**, not full
tempo/pitch invariance. A track between grid points can be missed. Independent
pitch-only shifts and changing tempo inside a window are not modelled.

## Implemented

- audfprint landmark extraction, with an on-disk SQLite index.
- Overlapping windows and explicit playback-rate hypotheses.
- Optional key-lock and high-pass query views.
- Multiple track candidates within the same query window.
- Consistent time-offset voting and reference/mix timestamps.
- Suppression of hashes common to more than max(2, 10% of catalogue) tracks,
  to reduce false IDs caused by shared rhythmic material.
- Grouping by coherent reference-time trajectory. Different loop/jump offsets
  remain separate candidate runs.
- Explicit no-match results when thresholds are not met.
- SHA-256-based idempotent indexing and changed-file replacement.

Thresholds (40 aligned landmarks and at least 3 seconds of support by default)
are **heuristics tested on synthetic fixtures**, not calibrated probabilities.
Two coherent overlapping windows produce `supported_candidate`; a single one
produces `single_window_candidate`. These labels do not certify identity.
Overlapping windows share audio and must not be counted as independent trials.
Reports use filenames as labels; automatic tag/ISRC enrichment is not implemented.

## Which reference library?

1. **Local purchases, vinyl rips, and artist/label-supplied promos:** best initial
   full-audio catalogue. A focused techno collection is a useful pilot, but the
   system cannot name a recording it has never indexed.
2. **ACRCloud recognition API:** broad external lookup option. ACRCloud advertises
   150M+ reference tracks, but access is to recognition results, not their audio
   archive. Coverage of a specific underground scene is unverified. An integration
   would need a separately configured account and would submit query audio or
   fingerprints; none is implemented or enabled here.
3. **Official previews or label partnerships:** potentially useful extra coverage
   where indexing is permitted. A preview only identifies passages it contains;
   intros/outros absent from the preview remain blind spots. No scraper included.
4. **AcoustID / Chromaprint:** an open fingerprint/metadata catalogue, not a full
   recording collection. Its own documentation says it is optimized for
   near-identical audio, so it is not an obvious solution for layered DJ mixes.
   Its fingerprints are not compatible with this audfprint index.
5. **Discogs / MusicBrainz / tracklists:** help name and rank candidates, but
   metadata cannot replace reference audio. Spotify's standard Web API likewise
   does not provide a full-track audio archive for our custom index.
6. **Other DJ sets:** can be indexed as reference audio to find another sighting
   of an unknown passage, but a match names the set, not the underlying track.
   Automated cross-set clustering and later title propagation are future work.

Sources checked 2026-10-28:
- https://www.acrcloud.com/music-recognition/
- https://acoustid.org/
- https://developer.spotify.com/documentation/web-api/reference/get-track
- https://github.com/dpwe/audfprint

## Validation

```bash
.venv/bin/python validate.py
```

Generates six synthetic reference tracks and a speed-changed overlapping mix.
Checks both overlapping IDs, reference timestamps, key-locked time stretching,
high-pass filtering, unknown-source/silence abstention, and indexing semantics.
Writes a generated-audio demonstration and results under `data/validation/`.
There is no copyrighted test music in the distributable source.

These are engineering smoke tests, **not a real-music accuracy benchmark**.
Shared loops can still create false positives. Dense layering, reverb, distortion,
heavy EQ, vinyl noise/wow, tempo drift, short previews and absent recordings can
cause misses. Lower thresholds may retrieve weaker layers but increase false IDs.

The next meaningful evaluation is a reference collection plus real DJ-set
excerpts with confirmed track IDs, testing fixed windows, transformed windows,
Shazam/another service and unknown tracks on the same held-out examples.

## Scope / performance

The implementation decodes one source file into mono memory at 11,025 Hz.
Full-set searching is supported but work grows with windows × speeds × modes ×
bands. Start with a 1–3 minute excerpt. The current SQLite hit join is intended
for pilot collections, not a million-track service; repetitive fingerprints can
create large joins. There is no web service, neural retrieval, source separation,
known-track subtraction, remote API backend or production-scale performance claim.

Vendored audfprint sources are pinned with their MIT license; see vendor/NOTICE.txt.
