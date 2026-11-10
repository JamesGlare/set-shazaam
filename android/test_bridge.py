"""Exercise the Android Python adapter against real PCM files, without Android."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parent.parent
sys.path[:0]=[str(ROOT),str(ROOT/'android/app/src/main/python')]
import numpy as np
from scipy import signal
from scipy.io import wavfile
import android_bridge as bridge
from techno_id import Catalogue, SR
from validate import track


class BridgeTest(unittest.TestCase):
    def test_generated_demo_resampling_unknown_cancel_and_delete(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);db=str(root/'library.sqlite');pcm=root/'audio.f32'
            assets=ROOT/'android/app/src/main/assets/demo'
            self.assertEqual(json.loads(bridge.library(db)),[])
            for i in range(2):
                rate,audio=wavfile.read(assets/f'demo-reference-{i}.wav')
                audio=(audio.astype(np.float32)/32768)
                # Exercise the same resampling path used by Android codecs.
                high=signal.resample_poly(audio,640,147).astype('<f4');high.tofile(pcm)
                bridge.index_pcm(db,str(pcm),48000,f'demo-reference-{i}.wav')
                self.assertEqual(bridge.index_pcm(db,str(pcm),48000,'renamed.wav'),'Already indexed')
            self.assertEqual(len(json.loads(bridge.library(db))),2)
            # Catalogue remains readable by the desktop matcher.
            desktop=Catalogue(db);self.assertEqual(len(desktop.names),2);desktop.conn.close()
            rate,audio=wavfile.read(assets/'demo-mix.wav');(audio.astype('<f4')/32768).tofile(pcm)
            result=json.loads(bridge.match_pcm(db,str(pcm),rate,'demo',120,True))
            found={c['track'] for c in result['candidates'] if c['supporting_windows']>=2}
            self.assertEqual(found,{'demo-reference-0.wav','demo-reference-1.wav'})
            for c in result['candidates']:
                self.assertGreaterEqual(c['mix_start'],120)
                if c['supporting_windows'] < 2:
                    continue  # Weak leads are not certified alignments.
                local=c['mix_start']-120
                expected=8+1.04*local if c['track']=='demo-reference-0.wav' else 6+.98*(local-20)
                self.assertLess(abs(c['reference_start']-expected),.4)
            class Cancel:
                def isCancelled(self):return True
                def onProgress(self,text):pass
            with self.assertRaisesRegex(RuntimeError,'Cancelled'):
                bridge.match_pcm(db,str(pcm),rate,'demo',0,False,Cancel())
            track(101,20).astype('<f4').tofile(pcm)
            unknown=json.loads(bridge.match_pcm(db,str(pcm),SR,'unknown',0,False))
            self.assertEqual(unknown['candidates'],[])
            rows=json.loads(bridge.library(db));bridge.remove_track(db,rows[0]['id'])
            self.assertEqual(len(json.loads(bridge.library(db))),1)


if __name__=='__main__':unittest.main()
