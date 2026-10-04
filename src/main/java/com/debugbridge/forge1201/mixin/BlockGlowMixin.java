package com.debugbridge.forge1201.mixin;

import com.debugbridge.core.block.ClientBlockGlowManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class BlockGlowMixin {

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void debugbridge$renderBlockGlows(
            PoseStack poseStack,
            float partialTick,
            long finishNanoTime,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f projectionMatrix,
            CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        var glowing = ClientBlockGlowManager.snapshot();
        if (glowing.isEmpty()) return;

        Vec3 camPos = camera.getPosition();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderType.LINES);

        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        float r = 1.0f;
        float g = 1.0f;
        float b = 0.0f;
        float a = 0.5f;

        for (var p : glowing) {
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            LevelRenderer.renderLineBox(
                    poseStack,
                    vertexConsumer,
                    pos.getX(),
                    pos.getY(),
                    pos.getZ(),
                    pos.getX() + 1,
                    pos.getY() + 1,
                    pos.getZ() + 1,
                    r,
                    g,
                    b,
                    a);
        }

        poseStack.popPose();
        bufferSource.endBatch(RenderType.LINES);
    }
}
