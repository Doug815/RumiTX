package com.rumitx.app;
import android.content.Context;import android.util.AttributeSet;import android.graphics.*;import android.widget.TextView;
public class GlowingFrequencyView extends TextView {
 private final Paint scan=new Paint();
 public GlowingFrequencyView(Context c,AttributeSet a){super(c,a);}
 protected void onDraw(Canvas c){super.onDraw(c);float d=getResources().getDisplayMetrics().density;scan.setColor(0x26050508);scan.setStrokeWidth(d);for(float y=0;y<getHeight();y+=4*d)c.drawLine(0,y,getWidth(),y,scan);scan.setColor(0x1600f0ff);float y=(android.os.SystemClock.uptimeMillis()%5000)*getHeight()/5000f;c.drawRect(0,y,getWidth(),y+2*d,scan);if(isShown())postInvalidateDelayed(70);}
}
