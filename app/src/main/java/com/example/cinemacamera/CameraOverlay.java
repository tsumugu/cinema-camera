package com.example.cinemacamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import java.util.Locale;
import java.util.function.Supplier;

/** UI-only guides, never included in the offscreen photo renderer. */
final class CameraOverlay extends View {
    private final Supplier<PreviewFrameInfo> frame;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rectangle=new RectF();
    private boolean grid,level,touching,hasFocus,exposureSupported;
    private float focusX,focusY,ev,minEv,maxEv;
    private int focusState;
    private long focusUntil;
    private LevelReading reading;
    private final int yellow=Color.rgb(235,201,100),green=Color.rgb(126,220,157);
    CameraOverlay(Context context,Supplier<PreviewFrameInfo> frame) {
        super(context); this.frame=frame; setFocusable(true); setClickable(true);
        setContentDescription("タップでフォーカス、上下にドラッグして明るさを調整");
    }
    void setGrid(boolean value) { grid=value; invalidate(); }
    void setLevel(boolean value) { level=value; invalidate(); }
    void setReading(LevelReading value) { reading=value; if(level) invalidate(); }
    void clearIndicator() { hasFocus=false; touching=false; invalidate(); }
    void showFocus(float x,float y,float ev,float min,float max,boolean supported) {
        focusX=x; focusY=y; this.ev=ev; minEv=min; maxEv=max;
        exposureSupported=supported; focusState=0; hasFocus=true;
        focusUntil=SystemClock.uptimeMillis()+3500; invalidate();
    }
    void focusResult(boolean success) { focusState=success?1:2; invalidate(); }
    void setTouching(boolean value) {
        touching=value; focusUntil=SystemClock.uptimeMillis()+3500; invalidate();
    }
    void exposure(float ev) {
        this.ev=ev; focusUntil=SystemClock.uptimeMillis()+3500; invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        PreviewFrameInfo info=frame.get();
        if(info==null||getWidth()==0||getHeight()==0) return;
        int[] b=info.bounds(getWidth(),getHeight());
        float left=b[0],top=b[1],right=left+b[2],bottom=top+b[3];
        canvas.save(); canvas.clipRect(left,top,right,bottom);
        if(grid) {
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); paint.setColor(0x80ffffff);
            for(int i=1;i<3;i++) {
                float x=left+b[2]*i/3f,y=top+b[3]*i/3f;
                canvas.drawLine(x,top,x,bottom,paint); canvas.drawLine(left,y,right,y,paint);
            }
        }
        if(level&&reading!=null) drawLevel(canvas,(left+right)/2,(top+bottom)/2);
        if(hasFocus&&(touching||SystemClock.uptimeMillis()<focusUntil)) {
            drawFocus(canvas,left,top,right,bottom);
            postInvalidateDelayed(100);
        }
        canvas.restore();
    }
    private void drawLevel(Canvas canvas,float x,float y) {
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2));
        if(reading.flat) {
            paint.setColor(0x99ffffff); cross(canvas,x,y,dp(10));
            paint.setColor(reading.aligned?green:yellow);
            float dx=reading.aligned?0:reading.bubbleX*dp(80);
            float dy=reading.aligned?0:reading.bubbleY*dp(80);
            cross(canvas,x+dx,y+dy,dp(6));
        } else {
            canvas.save(); canvas.translate(x,y); canvas.rotate(reading.referenceAngle);
            paint.setColor(0x99ffffff);
            canvas.drawLine(-dp(62),0,-dp(38),0,paint); canvas.drawLine(dp(38),0,dp(62),0,paint);
            paint.setColor(reading.aligned?green:yellow);
            canvas.rotate(reading.aligned?0:reading.error);
            canvas.drawLine(-dp(34),0,dp(34),0,paint);
            canvas.restore();
        }
    }
    private void cross(Canvas canvas,float x,float y,float radius) {
        canvas.drawLine(x-radius,y,x+radius,y,paint);
        canvas.drawLine(x,y-radius,x,y+radius,paint);
    }
    private void drawFocus(Canvas canvas,float left,float top,float right,float bottom) {
        float radius=Math.min(dp(30),Math.min((right-left)/3,(bottom-top)/3));
        float x=Math.max(left+radius,Math.min(right-radius,focusX));
        float y=Math.max(top+radius,Math.min(bottom-radius,focusY));
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.5f));
        paint.setColor(focusState==2?0xffb4b4ad:yellow);
        rectangle.set(x-radius,y-radius,x+radius,y+radius);
        canvas.drawRoundRect(rectangle,dp(3),dp(3),paint);
        if(!exposureSupported) return;
        float sunX=x+radius+dp(16);
        if(sunX+dp(20)>right) sunX=x-radius-dp(16);
        float range=Math.max(Math.abs(minEv),Math.abs(maxEv));
        float offset=range>0 ? ev/range*dp(40) : 0;
        float sunY=Math.max(top+dp(18),Math.min(bottom-dp(18),y-offset));
        paint.setColor(0x99ebc964);
        canvas.drawLine(sunX,Math.max(top+dp(10),y-dp(46)),sunX,
            Math.min(bottom-dp(10),y+dp(46)),paint);
        paint.setColor(yellow); canvas.drawCircle(sunX,sunY,dp(3.5f),paint);
        for(int i=0;i<8;i++) {
            double angle=i*Math.PI/4;
            canvas.drawLine(sunX+(float)Math.cos(angle)*dp(6),sunY+(float)Math.sin(angle)*dp(6),
                sunX+(float)Math.cos(angle)*dp(8),sunY+(float)Math.sin(angle)*dp(8),paint);
        }
        paint.setStyle(Paint.Style.FILL); paint.setTextSize(dp(11)); paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(String.format(Locale.JAPAN,"%+.1f",ev),sunX,
            Math.min(bottom-dp(3),sunY+dp(24)),paint);
    }
    private float dp(float value) { return value*getResources().getDisplayMetrics().density; }
}
