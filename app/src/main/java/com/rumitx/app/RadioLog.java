package com.rumitx.app;
public final class RadioLog {
 private static final java.util.ArrayDeque<String> events=new java.util.ArrayDeque<>();
 private static synchronized void record(String tag,String message,Throwable error){if(events.size()>=600)events.removeFirst();events.addLast(new java.text.SimpleDateFormat("HH:mm:ss.SSS",java.util.Locale.US).format(new java.util.Date())+" ["+Thread.currentThread().getName()+"] "+tag+" "+message+(error==null?"":" "+android.util.Log.getStackTraceString(error)));}
 public static int i(String tag,String message){record(tag,message,null);return android.util.Log.i(tag,message);}
 public static int e(String tag,String message){record(tag,message,null);return android.util.Log.e(tag,message);}
 public static int e(String tag,String message,Throwable error){record(tag,message,error);return android.util.Log.e(tag,message,error);}
 public static synchronized String snapshot(){StringBuilder s=new StringBuilder();for(String e:events)s.append(e).append("\n");return s.toString();}
}
