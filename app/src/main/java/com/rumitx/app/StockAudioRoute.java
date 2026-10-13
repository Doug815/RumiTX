package com.rumitx.app;
import android.content.Context;import android.media.AudioManager;
/** Vendor parameters recovered from stock VoiceRecorder, not a generated chirp. */
public final class StockAudioRoute {
 private final AudioManager audio;
 private final AudioManager.OnAudioFocusChangeListener focus=change -> RadioLog.i("RumiTX-Audio","focus="+change);
 public StockAudioRoute(Context c){audio=(AudioManager)c.getSystemService(Context.AUDIO_SERVICE);}
 private void parameter(String value){try{AudioManager.class.getMethod("setParameters",String.class).invoke(audio,value);RadioLog.i("RumiTX-Audio","setParameters "+value);}catch(Exception e){RadioLog.e("RumiTX-Audio","Vendor audio parameter failed "+value,e);}}
 public void prepare(){if(audio==null)return;int result=audio.requestAudioFocus(focus,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN);RadioLog.i("RumiTX-Audio","Stock music audio focus result="+result);parameter("SET_MIC_NUM=1");}
 public void onDriverResult(int value){if(audio==null)return;RadioLog.i("RumiTX-Audio","Driver result="+value+"; stock receive-open value=1");if(value==1){parameter("SET_MIC_NUM=2");parameter("RESET_MIC2_COUNTER=0");parameter("SET_LOOPBACK_TYPE=3, 3");}else stop();}
 public void stop(){if(audio==null)return;parameter("SET_MIC_CHOOSE=2");parameter("SET_LOOPBACK_TYPE=0");parameter("SET_MIC_NUM=1");}
 public void close(){stop();if(audio!=null)audio.abandonAudioFocus(focus);}
}
