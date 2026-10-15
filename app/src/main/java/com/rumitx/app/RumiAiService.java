package com.rumitx.app;

import android.app.*;
import android.content.*;
import android.os.*;
import android.util.Log;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dedicated foreground process supervisor. Does not touch radio PTT / HIDL / DMR. */
public final class RumiAiService extends Service {
    public static final String START="com.rumitx.app.V3_START", STOP="com.rumitx.app.V3_STOP";
    private static volatile String state="not started";
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private volatile boolean stopped=false;
    private volatile Process process;
    public static String status(){return state;}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("rumitx_v3","RumiTX 2030 AI",NotificationManager.IMPORTANCE_LOW));
        startForeground(343,notification());
    }
    private Notification notification(){
        Intent open=new Intent(this,RumiAiActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,343,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,344,new Intent(this,RumiAiService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"rumitx_v3").setContentTitle("RumiTX 2030 · V3 local AI").setContentText(state).setContentIntent(pi)
            .setSmallIcon(R.drawable.ic_tak_notification).addAction(new Notification.Action.Builder(null,"STOP AI",stop).build())
            .setOngoing(true).setOnlyAlertOnce(true).build();
    }
    private void update(String s){state=s;try{((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(343,notification());}catch(Exception ignored){}}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(process!=null&&process.isAlive())return START_NOT_STICKY;
        executor.execute(this::launch);
        return START_NOT_STICKY;
    }
    private void launch(){
        if(stopped)return;
        File executable=new File(getFilesDir(),"llama-server"),library=new File(getFilesDir(),"libomp.so"),model=new File(getFilesDir(),"v3_model.gguf");
        if(!executable.isFile()||!executable.canExecute()||!library.isFile()||!model.isFile()){update("missing S80 runtime or GGUF model");return;}
        if(model.length()!=1107409344L){update("model size mismatch");return;}
        try{
            update("loading V3 model · this may take a minute");
            ProcessBuilder pb=new ProcessBuilder(Arrays.asList(executable.getAbsolutePath(),"-m",model.getAbsolutePath(),"--host","127.0.0.1","--port","8080","-c","1024","--threads","4"));
            pb.directory(getFilesDir());pb.environment().put("LD_LIBRARY_PATH",getFilesDir().getAbsolutePath());
            pb.redirectErrorStream(true);
            process=pb.start();
            final Process p=process;
            Thread drain=new Thread(()->{
                try(BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream()))){
                    String line;while((line=r.readLine())!=null&&!stopped){Log.i("RumiTX-V3",line.length()>500?line.substring(0,500):line);}
                }catch(IOException e){Log.w("RumiTX-V3","log reader stopped",e);}
            },"RumiTX-V3-logs");drain.setDaemon(true);drain.start();
            for(int i=0;i<150&&!stopped;i++){
                if(!p.isAlive()){update("V3 engine exited early · check logcat RumiTX-V3");return;}
                if(isReady()){update("READY · fully offline");return;}
                Thread.sleep(1000);
            }
            if(!stopped)update("V3 startup timeout · check logcat");
        }catch(Exception e){update("V3 launch error: "+e.getMessage());Log.e("RumiTX-V3","cannot launch",e);}
    }
    private boolean isReady(){
        HttpURLConnection c=null;
        try{c=(HttpURLConnection)new URL("http://127.0.0.1:8080/health").openConnection();c.setConnectTimeout(450);c.setReadTimeout(450);
            return c.getResponseCode()==200;
        }catch(Exception e){return false;}finally{if(c!=null)c.disconnect();}
    }
    @Override public void onDestroy(){
        stopped=true;Process p=process;if(p!=null){p.destroy();try{p.waitFor(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}if(p.isAlive())p.destroyForcibly();}
        executor.shutdownNow();update("AI stopped · radio remains active");stopForeground(true);super.onDestroy();
    }
}
