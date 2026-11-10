package london.setfinder;

import android.content.Context;
import android.net.Uri;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import com.chaquo.python.Python;
import com.chaquo.python.PyObject;
import java.io.*;
import org.json.*;

/** End-to-end on Android: packaged PCM -> platform decoder -> Python -> SQLite. */
@RunWith(AndroidJUnit4.class)
public class OfflineTest {
    @Test public void testOfflineDemo() throws Exception {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File db=new File(c.getCacheDir(),"test-library.sqlite"),pcm=new File(c.getCacheDir(),"test.f32");
        db.delete();
        PyObject bridge=Python.getInstance().getModule("android_bridge");
        AudioDecoder.Progress progress=new AudioDecoder.Progress(){
            public boolean isCancelled(){return false;}
            public void onProgress(String text){}
        };
        try {
            for(int i=0;i<2;i++) {
                String name="demo-reference-"+i+".wav";File source=copy(c,name);
                AudioDecoder.Result a=AudioDecoder.decode(c,Uri.fromFile(source),pcm,0,901,true,progress);
                assertEquals(11025,a.rate);
                bridge.callAttr("index_pcm",db.getPath(),pcm.getPath(),a.rate,name);
                source.delete();
            }
            File mix=copy(c,"demo-mix.wav");
            AudioDecoder.Result a=AudioDecoder.decode(c,Uri.fromFile(mix),pcm,0,65,false,progress);
            JSONObject result=new JSONObject(bridge.callAttr("match_pcm",db.getPath(),pcm.getPath(),a.rate,"demo",a.startSeconds,true).toString());
            JSONArray rows=result.getJSONArray("candidates");
            java.util.Set<String> found=new java.util.HashSet<>();
            for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).getInt("supporting_windows")>=2)found.add(rows.getJSONObject(i).getString("track"));
            assertTrue(found.contains("demo-reference-0.wav"));assertTrue(found.contains("demo-reference-1.wav"));
            AudioDecoder.Result excerpt=AudioDecoder.decode(c,Uri.fromFile(mix),pcm,20,10,false,progress);
            assertTrue(Math.abs(excerpt.startSeconds-20)<.01);
            assertTrue(Math.abs(pcm.length()/4.0/excerpt.rate-10)<.02);
            mix.delete();
            String[] permissions=c.getPackageManager().getPackageInfo(c.getPackageName(),android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions;
            for(String permission:permissions)assertFalse("App must work offline",permission.equals("android.permission.INTERNET"));
        } finally {db.delete();pcm.delete();}
    }
    private File copy(Context c,String name)throws Exception{
        File f=new File(c.getCacheDir(),"test-"+name);
        try(InputStream in=c.getAssets().open("demo/"+name);OutputStream out=new FileOutputStream(f)){
            byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);
        }return f;
    }
}
