package com.rumitx.app;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.content.Intent;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

/**
 * Hands-free turn-based interface for RumiTX.
 *
 * Speech recognition uses the Android-installed recognizer with EXTRA_PREFER_OFFLINE.
 * On Android 8.1 this is a preference, NOT proof speech stays on-device. The
 * UI explicitly discloses that its recognition provider may require internet.
 * Speech synthesis selects an installed voice that does not require a network.
 * Listening is only started after the user's explicit tap, never always-on.
 */
public final class RumiVoice implements TextToSpeech.OnInitListener {
    public interface Callback {
        void onSpeech(String words);
        void onStatus(String status);
    }

    private final Activity host;
    private final Callback cb;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean closed;
    private boolean listening;
    private boolean voiceReady;
    private boolean speakingEnabled;
    private String ttsIssue="Offline spoken voice not installed on this phone.";

    public RumiVoice(Activity host,Callback cb) {
        this.host=host;
        this.cb=cb;
        tts=new TextToSpeech(host.getApplicationContext(),this);
    }

    public boolean isSpeakingEnabled(){return speakingEnabled;}
    public boolean isListening(){return listening;}
    public void setSpeakingEnabled(boolean enabled){
        speakingEnabled=enabled;
        if(!enabled&&tts!=null)tts.stop();
        cb.onStatus(enabled ? (voiceReady?"Spoken replies ON · local voice":"Spoken replies unavailable · "+ttsIssue) : "Spoken replies OFF");
    }

    @Override public void onInit(int status) {
        if(closed)return;
        if(status!=TextToSpeech.SUCCESS){ttsIssue="Android text-to-speech failed to initialize.";cb.onStatus(ttsIssue);return;}
        if(tts.setLanguage(Locale.US)==TextToSpeech.LANG_MISSING_DATA){
            ttsIssue="English speech files are missing; install an offline English voice in Android settings.";
            cb.onStatus(ttsIssue);return;
        }
        Set<Voice> voices=tts.getVoices();
        if(voices!=null){
            Voice best=null;
            for(Voice v:voices) {
                if(!v.isNetworkConnectionRequired()&&"en".equalsIgnoreCase(v.getLocale().getLanguage())){
                    if(best==null||Locale.US.equals(v.getLocale())) best=v;
                    if(Locale.US.equals(v.getLocale()))break;
                }
            }
            if(best!=null&&tts.setVoice(best)==TextToSpeech.SUCCESS){
                voiceReady=true;cb.onStatus("Offline English voice available");return;
            }
        }
        ttsIssue="No installed offline English speech voice; Android may need an offline voice pack.";
        cb.onStatus(ttsIssue);
    }

    public void say(String words){
        if(closed||!speakingEnabled||!voiceReady||tts==null||words==null)return;
        if(VoiceTxBridge.isTransmitting()||RadioController.getInstance().isKeyed()){
            cb.onStatus("Spoken reply suppressed during radio transmission");
            return;
        }
        String clean=words.replaceAll("(?s)\u0060\u0060\u0060.*?\u0060\u0060\u0060","").trim();
        if(clean.length()>800)clean=clean.substring(0,800);
        if(clean.length()==0)return;
        Bundle options=new Bundle();
        options.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,0.9f);
        tts.speak(clean,TextToSpeech.QUEUE_FLUSH,options,"rumitx_v3_spoken_reply");
    }

    public void stopSpeech(){if(tts!=null)tts.stop();}

    public void listen(){
        if(closed)return;
        stopSpeech();
        if(host.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            host.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},1017);
            cb.onStatus("Allow microphone permission and tap MIC again");return;
        }
        if(!SpeechRecognizer.isRecognitionAvailable(host)){
            cb.onStatus("No Android speech recognizer installed. Text chat still works.");return;
        }
        if(listening){
            if(recognizer!=null)recognizer.stopListening();
            cb.onStatus("Finishing speech recognition…");return;
        }
        try{
            if(recognizer==null){
                recognizer=SpeechRecognizer.createSpeechRecognizer(host);
                recognizer.setRecognitionListener(new RecognitionListener(){
                    @Override public void onReadyForSpeech(Bundle b){cb.onStatus("Listening… speak now (Android recognizer, offline preferred)");}
                    @Override public void onBeginningOfSpeech(){cb.onStatus("Listening…");}
                    @Override public void onRmsChanged(float rms){}
                    @Override public void onBufferReceived(byte[] buffer){}
                    @Override public void onEndOfSpeech(){cb.onStatus("Recognizing speech…");}
                    @Override public void onError(int error){
                        listening=false;
                        cb.onStatus(error==SpeechRecognizer.ERROR_NETWORK||error==SpeechRecognizer.ERROR_NETWORK_TIMEOUT?
                          "Android voice recognition needs an offline speech pack, or network access. Try text chat.":
                          "Speech recognition error "+error+". Tap MIC to retry.");
                    }
                    @Override public void onResults(Bundle results){
                        listening=false;
                        ArrayList<String> phrases=results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                        if(phrases!=null&&!phrases.isEmpty()&&phrases.get(0)!=null&&!phrases.get(0).trim().isEmpty()){
                            cb.onSpeech(phrases.get(0).trim());
                        }else cb.onStatus("No speech recognized. Tap MIC to try again.");
                    }
                    @Override public void onPartialResults(Bundle partialResults){}
                    @Override public void onEvent(int eventType,Bundle params){}
                });
            }
            Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE,Locale.US.toLanguageTag());
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1);
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true);
            listening=true;
            recognizer.startListening(intent);
            cb.onStatus("Starting speech recognition · Android may use a network service");
        }catch(Exception e){
            listening=false;
            cb.onStatus("Could not start microphone: "+e.getMessage());
        }
    }

    public void close(){
        closed=true;
        if(recognizer!=null){recognizer.cancel();recognizer.destroy();recognizer=null;}
        if(tts!=null){tts.stop();tts.shutdown();tts=null;}
    }
}
