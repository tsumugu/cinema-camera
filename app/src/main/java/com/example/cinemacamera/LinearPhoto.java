package com.example.cinemacamera;

import java.nio.FloatBuffer;

/** Scene-linear sRGB, still floating point. Quantization happens after the look. */
public final class LinearPhoto {
    public final int width, height;
    public final FloatBuffer rgba;
    public LinearPhoto(int width,int height,FloatBuffer rgba) {
        this.width=width; this.height=height; this.rgba=rgba;
    }
}
