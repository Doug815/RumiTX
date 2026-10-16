package com.rumitx.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/** Lightweight animated transceiver spectrum: active only while the AI is processing. */
public final class RumiThinkingView extends View {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean active;
    private long startedAt;
    private int cyan=Color.rgb(0,229,255);
    private final RectF rect=new RectF();
    public RumiThinkingView(Context context){
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        setVisibility(GONE);
        setContentDescription("RumiTX is generating a response");
    }
    public void setActive(boolean on){
        if(active==on)return;
        active=on;startedAt=SystemClock.uptimeMillis();
        setVisibility(on?VISIBLE:GONE);invalidate();
    }
    private void rounded(Canvas c,float x,float y,float w,float h,float r,int color){
        p.setStyle(Paint.Style.FILL);p.setColor(color);p.clearShadowLayer();
        rect.set(x,y,x+w,y+h);c.drawRoundRect(rect,r,r,p);
    }
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);if(!active)return;
        float scale=getResources().getDisplayMetrics().density;
        float w=getWidth(),h=getHeight();
        rounded(c,0,0,w,h,11*scale,Color.rgb(9,27,39));
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1*scale);p.setColor(Color.rgb(34,88,105));
        rect.set(1*scale,1*scale,w-scale,h-scale);c.drawRoundRect(rect,10*scale,10*scale,p);
        // Compact radio body and antenna.
        float x=14*scale, y=10*scale;
        p.setColor(cyan);p.setStrokeWidth(2*scale);p.setStyle(Paint.Style.STROKE);
        c.drawLine(x+8*scale,y+4*scale,x+4*scale,y-5*scale,p);
        rounded(c,x,y,29*scale,31*scale,5*scale,Color.rgb(20,55,67));
        rounded(c,x+5*scale,y+5*scale,19*scale,7*scale,2*scale,Color.rgb(20,154,178));
        rounded(c,x+5*scale,y+17*scale,19*scale,2*scale,1*scale,Color.rgb(71,128,142));
        rounded(c,x+5*scale,y+22*scale,13*scale,2*scale,1*scale,Color.rgb(71,128,142));
        double t=(SystemClock.uptimeMillis()-startedAt)/170.0;
        boolean lit=Math.sin(t)>0;
        p.setColor(lit?cyan:Color.rgb(35,91,105));p.setStyle(Paint.Style.FILL);
        c.drawCircle(x+24*scale,y+24*scale,2*scale,p);
        // Moving audio/signal bars. Modest refresh rate for the Cortex-A53.
        float left=58*scale,right=w-16*scale;
        int bars=15;float available=right-left;
        for(int i=0;i<bars;i++){
            double v=Math.abs(Math.sin(t*0.35+i*0.75)*Math.cos(t*0.27+i*0.22));
            float bh=(float)((8+v*26)*scale);
            float bx=left+i*(available/bars),by=(h-bh)/2;
            int a=105+(int)(v*150);
            p.setColor(Color.argb(a,0,229,255));
            p.setStyle(Paint.Style.FILL);
            rect.set(bx,by,bx+Math.max(2*scale,available/bars-5*scale),by+bh);
            c.drawRoundRect(rect,2*scale,2*scale,p);
        }
        postInvalidateDelayed(75);
    }
}
