package com.debugbridge.core.screenshot;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.FileImageOutputStream;

/**
 * JPEG encoder shared by all fabric shims. Takes raw ARGB pixels (alpha is
 * discarded by JPEG) or a {@link BufferedImage} and writes a quality-controlled
 * JPEG.
 * <p>
 * Lives in core because it depends only on the JDK ({@link ImageIO} via the
 * {@code java.desktop} module, which is part of every standard JRE).
 */
public final class JpegEncoder {
    private JpegEncoder() {}

    /**
     * Write the given ARGB pixels as a JPEG to a fresh OS-temp file. Used by
     * the {@code screenshot} handler, whose output is opaque-named.
     */
    public static Path writeJpegTempFile(int[] argbPixels, int width, int height, float quality) throws IOException {
        Path directory = Path.of(System.getProperty("debugbridge.captureDir",
                Path.of(System.getProperty("java.io.tmpdir"), "voxelprobe").toString()));
        Files.createDirectories(directory);
        Path file = Files.createTempFile(directory, "frame-", ".jpg");
        writeJpeg(argbPixels, width, height, file, quality);
        return file.toAbsolutePath();
    }

    /**
     * Write ARGB pixels as a JPEG to a specific path. Used by the recording
     * pipeline, which controls the output directory + filename.
     *
     * @param argbPixels pixels in row-major order, 0xAARRGGBB layout. Length must be width * height.
     * @param width      image width
     * @param height     image height
     * @param destPath   destination file path (parent directory must exist)
     * @param quality    JPEG quality in [0.0, 1.0]; clamped to [0.05, 1.0]
     */
    public static void writeJpeg(int[] argbPixels, int width, int height, Path destPath, float quality)
            throws IOException {
        if (argbPixels.length != width * height) {
            throw new IllegalArgumentException(
                    "pixel buffer length " + argbPixels.length + " != " + width + "*" + height);
        }
        // TYPE_INT_RGB drops the alpha byte when we setRGB with ARGB ints,
        // which is exactly what we want for JPEG (no alpha channel).
        BufferedImage bi = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        bi.setRGB(0, 0, width, height, argbPixels, 0, width);
        writeJpeg(bi, destPath, quality);
    }

    /**
     * Write a {@link BufferedImage} as a JPEG to a specific path. Used by the
     * recording pipeline to dump the composed grid in one pass.
     *
     * @param image    source image (TYPE_INT_RGB recommended; alpha is dropped).
     * @param destPath destination file path (parent directory must exist).
     * @param quality  JPEG quality in [0.0, 1.0]; clamped to [0.05, 1.0].
     */
    public static void writeJpeg(BufferedImage image, Path destPath, float quality) throws IOException {
        float clamped = Math.max(0.05f, Math.min(quality, 1.0f));
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG ImageWriter available in this JRE");
        }
        ImageWriter writer = writers.next();
        try (FileImageOutputStream out = new FileImageOutputStream(destPath.toFile())) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(clamped);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }
}
