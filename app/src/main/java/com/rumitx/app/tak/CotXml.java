package com.rumitx.app.tak;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
/** Legacy CoT XML, one event per UDP datagram. No Android dependencies. */
public final class CotXml {
 private CotXml() {}
 public static String escape(String value){if(value==null)return "";StringBuilder s=new StringBuilder();for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c<32&&c!='\n'&&c!='\r'&&c!='\t')continue;switch(c){case '&':s.append("&amp;");break;case '<':s.append("&lt;");break;case '>':s.append("&gt;");break;case '"':s.append("&quot;");break;case '\'':s.append("&apos;");break;default:s.append(c);}}return s.toString();}
 public static String utc(long time){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date(time));}
 public static final class Fix {
  public final double lat,lon,alt,accuracy,speed,course;
  public Fix(double a,double b,double h,double e,double s,double c){if(!Double.isFinite(a)||!Double.isFinite(b)||a< -90||a>90||b< -180||b>180)throw new IllegalArgumentException("Invalid location");lat=a;lon=b;alt=Double.isFinite(h)?h:999999;accuracy=Double.isFinite(e)&&e>=0?e:999999;speed=Double.isFinite(s)&&s>=0?s:0;course=Double.isFinite(c)?((c%360)+360)%360:0;}
 }
 private static String event(String uid,String type,Fix f,long now,long lifetime,String detail,String how){return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<event version=\"2.0\" uid=\""+escape(uid)+"\" type=\""+type+"\" how=\""+how+"\" time=\""+utc(now)+"\" start=\""+utc(now)+"\" stale=\""+utc(now+lifetime)+"\"><point lat=\""+f.lat+"\" lon=\""+f.lon+"\" hae=\""+f.alt+"\" ce=\""+f.accuracy+"\" le=\"999999\"/><detail>"+detail+"</detail></event>";}
 public static String pli(String uid,String callsign,Fix f,long now,int interval,boolean emergency){String detail="<contact callsign=\""+escape(callsign)+"\"/><track speed=\""+f.speed+"\" course=\""+f.course+"\"/><remarks>RumiTX 2030 position</remarks>";if(emergency)detail+="<emergency type=\"911 Alert\">"+escape(callsign)+"</emergency>";return event(uid,"a-f-G-U-C-I",f,now,Math.max(60000L,interval*3000L),detail,"m-g");}
 public static String alert(String uid,String callsign,Fix f,long now,boolean cancel){String detail="<link uid=\""+escape(uid)+"\" type=\"a-f-G-U-C-I\" relation=\"p-p\"/><contact callsign=\""+escape(callsign)+"-Alert\"/><emergency "+(cancel?"cancel=\"true\"":"type=\"911 Alert\"")+">"+escape(callsign)+"</emergency>";return event(uid+"-emergency",cancel?"b-a-o-can":"b-a-o-tbl",f,now,60000,detail,"m-g");}
 public static String repeater(String uid,String callsign,Fix f,String notes,long now){return event(uid,"a-f-G-U-C-R",f,now,1800000,"<contact callsign=\""+escape(callsign)+"\"/><remarks>"+escape(notes)+"</remarks>","h-e");}
}
