package com.example.cinemacamera;

/** Pure geometry shared by rendering and host-side verification. */
public final class FrameGeometry {
    private FrameGeometry() {}

    /** Center-fit the entire photo, without hiding edges the JPEG will keep. */
    public static int[] fit(int imageWidth, int imageHeight, int viewWidth, int viewHeight) {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive");
        }
        double scale = Math.min((double) viewWidth / imageWidth, (double) viewHeight / imageHeight);
        int width = Math.max(1, (int) Math.round(imageWidth * scale));
        int height = Math.max(1, (int) Math.round(imageHeight * scale));
        return new int[]{(viewWidth - width) / 2, (viewHeight - height) / 2, width, height};
    }
}
