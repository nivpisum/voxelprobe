package com.debugbridge.core.recording;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Pure-pixel composition of N captured frames into a single
 * {@code gridRows × gridCols} grid image, row-major, frame 0 in the top-left.
 *
 * <p>All input frames must share the same dimensions ({@code frameWidth ×
 * frameHeight}). The output canvas is exactly {@code (cols * frameWidth) ×
 * (rows * frameHeight)}; trailing slots in the last row (when the frame count
 * doesn't fill the grid) are left transparent black (RGB 0, alpha 0 → JPEG
 * renders that as solid black, which reads as "no frame here").
 *
 * <p>No MC dependency — pure JDK; safe to unit-test.
 */
public final class GridComposer {
    private GridComposer() {}

    /**
     * Compose the frames into a single image.
     *
     * @param framePixels    one ARGB int[] per frame (row-major, length =
     *                       frameWidth * frameHeight). Frame ordering is the
     *                       list ordering.
     * @param frameWidth     per-frame pixel width.
     * @param frameHeight    per-frame pixel height.
     * @param gridCols       number of columns; rows derived from frame count.
     * @return a fresh {@link BufferedImage} of TYPE_INT_RGB ready for JPEG
     *         encoding via {@code JpegEncoder.writeJpeg(BufferedImage, ...)}.
     */
    public static BufferedImage compose(List<int[]> framePixels, int frameWidth, int frameHeight, int gridCols) {
        if (frameWidth <= 0 || frameHeight <= 0) {
            throw new IllegalArgumentException("frame dimensions must be positive");
        }
        if (gridCols < 1) {
            throw new IllegalArgumentException("gridCols must be >= 1");
        }
        int frameCount = framePixels.size();
        if (frameCount < 1) {
            throw new IllegalArgumentException("framePixels must be non-empty");
        }
        int gridRows = (frameCount + gridCols - 1) / gridCols;

        int outW = gridCols * frameWidth;
        int outH = gridRows * frameHeight;
        // TYPE_INT_RGB drops the alpha byte on setRGB, which is what JPEG
        // wants. Empty slots in the last row stay at the default (black).
        BufferedImage out = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);

        int expectedLen = frameWidth * frameHeight;
        for (int i = 0; i < frameCount; i++) {
            int[] pixels = framePixels.get(i);
            if (pixels == null || pixels.length != expectedLen) {
                throw new IllegalArgumentException("frame " + i + ": expected " + expectedLen + " pixels, got "
                        + (pixels == null ? "null" : pixels.length));
            }
            int col = i % gridCols;
            int row = i / gridCols;
            int x = col * frameWidth;
            int y = row * frameHeight;
            out.setRGB(x, y, frameWidth, frameHeight, pixels, 0, frameWidth);
        }
        return out;
    }
}
