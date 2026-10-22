package com.rumitx.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.PopupMenu;
import com.rumitx.app.agent.CommandLayer;
import com.rumitx.app.agent.LlamaRuntime;
import com.rumitx.app.agent.RumiMemory;
import com.rumitx.app.agent.ToolCall;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android 8.1, non-root AI with production-style bottom controls.
 * Model verification, model load, SQLite and all inference run exclusively
 * on a BACKGROUND worker. UI updates are coalesced at <= ~7Hz.
 * This chat does not connect to any cloud inference service.
 */
public final class LlamaChatActivity extends Activity {
    private static final int PICK_GGUF=4108;
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(()->{
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        },"RumiTX-JNI-AI");
        t.setDaemon(true);
        return t;
    });
    private static final Object ENGINE_LOCK=new Object();
    private static LlamaBridge engine;

    private final Handler main=new Handler(Looper.getMainLooper());
    private ScrollView scroller;
    private TextView transcript,status,progress,voiceStatus,thinkingLabel;
    private EditText input;
    private Button send,retry,loadFromPhone,micButton,speakButton;
    private RumiThinkingView thinking;
    private RumiVoice voice;
    private volatile boolean busy;
    private String conversation="";
    private long startedAt;
    private volatile long lastNativePreview;
    private volatile String latestPreview="";
    private volatile boolean previewPending;
    private volatile long activeRequestId;
    private volatile long lastTokenAt;
    private volatile String repeaterPromptContext="";
    private static final long INFERENCE_TIMEOUT_MS=30000L;
    private final Runnable thinkTick=new Runnable(){
        @Override public void run(){
            if(!busy || isFinishing())return;
            thinkingLabel.setText("RUMI IS THINKING  ·  "+
                Math.max(0,(SystemClock.uptimeMillis()-startedAt)/1000)+"s");
            main.postDelayed(this,1000);
        }
    };
    private static final String SYSTEM=
        "You are RumiTX, a helpful, friendly conversational assistant on an S80 radio. "+
        "Speak naturally in English. Chat casually, explain practical topics clearly, "+
        "and admit uncertainty. Ordinary replies are plain text. "+
        "When the user explicitly asks you to key or unkey, answer with only one JSON object "+
        "{\"tool\":\"key_ptt\",\"args\":{}} or {\"tool\":\"unkey_ptt\",\"args\":{}} and no other text. "+
        "Never invent encryption keys. "+
        "You may describe radio procedures, but NEVER claim hardware changed unless "+
        "the radio command handler confirmed it. Keep answers brief. /no_think";

    private int dp(int value){
        return Math.round(getResources().getDisplayMetrics().density*value);
    }
    private TextView text(String value,int size,int color){
        TextView view=new TextView(this);
        view.setText(value);view.setTextSize(size);view.setTextColor(color);
        view.setPadding(dp(7),dp(5),dp(7),dp(5));
        return view;
    }
    private Button button(String value){
        Button b=new Button(this);
        b.setAllCaps(false);b.setText(value);b.setTextSize(12);
        b.setTextColor(Color.rgb(0,228,255));
        b.setMinWidth(0);b.setPadding(0,0,0,0);
        return b;
    }
    private void statusUi(String msg){
        main.post(()->{if(!isFinishing())status.setText(msg);});
    }
    private void scrollLatest(){
        scroller.post(()->{
            if(!isFinishing())scroller.fullScroll(View.FOCUS_DOWN);
        });
    }
    private void append(String entry){
        main.post(()->{
            if(isFinishing())return;
            conversation=RumiMemory.bound(conversation+entry+"\n\n");
            transcript.setText(conversation);
            getPreferences(0).edit().putString("conversation",conversation).apply();
            scrollLatest();
        });
    }
    private void setBusy(boolean on){
        busy=on;
        main.post(()->{
            if(isFinishing())return;
            send.setEnabled(!on);retry.setEnabled(!on);
            loadFromPhone.setEnabled(!on);micButton.setEnabled(!on);
            if(on){
                startedAt=SystemClock.uptimeMillis();
                thinking.setActive(true);
                thinkingLabel.setVisibility(View.VISIBLE);
                main.removeCallbacks(thinkTick);
                main.post(thinkTick);
            }else{
                main.removeCallbacks(thinkTick);
                thinking.setActive(false);
                thinkingLabel.setVisibility(View.GONE);
            }
        });
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff070d17);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout outer=new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(9),dp(7),dp(9),dp(7));
        outer.setBackgroundColor(0xff070d17);
        setContentView(outer);

        LinearLayout titleRow=new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("RUMITX / 2030",18,0xff00e6ff);
        title.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);
        titleRow.addView(title,new LinearLayout.LayoutParams(0,dp(45),1));
        Button options=button("⋮");
        options.setTextSize(22);
        titleRow.addView(options,new LinearLayout.LayoutParams(dp(45),dp(45)));
        outer.addView(titleRow);
        options.setOnClickListener(v->{
            PopupMenu menu=new PopupMenu(this,options);
            menu.getMenu().add("Retry model").setOnMenuItemClickListener(it->{initialize();return true;});
            menu.getMenu().add("Load model from phone").setOnMenuItemClickListener(it->{chooseModel();return true;});
            menu.show();
        });
        status=text("Initializing offline AI…",12,0xffffc76b);
        outer.addView(status);
        progress=text("Qwen3-1.7B Q4_K_M · verified private GGUF",11,0xff91b6c5);
        outer.addView(progress);
        // Keep both model recovery actions from the previous non-root build.
        LinearLayout modelControls=new LinearLayout(this);
        modelControls.setOrientation(LinearLayout.HORIZONTAL);
        retry=button("Retry / Download");
        loadFromPhone=button("Load from phone");
        modelControls.addView(retry,new LinearLayout.LayoutParams(0,dp(39),1));
        modelControls.addView(loadFromPhone,new LinearLayout.LayoutParams(0,dp(39),1));
        outer.addView(modelControls);
        retry.setOnClickListener(v->initialize());
        loadFromPhone.setOnClickListener(v->chooseModel());

        scroller=new ScrollView(this);
        scroller.setFillViewport(true);
        transcript=text("",15,0xffe4f5fc);
        transcript.setTextIsSelectable(true);
        scroller.addView(transcript);
        outer.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
        transcript.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,oright,ob)->{
            if(b!=ob)scrollLatest();
        });

        thinking=new RumiThinkingView(this);
        outer.addView(thinking,new LinearLayout.LayoutParams(-1,dp(47)));
        thinkingLabel=text("RUMI IS THINKING",11,0xff00e6ff);
        thinkingLabel.setGravity(Gravity.CENTER);
        thinkingLabel.setVisibility(View.GONE);
        outer.addView(thinkingLabel);

        // Restore the production control layout at the BOTTOM, not top right.
        LinearLayout compose=new LinearLayout(this);
        compose.setOrientation(LinearLayout.HORIZONTAL);
        input=new EditText(this);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xff7896a6);
        input.setHint("Ask RumiTX…");
        input.setTextSize(15);
        input.setMinLines(1);input.setMaxLines(3);
        compose.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        send=button("Send");
        compose.addView(send,new LinearLayout.LayoutParams(dp(72),dp(54)));
        outer.addView(compose);
        send.setOnClickListener(v->ask());

        LinearLayout bottom=new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        micButton=button("MIC · TALK");
        speakButton=button("SPEAK: OFF");
        bottom.addView(micButton,new LinearLayout.LayoutParams(0,dp(44),1));
        bottom.addView(speakButton,new LinearLayout.LayoutParams(0,dp(44),1));
        outer.addView(bottom);

        voiceStatus=text("Tap MIC to talk. Android recognizer may need internet; AI runs locally.",11,0xff9baec2);
        outer.addView(voiceStatus);
        voice=new RumiVoice(this,new RumiVoice.Callback(){
            @Override public void onSpeech(String words){
                main.post(()->{
                    if(isFinishing())return;
                    input.setText(words);
                    if(!busy)ask();
                    else voiceStatus.setText("Voice received; send after current answer.");
                });
            }
            @Override public void onStatus(String msg){
                main.post(()->{if(!isFinishing())voiceStatus.setText(msg);});
            }
        });
        micButton.setOnClickListener(v->{
            if(busy)return;
            if(VoiceTxBridge.isTransmitting()||RadioController.getInstance().isKeyed()){
                voiceStatus.setText("Release radio PTT before speaking to RumiTX.");
                return;
            }
            voice.setSpeakingEnabled(true);
            speakButton.setText("SPEAK: ON");
            voice.listen();
        });
        speakButton.setOnClickListener(v->{
            boolean enabled=!voice.isSpeakingEnabled();
            voice.setSpeakingEnabled(enabled);
            speakButton.setText(enabled?"SPEAK: ON":"SPEAK: OFF");
        });
        conversation=getPreferences(0).getString("conversation",
            "RumiTX 2030 · Offline AI\n\nAsk me anything, or use a radio command.\n\n");
        transcript.setText(conversation);
        scrollLatest();
        initialize();
    }

    private void chooseModel(){
        if(busy)return;
        Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(picker,PICK_GGUF);
    }
    private void modelProgress(long bytes,long total,String phase){
        main.post(()->{
            if(isFinishing())return;
            status.setText(phase);
            progress.setText(String.format(Locale.US,
                "Model: %d%%  ·  %d / %d MiB",total>0?(bytes*100)/total:0,
                bytes/1048576,total/1048576));
        });
    }
    private void loadNative(File model){
        statusUi("Loading GGUF into S80 memory…");
        synchronized(ENGINE_LOCK){
            if(engine==null){
                LlamaBridge b=new LlamaBridge();
                b.initModel(model.getAbsolutePath());
                engine=b;
                LlamaRuntime.attach(new LlamaRuntime.Generator(){
                    @Override public boolean loaded(){synchronized(ENGINE_LOCK){return engine!=null&&engine.isLoaded();}}
                    @Override public String complete(String prompt){
                        LlamaBridge nativeEngine;
                        synchronized(ENGINE_LOCK){nativeEngine=engine;}
                        if(nativeEngine==null)return null;
                        return nativeEngine.generate(prompt,80);
                    }
                });
            }
        }
        statusUi("READY · Local AI · Voice controls active");
    }
    private void initialize(){
        if(busy)return;
        setBusy(true);
        WORK.execute(()->{
            try{
                synchronized(ENGINE_LOCK){
                    if(engine!=null){statusUi("READY · Native GGUF already loaded");return;}
                }
                File model=LlamaModelManager.ensureDownloaded(getApplicationContext(),this::modelProgress);
                loadNative(model);
            }catch(Throwable t){
                statusUi("Model setup failed: "+t.getMessage()+" · Retry or load from phone");
                android.util.Log.e("RumiTX-JNI","Model setup",t);
            }finally{setBusy(false);}
        });
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=PICK_GGUF||resultCode!=RESULT_OK||data==null||data.getData()==null||busy)return;
        Uri uri=data.getData();
        setBusy(true);
        WORK.execute(()->{
            try{
                File model=LlamaModelManager.importFromUri(getApplicationContext(),uri,this::modelProgress);
                loadNative(model);
            }catch(Throwable t){
                statusUi("Selected GGUF rejected: "+t.getMessage());
                android.util.Log.e("RumiTX-JNI","Manual import",t);
            }finally{setBusy(false);}
        });
    }
    /** Tool JSON is handled before cleanAnswer, which would otherwise drop a tool object. */
    private String applyModel(String raw){
        ToolCall call=ToolCall.parse(stripThink(raw));
        if(call==null)return cleanAnswer(raw);
        CommandLayer layer=AgentRuntime.layer();
        if(layer==null)return "The agent service is not running, so that tool was not executed.";
        CommandLayer.Outcome outcome=layer.tool(call,CommandLayer.Actor.model(),null);
        return outcome.plain==null||outcome.plain.isEmpty()?outcome.body:outcome.plain;
    }
    private static String stripThink(String raw){
        if(raw==null)return "";
        return raw.replaceAll("(?s)<think>.*?</think>","")
            .replaceAll("(?s)<think>.*","")
            .replace("<|im_end|>","").replace("<|im_start|>","").trim();
    }
    /** Never expose radio fine-tuning JSON error schemas to normal chat users. */
    static String cleanAnswer(String raw){
        if(raw==null)return "I couldn't generate a reply. Please try again.";
        String s=raw.replaceAll("(?s)<think>.*?</think>","")
           .replaceAll("(?s)<think>.*","")
           .replace("<|im_end|>","").replace("<|im_start|>","").trim();
        if(s.startsWith("{")&&s.endsWith("}")){
            try{
                JSONObject json=new JSONObject(s);
                for(String key:new String[]{"text","answer","reply","response","content"}){
                    String answer=json.optString(key,"").trim();
                    if(!answer.isEmpty()&&!answer.startsWith("{"))return answer;
                }
                return "I'm ready to chat. Could you ask that another way?";
            }catch(Exception ignored){
                return "I had trouble interpreting that answer. Could you try again?";
            }
        }
        return s.isEmpty()?"I couldn't generate a reply. Please try again.":s;
    }
    /** Feed only safe, validated radio intents to the radio; never generated JSON. */
    private String runRadioIntent(RadioIntentParser.Intent intent){
        switch(intent.type){
            case UNSUPPORTED:return intent.error!=null?intent.error:"Unsupported command; nothing changed.";
            case CHANNEL_NUMBER: {
                if(intent.channelNumber<1||intent.channelNumber>999)return "Channel number out of range.";
                try(ChannelDatabase db=new ChannelDatabase(this)){
                    List<Channel> channels=db.getAllChannels();
                    if(intent.channelNumber>channels.size())
                        return "Channel "+intent.channelNumber+" isn't saved; no radio change made.";
                    return programSavedChannel(channels.get(intent.channelNumber-1));
                }
            }
            case CHANNEL_NAME: {
                try(ChannelDatabase db=new ChannelDatabase(this)){
                    Channel match=null;
                    for(Channel c:db.getAllChannels())if(c.name!=null &&
                            c.name.equalsIgnoreCase(intent.channelName)){
                        if(match!=null)return "Multiple channels match. Use a channel number.";
                        match=c;
                    }
                    return match==null?"No channel named '"+intent.channelName+"' is saved.":programSavedChannel(match);
                }
            }
            case FREQUENCY: {
                return programSavedChannel(AiTempChannel.create(
                    intent.frequencyMHz,intent.txFrequencyMHz,intent.plHz,intent.dcsCode));
            }
            default:return null; // Normal conversation goes to model.
        }
    }
    private String programSavedChannel(Channel channel){
        RadioController radio=RadioController.getInstance();
        if(VoiceTxBridge.isTransmitting()||radio.isKeyed())
            return "Release PTT first. No change made.";
        if(!radio.isConnected())return "Radio module disconnected. No change made.";
        String blocked=com.rumitx.app.repeaters.RepeaterProfiles.blockedChannel(channel);
        if(blocked!=null)return "Cannot program channel: "+blocked+". Nothing changed.";
        try{
            if(!radio.programChannel(channel))
                return "The radio did not acknowledge programming. No change confirmed.";
            getSharedPreferences("radio_session",0).edit()
                .putLong("active_channel",channel.id>0?channel.id:-1).apply();
            return String.format(Locale.US,
                "Radio acknowledged: %s. RX %.4f MHz, TX %.4f MHz. No transmission started.",
                channel.name,channel.rxFreqMHz,channel.txFreqMHz);
        }catch(Exception e){
            return "Radio programming failed; no success confirmed: "+e.getMessage();
        }
    }
    private String prompt(String question,String prior){
        // Last user /no_think applies even if the model was trained on action JSON.
        if(prior.length()>RumiMemory.PROMPT_CHARS)prior=prior.substring(prior.length()-RumiMemory.PROMPT_CHARS);
        String localFacts=repeaterPromptContext;
        if(localFacts.length()>800)localFacts=localFacts.substring(0,800);
        return "<|im_start|>system\n"+SYSTEM+"<|im_end|>\n"+
            "<|im_start|>user\n"+
            (localFacts.isEmpty()?"":"Relevant local database facts for this conversation; do not guess beyond them:\n"+localFacts+"\n")+
            (prior.isEmpty()?"":"Earlier conversation:\n"+prior+"\n")+
            question+"\n/no_think<|im_end|>\n"+
            // Qwen3 recommended non-thinking prefill when bypassing chat template.
            "<|im_start|>assistant\n<think>\n\n</think>\n\n";
    }
    private void preview(long requestId,String raw){
        if(activeRequestId!=requestId)return;
        lastTokenAt=SystemClock.uptimeMillis();
        long now=lastTokenAt;
        latestPreview=raw.trim().startsWith("{")?"Composing an answer…":cleanAnswer(raw);
        if(now-lastNativePreview<140||previewPending)return;
        lastNativePreview=now;
        previewPending=true;
        main.post(()->{
            previewPending=false;
            if(activeRequestId!=requestId || !busy || isFinishing())return;
            transcript.setText(conversation+"RUMITX · "+latestPreview+" ▌");
            scrollLatest();
        });
    }

    /** UI watchdog runs independently of JNI; native abort callback stops CPU decoding. */
    private void watchdog(long requestId){
        if(activeRequestId!=requestId||!busy||isFinishing())return;
        long now=SystemClock.uptimeMillis();
        if(now-lastTokenAt>=INFERENCE_TIMEOUT_MS || now-startedAt>=INFERENCE_TIMEOUT_MS){
            // Invalidate this request before cancellation so its late worker result
            // cannot replace the timeout message or unlock a new conversation.
            activeRequestId=requestId+1;
            LlamaBridge current;
            synchronized(ENGINE_LOCK){current=engine;}
            if(current!=null)current.cancelGeneration();
            append("RUMITX · That took too long — try asking about a specific city or frequency.");
            statusUi("Timed out after 30 seconds. Inference cancelled.");
            setBusy(false);
            return;
        }
        main.postDelayed(()->watchdog(requestId),1000);
    }

    private void ask(){
        if(busy)return;
        String question=input.getText().toString().trim();
        if(question.isEmpty())return;
        String prior=conversation;
        input.setText("");
        append("YOU · "+question);
        setBusy(true);
        statusUi("Rumi is processing on your S80…");
        final long requestId=++activeRequestId;
        lastTokenAt=SystemClock.uptimeMillis();
        main.postDelayed(()->watchdog(requestId),1000);
        WORK.execute(()->{
            String reply;
            try{
                // Local fact questions bypass the model completely: <15s target
                // even on slow S80 hardware, with no invented repeater details.
                RadioIntentParser.Intent intent=RadioIntentParser.parse(question);
                if(intent.type!=RadioIntentParser.Type.CHAT){
                    // Explicit tuning and channel commands must take priority.
                    reply=runRadioIntent(intent);
                } else {
                    RepeaterQuestion request=RepeaterQuestion.parse(question);
                    if(request!=null){
                        RepeaterLocalSearch.Answer result=
                            RepeaterLocalSearch.search(getApplicationContext(),request);
                        repeaterPromptContext=result.promptContext;
                        reply=result.reply;
                    } else {
                        reply=RumiChatRouter.greeting(question);
                        if(reply==null){
                            LlamaBridge nativeEngine;
                            synchronized(ENGINE_LOCK){nativeEngine=engine;}
                            if(nativeEngine==null)throw new IllegalStateException("Model not loaded; Retry first");
                            if(!LlamaRuntime.tryAcquire()){
                                reply="The on-device model is busy with another request.";
                            }else{
                                try{
                                    lastNativePreview=0;
                                    latestPreview="";
                                    previewPending=false;
                                    String raw=nativeEngine.generateStreaming(
                                        prompt(question,prior),80,bytes->
                                            preview(requestId,new String(bytes,StandardCharsets.UTF_8)));
                                    reply=applyModel(raw);
                                }finally{LlamaRuntime.release();}
                            }
                        }
                    }
                }
            }catch(Throwable t){
                reply="I couldn't finish that request: "+t.getMessage();
                android.util.Log.e("RumiTX-JNI","AI request failed",t);
            }
            final String result=reply;
            main.post(()->{
                if(activeRequestId!=requestId||isFinishing())return;
                conversation=RumiMemory.bound(conversation+"RUMITX · "+result+"\n\n");
                transcript.setText(conversation);
                getPreferences(0).edit().putString("conversation",conversation).apply();
                status.setText("READY · Local repeater search and offline AI");
                scrollLatest();
                if(voice!=null)voice.say(result);
                setBusy(false);
            });
        });
    }
    @Override protected void onDestroy(){
        main.removeCallbacks(thinkTick);
        if(voice!=null)voice.close();
        super.onDestroy();
        // Do not close the shared native model; it is reused between radio/AI screens.
    }
}
