package com.rumitx.app.tak;
import android.content.*;import com.rumitx.app.DmrIdentity;import java.util.UUID;
public final class TakSettings {
 private TakSettings(){}
 public static SharedPreferences prefs(Context c){return c.getSharedPreferences("tak_sharing",0);}
 public static synchronized String uid(Context c){SharedPreferences p=prefs(c);String value=p.getString("uid","");if(value.isEmpty()){value="RumiTX-"+UUID.randomUUID();p.edit().putString("uid",value).apply();}return value;}
 public static String callsign(Context c){String name=DmrIdentity.name(c);return name.isEmpty()?"RumiTX-"+uid(c).substring(uid(c).length()-6):name;}
 public static int interval(Context c){int n=prefs(c).getInt("interval",60);return n==15||n==30||n==60||n==300?n:60;}
 public static boolean enabled(Context c,String key){return prefs(c).getBoolean(key,false);}
 public static boolean active(Context c){return enabled(c,"pli")||enabled(c,"repeaters")||enabled(c,"emergency");}
 public static void set(Context c,String key,boolean value){prefs(c).edit().putBoolean(key,value).apply();}
 public static void refresh(Context c){GeoChatService.refresh(c);Intent i=new Intent(c,TakService.class).setAction("REFRESH");if(active(c))c.startForegroundService(i);else c.startService(i.setAction("STOP"));}
}
