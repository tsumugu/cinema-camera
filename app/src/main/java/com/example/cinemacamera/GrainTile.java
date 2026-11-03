package com.example.cinemacamera;

import java.nio.ByteBuffer;
import java.util.Random;

/** Repeatable, zero-centered luminance grain. No colored noise. */
public final class GrainTile {
    public static final int SIZE = 256;
    private GrainTile() {}

    public static ByteBuffer create() {
        ByteBuffer data = ByteBuffer.allocateDirect(SIZE * SIZE * 4);
        Random random = new Random(17031L);
        for (int i = 0; i < SIZE * SIZE; i++) {
            byte value = (byte) random.nextInt(256);
            data.put(value).put(value).put(value).put((byte) 255);
        }
        data.rewind();
        return data;
    }
}
