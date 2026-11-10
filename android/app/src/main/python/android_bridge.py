"""On-device adapter. Java decodes audio; all fingerprint matching stays local."""
import hashlib
import json
import math
from pathlib import Path
import numpy as np
from scipy import signal
from techno_id import Catalogue, SR, scan, summarize


def samples(path, rate):
    rate = int(rate)
    if not 8000 <= rate <= 192000:
        raise ValueError('Unsupported sample rate')
    audio = np.fromfile(path, dtype='<f4')
    if not np.isfinite(audio).all():
        raise ValueError('Audio contains invalid samples')
    if rate != SR:
        divisor = math.gcd(rate, SR)
        audio = signal.resample_poly(audio, SR // divisor, rate // divisor)
    return audio


def library(db_path):
    db = Catalogue(db_path, create=True)
    try:
        return json.dumps([dict(id=r[0], name=Path(r[1]).name, seconds=r[2])
                           for r in db.conn.execute('SELECT id,path,duration FROM tracks ORDER BY path')])
    finally:
        db.conn.close()


def index_pcm(db_path, pcm_path, rate, name):
    audio = samples(pcm_path, rate)
    if len(audio) > 15 * 60 * SR:
        raise ValueError('Reference tracks must be 15 minutes or shorter')
    digest = hashlib.sha256(audio.astype('<f4').tobytes()).hexdigest()
    # Equal display names may represent different tracks. Content-derived parent
    # paths retain their identity while results show the original filename.
    label = str(Path(db_path).parent / 'references' / digest[:16] / Path(name).name)
    db = Catalogue(db_path, create=True)
    try:
        existing = db.conn.execute('SELECT id FROM tracks WHERE digest=?', (digest,)).fetchone()
        if existing:
            return 'Already indexed'
        return db.add_samples(label, audio, digest)
    finally:
        db.conn.close()


def remove_track(db_path, track_id):
    db = Catalogue(db_path, create=True)
    try:
        with db.conn:
            db.conn.execute('DELETE FROM landmarks WHERE track=?', (int(track_id),))
            db.conn.execute('DELETE FROM tracks WHERE id=?', (int(track_id),))
    finally:
        db.conn.close()


def match_pcm(db_path, pcm_path, rate, name, start, deep, callback=None):
    audio = samples(pcm_path, rate)
    if not 5 * SR <= len(audio) <= 181 * SR:
        raise ValueError('Choose an excerpt between 5 and 180 seconds')
    db = Catalogue(db_path)
    try:
        if not db.names:
            raise ValueError('Import reference tracks first')
        def progress(done, total):
            if callback:
                if callback.isCancelled():
                    raise RuntimeError('Cancelled')
                callback.onProgress('Matching %.0f / %.0f seconds' % (done, total))
        speeds = [.96, .97, .98, .99, 1., 1.01, 1.02, 1.03, 1.04] if deep else [1.]
        evidence = scan(db, audio, speeds, ['vinyl'],
                        bands=('full', 'high') if deep else ('full',), progress=progress)
        candidates = summarize(evidence)
        for row in candidates:
            row['track'] = Path(row['track']).name
            row['mix_start'] += float(start)
            row['mix_end'] += float(start)
        return json.dumps(dict(query=name, excerpt_start=float(start),
                               duration=len(audio)/SR, candidates=candidates,
                               mode='pitch-linked speed search' if deep else 'original speed',
                               experimental=True))
    finally:
        db.conn.close()
