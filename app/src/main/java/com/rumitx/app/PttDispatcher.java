package com.rumitx.app;
import java.util.concurrent.*;
/** Ordered hardware operations. UI events only publish intent, never perform I/O. */
public final class PttDispatcher {
 public interface Transport {boolean down();boolean up();}
 public interface Listener {void state(boolean keyed,String error);}
 private final ExecutorService worker=Executors.newSingleThreadExecutor(r -> new Thread(r,"RumiTX-radio-io"));
 private final Transport radio;private final Listener listener;
 private volatile boolean wanted,closed;
 public PttDispatcher(Transport r,Listener l){radio=r;listener=l;}
 public synchronized void setTransmit(boolean down){
  if(VoiceTxBridge.isTransmitting()) {wanted=false;return;}
  if(closed||(wanted==down&&down))return;
  wanted=down;
  worker.execute(() -> {
   if(down&&!wanted)return; // released before queued press ran
   if(VoiceTxBridge.isTransmitting()){wanted=false;return;}
   try {
    if(down){boolean ok=radio.down();if(!wanted){radio.up();listener.state(false,null);}else listener.state(ok,ok?null:"PTT DOWN failed");}
    else {boolean ok=radio.up();listener.state(false,ok?null:"PTT UP not acknowledged");}
   }catch(RuntimeException e){wanted=false;try{radio.up();}catch(RuntimeException ignored){}listener.state(false,e.toString());}
  });
 }
 public synchronized void execute(Runnable command){if(!closed)worker.execute(()->{if(!VoiceTxBridge.isTransmitting())command.run();});}
 public synchronized void close(Runnable disconnect){if(closed)return;wanted=false;closed=true;worker.execute(() -> {try{radio.up();}catch(RuntimeException ignored){}finally{disconnect.run();}});worker.shutdown();}
}
