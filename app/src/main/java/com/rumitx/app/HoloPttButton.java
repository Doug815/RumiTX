package com.rumitx.app;
import android.content.Context;import android.util.AttributeSet;import android.graphics.*;import android.widget.Button;
public class HoloPttButton extends Button {
 private final Paint p=new Paint(3);private boolean previous;
 public HoloPttButton(Context c,AttributeSet a){super(c,a);setBackgroundColor(Color.TRANSPARENT);setContentDescription("Hold to transmit; release to stop");}
 protected void onDraw(Canvas c){float d=getResources().getDisplayMetrics().density,x=getWidth()/2f,y=getHeight()/2f,r=Math.min(x,y)-12*d;boolean tx=RadioController.getInstance().isKeyed();int col=tx?0xffff6b00:0xff00f0ff;
  if(previous!=tx){previous=tx;performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);animate().scaleX(tx?1.04f:1f).scaleY(tx?1.04f:1f).setDuration(150).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();}
  p.setStyle(Paint.Style.FILL);p.setShader(new RadialGradient(x,y,r,new int[]{0xff112832,0xff050508},null,Shader.TileMode.CLAMP));c.drawCircle(x,y,r,p);p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2*d);p.setColor(col);p.setShadowLayer(tx?18*d:8*d,0,0,col);c.drawCircle(x,y,r-8*d,p);p.clearShadowLayer();p.setColor(tx?0x88ff6b00:0x4400f0ff);p.setStrokeWidth(d);c.drawCircle(x,y,r,p);
  float phase=(android.os.SystemClock.uptimeMillis()%1200)/1200f;if(tx){p.setAlpha((int)(160*(1-phase)));c.drawCircle(x,y,r-20*d+phase*25*d,p);p.setAlpha(255);}
  p.setStyle(Paint.Style.FILL);p.setColor(col);p.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));p.setTextAlign(Paint.Align.CENTER);p.setTextSize(12*d);String text=getText().toString();String label=text.contains("FAILED")?"PTT FAILED":tx?"TRANSMITTING":"HOLD TO TALK";float width=Math.max(d,2*(r-10*d));if(p.measureText(label)>width)p.setTextSize(p.getTextSize()*width/p.measureText(label));c.drawText(label,x,y+4*d,p);p.setTextSize(8*d);p.setColor(0xffa1b5c1);c.drawText(tx?"RELEASE TO END":"PUSH / F9",x,y+19*d,p);if(isShown())postInvalidateDelayed(50);
 }
}
