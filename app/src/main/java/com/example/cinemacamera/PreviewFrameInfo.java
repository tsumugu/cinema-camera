package com.example.cinemacamera;

/** Immutable description of the particular frame currently visible on screen. */
public final class PreviewFrameInfo {
    public final int session,sourceWidth,sourceHeight,left,top,cropWidth,cropHeight,rotation;
    public final int displayWidth,displayHeight;
    public PreviewFrameInfo(int session,int sourceWidth,int sourceHeight,int left,int top,
            int cropWidth,int cropHeight,int rotation,int displayWidth,int displayHeight) {
        if(sourceWidth<=0||sourceHeight<=0||cropWidth<=0||cropHeight<=0||left<0||top<0
                ||left+cropWidth>sourceWidth||top+cropHeight>sourceHeight
                ||rotation<0||rotation>=360||rotation%90!=0||displayWidth<=0||displayHeight<=0)
            throw new IllegalArgumentException("Invalid preview geometry");
        this.session=session; this.sourceWidth=sourceWidth; this.sourceHeight=sourceHeight;
        this.left=left; this.top=top; this.cropWidth=cropWidth; this.cropHeight=cropHeight;
        this.rotation=rotation; this.displayWidth=displayWidth; this.displayHeight=displayHeight;
    }
    public int[] bounds(int viewWidth,int viewHeight) {
        return FrameGeometry.fit(displayWidth,displayHeight,viewWidth,viewHeight);
    }
    /** Invert letterboxing, crop and clockwise rotation to unrotated buffer XY. */
    public float[] bufferPoint(float x,float y,int viewWidth,int viewHeight) {
        if(viewWidth<=0||viewHeight<=0||!Float.isFinite(x)||!Float.isFinite(y)) return null;
        int[] b=bounds(viewWidth,viewHeight);
        if(x<b[0]||y<b[1]||x>b[0]+b[2]||y>b[1]+b[3]) return null;
        float u=(x-b[0])/b[2],v=(y-b[1])/b[3],rawU=u,rawV=v;
        switch(rotation) {
            case 90: rawU=v; rawV=1-u; break;
            case 180: rawU=1-u; rawV=1-v; break;
            case 270: rawU=1-v; rawV=u; break;
        }
        return new float[]{(left+rawU*cropWidth)/sourceWidth,(top+rawV*cropHeight)/sourceHeight};
    }
}
