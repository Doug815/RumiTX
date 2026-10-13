package com.rumitx.app;
import android.app.Activity;import android.content.Intent;
import java.io.*;import java.util.concurrent.TimeUnit;
public final class RxDiagnostics {
 private static final String COMMAND="echo '--- SELinux ---'; getenforce; echo '--- driver ---'; ls -l /dev/hctintercom_node /dev/ttyMT1; echo '--- service threads ---'; for p in $(pidof vendor.hct.hardware.talkie@1.0-service); do echo PID=$p; ls -l /proc/$p/fd; for t in /proc/$p/task/*; do echo THREAD=$t; cat $t/comm $t/wchan $t/syscall $t/stack 2>&1; done; done; echo '--- kernel ---'; dmesg | grep -iE 'talkie|hct|intercom|speak|eint|aw.pa' | tail -100";
 public static void share(Activity a){new Thread(() -> {
  String header="RumiTX 2.2 RX DIAGNOSTICS\n"+RadioLog.snapshot();String root;
  try {Process proc=new ProcessBuilder("su","-c",COMMAND).redirectErrorStream(true).start();ByteArrayOutputStream bytes=new ByteArrayOutputStream();Thread reader=new Thread(() -> {try{byte[] b=new byte[2048];int n;while((n=proc.getInputStream().read(b))!=-1){synchronized(bytes){if(bytes.size()<48000)bytes.write(b,0,Math.min(n,48000-bytes.size()));}}}catch(IOException ignored){}},"RumiTX-diagnostic-reader");reader.setDaemon(true);reader.start();boolean ended=proc.waitFor(8,TimeUnit.SECONDS);if(!ended)proc.destroy();reader.join(500);synchronized(bytes){root=bytes.toString("UTF-8");}if(!ended)root+="\nRoot collection timed out; app logs above remain valid.";}
  catch(Exception e){root="Root capture unavailable: "+e;}
  String report=header+"\nREAD-ONLY DEVICE SNAPSHOT\n"+root;
  try{File file=new File(a.getFilesDir(),"rx-diagnostics.txt");try(FileOutputStream out=new FileOutputStream(file)){out.write(report.getBytes("UTF-8"));}}catch(IOException ignored){}
  a.runOnUiThread(() -> {if(a.isFinishing())return;Intent send=new Intent(Intent.ACTION_SEND);send.setType("text/plain");send.putExtra(Intent.EXTRA_SUBJECT,"RumiTX 2.2 RX diagnostics");send.putExtra(Intent.EXTRA_TEXT,report);a.startActivity(Intent.createChooser(send,"Send RX diagnostics"));});
 },"RumiTX-rx-diagnostics").start();}
}
