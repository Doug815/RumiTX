package com.rumitx.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** RumiTX 2030 offline assistant, completely separate from the radio session. */
public final class RumiAiActivity extends Activity {
    private static final int PICK_RUNTIME=102, PICK_MODEL=103;
    private static final String MODEL_SHA="a9d99ed72848f0c26d2c380490108102aa19b47d565b192b7719e0d1f8832ba1";
    private static final String SYSTEM="You are RumiTX, a helpful radio assistant. Answer directly and concisely. Never output JSON tool calls or function definitions. For radio commands like 'switch to channel 5', confirm the action briefly. For questions, give a direct answer.";
    private static final Pattern CHANNEL=Pattern.compile("(?i)^\\s*(?:please\\s+)?(?:switch|change|go|tune|set)(?:\\s+(?:the\\s+)?radio)?\\s+to\\s+channel\\s+(\\d+)\\s*[.!]?\\s*$");
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler();
    private TextView status, transcript;
    private EditText message;
    private Button run, send, packageButton, modelButton;
    private boolean busy;
    private String conversation="";
    private static int dp(Activity a,int d){return Math.round(d*a.getResources().getDisplayMetrics().density);}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(dp(this,8),dp(this,7),dp(this,8),dp(this,7));return t;}
    private Button button(String label){Button b=new Button(this);b.setText(label);b.setTextAllCaps(false);b.setTextSize(12);return b;}
    private LinearLayout.LayoutParams full(){return new LinearLayout.LayoutParams(-1,-2);}
    private void post(String s){main.post(()->{transcript.append(s+"\n\n");});}
    private void info(String s){main.post(()->status.setText(s));}
    private File runtime(){return new File(getFilesDir(),"llama-server");}
    private File omp(){return new File(getFilesDir(),"libomp.so");}
    private File model(){return new File(getFilesDir(),"v3_model.gguf");}
    private boolean ready(){return runtime().isFile() && runtime().canExecute() && omp().isFile() && model().isFile() && model().length()==1107409344L;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(7,13,23));
        LinearLayout outer=new LinearLayout(this);outer.setOrientation(LinearLayout.VERTICAL);outer.setPadding(dp(this,12),dp(this,8),dp(this,12),dp(this,10));outer.setBackgroundColor(Color.rgb(7,13,23));
        setContentView(outer);
        TextView title=text("RUMITX  /  2030  /  V3 OFFLINE AI",18,0xff00e6ff);outer.addView(title,full());
        status=text("Checking local AI runtime…",12,0xffffc76b);outer.addView(status,full());
        TextView help=text("First use: import v3-phone-package.tar.gz, then Qwen3-1.7B.Q4_K_M.gguf from Downloads or Drive. Both remain private on this phone; no cloud inference.",11,0xffa9bece);outer.addView(help,full());
        LinearLayout controls=new LinearLayout(this);controls.setOrientation(LinearLayout.HORIZONTAL);
        packageButton=button("1 · Runtime");modelButton=button("2 · Model");run=button("Start AI");
        for(Button b:new Button[]{packageButton,modelButton,run})controls.addView(b,new LinearLayout.LayoutParams(0,dp(this,48),1));
        outer.addView(controls,full());
        packageButton.setOnClickListener(v->pick(PICK_RUNTIME,"application/gzip"));
        modelButton.setOnClickListener(v->pick(PICK_MODEL,"*/*"));
        run.setOnClickListener(v->{if(!ready()){info("Import both files first.");return;}startForegroundService(new Intent(this,RumiAiService.class).setAction(RumiAiService.START));info("Starting the local V3 engine…");});
        ScrollView scroll=new ScrollView(this);transcript=text("Offline radio assistant · ready for setup\n\n",15,0xffebf6fc);transcript.setTextIsSelectable(true);scroll.addView(transcript);
        outer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        message=new EditText(this);message.setTextColor(Color.WHITE);message.setHintTextColor(0xff829caf);message.setHint("Ask RumiTX…");message.setSingleLine(false);message.setMinLines(1);message.setMaxLines(3);message.setTextSize(15);
        row.addView(message,new LinearLayout.LayoutParams(0,-2,1));send=button("Send");row.addView(send,new LinearLayout.LayoutParams(dp(this,73),dp(this,54)));outer.addView(row,full());
        send.setOnClickListener(v->ask());
        main.postDelayed(poll,400);
    }
    private final Runnable poll=new Runnable(){public void run(){if(isFinishing())return;if(!busy){String state=RumiAiService.status();status.setText(ready()?"V3 runtime installed · "+state:"Setup needed · "+state);send.setEnabled(ready());run.setEnabled(ready());}main.postDelayed(this,1800);}};
    private void pick(int request,String type){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(type);if(request==PICK_RUNTIME)i.setType("*/*");startActivityForResult(i,request);}
    @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();setBusy(true);io.execute(()->{
        try{if(req==PICK_RUNTIME)extractRuntime(uri);else if(req==PICK_MODEL)installModel(uri);info("Import successful. "+(ready()?"Start AI to run the model.":"Import the other file."));}
        catch(Exception e){info("Import failed: "+e.getMessage());}
        finally{main.post(()->setBusy(false));}
    });}
    private void setBusy(boolean v){busy=v;packageButton.setEnabled(!v);modelButton.setEnabled(!v);run.setEnabled(!v&&ready());send.setEnabled(!v&&ready());if(v)status.setText("Importing and verifying… keep RumiTX open.");}
    private static long tarOctal(byte[] hdr,int start,int width){long n=0;for(int i=start;i<start+width;i++){int c=hdr[i]&255;if(c>='0'&&c<='7')n=(n<<3)+(c-'0');else if(c!=0&&c!=' ')throw new IllegalArgumentException("Invalid TAR length");}return n;}
    private static String tarName(byte[] hdr,int start,int len){int end=start;while(end<start+len&&hdr[end]!=0)end++;return new String(hdr,start,end-start,java.nio.charset.StandardCharsets.US_ASCII);}
    private static void exact(InputStream in,byte[] b,int size)throws IOException{int offset=0;while(offset<size){int r=in.read(b,offset,size-offset);if(r<0)throw new EOFException("Truncated TAR");offset+=r;}}
    private static void skip(InputStream in,long length)throws IOException{byte[] buf=new byte[65536];while(length>0){int r=in.read(buf,0,(int)Math.min(length,buf.length));if(r<0)throw new EOFException("Truncated TAR entry");length-=r;}}
    private static void copyN(InputStream in,OutputStream out,long length)throws IOException{byte[] buf=new byte[65536];while(length>0){int r=in.read(buf,0,(int)Math.min(length,buf.length));if(r<0)throw new EOFException("Truncated file");out.write(buf,0,r);length-=r;}}
    private void extractRuntime(Uri uri)throws Exception{
        boolean gotServer=false,gotOmp=false;
        File serverTemp=new File(getFilesDir(),"llama-server.partial"),ompTemp=new File(getFilesDir(),"libomp.so.partial");
        try(InputStream source=getContentResolver().openInputStream(uri);GZIPInputStream gzip=new GZIPInputStream(new BufferedInputStream(source,65536))){
            byte[] header=new byte[512];
            while(true){
                int b=gzip.read();if(b<0)break;header[0]=(byte)b;exact(gzip,header,511);
                boolean empty=true;for(byte h:header)if(h!=0){empty=false;break;}if(empty)break;
                long size=tarOctal(header,124,12);
                if(size<0||size>300000000L)throw new IOException("Invalid entry size");
                String name=tarName(header,0,100);
                File dest=name.endsWith("/bin/llama-server")?serverTemp:name.endsWith("/bin/libomp.so")?ompTemp:null;
                if(dest!=null&&header[156]=='0'){
                    try(OutputStream out=new BufferedOutputStream(new FileOutputStream(dest),65536)){copyN(gzip,out,size);}
                    if(dest==serverTemp)gotServer=true;else gotOmp=true;
                }else skip(gzip,size);
                skip(gzip,(512-(size%512))%512);
            }
        }catch(Exception e){serverTemp.delete();ompTemp.delete();throw e;}
        if(!gotServer||!gotOmp||serverTemp.length()<100000000||ompTemp.length()<100000){serverTemp.delete();ompTemp.delete();throw new IOException("Required S80 binaries not found in archive");}
        if(runtime().exists()&&!runtime().delete())throw new IOException("Cannot replace server");
        if(omp().exists()&&!omp().delete())throw new IOException("Cannot replace OpenMP runtime");
        if(!serverTemp.renameTo(runtime())||!ompTemp.renameTo(omp()))throw new IOException("Cannot finalize binaries");
        if(!runtime().setExecutable(true,true))throw new IOException("Cannot execute runtime");
        info("S80-specific native binaries installed.");
    }
    private void installModel(Uri uri)throws Exception{
        if(getFilesDir().getUsableSpace()<1250000000L)throw new IOException("Need at least 1.25 GB free storage");
        File part=new File(getFilesDir(),"v3_model.gguf.partial");MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;
        try(InputStream in=new BufferedInputStream(getContentResolver().openInputStream(uri),131072);OutputStream out=new BufferedOutputStream(new FileOutputStream(part),131072)){
            byte[] buffer=new byte[131072];int n;
            while((n=in.read(buffer))!=-1){total+=n;if(total>1200000000L)throw new IOException("Model too large");digest.update(buffer,0,n);out.write(buffer,0,n);}
        }catch(Exception e){part.delete();throw e;}
        StringBuilder sha=new StringBuilder();for(byte b:digest.digest())sha.append(String.format(Locale.US,"%02x",b&0xff));
        if(total!=1107409344L||!MODEL_SHA.equals(sha.toString())){part.delete();throw new IOException("Model SHA-256 / size mismatch; wrong GGUF");}
        if(model().exists()&&!model().delete())throw new IOException("Cannot replace model");
        if(!part.renameTo(model()))throw new IOException("Cannot finalize model");
        info("Qwen3 V3 model verified with SHA-256.");
    }
    private void ask(){
        String q=message.getText().toString().trim();if(q.length()==0||busy)return;message.setText("");
        post("YOU  ·  "+q);send.setEnabled(false);
        io.execute(()->{
            String response;
            try{
                Matcher command=CHANNEL.matcher(q);
                if(command.matches())response=changeChannel(Integer.parseInt(command.group(1)));
                else response=complete(q);
            }catch(Exception e){response="V3 unavailable: "+e.getMessage()+". Check that the AI service says READY.";}
            post("RUMITX  ·  "+response);main.post(()->send.setEnabled(ready()));
        });
    }
    private String changeChannel(int index){
        if(index<1||index>999)return "Channel number is outside the supported range.";
        if(VoiceTxBridge.isTransmitting())return "Cannot change channels during voice transmission.";
        if(!RadioController.getInstance().isConnected())return "Radio is not connected.";
        try(ChannelDatabase db=new ChannelDatabase(this)){
            List<Channel> channels=db.getAllChannels();if(index>channels.size())return "Channel "+index+" is not in your saved channel bank.";
            Channel c=channels.get(index-1);String blocked=com.rumitx.app.repeaters.RepeaterProfiles.blockedChannel(c);
            if(blocked!=null)return "Channel change blocked: "+blocked;
            if(!RadioController.getInstance().programChannel(c))return "The radio did not accept channel "+index+".";
            getSharedPreferences("radio_session",0).edit().putLong("active_channel",c.id).apply();
            return "Switched to channel "+index+": "+c.name+".";
        }catch(Exception e){return "Could not change channel: "+e.getMessage();}
    }
    private String complete(String question)throws Exception{
        JSONObject req=new JSONObject();String prompt="<|im_start|>system\n"+SYSTEM+"<|im_end|>\n"+conversation+"<|im_start|>user\n"+question+"<|im_end|>\n<|im_start|>assistant\n";
        req.put("prompt",prompt);req.put("n_predict",128);req.put("temperature",0.3);req.put("stop",new JSONArray().put("<|im_end|>"));
        HttpURLConnection con=(HttpURLConnection)new URL("http://127.0.0.1:8080/completion").openConnection();
        con.setRequestMethod("POST");con.setConnectTimeout(3500);con.setReadTimeout(180000);con.setDoOutput(true);con.setRequestProperty("Content-Type","application/json");
        try{
            byte[] data=req.toString().getBytes("UTF-8");try(OutputStream out=con.getOutputStream()){out.write(data);}
            if(con.getResponseCode()!=200)throw new IOException("Engine HTTP "+con.getResponseCode());
            ByteArrayOutputStream sink=new ByteArrayOutputStream();try(InputStream in=con.getInputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(sink.size()+n>262144)throw new IOException("Response too large");sink.write(buf,0,n);}}
            String answer=new JSONObject(sink.toString("UTF-8")).optString("content","").replaceAll("(?s)<think>.*?</think>","").trim();
            if(answer.length()==0)answer="No response received.";
            // Retain only the latest conversational exchange to limit S80 context usage.
            String safeQ=question.replace("<|im_","[im_"),safeA=answer.replace("<|im_","[im_");
            conversation="<|im_start|>user\n"+safeQ+"<|im_end|>\n<|im_start|>assistant\n"+safeA+"<|im_end|>\n";
            return answer;
        }finally{con.disconnect();}
    }
    @Override protected void onDestroy(){main.removeCallbacks(poll);io.shutdownNow();super.onDestroy();}
}
