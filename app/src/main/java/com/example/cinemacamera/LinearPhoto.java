package com.example.cinemacamera;

import java.nio.FloatBuffer;

/** Scene-linear sRGB, still floating point. Quantization happens after the look. */
public final class LinearPhoto {
    public final int width, height;
    public final FloatBuffer rgba;
    public final float nearSaturatedFraction;
    public LinearPhoto(int width,int height,FloatBuffer rgba) { this(width,height,rgba,-1); }
    public LinearPhoto(int width,int height,FloatBuffer rgba,float nearSaturatedFraction) {
        this.width=width; this.height=height; this.rgba=rgba; this.nearSaturatedFraction=nearSaturatedFraction;
    }
}
