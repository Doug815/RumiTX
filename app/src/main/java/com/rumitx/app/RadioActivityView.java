package com.rumitx.app;
import android.content.Context;import android.util.AttributeSet;import android.graphics.*;import android.view.View;
/** Animated event history, not an RF spectrum or RSSI measurement. */
public class RadioActivityView extends View {
 private final Paint p=new Paint(3);private final float[] history=new float[72];private long tick;private float level;
 public RadioActivityView(Context c,AttributeSet a){super(c,a);setContentDescription("Radio RX and TX activity history; not an RF spectrum");}
 protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight(),d=getResources().getDisplayMetrics().density;long now=android.os.SystemClock.uptimeMillis();RadioController r=RadioController.getInstance();boolean tx=r.isKeyed(),rx=r.isReceiving();int color=tx?0xffff6b00:rx?0xff00ff88:0xff00f0ff;
  if(now-tick>100){System.arraycopy(history,1,history,0,71);history[71]=tx?1:rx?.68f:0;tick=now;}level+=( (tx?1:rx?.68f:0)-level)*.18f;
  p.setStyle(Paint.Style.FILL);p.setColor(0xee0b1720);c.drawRoundRect(0,0,w,h,16*d,16*d,p);
  p.setStrokeWidth(d);p.setColor(0x223af4ff);for(int i=0;i<5;i++)c.drawLine(12*d,h*i/5,w-12*d,h*i/5,p);for(int i=0;i<12;i++)c.drawLine(w*i/12,0,w*i/12,h,p);
  p.setTypeface(Typeface.MONOSPACE);p.setTextSize(9*d);p.setColor(0xff83a9b8);c.drawText("ACTIVITY TELEMETRY / NOT RF SPECTRUM",12*d,19*d,p);
  for(int i=0;i<72;i++){float x=12*d+i*(w-24*d)/72,bar=history[i]*(h-50*d);p.setColor(history[i]>0?color:0xff174452);p.setShadowLayer(history[i]>0?6*d:0,0,0,color);c.drawRoundRect(x,h-17*d-bar,x+(w-24*d)/72-2*d,h-17*d,2*d,2*d,p);}p.clearShadowLayer();
  p.setColor(color);p.setTextSize(10*d);c.drawText(tx?"TX ACTIVE":rx?"RX · SQUELCH OPEN":"RX STANDBY",12*d,h-4*d,p);
  if(isShown())postInvalidateDelayed(50);
 }
}
