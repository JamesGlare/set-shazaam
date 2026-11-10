#!/usr/bin/env python3
"""Local experimental DJ-mix recognition. No uploads or remote catalogue calls."""
import argparse
from collections import defaultdict
from fractions import Fraction
import hashlib
import html
import json
import math
from pathlib import Path
import sqlite3
import subprocess
import sys

import numpy as np
from scipy import signal

sys.path.insert(0, str(Path(__file__).parent / 'vendor' / 'audfprint'))
from audfprint_analyze import Analyzer, landmarks2hashes
from audfprint_match import Matcher

SR = 11025
HOP = 256 / SR
EXTENSIONS = {'.wav', '.flac', '.mp3', '.m4a', '.aiff', '.aif', '.ogg', '.opus'}
SCHEMA = 'techno-id-v1-sr11025-density30'


def decode(path):
    import imageio_ffmpeg
    cmd = [imageio_ffmpeg.get_ffmpeg_exe(), '-v', 'error', '-nostdin', '-i',
           str(Path(path).resolve()), '-vn', '-ac', '1', '-ar', str(SR),
           '-f', 'f32le', 'pipe:1']
    result = subprocess.run(cmd, capture_output=True, check=True)
    return np.frombuffer(result.stdout, dtype='<f4').copy()


def fingerprint(audio):
    if len(audio) < SR or not np.any(audio):
        return np.empty((0, 2), dtype=np.int32)
    analyzer = Analyzer(density=30)
    pieces = []
    # Sub-frame shifts reduce sensitivity to query-window boundaries.
    for offset in (0, 128):
        peaks = analyzer.find_peaks(audio[offset:], SR)
        landmarks = analyzer.peaks2landmarks(peaks)
        if len(landmarks):
            pieces.append(landmarks2hashes(landmarks))
    return np.unique(np.concatenate(pieces), axis=0) if pieces else np.empty((0, 2), dtype=np.int32)


class Catalogue:
    """Sparse on-disk landmark index with the audfprint matching interface."""
    def __init__(self, path, create=False):
        path = Path(path).resolve()
        if create:
            path.parent.mkdir(parents=True, exist_ok=True)
            self.conn = sqlite3.connect(path)
            self.conn.executescript('''
                CREATE TABLE IF NOT EXISTS metadata(key TEXT PRIMARY KEY, value TEXT);
                CREATE TABLE IF NOT EXISTS tracks(id INTEGER PRIMARY KEY, path TEXT UNIQUE,
                    digest TEXT, duration REAL, hashes INTEGER);
                CREATE TABLE IF NOT EXISTS landmarks(hash INTEGER, track INTEGER, frame INTEGER);
                CREATE INDEX IF NOT EXISTS hash_lookup ON landmarks(hash);
                CREATE INDEX IF NOT EXISTS track_lookup ON landmarks(track);
            ''')
            with self.conn:
                self.conn.execute('INSERT OR IGNORE INTO metadata VALUES (?,?)', ('schema', SCHEMA))
        else:
            self.conn = sqlite3.connect(path.as_uri() + '?mode=ro', uri=True)
        version = self.conn.execute('SELECT value FROM metadata WHERE key=?', ('schema',)).fetchone()
        if not version or version[0] != SCHEMA:
            raise ValueError('Incompatible catalogue; build a separate v1 database')
        self.refresh()

    def refresh(self):
        rows = self.conn.execute('SELECT id,path,hashes FROM tracks').fetchall()
        self.names = {r[0]: r[1] for r in rows}
        self.hashesperid = np.ones(max(self.names, default=0) + 1, dtype=np.int64)
        for idx, _, count in rows:
            self.hashesperid[idx] = max(1, count)

    def add(self, path):
        path = Path(path).resolve()
        with path.open('rb') as handle:
            digest = hashlib.file_digest(handle, 'sha256').hexdigest()
        old = self.conn.execute('SELECT id,digest FROM tracks WHERE path=?', (str(path),)).fetchone()
        if old and old[1] == digest:
            return 'unchanged'
        audio = decode(path)
        return self.add_samples(path, audio, digest)

    def add_samples(self, path, audio, digest):
        """Index already-decoded mono 11025 Hz audio (also used by Android)."""
        path = Path(path).resolve()
        old = self.conn.execute('SELECT id,digest FROM tracks WHERE path=?', (str(path),)).fetchone()
        if old and old[1] == digest:
            return 'unchanged'
        hashes = fingerprint(audio)
        if len(hashes) < 20:
            raise ValueError(f'Insufficient fingerprints in {path.name}')
        with self.conn:
            if old:
                idx = old[0]
                self.conn.execute('DELETE FROM landmarks WHERE track=?', (idx,))
                self.conn.execute('UPDATE tracks SET digest=?,duration=?,hashes=? WHERE id=?',
                                  (digest, len(audio)/SR, len(hashes), idx))
            else:
                idx = self.conn.execute('INSERT INTO tracks(path,digest,duration,hashes) VALUES (?,?,?,?)',
                                        (str(path), digest, len(audio)/SR, len(hashes))).lastrowid
            self.conn.executemany('INSERT INTO landmarks VALUES (?,?,?)',
                                 ((int(h), idx, int(t)) for t, h in hashes))
        return f'indexed {len(hashes)} landmarks'

    def get_hits(self, hashes):
        query = defaultdict(list)
        for frame, hash_ in hashes:
            query[int(hash_)].append(int(frame))
        keys, hits = list(query), []
        for start in range(0, len(keys), 400):
            chunk = keys[start:start+400]
            marks = ','.join('?' for _ in chunk)
            # Shared kicks/loops produce many apparently aligned but nonspecific
            # matches. Ignore fingerprints occurring across much of the catalogue.
            max_tracks = max(2, math.ceil(len(self.names)*.1))
            for h, track, frame in self.conn.execute(
                    f'''SELECT hash,track,frame FROM landmarks WHERE hash IN
                    (SELECT hash FROM landmarks WHERE hash IN ({marks}) GROUP BY hash
                    HAVING COUNT(DISTINCT track)<=?)''', chunk+[max_tracks]):
                hits.extend((track, frame-t, h, t) for t in query[h])
        return np.asarray(hits, dtype=np.int64).reshape(-1, 4)


def undo_speed(audio, speed, mode):
    if speed == 1:
        return audio
    if mode == 'vinyl':
        ratio = Fraction(speed).limit_denominator(10000)
        return signal.resample_poly(audio, ratio.numerator, ratio.denominator)
    import imageio_ffmpeg
    cmd = [imageio_ffmpeg.get_ffmpeg_exe(), '-v', 'error', '-nostdin',
           '-f', 'f32le', '-ar', str(SR), '-ac', '1', '-i', 'pipe:0',
           '-af', f'atempo={1/speed:.8f}', '-f', 'f32le', 'pipe:1']
    result = subprocess.run(cmd, input=audio.astype('<f4').tobytes(), capture_output=True, check=True)
    return np.frombuffer(result.stdout, dtype='<f4').copy()


def scan(db, audio, speeds, modes, window=20., hop=10., min_hashes=40, bands=('full',), progress=None):
    matcher = Matcher()
    matcher.threshcount = min_hashes
    matcher.window = 2
    matcher.exact_count = True
    matcher.find_time_range = True
    evidence = []
    for start_sample in range(0, len(audio), round(hop*SR)):
        clip = audio[start_sample:start_sample+round(window*SR)]
        if len(clip) < 5*SR:
            break
        start = start_sample/SR
        for speed in speeds:
            for mode in modes:
                if speed == 1 and mode != modes[0]:
                    continue
                corrected = undo_speed(clip, speed, mode)
                for band in bands:
                    if progress:
                        progress(start, len(audio)/SR)
                    wave = corrected if band == 'full' else signal.sosfilt(
                        signal.butter(3, 700, 'highpass', fs=SR, output='sos'), corrected)
                    hashes = fingerprint(wave)
                    if not len(hashes):
                        continue
                    results = matcher.match_hashes(db, hashes)
                    used = set()
                    for idx, count, offset, raw, _, lo, hi in results:
                        span = (hi-lo)*HOP/speed
                        if span < 3 or int(idx) in used:
                            continue
                        used.add(int(idx))
                        qstart, qend = start+lo*HOP/speed, start+hi*HOP/speed
                        evidence.append(dict(track=db.names[int(idx)], window_start=start,
                            mix_start=qstart, mix_end=qend, reference_start=(lo+offset)*HOP,
                            speed=speed, mode=mode, band=band, aligned_hashes=int(count),
                            common_hashes=int(raw), intercept=offset*HOP-speed*start))
        if progress:
            progress(min(start+window,len(audio)/SR), len(audio)/SR)
        else:
            print(f'Scanned {min(start+window,len(audio)/SR):.0f}/{len(audio)/SR:.0f}s', file=sys.stderr)
    return evidence


def summarize(evidence, hop=10.):
    # Cluster coherent reference-time trajectories, not just repeated track names.
    groups = []
    for e in sorted(evidence, key=lambda x: -x['aligned_hashes']):
        group = next((g for g in groups if g[0]['track'] == e['track']
                      and abs(g[0]['speed']-e['speed']) < .005
                      and abs(g[0]['intercept']-e['intercept']) < .6), None)
        if group is None:
            groups.append([e])
        else:
            group.append(e)
    summaries = []
    for group in groups:
        # One vote per window: multiple transforms/bands aren't independent evidence.
        unique = {}
        for e in group:
            if e['window_start'] not in unique or e['aligned_hashes'] > unique[e['window_start']]['aligned_hashes']:
                unique[e['window_start']] = e
        ordered = sorted(unique.values(), key=lambda e:e['window_start'])
        runs = [[]]
        for e in ordered:
            if runs[-1] and e['window_start']-runs[-1][-1]['window_start'] > 2.1*hop:
                runs.append([])
            runs[-1].append(e)
        for run in runs:
            first = min(run,key=lambda e:e['mix_start'])
            summaries.append(dict(track=first['track'],
                status='supported_candidate' if len(run)>=2 else 'single_window_candidate',
                mix_start=min(e['mix_start'] for e in run), mix_end=max(e['mix_end'] for e in run),
                reference_start=first['reference_start'], speed=first['speed'], mode=first['mode'],
                supporting_windows=len(run), best_aligned_hashes=max(e['aligned_hashes'] for e in run)))
    return sorted(summaries,key=lambda x:(x['mix_start'],-x['supporting_windows']))


def report_html(result):
    duration = max(1, result['duration'])
    lines = []
    for row in result['candidates']:
        label = html.escape(Path(row['track']).stem)
        start, end = row['mix_start'], row['mix_end']
        lines.append(f'<section><b>{label}</b> <small>{html.escape(row["status"])} · '
                     f'{start:.1f}–{end:.1f}s · reference {row["reference_start"]:.1f}s · '
                     f'{row["speed"]:.3f}× · {row["supporting_windows"]} windows</small>'
                     f'<div class="lane"><div class="bar" style="margin-left:{100*start/duration:.3f}%;'
                     f'width:{100*(end-start)/duration:.3f}%"></div></div></section>')
    return '<!doctype html><meta charset="utf-8"><title>Techno ID report</title><style>' \
        'body{font:16px system-ui;max-width:1000px;margin:3rem auto;padding:1rem;background:#10151e;color:#eef2f7}' \
        'section{margin:1.5rem 0}small{display:block;color:#b3bdca}.lane{background:#253040;height:12px;margin-top:8px}' \
        '.bar{background:#61c8ba;height:12px}p{line-height:1.5}</style><h1>Mix recognition candidates</h1>' \
        '<p>Local experimental fingerprint matching. These are evidence-backed candidates, not calibrated probabilities. ' \
        'Overlapping windows are correlated. Blank intervals are unidentified, not silent.</p>' + \
        f'<p>Mix: {html.escape(Path(result["query"]).name)} · {duration:.1f}s</p>' + \
        (''.join(lines) or '<p>No candidates met the evidence thresholds.</p>')


def positive(value):
    number = float(value)
    if not math.isfinite(number) or number <= 0:
        raise argparse.ArgumentTypeError('Must be positive and finite')
    return number


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    index = sub.add_parser('index', help='Index local audio; changed files are replaced atomically')
    index.add_argument('folder', type=Path)
    index.add_argument('--db', type=Path, required=True)
    match = sub.add_parser('match', help='Search overlapping mix windows')
    match.add_argument('audio', type=Path)
    match.add_argument('--db', type=Path, required=True)
    match.add_argument('--out', type=Path, required=True)
    match.add_argument('--speeds', default='0.96,0.98,1,1.02,1.04', help='Hypothesised deck playback rates; finer grids cost more')
    match.add_argument('--modes', default='vinyl', help='vinyl,keylock or both, comma separated')
    match.add_argument('--bands', default='full', help='full,high or both, comma separated')
    match.add_argument('--window', type=positive, default=20)
    match.add_argument('--hop', type=positive, default=10)
    match.add_argument('--min-hashes', type=int, default=40)
    args = parser.parse_args()
    if args.command == 'index':
        if not args.folder.is_dir():
            parser.error('Reference folder does not exist')
        files = sorted(p for p in args.folder.rglob('*') if p.suffix.lower() in EXTENSIONS and p.is_file())
        if not files:
            parser.error('No supported audio files found')
        db = Catalogue(args.db, create=True)
        for path in files:
            print(f'{path.name}: {db.add(path)}')
        db.conn.close()
        return
    try:
        speeds = sorted(set(positive(x) for x in args.speeds.split(',')))
    except (ValueError, argparse.ArgumentTypeError):
        parser.error('Speeds must be comma-separated positive finite numbers')
    modes, bands = args.modes.split(','), args.bands.split(',')
    if set(modes)-{'vinyl','keylock'} or set(bands)-{'full','high'}:
        parser.error('Unknown mode or band')
    if min(speeds)<.5 or max(speeds)>2 or args.min_hashes<5 or args.window<5 or args.hop>args.window:
        parser.error('Use speeds 0.5–2, min-hashes >=5, window >=5s, hop <=window')
    db = Catalogue(args.db)
    if not db.names:
        parser.error('Catalogue is empty')
    audio = decode(args.audio)
    evidence = scan(db, audio, speeds, modes, args.window, args.hop, args.min_hashes, bands)
    result = dict(query=str(args.audio.resolve()), duration=len(audio)/SR,
                  settings=dict(speeds=speeds,modes=modes,bands=bands,window=args.window,
                                hop=args.hop,min_hashes=args.min_hashes),
                  candidates=summarize(evidence,args.hop), evidence=evidence,
                  caveat='Experimental candidates, not calibrated confidence or proof of identity')
    args.out.parent.mkdir(parents=True,exist_ok=True)
    args.out.write_text(json.dumps(result,indent=2))
    args.out.with_suffix('.html').write_text(report_html(result))
    print(json.dumps(result['candidates'],indent=2))
    db.conn.close()


if __name__ == '__main__':
    main()
