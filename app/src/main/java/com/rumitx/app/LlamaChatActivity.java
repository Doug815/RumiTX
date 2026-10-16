package com.rumitx.app;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.text.TextUtils;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Real local AI, not a proxy or external rooted binary.
 * First launch automatically downloads and validates the 1.1GB GGUF.
 * The radio's existing PTT and HIDL service are deliberately untouched.
 */
public final class LlamaChatActivity extends Activity {
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor(r->new Thread(r,"RumiTX-JNI-AI"));
    private static final Object ENGINE_LOCK=new Object();
    private static LlamaBridge engine;
    private ScrollView scroller;
    private TextView transcript,status,progress;
    private EditText input;
    private static final int PICK_GGUF=4108;
    private Button send,retry,loadFromPhone;
    private volatile boolean busy;
    private String conversation="";
    private final String SYSTEM=
        "You are RumiTX 2030, a helpful, conversational local radio and survival assistant "+
        "on a DOOGEE S80 Android 8.1 phone. Answer the user directly in plain English "+
        "and admit uncertainty. Do not output JSON unless asked. You do not directly "+
        "control PTT or transmit. Never claim you tuned a real radio from this chat. "+
        "Provide practical radio knowledge, clarify frequencies, and be concise. /no_think";
    private int dp(float value){return Math.round(getResources().getDisplayMetrics().density*value);}
    private TextView text(String s,int sz,int color){
        TextView view=new TextView(this);view.setText(s);view.setTextSize(sz);view.setTextColor(color);
        view.setPadding(dp(7),dp(5),dp(7),dp(5));return view;
    }
    private Button button(String label){
        Button b=new Button(this);b.setAllCaps(false);b.setText(label);
        b.setTextColor(Color.rgb(0,228,255));b.setTextSize(13);return b;
    }
    private void uiStatus(String s){
        runOnUiThread(()->{if(!isFinishing())status.setText(s);});
    }
    private void append(String entry){
        runOnUiThread(()->{
            if(isFinishing())return;
            conversation+=entry+"\n\n";
            if(conversation.length()>50000)conversation=conversation.substring(conversation.length()-50000);
            transcript.setText(conversation);
            getPreferences(0).edit().putString("conversation",conversation).apply();
            scroller.post(()->scroller.fullScroll(View.FOCUS_DOWN));
        });
    }
    private void setBusy(boolean on){
        busy=on;
        runOnUiThread(()->{if(!isFinishing()){send.setEnabled(!on);retry.setEnabled(!on);loadFromPhone.setEnabled(!on);}});
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff070e1a);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(9),dp(8),dp(9),dp(6));
        page.setBackgroundColor(0xff080d16);setContentView(page);
        TextView title=text("RUMITX 2030  /  NATIVE OFFLINE AI",18,0xff00e5ff);
        title.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);page.addView(title);
        status=text("Initializing local AI…",12,0xffe7b15b);
        page.addView(status);
        progress=text("Model: Qwen3-1.7B Q4_K_M · Internal Storage",11,0xff91b6c5);
        page.addView(progress);
        LinearLayout modelControls=new LinearLayout(this);
        modelControls.setOrientation(LinearLayout.HORIZONTAL);
        retry=button("Retry / Download");
        loadFromPhone=button("Load from phone");
        modelControls.addView(retry,new LinearLayout.LayoutParams(0,dp(48),1));
        modelControls.addView(loadFromPhone,new LinearLayout.LayoutParams(0,dp(48),1));
        page.addView(modelControls,new LinearLayout.LayoutParams(-1,dp(48)));
        retry.setOnClickListener(v->initialize());
        loadFromPhone.setOnClickListener(v->{
            if(busy)return;
            Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            picker.setType("*/*"); // Android provider MIME for .gguf varies.
            picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(picker,PICK_GGUF);
        });
        scroller=new ScrollView(this);scroller.setFillViewport(true);
        transcript=text("",15,0xffe4f5fc);
        transcript.setTextIsSelectable(true);
        scroller.addView(transcript);
        page.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        input=new EditText(this);input.setTextColor(Color.WHITE);input.setHintTextColor(0xff7896a6);
        input.setHint("Ask RumiTX…");input.setTextSize(15);input.setMinLines(1);
        input.setMaxLines(3);
        row.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        send=button("Send");row.addView(send,new LinearLayout.LayoutParams(dp(77),dp(55)));
        page.addView(row);send.setOnClickListener(v->ask());
        conversation=getPreferences(0).getString("conversation",
            "RumiTX 2030 · Native llama.cpp on ARM64\n\n"+
            "Offline after first model download. No root, no ADB and no external inference service.\n\n");
        transcript.setText(conversation);
        initialize();
    }
    private void modelProgress(long bytes,long total,String phase){
        runOnUiThread(()->{
            if(isFinishing())return;
            long pct=total>0?(bytes*100)/total:0;
            status.setText(phase);
            progress.setText(String.format(java.util.Locale.US,
              "Model: %d%%  ·  %d / %d MiB",pct,bytes/1048576,total/1048576));
        });
    }
    private void loadNative(File model){
        uiStatus("Loading verified GGUF into device memory…");
        synchronized(ENGINE_LOCK){
            if(engine==null){
                LlamaBridge bridge=new LlamaBridge();
                bridge.initModel(model.getAbsolutePath());
                engine=bridge;
            }
        }
        uiStatus("READY · Local ARM64 inference; no root needed");
    }
    private void initialize(){
        if(busy)return;
        setBusy(true);
        WORK.execute(()->{
            try{
                synchronized(ENGINE_LOCK){
                    if(engine!=null){
                        uiStatus("READY · Native model already loaded");
                        return;
                    }
                }
                File model=LlamaModelManager.ensureDownloaded(getApplicationContext(),this::modelProgress);
                loadNative(model);
            }catch(Throwable t){
                uiStatus("Model setup failed: "+t.getMessage()+" — retry or load from phone");
                android.util.Log.e("RumiTX-JNI","Model setup failed",t);
            }finally{setBusy(false);}
        });
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=PICK_GGUF||resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        if(busy)return;
        final Uri uri=data.getData();
        setBusy(true);
        WORK.execute(()->{
            try{
                File model=LlamaModelManager.importFromUri(getApplicationContext(),uri,this::modelProgress);
                loadNative(model);
            }catch(Throwable t){
                uiStatus("Selected model rejected: "+t.getMessage());
                android.util.Log.e("RumiTX-JNI","Manual model import failed",t);
            }finally{setBusy(false);}
        });
    }
    private void ask(){
        if(busy)return;
        String question=input.getText().toString().trim();
        if(question.isEmpty())return;
        input.setText("");
        append("YOU · "+question);
        setBusy(true);
        uiStatus("Rumi is thinking on your S80…");
        WORK.execute(()->{
            try{
                LlamaBridge current;
                synchronized(ENGINE_LOCK){current=engine;}
                if(current==null)throw new IllegalStateException("Local model is not yet loaded; tap Retry");
                // Keep under small-context budget on the S80's older Cortex-A53.
                String history=conversation;
                if(history.length()>1200)history=history.substring(history.length()-1200);
                String prompt="<|im_start|>system\n"+SYSTEM+"<|im_end|>\n"+
                    "<|im_start|>user\n"+
                    "Conversation context:\n"+history+"\nLatest question: "+question+
                    "<|im_end|>\n<|im_start|>assistant\n";
                String answer=current.generate(prompt,144);
                // The small model may emit reasoning delimiters; only show its answer.
                answer=answer.replaceAll("(?s)<think>.*?</think>","").trim();
                if(answer.isEmpty())answer="I couldn't generate an answer. Please try rephrasing.";
                append("RUMITX · "+answer);
                uiStatus("READY · Local ARM64 inference");
            }catch(Throwable t){
                append("RUMITX · AI request failed: "+t.getMessage());
                uiStatus("Inference error · retry your message");
                android.util.Log.e("RumiTX-JNI","Inference failure",t);
            }finally{setBusy(false);}
        });
    }
}