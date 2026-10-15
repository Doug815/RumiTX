package com.rumitx.rumiai;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Standalone client: NEVER uses direct radio hardware APIs or silently sends commands. */
public final class MainActivity extends Activity {
    private static final int BG=Color.rgb(9,17,31), PANEL=Color.rgb(19,34,55);
    private static final int CARD=Color.rgb(25,43,66), BLUE=Color.rgb(22,112,224);
    private static final int CYAN=Color.rgb(111,211,252), WHITE=Color.rgb(233,244,253);
    private static final int MUTED=Color.rgb(157,178,202), GREEN=Color.rgb(91,214,174);
    private static final int REQ_SPEECH=310, REQ_MIC=311;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ArrayList<String> conversation=new ArrayList<>();
    private final ArrayList<String> diagnostics=new ArrayList<>();
    private String tab="CHAT", chatPath="", apiRoot="";
    private boolean speakAnswers=true, ttsReady=false;
    private JSONObject lastAction;
    private boolean lastNeedsConfirmation=false;
    private LinearLayout body, root;
    private ScrollView scroll;
    private TextView connection;
    private EditText prompt;
    private TextToSpeech tts;
    private SharedPreferences prefs;
    private TokenVault vault;
    private boolean destroyed=false;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        prefs=getSharedPreferences("rumi_ui",MODE_PRIVATE);
        vault=new TokenVault(this);
        apiRoot=prefs.getString("base","");
        chatPath=prefs.getString("chat_path","");
        speakAnswers=prefs.getBoolean("speak",true);
        tts=new TextToSpeech(this, code -> {
            ttsReady = code==TextToSpeech.SUCCESS;
            if(ttsReady) tts.setLanguage(Locale.US);
        });
        conversation.add("RUMI AI  •  Standalone Android 8+ companion");
        conversation.add("Current verified API: /status, /infer, /command. The custom V2 conversation endpoint remains configurable.");
        render();
    }

    private int dp(float value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int fill, int radius, int stroke) {
        GradientDrawable d=new GradientDrawable();
        d.setColor(fill); d.setCornerRadius(dp(radius));
        if(stroke!=0) d.setStroke(dp(1),stroke);
        return d;
    }
    private LinearLayout column() {
        LinearLayout l=new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }
    private TextView label(String s, int size, int color, boolean bold) {
        TextView v=new TextView(this);
        v.setText(s); v.setTextSize(size); v.setTextColor(color);
        if(bold) v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        v.setLineSpacing(dp(3),1.02f);
        return v;
    }
    private void space(LinearLayout into, int h) {
        View v=new View(this); into.addView(v,new LinearLayout.LayoutParams(1,dp(h)));
    }
    private LinearLayout panel() {
        LinearLayout l=column();
        l.setPadding(dp(15),dp(14),dp(15),dp(14));
        l.setBackground(shape(PANEL,15,Color.rgb(42,67,93)));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.bottomMargin=dp(12);
        body.addView(l,p);
        return l;
    }
    private Button button(String text, boolean primary) {
        Button b=new Button(this);
        b.setText(text); b.setTextColor(primary?Color.WHITE:WHITE);
        b.setAllCaps(false); b.setTextSize(13);
        b.setBackground(shape(primary?BLUE:CARD,12,primary?0:Color.rgb(57,83,112)));
        b.setPadding(dp(10),dp(7),dp(10),dp(7)); b.setMinHeight(dp(44));
        return b;
    }
    private EditText edit(String hint,String value,boolean secret) {
        EditText e=new EditText(this);
        e.setTextColor(WHITE); e.setHintTextColor(MUTED); e.setTextSize(14);
        e.setSingleLine(true); e.setHint(hint); e.setText(value);
        e.setPadding(dp(13),dp(8),dp(13),dp(8));
        e.setBackground(shape(CARD,10,Color.rgb(53,79,108)));
        if(secret) e.setTransformationMethod(PasswordTransformationMethod.getInstance());
        e.setSelectAllOnFocus(false);
        return e;
    }
    private void addBodyButton(LinearLayout target,String text,boolean primary,View.OnClickListener click) {
        Button b=button(text,primary); b.setOnClickListener(click);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(47));
        p.topMargin=dp(9); target.addView(b,p);
    }
    private void addText(LinearLayout into,String s,int size,int color,boolean strong) {
        TextView v=label(s,size,color,strong); into.addView(v);
    }
    private void render() {
        root=column();root.setBackgroundColor(BG);
        root.setPadding(dp(12),dp(12),dp(12),dp(8));
        setContentView(root);
        LinearLayout header=new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackground(shape(PANEL,16,Color.rgb(39,64,91)));
        header.setPadding(dp(14),dp(13),dp(14),dp(13));
        LinearLayout heading=column();
        heading.addView(label("◉  RUMI  AI",23,WHITE,true));
        heading.addView(label("RADIO INTELLIGENCE COMPANION",10,CYAN,true));
        header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        connection=label(apiRoot.isEmpty()?"NOT SET UP":"● API CONFIGURED",10,apiRoot.isEmpty()?MUTED:GREEN,true);
        header.addView(connection);
        root.addView(header);
        space(root,10);
        LinearLayout nav=new LinearLayout(this);
        String[] tabs={"CHAT","RADIO","DIAG","SETUP"};
        for(String name:tabs) {
            Button b=button(name,tab.equals(name));
            b.setTextSize(11);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(42),1);
            lp.rightMargin=dp(3);
            nav.addView(b,lp);
            b.setOnClickListener(v->{tab=name;render();});
        }
        root.addView(nav);
        space(root,12);
        scroll=new ScrollView(this); scroll.setFillViewport(false);
        body=column();
        body.setPadding(dp(2),dp(2),dp(2),dp(15));
        scroll.addView(body);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if("CHAT".equals(tab)) renderChat();
        else if("RADIO".equals(tab)) renderRadio();
        else if("DIAG".equals(tab)) renderDiag();
        else renderSetup();
    }

    private void renderChat() {
        LinearLayout p=panel();
        addText(p,"VOICE + INTELLIGENCE",16,WHITE,true);
        space(p,5);
        addText(p,"Tap the microphone to dictate a phrase. Interpret parses a command without executing it. Ask V2 uses a separately configured chat endpoint.",12,MUTED,false);
        space(p,11);
        int first=Math.max(0,conversation.size()-10);
        for(int i=first;i<conversation.size();i++) {
            TextView v=label(conversation.get(i),13,i==first?CYAN:WHITE,false);
            v.setBackground(shape(CARD,11,0));
            v.setPadding(dp(11),dp(10),dp(11),dp(10));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
            lp.bottomMargin=dp(7);
            p.addView(v,lp);
        }
        space(p,8);
        prompt=edit("Say or type a radio command...", "",false);
        prompt.setSingleLine(false);
        prompt.setMinLines(2);prompt.setMaxLines(4);
        p.addView(prompt,new LinearLayout.LayoutParams(-1,-2));
        addBodyButton(p,"🎙  Speak a command",false,v->beginSpeech());
        addBodyButton(p,"Interpret command — no execution",true,v->infer());
        addBodyButton(p,"Ask V2 conversation API",false,v->askV2());
        if(lastAction!=null) {
            space(p,13);
            addText(p,"VALIDATED COMMAND PREVIEW",12,CYAN,true);
            try { addText(p,lastAction.toString(2),12,WHITE,false); }
            catch(Exception e) { addText(p,lastAction.toString(),12,WHITE,false); }
            addBodyButton(p,lastNeedsConfirmation?"Stage for local confirmation":"Confirm and execute",true,
                v->confirmCommand(lastAction));
        }
        addBodyButton(p,speakAnswers?"Audio responses: ON":"Audio responses: OFF",false,v-> {
            speakAnswers=!speakAnswers; prefs.edit().putBoolean("speak",speakAnswers).apply();render();
        });
    }
    private void infer() {
        String utterance=prompt==null?"":prompt.getText().toString().trim();
        if(utterance.isEmpty()) {toast("Enter or dictate a command");return;}
        if(utterance.length()>500){toast("Maximum 500 characters");return;}
        lastAction=null; conversation.add("YOU  •  "+utterance);
        try {
            JSONObject data=new JSONObject();data.put("utterance",utterance);
            request("POST","/infer",data,false,(r)->{
                if(!r.ok()){conversation.add("API ERROR  •  HTTP "+r.code+" "+shortBody(r.body));render();return;}
                JSONObject j=r.json();
                lastAction=j.optJSONObject("action");
                lastNeedsConfirmation=j.optBoolean("confirmation_required",false);
                if(lastAction==null) conversation.add("RUMI  •  No validated action in the response.");
                else conversation.add("RUMI  •  Interpreted as "+lastAction.optString("intent")+"; NOT executed. Review and confirm below.");
                render();
            });
        } catch(Exception e){toast("Cannot serialize command");}
    }
    private void askV2() {
        if(chatPath.isEmpty()){toast("Set the optional V2 chat path in SETUP after the API contract is confirmed.");return;}
        String text=prompt==null?"":prompt.getText().toString().trim();
        if(text.isEmpty()){toast("Enter your question");return;}
        if(text.length()>500){toast("Maximum 500 characters");return;}
        lastAction=null;conversation.add("YOU  •  "+text);
        try {
            JSONObject data=new JSONObject();data.put("message",text);
            request("POST",chatPath,data,false,r->{
                if(!r.ok()){conversation.add("V2 API ERROR  •  HTTP "+r.code+" "+shortBody(r.body));render();return;}
                JSONObject j=r.json();
                String answer="";
                for(String name:new String[]{"answer","response","text","message"}) {
                    if(j.opt(name) instanceof String) {answer=j.optString(name);break;}
                }
                if(answer.isEmpty()) answer="Unrecognized V2 response schema; view DIAG and verify contract.";
                if(answer.length()>3000) answer=answer.substring(0,3000)+"…";
                conversation.add("RUMI  •  "+answer);render();speak(answer);
            });
        }catch(Exception e){toast("Cannot serialize question");}
    }
    private void renderRadio() {
        LinearLayout p=panel();
        addText(p,"RADIO CONTROLLER",17,WHITE,true);
        space(p,5);
        addText(p,"Commands use the API validator and device controller. No direct RF or PTT access is included.",12,MUTED,false);
        addBodyButton(p,"Read current radio status",true,v->pingStatus());
        space(p,14);
        addText(p,"PROGRAMMED CHANNEL",12,CYAN,true);
        EditText number=edit("Enter channel number (e.g., 5)","",false);
        number.setInputType(InputType.TYPE_CLASS_NUMBER);
        p.addView(number,new LinearLayout.LayoutParams(-1,dp(48)));
        addBodyButton(p,"Review channel switch",false,v->{
            try {
                int n=Integer.parseInt(number.getText().toString().trim());
                if(n<1||n>9999)throw new IllegalArgumentException();
                JSONObject action=new JSONObject();
                JSONObject args=new JSONObject();args.put("channel",n);
                action.put("intent","set_channel");action.put("args",args);
                confirmCommand(action);
            }catch(Exception e){toast("Enter a valid positive channel number");}
        });
        space(p,8);
        addText(p,"Frequency changes and DMR message composition must be interpreted, allowlisted, and staged for local approval. Remote API does not provide a transmit action.",12,MUTED,false);
        addBodyButton(p,"Go to command interpreter",false,v->{tab="CHAT";render();});
    }
    private void confirmCommand(JSONObject action) {
        if(action==null){toast("No action to execute");return;}
        String intent=action.optString("intent","unknown");
        boolean stage="set_frequency".equals(intent)||"compose_dmr_text".equals(intent);
        new AlertDialog.Builder(this)
            .setTitle(stage?"Stage command for review?":"Submit validated command?")
            .setMessage(action.toString()+"\n\nOnly the server-side policy and controller decide whether to apply or stage this. No direct radio transmit is supported.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton(stage?"Stage":"Submit",(d,w)->request("POST","/command",action,true,r->{
                String state=r.json().optJSONObject("result")==null?"":r.json().optJSONObject("result").optString("state");
                conversation.add(r.ok()?"API RESULT  •  HTTP "+r.code+" "+(state.isEmpty()?shortBody(r.body):state):
                    "COMMAND REJECTED  •  HTTP "+r.code+" "+shortBody(r.body));
                if(r.ok())lastAction=null;
                render();
            })).show();
    }
    private void pingStatus(){
        request("GET","/status",null,false,r->{
            JSONObject j=r.json();
            String mode=j.optJSONObject("radio")==null?"unknown":j.optJSONObject("radio").optString("mode","unknown");
            conversation.add(r.ok()?"STATUS  •  LLM "+j.optString("llm")+" · Radio "+mode:
                "STATUS ERROR  •  HTTP "+r.code+" "+shortBody(r.body));
            if("DIAG".equals(tab)||"RADIO".equals(tab)) {
                diagnostics.add(0,"Server status: "+(r.ok()?j.toString():shortBody(r.body)));
                trimDiag();
            }
            render();
        });
    }
    private void renderDiag() {
        LinearLayout p=panel();
        addText(p,"LIVE API DIAGNOSTICS",17,WHITE,true);
        space(p,5);
        addText(p,"Request timing is measured on-device. Logs contain HTTP status and latency, not token values, message contents or speech transcripts.",12,MUTED,false);
        addBodyButton(p,"Ping /status now",true,v->pingStatus());
        space(p,13);
        addText(p,"BACKEND",12,CYAN,true);
        addText(p,apiRoot.isEmpty()?"Not configured":apiRoot,12,WHITE,false);
        addText(p,"Transport: TLS certificate + hostname verified",12,MUTED,false);
        addText(p,"Chat route: "+(chatPath.isEmpty()?"not configured":chatPath+" (V2 provisional)"),12,MUTED,false);
        space(p,14);
        addText(p,"RECENT EVENTS",12,CYAN,true);
        if(diagnostics.isEmpty())addText(p,"No requests made yet.",12,MUTED,false);
        for(String d:diagnostics) {space(p,6);addText(p,d,12,WHITE,false);}
        addBodyButton(p,"Clear session events",false,v->{diagnostics.clear();render();});
        LinearLayout note=panel();
        addText(note,"FIRST AID + FIRST RESPONDER CONTENT",14,WHITE,true);
        space(note,5);
        addText(note,"V2 knowledge access depends on an authenticated, verified server endpoint. Radio or health guidance from a language model is informational; in an emergency, contact emergency services and follow local protocols.",12,MUTED,false);
    }
    private void renderSetup() {
        LinearLayout p=panel();
        addText(p,"PRIVATE SERVER CONNECTION",17,WHITE,true);
        space(p,5);
        addText(p,"Use your Tailscale Serve HTTPS URL. HTTP, custom ports, userinfo, query strings, and certificate bypasses are refused.",12,MUTED,false);
        space(p,10);
        addText(p,"TAILSCALE HTTPS BASE URL",11,CYAN,true);
        EditText server=edit("https://device.tailnet.ts.net",apiRoot,false);
        p.addView(server,new LinearLayout.LayoutParams(-1,dp(46)));
        space(p,12);
        addText(p,"READ API TOKEN",11,CYAN,true);
        EditText read=edit("Paste read token (blank to keep existing)","",true);
        p.addView(read,new LinearLayout.LayoutParams(-1,dp(46)));
        space(p,12);
        addText(p,"COMMAND API TOKEN",11,CYAN,true);
        EditText command=edit("Paste command token (blank to keep existing)","",true);
        p.addView(command,new LinearLayout.LayoutParams(-1,dp(46)));
        space(p,12);
        addText(p,"OPTIONAL V2 CHAT PATH",11,CYAN,true);
        EditText chat=edit("Leave blank until confirmed, e.g., /chat",chatPath,false);
        p.addView(chat,new LinearLayout.LayoutParams(-1,dp(46)));
        addBodyButton(p,"Save connection settings",true,v->{
            String base=server.getText().toString().trim();
            String optional=chat.getText().toString().trim();
            if(!ApiClient.isSafeBase(base)){toast("Enter HTTPS base URL without port or path");return;}
            if(!optional.isEmpty()&&!ApiClient.isSafePath(optional)){toast("Invalid chat path");return;}
            String rt=read.getText().toString().trim(),ct=command.getText().toString().trim();
            if(!rt.isEmpty()&&rt.length()<32){toast("Read token must be 32+ characters");return;}
            if(!ct.isEmpty()&&ct.length()<32){toast("Command token must be 32+ characters");return;}
            try{
                if(!rt.isEmpty())vault.save("read",rt);
                if(!ct.isEmpty())vault.save("command",ct);
                apiRoot=base;chatPath=optional;
                prefs.edit().putString("base",base).putString("chat_path",optional).apply();
                toast("Settings saved securely");tab="DIAG";render();
            } catch(Exception ex){toast("Device Keystore error; settings not saved");}
        });
        addBodyButton(p,"Clear stored API tokens",false,v->new AlertDialog.Builder(this)
            .setTitle("Remove both tokens?")
            .setMessage("This device will lose API access until new tokens are entered.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Clear",(d,w)->{vault.clear();toast("Tokens cleared");}).show());
        LinearLayout a=panel();
        addText(a,"SAFETY AND COMPATIBILITY",14,WHITE,true);
        space(a,5);
        addText(a,"Standalone package com.rumitx.rumiai, Android 8.0+. Does not replace RumiTX, does not bind to its non-exported RadioService, and cannot operate the radio directly.",12,MUTED,false);
    }
    private interface Done{void complete(ApiClient.Response r);}
    private void request(String method,String path,JSONObject data,boolean command,Done done) {
        if(apiRoot.isEmpty()){toast("Configure the server in SETUP first");return;}
        final String base=apiRoot;
        final long submitted=SystemClock.elapsedRealtime();
        connection.setText("● CONNECTING");
        worker.execute(()->{
            try {
                String read=vault.load("read"),write=vault.load("command");
                ApiClient.Response result=new ApiClient(base,read,write).call(method,path,data,command);
                runOnUiThread(()->{
                    if(destroyed)return;
                    diagnostics.add(0,method+" "+path+"  HTTP "+result.code+"  "+result.elapsedMs+" ms");
                    trimDiag();
                    connection.setText(result.ok()?"● API ONLINE":"● API ERROR");
                    connection.setTextColor(result.ok()?GREEN:MUTED);
                    done.complete(result);
                });
            }catch(Exception e){
                final String name=e.getClass().getSimpleName();
                final long duration=SystemClock.elapsedRealtime()-submitted;
                runOnUiThread(()->{
                    if(destroyed)return;
                    diagnostics.add(0,method+" "+path+"  "+name+"  "+duration+" ms");
                    trimDiag();
                    connection.setText("● DISCONNECTED");connection.setTextColor(MUTED);
                    toast("Request failed: "+name+". Verify Tailscale, HTTPS, and API credentials.");
                });
            }
        });
    }
    private void trimDiag(){while(diagnostics.size()>40) diagnostics.remove(diagnostics.size()-1);}
    private String shortBody(String x){return x==null?"":x.substring(0,Math.min(180,x.length())).replace('\n',' ');}
    private void toast(String msg){android.widget.Toast.makeText(this,msg,android.widget.Toast.LENGTH_LONG).show();}
    private void beginSpeech() {
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_MIC);return;
        }
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.getDefault());
        i.putExtra(RecognizerIntent.EXTRA_PROMPT,"Talk to Rumi AI");
        try {startActivityForResult(i,REQ_SPEECH);}
        catch(ActivityNotFoundException e){toast("No speech recognizer installed. Type the command or install an on-device speech engine.");}
    }
    @Override public void onRequestPermissionsResult(int req,String[] perms,int[] results) {
        super.onRequestPermissionsResult(req,perms,results);
        if(req==REQ_MIC && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED)beginSpeech();
    }
    @Override protected void onActivityResult(int req,int result,Intent data) {
        super.onActivityResult(req,result,data);
        if(req==REQ_SPEECH&&result==RESULT_OK&&data!=null) {
            ArrayList<String> values=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if(values!=null&&!values.isEmpty()) {
                tab="CHAT";render();
                if(prompt!=null)prompt.setText(values.get(0));
            }
        }
    }
    private void speak(String text) {
        if(speakAnswers&&ttsReady&&tts!=null&&text!=null&&!text.isEmpty()) {
            tts.speak(text,TextToSpeech.QUEUE_FLUSH,null,"rumi-answer");
        }
    }
    @Override protected void onDestroy(){
        destroyed=true;worker.shutdownNow();
        if(tts!=null){tts.stop();tts.shutdown();}
        super.onDestroy();
    }
}
