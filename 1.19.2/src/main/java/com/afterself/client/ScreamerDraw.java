package com.afterself.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;

/** 1.19.2 draws through GuiComponent and a PoseStack. */
public final class ScreamerDraw implements ScreamerOverlay.Canvas {
    private final PoseStack pose;

    public ScreamerDraw(PoseStack pose) {
        this.pose = pose;
    }

    @Override
    public void rect(int x, int y, int width, int height, int argb) {
        // Without the depth test, or the chat, drawn closer, punches a hole through the face.
        RenderSystem.disableDepthTest();
        GuiComponent.fill(pose, x, y, x + width, y + height, argb);
        RenderSystem.enableDepthTest();
    }

    @Override
    public void part(ResourceLocation skin, int x, int y, int width, int height, int row, int rows,
            float red, float green, float blue, float alpha) {
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, skin);
        RenderSystem.setShaderColor(red, green, blue, alpha);
        GuiComponent.blit(pose, x, y, width, height, 8f, 8f + row, 8, rows, 64, 64);
        GuiComponent.blit(pose, x, y, width, height, 40f, 8f + row, 8, rows, 64, 64);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableDepthTest();
    }
}
