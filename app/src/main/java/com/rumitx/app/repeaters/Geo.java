package com.rumitx.app.repeaters;
public final class Geo {
 public static double distanceKm(double a,double b,double c,double d){double lat=Math.toRadians(c-a),lon=Math.toRadians(d-b);double q=Math.sin(lat/2)*Math.sin(lat/2)+Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(c))*Math.sin(lon/2)*Math.sin(lon/2);return 6371.0088*2*Math.atan2(Math.sqrt(q),Math.sqrt(Math.max(0,1-q)));}
 public static double bearing(double a,double b,double c,double d){double delta=Math.toRadians(d-b);double y=Math.sin(delta)*Math.cos(Math.toRadians(c)),x=Math.cos(Math.toRadians(a))*Math.sin(Math.toRadians(c))-Math.sin(Math.toRadians(a))*Math.cos(Math.toRadians(c))*Math.cos(delta);return (Math.toDegrees(Math.atan2(y,x))+360)%360;}
 public static double wrap(double lon){return ((lon+180)%360+360)%360-180;}
}
