package com.rumitx.app;
import android.content.*;
public final class DmrIdentity {
 private DmrIdentity(){}
 public static String name(Context c){return c.getSharedPreferences("dmr_identity",0).getString("name","");}
 public static String id(Context c){return c.getSharedPreferences("dmr_identity",0).getString("id","");}
 public static void save(Context c,String name,String id){String value=id.trim();if(!value.isEmpty()){long n;try{n=Long.parseLong(value);}catch(NumberFormatException e){throw new IllegalArgumentException("DMR ID must be numeric");}if(n<1||n>16777214)throw new IllegalArgumentException("DMR ID must be 1–16777214");value=""+n;}c.getSharedPreferences("dmr_identity",0).edit().putString("name",name.trim()).putString("id",value).apply();RadioController.getInstance().setIdentity(value);}
 public static String label(Context c){String n=name(c),id=id(c);return n.isEmpty()&&id.isEmpty()?"DMR IDENTITY · TAP TO SET":(n.isEmpty()?"RADIO":n)+" · DMR "+(id.isEmpty()?"ID NOT SET":id);}
 // TODO: Radio Name UART command is unverified. Never send guessed commands.
 // DMR ID is applied only in the documented 68 36 phoneid field when programming a digital channel.
}
