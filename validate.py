"""Deterministic synthetic integration benchmark; NOT evidence of real DJ-set accuracy."""
import json
from pathlib import Path
import tempfile
import time

import numpy as np
from scipy import signal
from scipy.io import wavfile

from techno_id import Catalogue, SR, decode, scan, summarize, report_html, undo_speed


def track(seed, seconds=60):
    rng = np.random.default_rng(seed)
    audio = np.zeros(int(seconds*SR))
    # Musical, transient-rich signals; shared kick structure plus distinctive notes.
    for start in np.arange(0, seconds-.3, .43):
        t = np.arange(int(.25*SR))/SR
        kick = .3*np.sin(2*np.pi*(48*t+9*(1-np.exp(-25*t))))*np.exp(-20*t)
        index = int(start*SR)
        audio[index:index+len(t)] += kick
    for start in np.arange(0, seconds-.5, .215):
        t = np.arange(int(.4*SR))/SR
        f = rng.choice([220,247,294,330,392,440,494,587,659,784,880,988]) * (1+seed*.013)
        note = sum(np.sin(2*np.pi*f*k*t+rng.uniform(0,6.28))/k for k in (1,2,3,4))
        note *= .22*np.exp(-rng.uniform(6,16)*t)*np.minimum(t/.008,1)
        index = int(start*SR)
        audio[index:index+len(t)] += note
    audio += .006*rng.normal(size=len(audio))
    return (audio/max(1,np.max(np.abs(audio)))).astype(np.float32)


def write(path, audio):
    wavfile.write(path, SR, np.asarray(audio,dtype=np.float32))


def names(candidates, supported=True):
    return {Path(c['track']).stem for c in candidates
            if not supported or c['status']=='supported_candidate'}


def main():
    begin = time.monotonic()
    output = Path(__file__).parent/'data'/'validation'
    output.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        root=Path(tmp)
        refs=[track(i) for i in range(6)]
        db=Catalogue(root/'index.sqlite',create=True)
        for i,audio in enumerate(refs):
            p=root/f'reference-{i}.wav'
            write(p,audio)
            db.add(p)
        assert db.add(root/'reference-0.wav')=='unchanged'
        db.refresh()
        # Both originals are changed by deck-speed resampling, then mixed.
        a=signal.resample_poly(refs[0][8*SR:48*SR],25,26)  # 1.04x deck speed
        b=signal.resample_poly(refs[1][6*SR:46*SR],50,49)  # 0.98x deck speed
        mixture=np.zeros(65*SR,dtype=np.float32)
        mixture[:len(a)]+=.65*a
        mixture[20*SR:20*SR+len(b)]+=.65*b
        write(root/'mix.wav',mixture)
        audio=decode(root/'mix.wav')  # exercise FFmpeg path, not just arrays
        ev=scan(db,audio,[.98,1.,1.04],['vinyl'])
        candidates=summarize(ev)
        found=names(candidates)
        assert {'reference-0','reference-1'} <= found, candidates
        assert not found-{'reference-0','reference-1'}, candidates
        overlap={Path(e['track']).stem for e in ev if e['window_start']==20}
        assert {'reference-0','reference-1'} <= overlap, overlap
        for c in candidates:
            name=Path(c['track']).stem
            expected=(8+1.04*c['mix_start']) if name=='reference-0' else (6+.98*(c['mix_start']-20))
            assert abs(c['reference_start']-expected)<.3,(c,expected)
        baseline=summarize(scan(db,audio,[1.],['vinyl']))
        unknown=[]
        for seed in (100,101,102):
            unknown += scan(db,track(seed,25),[.98,1.,1.04],['vinyl'])
        assert not summarize(unknown), summarize(unknown)
        assert not scan(db,np.zeros(10*SR),[1.],['vinyl'])
        # Independent key-locked time stretching, then inverse query transform.
        locked=undo_speed(refs[2][5*SR:40*SR],1/1.04,'keylock')
        key=summarize(scan(db,locked,[1.,1.04],['keylock']))
        assert 'reference-2' in names(key),key
        # EQ-reduced excerpt.
        eq=signal.sosfilt(signal.butter(3,700,'highpass',fs=SR,output='sos'),refs[3][10*SR:40*SR])
        equalized=summarize(scan(db,eq,[1.],['vinyl'],bands=('full','high')))
        assert 'reference-3' in names(equalized),equalized
        # Changed-file indexing replaces, rather than appends, the same reference.
        write(root/'reference-5.wav',refs[4])
        db.add(root/'reference-5.wav')
        assert db.conn.execute('SELECT COUNT(*) FROM tracks').fetchone()[0]==6
        # Demo is entirely generated audio and can be shared.
        write(output/'synthetic-mix.wav',mixture)
        for row in candidates:
            row['track']=Path(row['track']).name
        result=dict(query='synthetic-mix.wav',duration=len(mixture)/SR,candidates=candidates)
        (output/'report.html').write_text(report_html(result))
        result.update(synthetic_only=True,reference_tracks=6,
                      checks=['two overlapping IDs','reference timestamp mapping','three unknown tracks abstained',
                              'silence abstained','key-lock transform','high-pass EQ','idempotent and changed-file indexing'],
                      fixed_speed_baseline_supported=sorted(names(baseline)),
                      transform_search_supported=sorted(found),seconds=time.monotonic()-begin)
        (output/'results.json').write_text(json.dumps(result,indent=2))
        print(json.dumps(result,indent=2))
        db.conn.close()


if __name__=='__main__':
    main()
