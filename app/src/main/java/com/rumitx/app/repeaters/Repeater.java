package com.rumitx.app.repeaters;
public final class Repeater {
 public long id,updated;public String callsign="",name="",toneIn="",toneOut="",dcsIn="",dcsOut="",mode="FM",tg="",dmrId="",city="",county="",state="",status="unknown",restriction="open",source="manual";
 public double output;public Double input,latitude,longitude;public Integer cc,ts;
 public boolean located(){return latitude!=null&&longitude!=null;}
 public double offset(){return input==null?0:input-output;}
 public String title(){return (callsign.isEmpty()?name:callsign)+(name.isEmpty()||name.equals(callsign)?"":" · "+name);}
}
