package com.example.cinemacamera;

/** Gravity-derived world horizon, including a flat-lay crosshair mode. */
public final class LevelReading {
    public final boolean flat,aligned;
    public final float angle,referenceAngle,error,bubbleX,bubbleY;
    private LevelReading(boolean flat,float angle,float referenceAngle,float error,float x,float y) {
        this.flat=flat; this.angle=angle; this.referenceAngle=referenceAngle;
        this.error=error; bubbleX=x; bubbleY=y; aligned=Math.abs(error)<=1.5f;
    }
    public static LevelReading fromGravity(float x,float y,float z) {
        double length=Math.sqrt((double)x*x+(double)y*y+(double)z*z);
        if(!Double.isFinite(length)||length<0.01) return null;
        if(Math.abs(z)/length>0.85) {
            float error=(float)Math.toDegrees(Math.atan2(Math.hypot(x,y),Math.abs(z)));
            return new LevelReading(true,0,0,error,(float)(x/length),(float)(-y/length));
        }
        float angle=(float)Math.toDegrees(Math.atan2(x,y));
        float axis=Math.round(angle/90f)*90f;
        return new LevelReading(false,angle,axis,angle-axis,0,0);
    }
}
