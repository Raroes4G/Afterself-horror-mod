package com.afterself.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** 1.20.1 draws through GuiGraphics. */
public final class ScreamerDraw implements ScreamerOverlay.Canvas {
    /** In front of the chat and of every tooltip, which would otherwise punch holes through the face. */
    private static final float DEPTH = 900f;
    private final GuiGraphics graphics;

    public ScreamerDraw(GuiGraphics graphics) {
        this.graphics = graphics;
    }

    @Override
    public void rect(int x, int y, int width, int height, int argb) {
        graphics.pose().pushPose();
        graphics.pose().translate(0f, 0f, DEPTH);
        graphics.fill(x, y, x + width, y + height, argb);
        graphics.flush();
        graphics.pose().popPose();
    }

    @Override
    public void part(ResourceLocation skin, int x, int y, int width, int height, int row, int rows,
            float red, float green, float blue, float alpha) {
        // Set here because flush() turns the depth test back on.
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(red, green, blue, alpha);
        graphics.blit(skin, x, y, width, height, 8f, 8f + row, 8, rows, 64, 64);
        graphics.blit(skin, x, y, width, height, 40f, 8f + row, 8, rows, 64, 64);
        graphics.flush();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableDepthTest();
    }
}
