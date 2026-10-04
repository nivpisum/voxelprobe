package com.debugbridge.forge1201;

import com.debugbridge.core.recording.FrameCapturer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

public final class Minecraft1201FrameCapturer implements FrameCapturer {

    private static int clampDownscale(int requested, int width, int height) {
        if (requested < 1) return 1;
        for (int f = requested; f >= 1; f--) {
            if (width % f == 0 && height % f == 0) return f;
        }
        return 1;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void capture(int requestedDownscale, FrameSink sink) {
        Minecraft mc = Minecraft.getInstance();
        NativeImage full = null;
        NativeImage scaled = null;
        try {
            RenderTarget target = mc.getMainRenderTarget();
            if (target == null) {
                sink.onError(new IllegalStateException("Main render target is null"));
                return;
            }
            full = Screenshot.takeScreenshot(target);
            int srcW = full.getWidth();
            int srcH = full.getHeight();

            NativeImage out = full;
            int downscale = clampDownscale(requestedDownscale, srcW, srcH);
            if (downscale > 1) {
                int dstW = srcW / downscale;
                int dstH = srcH / downscale;
                scaled = new NativeImage(dstW, dstH, false);
                full.resizeSubRectTo(0, 0, srcW, srcH, scaled);
                out = scaled;
            }
            int w = out.getWidth();
            int h = out.getHeight();
            int[] pixels = out.makePixelArray();
            sink.onPixels(pixels, w, h);
        } catch (Throwable t) {
            sink.onError(t);
        } finally {
            if (full != null) full.close();
            if (scaled != null) scaled.close();
        }
    }
}
