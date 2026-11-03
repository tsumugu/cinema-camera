package com.example.cinemacamera;

public final class ExposureDrag {
    private ExposureDrag() {}
    /** Upward movement increases exposure; quantization uses the device's EV step. */
    public static int index(int startIndex,float deltaY,float pixelsPerEv,float evStep,int low,int high) {
        if(!Float.isFinite(deltaY)||!Float.isFinite(pixelsPerEv)||pixelsPerEv<=0
                ||!Float.isFinite(evStep)||evStep<=0||low>high)
            throw new IllegalArgumentException("Invalid exposure gesture");
        double target=startIndex-(double)deltaY/pixelsPerEv/evStep;
        return (int)Math.max(low,Math.min(high,Math.round(target)));
    }
}
