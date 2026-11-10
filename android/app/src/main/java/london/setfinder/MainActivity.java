package london.setfinder;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.nio.file.Files;
import java.util.*;
import org.json.*;

public class MainActivity extends Activity {
    static final int BG=Color.rgb(16,21,30), PANEL=Color.rgb(27,35,47), TEXT=Color.rgb(238,243,248), MUTED=Color.rgb(174,185,201), ACCENT=Color.rgb(114,224,196);
    private final Handler handler=new Handler(Looper.getMainLooper());
    private LinearLayout body,results;
    private TextView count,chosen,status;
    private EditText start,length;
    private Switch deep;
    private ProgressBar progress;
    private final ArrayList<Button> controls=new ArrayList<>();
    private Button cancel,export;
    private String selectedUri="", selectedName="", rendered="";
    private boolean showWeak=false;
    private final Runnable refresh=new Runnable(){public void run(){update();handler.postDelayed(this,500);}};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        android.content.SharedPreferences prefs=getPreferences(0);
        selectedUri=prefs.getString("uri","");selectedName=prefs.getString("name","");
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(22),dp(20),dp(22),dp(24));
        scroll.addView(body);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;
        });
        label(body,"SET FINDER",12,ACCENT,true);
        label(body,"Find the tracks\nin your set.",32,TEXT,true);
        label(body,"Your reference library. Your phone. No uploads.",14,MUTED,false);

        LinearLayout refs=card();
        label(refs,"01  /  REFERENCE LIBRARY",12,ACCENT,true);
        count=label(refs,"Loading library…",20,TEXT,true);
        label(refs,"Add full tracks you own. Only their fingerprints and filenames are kept; imported audio is temporary.",14,MUTED,false);
        button(refs,"Add reference tracks",v->pick(true));
        button(refs,"Manage library",v->manage());

        LinearLayout query=card();
        label(query,"02  /  DJ SET",12,ACCENT,true);
        chosen=label(query,selectedName.isEmpty()?"Choose a recording":selectedName,19,TEXT,true);
        button(query,"Choose a set or excerpt",v->pick(false));
        LinearLayout fields=new LinearLayout(this);query.addView(fields);
        LinearLayout left=new LinearLayout(this),right=new LinearLayout(this);left.setOrientation(1);right.setOrientation(1);
        fields.addView(left,new LinearLayout.LayoutParams(0,-2,1));fields.addView(right,new LinearLayout.LayoutParams(0,-2,1));
        label(left,"Start (mm:ss)",12,MUTED,false);start=field(left,prefs.getString("start","0:00"),false);
        label(right,"Length (seconds)",12,MUTED,false);length=field(right,prefs.getString("length","90"),true);
        deep=new Switch(this);deep.setText("Search tempo changes + EQ");deep.setTextColor(TEXT);deep.setChecked(prefs.getBoolean("deep",true));deep.setPadding(0,dp(12),0,dp(12));query.addView(deep);
        label(query,"5–180 seconds per scan. Tempo search covers ±4% in 1% steps, with pitch changing alongside tempo. Key-lock is not supported in this preview.",12,MUTED,false);
        button(query,"Find candidates",v->scan());
        button(query,"Try generated-audio demo",v->launch(new Intent(this,MatchService.class).putExtra("job","demo")));
        status=label(query,"Ready",14,ACCENT,false);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);query.addView(progress);progress.setVisibility(View.GONE);
        cancel=button(query,"Cancel",v->{MatchService.cancelled.set(true);status.setText("Stopping after the current operation…");});controls.remove(cancel);cancel.setVisibility(View.GONE);

        results=card();label(results,"03  /  RESULTS",12,ACCENT,true);label(results,"Candidate tracks will appear here.",14,MUTED,false);
        export=button(body,"Export latest results (JSON)",v->export());export.setVisibility(View.GONE);
        label(body,"Experimental matching, not a commercial recognition catalogue. Unknown tracks stay unknown; matches are candidates, not guaranteed IDs.",12,MUTED,false);
        TextView about=label(body,"About & open-source credits",13,ACCENT,false);about.setPadding(0,dp(14),0,dp(12));about.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Set Finder · preview 0.1")
                .setMessage("Offline Android packaging of Techno ID. Fingerprints and matching use audfprint by Dan Ellis (MIT). Python runtime: Chaquopy. Numeric processing: NumPy and SciPy.\n\nNo Internet or microphone permission. Android's document picker grants access only to files you choose; cloud-backed providers may download those files themselves.\n\nSynthetic checks do not establish real-techno accuracy. Full third-party notices are included in the source package and app assets.").setPositiveButton("OK",null).show());
        new Thread(()->{
            try {
                if(!MatchService.busy)MatchService.refresh(this);
                if(MatchService.result.isEmpty() && MatchService.resultFile(this).exists())
                    MatchService.result=new String(Files.readAllBytes(MatchService.resultFile(this).toPath()),java.nio.charset.StandardCharsets.UTF_8);
            }catch(Exception e){MatchService.status="Could not open saved library: "+MatchService.cleanError(e);}
        },"library-loader").start();
    }
    @Override public void onResume(){super.onResume();handler.post(refresh);}
    @Override public void onPause(){
        handler.removeCallbacks(refresh);
        getPreferences(0).edit().putString("uri",selectedUri).putString("name",selectedName)
                .putString("start",start.getText().toString()).putString("length",length.getText().toString()).putBoolean("deep",deep.isChecked()).apply();
        super.onPause();
    }
    private void update(){
        boolean busy=MatchService.busy;
        for(Button b:controls)b.setEnabled(!busy);
        start.setEnabled(!busy);length.setEnabled(!busy);deep.setEnabled(!busy);
        status.setText(MatchService.status);progress.setVisibility(busy?View.VISIBLE:View.GONE);cancel.setVisibility(busy?View.VISIBLE:View.GONE);
        try{int n=new JSONArray(MatchService.library).length();count.setText(n+" reference track"+(n==1?"":"s"));}catch(Exception ignored){}
        if(!rendered.equals(MatchService.result)) {rendered=MatchService.result;renderResults();}
        export.setVisibility(MatchService.result.isEmpty()?View.GONE:View.VISIBLE);
    }
    private void pick(boolean refs){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE,refs).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i,refs?10:11);
    }
    private String name(Uri uri){
        try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if(c!=null && c.moveToFirst())return c.getString(0);
        }catch(Exception ignored){}
        return "Audio recording";
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK || data==null)return;
        if(request==12){
            try(java.io.OutputStream out=getContentResolver().openOutputStream(data.getData())){
                out.write(MatchService.result.getBytes(java.nio.charset.StandardCharsets.UTF_8));toast("Results exported");
            }catch(Exception e){toast("Could not export results");}return;
        }
        ArrayList<String> uris=new ArrayList<>(),names=new ArrayList<>();
        ClipData clip=data.getClipData();int size=clip==null?1:clip.getItemCount();
        for(int n=0;n<size;n++) {
            Uri uri=clip==null?data.getData():clip.getItemAt(n).getUri();if(uri==null)continue;
            try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}
            uris.add(uri.toString());names.add(name(uri));
        }
        if(uris.isEmpty())return;
        if(request==10)launch(new Intent(this,MatchService.class).putExtra("job","index")
                .putExtra("uris",uris.toArray(new String[0])).putExtra("names",names.toArray(new String[0])));
        else {selectedUri=uris.get(0);selectedName=names.get(0);chosen.setText(selectedName);}
    }
    private void scan(){
        if(selectedUri.isEmpty()){toast("Choose a set first");return;}
        try {
            if(new JSONArray(MatchService.library).length()==0){toast("Add reference tracks first, or try the demo");return;}
            String[] parts=start.getText().toString().trim().split(":");double offset;
            if(parts.length==1)offset=Double.parseDouble(parts[0]);
            else if(parts.length==2){double sec=Double.parseDouble(parts[1]);if(sec<0||sec>=60)throw new IllegalArgumentException();offset=60*Double.parseDouble(parts[0])+sec;}
            else throw new IllegalArgumentException();
            double seconds=Double.parseDouble(length.getText().toString());
            if(!Double.isFinite(offset)||offset<0||seconds<5||seconds>180||!Double.isFinite(seconds))throw new IllegalArgumentException();
            launch(new Intent(this,MatchService.class).putExtra("job","match").putExtra("uri",selectedUri)
                    .putExtra("name",selectedName).putExtra("start",offset).putExtra("seconds",seconds).putExtra("deep",deep.isChecked()));
        }catch(Exception e){toast("Use a start time like 12:30 and a length of 5–180 seconds");}
    }
    private void launch(Intent job){
        if(MatchService.busy)return;
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},20);
        try{startForegroundService(job);}catch(Exception e){toast("Could not start analysis: "+MatchService.cleanError(e));}
    }
    private void manage(){
        try {
            JSONArray tracks=new JSONArray(MatchService.library);if(tracks.length()==0){toast("No references yet");return;}
            String[] labels=new String[tracks.length()];for(int i=0;i<labels.length;i++)labels[i]=tracks.getJSONObject(i).getString("name");
            new AlertDialog.Builder(this).setTitle("Remove a reference track").setItems(labels,(d,ix)->{
                try{long id=tracks.getJSONObject(ix).getLong("id");new AlertDialog.Builder(this).setTitle("Remove "+labels[ix]+"?")
                        .setMessage("Only its local fingerprints will be removed. The original audio is untouched.")
                        .setNegativeButton("Keep",null).setPositiveButton("Remove",(a,b)->launch(new Intent(this,MatchService.class).putExtra("job","remove").putExtra("track",id))).show();}catch(Exception ignored){}
            }).setNegativeButton("Close",null).show();
        }catch(Exception e){toast("Could not read library");}
    }
    private void renderResults(){
        results.removeAllViews();label(results,"03  /  RESULTS",12,ACCENT,true);
        if(rendered.isEmpty()){label(results,"Candidate tracks will appear here.",14,MUTED,false);return;}
        try {
            JSONObject report=new JSONObject(rendered);JSONArray rows=report.getJSONArray("candidates");
            label(results,report.getString("query"),18,TEXT,true);
            double begin=report.getDouble("excerpt_start"),duration=report.getDouble("duration");
            label(results,time(begin)+"–"+time(begin+duration)+" · "+rows.length()+" candidate passages",13,MUTED,false);
            if(rows.length()==0)label(results,"No match met the evidence thresholds. The track may be missing from your library, or the mix may be too transformed.",15,TEXT,false);
            int weak=0;
            for(int i=0;i<rows.length();i++) {
                JSONObject row=rows.getJSONObject(i);
                int windows=row.getInt("supporting_windows");
                if(windows<2){weak++;if(!showWeak)continue;}
                TextView name=label(results,row.getString("track"),17,TEXT,true);name.setPadding(0,dp(16),0,0);
                label(results,windows>=2?"SUPPORTED CANDIDATE":"SINGLE-WINDOW CANDIDATE",10,ACCENT,true);
                double a=row.getDouble("mix_start"),b=row.getDouble("mix_end");
                label(results,time(a)+"–"+time(b)+" in set · "+time(row.getDouble("reference_start"))+" in reference",13,MUTED,false);
                label(results,String.format(Locale.UK,"%.2f× speed · %d supporting windows",row.getDouble("speed"),windows),12,MUTED,false);
                results.addView(new Timeline(this,(a-begin)/duration,(b-begin)/duration),new LinearLayout.LayoutParams(-1,dp(16)));
            }
            if(weak>0){
                if(weak==rows.length()&&!showWeak)label(results,"No candidates were supported across multiple windows.",15,TEXT,false);
                TextView toggle=label(results,showWeak?"Hide weaker leads":"Show "+weak+" weaker single-window leads",13,ACCENT,true);
                toggle.setPadding(0,dp(16),0,dp(12));toggle.setOnClickListener(v->{showWeak=!showWeak;renderResults();});
            }
            label(results,"Repeated windows share audio and are not independent proof. Blank intervals are unidentified, not silent.",12,MUTED,false);
        }catch(Exception e){label(results,"Could not read this report.",14,MUTED,false);}
    }
    private void export(){startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"set-finder-results.json"),12);}
    private LinearLayout card(){LinearLayout v=new LinearLayout(this);v.setOrientation(1);v.setPadding(dp(18),dp(18),dp(18),dp(18));
        GradientDrawable bg=new GradientDrawable();bg.setColor(PANEL);bg.setCornerRadius(dp(18));v.setBackground(bg);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(20);body.addView(v,p);return v;}
    private TextView label(LinearLayout parent,String text,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(4),0,dp(4));t.setLineSpacing(dp(2),1);if(bold)t.setTypeface(null,Typeface.BOLD);parent.addView(t);return t;}
    private Button button(LinearLayout parent,String title,View.OnClickListener click){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextColor(ACCENT);b.setOnClickListener(click);parent.addView(b,new LinearLayout.LayoutParams(-1,dp(54)));controls.add(b);return b;}
    private EditText field(LinearLayout parent,String value,boolean numeric){EditText e=new EditText(this);e.setText(value);e.setTextSize(18);e.setTextColor(TEXT);e.setSingleLine(true);e.setInputType(numeric?InputType.TYPE_CLASS_NUMBER:InputType.TYPE_CLASS_DATETIME|InputType.TYPE_DATETIME_VARIATION_TIME);parent.addView(e);return e;}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private static String time(double s){int n=(int)Math.round(s);return String.format(Locale.UK,"%d:%02d",n/60,n%60);}
    private static class Timeline extends View {
        final float a,b;final Paint p=new Paint(3);
        Timeline(Context c,double a,double b){super(c);this.a=(float)Math.max(0,Math.min(1,a));this.b=(float)Math.max(0,Math.min(1,b));}
        @Override protected void onDraw(Canvas c){p.setColor(Color.rgb(48,61,78));c.drawRoundRect(0,5,getWidth(),getHeight()-5,5,5,p);p.setColor(ACCENT);c.drawRoundRect(a*getWidth(),5,b*getWidth(),getHeight()-5,5,5,p);}
    }
}
