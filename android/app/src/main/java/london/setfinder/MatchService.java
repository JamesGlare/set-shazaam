package london.setfinder;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;

/** One foreground job at a time; work survives Activity recreation. */
public class MatchService extends Service implements AudioDecoder.Progress {
    public static volatile boolean busy=false;
    public static volatile String status="Ready", result="", library="[]";
    public static final AtomicBoolean cancelled=new AtomicBoolean(false);
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private PowerManager.WakeLock wakeLock;
    public static File database(Context c){return new File(c.getFilesDir(),"catalogue.sqlite");}
    public static File resultFile(Context c){return new File(c.getFilesDir(),"last-result.json");}
    public static void refresh(Context c) {
        library=Python.getInstance().getModule("android_bridge").callAttr("library",database(c).getPath()).toString();
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if(busy)return START_NOT_STICKY;
        busy=true;cancelled.set(false);status="Starting…";
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("analysis","Audio analysis",NotificationManager.IMPORTANCE_LOW));
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(1,new Notification.Builder(this,"analysis").setContentTitle("Set Finder")
                .setContentText("Analysing audio on this phone").setSmallIcon(R.drawable.ic_set_finder)
                .setContentIntent(pi).setOngoing(true).build());
        wakeLock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SetFinder:analysis");
        wakeLock.acquire(60*60*1000L);
        worker.execute(()->runJob(intent));
        return START_NOT_STICKY;
    }
    private void runJob(Intent intent) {
        File temp=new File(getCacheDir(),"decoded.f32");
        try {
            PyObject bridge=Python.getInstance().getModule("android_bridge");
            String db=database(this).getPath();
            String action=intent.getStringExtra("job");
            if("demo".equals(action)) {
                for(int i=0;i<2;i++) {
                    if(isCancelled())throw new IllegalStateException("Cancelled");
                    File source=copyDemo("demo-reference-"+i+".wav");
                    try {
                        onProgress("Preparing demo reference "+(i+1)+" / 2");
                        AudioDecoder.Result decoded=AudioDecoder.decode(this,Uri.fromFile(source),temp,0,901,true,this);
                        bridge.callAttr("index_pcm",db,temp.getPath(),decoded.rate,source.getName());
                    } finally {source.delete();temp.delete();}
                }
                File source=copyDemo("demo-mix.wav");
                intent.putExtra("uri",Uri.fromFile(source).toString()).putExtra("name","Generated demo · two overlapping tracks")
                        .putExtra("start",0.0).putExtra("seconds",65.0).putExtra("deep",true);
            }
            if("index".equals(action)) {
                String[] uris=intent.getStringArrayExtra("uris"), names=intent.getStringArrayExtra("names");
                int done=0,failed=0;
                String lastError="";
                for(int i=0;i<uris.length;i++) {
                    if(isCancelled())break;
                    try {
                        onProgress("Reading "+(i+1)+" / "+uris.length+" · "+names[i]);
                        AudioDecoder.Result audio=AudioDecoder.decode(this,Uri.parse(uris[i]),temp,0,901,true,this);
                        if(isCancelled())break;
                        onProgress("Indexing "+(i+1)+" / "+uris.length+" · "+names[i]);
                        bridge.callAttr("index_pcm",db,temp.getPath(),audio.rate,names[i]);
                        done++;
                    } catch(Exception e) {
                        if(isCancelled())break;
                        failed++;lastError=cleanError(e);
                    } finally { temp.delete(); }
                }
                status=isCancelled()?"Stopped · "+done+" tracks processed"
                        :done+" tracks processed"+(failed>0?" · "+failed+" failed: "+lastError:"");
            } else if("remove".equals(action)) {
                bridge.callAttr("remove_track",db,intent.getLongExtra("track",-1));status="Track removed";
            } else {
                result="";resultFile(this).delete();
                String name=intent.getStringExtra("name");
                double start=intent.getDoubleExtra("start",0), seconds=intent.getDoubleExtra("seconds",90);
                if(start<0 || seconds<5 || seconds>180 || !Double.isFinite(start))
                    throw new IllegalArgumentException("Choose 5–180 seconds and a non-negative start time");
                AudioDecoder.Result audio=AudioDecoder.decode(this,Uri.parse(intent.getStringExtra("uri")),temp,start,seconds,false,this);
                if(isCancelled())throw new IllegalStateException("Cancelled");
                onProgress("Finding candidates…");
                String json=bridge.callAttr("match_pcm",db,temp.getPath(),audio.rate,name,
                        audio.startSeconds,intent.getBooleanExtra("deep",true),this).toString();
                if(isCancelled())throw new IllegalStateException("Cancelled");
                File pending=new File(getFilesDir(),"last-result.tmp");
                Files.write(pending.toPath(),json.getBytes(StandardCharsets.UTF_8));
                Files.move(pending.toPath(),resultFile(this).toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                result=json;status="Scan complete";
            }
        } catch(Exception e) {
            status=isCancelled()?"Cancelled":cleanError(e);
        } finally {
            temp.delete();
            new File(getCacheDir(),"demo-mix.wav").delete();
            try{refresh(this);}catch(Exception ignored){}
            busy=false;
            if(wakeLock!=null && wakeLock.isHeld())wakeLock.release();
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
        }
    }
    private File copyDemo(String name) throws Exception {
        File file=new File(getCacheDir(),name);
        try(java.io.InputStream in=getAssets().open("demo/"+name);
            java.io.OutputStream out=new java.io.FileOutputStream(file)) {
            byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
        }
        return file;
    }
    static String cleanError(Exception e) {
        String s=e.getMessage();
        if(s==null)return "Could not process this file";
        int newline=s.indexOf('\n');if(newline>=0)s=s.substring(0,newline);
        return s.length()>220?s.substring(0,220):s;
    }
    @Override public boolean isCancelled(){return cancelled.get();}
    @Override public void onProgress(String text){status=text;}
    @Override public void onDestroy(){cancelled.set(true);worker.shutdown();super.onDestroy();}
}
