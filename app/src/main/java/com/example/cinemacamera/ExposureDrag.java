package com.example.cinemacamera;

public final class ExposureDrag {
    private ExposureDrag() {}
    /** Project a touch displacement onto the rotated indicator's vertical axis. */
    public static float verticalDelta(int degrees,float dx,float dy) {
        switch(degrees) {
            case 0: return dy;
            case 90: return -dx;
            case 180: return -dy;
            case 270: return dx;
            default: throw new IllegalArgumentException("Right-angle orientation required");
        }
    }
    /** Upward movement increases exposure; quantization uses the device's EV step. */
    public static int index(int startIndex,float deltaY,float pixelsPerEv,float evStep,int low,int high) {
        if(!Float.isFinite(deltaY)||!Float.isFinite(pixelsPerEv)||pixelsPerEv<=0
                ||!Float.isFinite(evStep)||evStep<=0||low>high)
            throw new IllegalArgumentException("Invalid exposure gesture");
        double target=startIndex-(double)deltaY/pixelsPerEv/evStep;
        return (int)Math.max(low,Math.min(high,Math.round(target)));
    }
}
