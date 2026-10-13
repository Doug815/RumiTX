package com.rumitx.app.repeaters;
import java.util.*;
public final class RepeaterParser {
 public static String norm(String s){return s.toLowerCase(Locale.US).replaceAll("[^a-z0-9]","");}
 private static String get(Map<String,String> m,String... names){for(String k:names){String v=m.get(norm(k));if(v!=null&&!v.trim().isEmpty())return v.trim();}return "";}
 private static Double number(String s){if(s.isEmpty())return null;try{double v=Double.parseDouble(s);return Double.isFinite(v)?v:null;}catch(Exception e){return null;}}
 private static Integer integer(String s){try{return Integer.valueOf(s);}catch(Exception e){return null;}}
 private static boolean yes(String s){return s.equalsIgnoreCase("yes")||s.equals("1")||s.equalsIgnoreCase("true");}
 private static String tone(String s){if(s.equalsIgnoreCase("CSQ")||s.equalsIgnoreCase("none")||s.equals("0")||s.equalsIgnoreCase("carrier"))return "";return s;}
 private static String dcs(String s){return s.toUpperCase(Locale.US).replace("DCS","").replaceFirst("^D","").trim();}
 public static Repeater parse(Map<String,String> values){Map<String,String> m=new HashMap<>();for(Map.Entry<String,String> e:values.entrySet())m.put(norm(e.getKey()),e.getValue());Repeater r=new Repeater();
  String out=get(m,"freq_out","Frequency","Output Frequency","Output","Output Freq");Double f=number(out);if(f==null||f<=0||f>2000)throw new IllegalArgumentException("Invalid output frequency");r.output=f;
  boolean chirp=m.containsKey("duplex");String duplex=get(m,"Duplex"),offset=get(m,"Offset","offset_mhz");String explicit=get(m,"freq_in","Input Freq","Input Frequency","Input","Uplink");
  if(!explicit.isEmpty()){r.input=number(explicit);if(r.input==null||r.input<=0||r.input>2000)throw new IllegalArgumentException("Invalid input frequency");}
  else if(chirp){if(duplex.equals("split")){r.input=number(offset);if(r.input==null)throw new IllegalArgumentException("Invalid split input");}else if(duplex.equals("+")||duplex.equals("-")){Double o=number(offset);if(o==null)throw new IllegalArgumentException("Invalid offset");r.input=r.output+(duplex.equals("+")?Math.abs(o):-Math.abs(o));}else if(duplex.equals("off")){r.input=null;}else if(duplex.isEmpty()){r.input=r.output;}else throw new IllegalArgumentException("Unsupported duplex");}
  else if(!offset.isEmpty()){Double o=number(offset);if(o==null)throw new IllegalArgumentException("Invalid offset");r.input=r.output+o;}
  if(r.input!=null&&(r.input<=0||r.input>2000))throw new IllegalArgumentException("Invalid TX frequency");
  r.callsign=get(m,"Callsign","Call","Call Sign");r.name=get(m,"Name","Landmark","Location","Nearest City");if(r.name.isEmpty())r.name=r.callsign.isEmpty()?String.format(Locale.US,"%.4f",r.output):r.callsign;
  r.city=get(m,"City","Nearest City");r.county=get(m,"County");r.state=get(m,"State","State Name","State ID");r.toneIn=tone(get(m,"tone_in","PL","CTCSS","Uplink Tone","Tone In"));r.toneOut=tone(get(m,"tone_out","TSQ","Downlink Tone","Tone Out"));r.dcsIn=dcs(get(m,"dcs_in","DCS In"));r.dcsOut=dcs(get(m,"dcs_out","DCS Out"));
  if(r.toneIn.toUpperCase(Locale.US).startsWith("D")){r.dcsIn=dcs(r.toneIn);r.toneIn="";}if(r.toneOut.toUpperCase(Locale.US).startsWith("D")){r.dcsOut=dcs(r.toneOut);r.toneOut="";}
  String tm=get(m,"ToneMode");if(chirp){String rt=tone(get(m,"rToneFreq")),ct=tone(get(m,"cToneFreq"));if(tm.equals("Tone"))r.toneIn=rt;else if(tm.equals("TSQL")){r.toneIn=rt;r.toneOut=ct.isEmpty()?rt:ct;}else if(tm.equals("DTCS")){r.dcsIn=dcs(get(m,"DtcsCode"));r.dcsOut=dcs(get(m,"RxDtcsCode"));if(r.dcsOut.isEmpty())r.dcsOut=r.dcsIn;}else if(tm.equals("Cross")){String cross=get(m,"CrossMode");String[] types=cross.split("->",-1);if(types.length==2){if(types[0].equals("Tone"))r.toneIn=rt;else if(types[0].equals("DTCS"))r.dcsIn=dcs(get(m,"DtcsCode"));if(types[1].equals("Tone"))r.toneOut=ct;else if(types[1].equals("DTCS"))r.dcsOut=dcs(get(m,"RxDtcsCode"));}}else if(!tm.isEmpty())throw new IllegalArgumentException("Unsupported tone mode");}
  if(chirp){String polarity=get(m,"DtcsPolarity").toUpperCase(Locale.US);if(polarity.length()==2){if(!r.dcsIn.isEmpty()&&polarity.charAt(0)=='R')r.dcsIn+="I";if(!r.dcsOut.isEmpty()&&polarity.charAt(1)=='R')r.dcsOut+="I";}}
  r.mode=get(m,"Mode","Operating Mode").toUpperCase(Locale.US);if(r.mode.isEmpty()){r.mode=yes(get(m,"DMR","DMR Capable"))?"DMR":yes(get(m,"P-25","P25"))?"P25":yes(get(m,"NXDN"))?"NXDN":yes(get(m,"D-Star","DStar"))?"DSTAR":yes(get(m,"YSF","System Fusion"))?"FUSION":"FM";}if(r.mode.equals("NFM")||r.mode.equals("ANALOG"))r.mode="FM";if(r.mode.equals("D-STAR"))r.mode="DSTAR";
  r.cc=integer(get(m,"dmr_cc","DMR Color Code","Color Code","ColorCode"));if(r.cc!=null&&(r.cc<0||r.cc>15))throw new IllegalArgumentException("Invalid DMR color code");r.ts=integer(get(m,"dmr_ts","Timeslot","Time Slot","TS"));if(r.ts!=null&&r.ts!=1&&r.ts!=2)throw new IllegalArgumentException("Invalid timeslot");r.tg=get(m,"dmr_tg","Talkgroup","Talkgroups","TG");r.dmrId=get(m,"dmr_id","DMR ID");
  String lat=get(m,"Latitude","Lat"),lon=get(m,"Longitude","Lon","Long");r.latitude=number(lat);r.longitude=number(lon);if(r.latitude==null||r.longitude==null||r.latitude<-90||r.latitude>90||r.longitude<-180||r.longitude>180){r.latitude=null;r.longitude=null;}
  String st=get(m,"Status","Operational Status").toLowerCase(Locale.US);r.status=st.contains("off")?"off-air":st.contains("on")||st.equals("1")?"on-air":"unknown";String use=get(m,"Use","Use/Membership","use_restriction").toLowerCase(Locale.US);r.restriction=use.contains("private")?"private":use.contains("closed")?"closed":"open";
  String source=get(m,"Source");r.source=source.isEmpty()?(chirp?"manual":"repeaterbook"):source.toLowerCase(Locale.US);r.updated=System.currentTimeMillis()/1000;return r;
 }
}
