package london.setfinder;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CancellationException;

/** Decode only the chosen interval using Android codecs; never load a full set. */
public final class AudioDecoder {
    public interface Progress {
        boolean isCancelled();
        void onProgress(String text);
    }
    public static final class Result {
        public final File file;
        public final int rate;
        public final double startSeconds;
        Result(File f, int r, double s) { file=f; rate=r; startSeconds=s; }
    }
    public static Result decode(Context context, Uri uri, File output,
                                double start, double seconds, boolean reference,
                                Progress progress) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        boolean completed = false;
        try {
            extractor.setDataSource(context, uri, null);
            MediaFormat inputFormat = null;
            for (int i=0; i<extractor.getTrackCount(); i++) {
                MediaFormat f=extractor.getTrackFormat(i);
                String mime=f.getString(MediaFormat.KEY_MIME);
                if (mime!=null && mime.startsWith("audio/")) {
                    inputFormat=f; extractor.selectTrack(i); break;
                }
            }
            if (inputFormat==null) throw new IllegalArgumentException("No supported audio track found");
            if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                long duration=inputFormat.getLong(MediaFormat.KEY_DURATION);
                if (reference && duration>900_000_000L)
                    throw new IllegalArgumentException("Import full reference tracks of up to 15 minutes, not whole sets");
                if (start*1_000_000>=duration)
                    throw new IllegalArgumentException("The start time is beyond the end of this file");
            }
            long startUs=Math.round(start*1_000_000), endUs=startUs+Math.round(seconds*1_000_000);
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            codec=MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
            codec.configure(inputFormat,null,null,0); codec.start();
            int rate=inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels=inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int encoding=AudioFormat.ENCODING_PCM_16BIT;
            boolean inputDone=false, outputDone=false;
            long samples=0, firstSampleUs=-1;
            long lastProgress=0, lastActivity=System.nanoTime();
            MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            try (BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(output),65536)) {
                while (!outputDone) {
                    if (progress.isCancelled()) throw new CancellationException("Cancelled");
                    if (!inputDone) {
                        int ix=codec.dequeueInputBuffer(10000);
                        if (ix>=0) {
                            ByteBuffer b=codec.getInputBuffer(ix);
                            int size=extractor.readSampleData(b,0);
                            long time=extractor.getSampleTime();
                            if (size<0 || time>=endUs) {
                                codec.queueInputBuffer(ix,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone=true;
                            } else {
                                codec.queueInputBuffer(ix,0,size,time,0); extractor.advance();
                            }
                            lastActivity=System.nanoTime();
                        }
                    }
                    int ix=codec.dequeueOutputBuffer(info,10000);
                    if (ix==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat f=codec.getOutputFormat();
                        int newRate=f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        if (samples>0 && newRate!=rate) throw new IllegalArgumentException("Sample rate changed within file");
                        rate=newRate; channels=f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        encoding=f.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                ? f.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                    } else if (ix>=0) {
                        lastActivity=System.nanoTime();
                        ByteBuffer b=codec.getOutputBuffer(ix).order(ByteOrder.LITTLE_ENDIAN);
                        b.position(info.offset); b.limit(info.offset+info.size);
                        int bytes=encoding==AudioFormat.ENCODING_PCM_FLOAT || encoding==AudioFormat.ENCODING_PCM_32BIT ? 4
                                : encoding==AudioFormat.ENCODING_PCM_24BIT_PACKED ? 3
                                : encoding==AudioFormat.ENCODING_PCM_16BIT ? 2
                                : encoding==AudioFormat.ENCODING_PCM_8BIT ? 1 : 0;
                        if (bytes==0 || channels<1 || channels>32 || rate<8000 || rate>192000)
                            throw new IllegalArgumentException("Unsupported decoded audio format");
                        int frames=b.remaining()/(bytes*channels);
                        ByteBuffer mono=ByteBuffer.allocate(frames*4).order(ByteOrder.LITTLE_ENDIAN);
                        for (int f=0; f<frames; f++) {
                            long time=info.presentationTimeUs+Math.round(f*1_000_000.0/rate);
                            float value=0;
                            for (int c=0;c<channels;c++) {
                                if (encoding==AudioFormat.ENCODING_PCM_FLOAT) value+=b.getFloat();
                                else if (encoding==AudioFormat.ENCODING_PCM_32BIT) value+=b.getInt()/2147483648f;
                                else if (encoding==AudioFormat.ENCODING_PCM_24BIT_PACKED) {
                                    int x=(b.get()&255)|((b.get()&255)<<8)|(b.get()<<16);
                                    value+=x/8388608f;
                                } else if (encoding==AudioFormat.ENCODING_PCM_8BIT) value+=((b.get()&255)-128)/128f;
                                else value+=b.getShort()/32768f;
                            }
                            if(time>=startUs && time<endUs) {
                                if(firstSampleUs<0)firstSampleUs=time;
                                mono.putFloat(value/channels);samples++;
                            }
                        }
                        out.write(mono.array(),0,mono.position());
                        boolean eos=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;
                        codec.releaseOutputBuffer(ix,false);
                        outputDone=eos;
                        if(reference && samples>900L*rate)throw new IllegalArgumentException("Reference exceeds 15 minutes");
                        if(samples*4>400_000_000L)throw new IllegalArgumentException("Audio is too large; use a lower sample rate");
                        long now=System.currentTimeMillis();
                        if(now-lastProgress>700) {
                            progress.onProgress("Reading audio · "+Math.round(samples/(double)rate)+" seconds"); lastProgress=now;
                        }
                    }
                    if(System.nanoTime()-lastActivity>30_000_000_000L)throw new IllegalStateException("Audio decoder timed out");
                }
            }
            if(samples<rate)throw new IllegalArgumentException("No usable audio at this position");
            completed=true;
            return new Result(output,rate,firstSampleUs/1_000_000.0);
        } finally {
            if(codec!=null) { try{codec.stop();}catch(Exception ignored){} codec.release(); }
            extractor.release();
            if(!completed)output.delete();
        }
    }
}
