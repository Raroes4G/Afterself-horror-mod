package com.afterself.forge;

import com.mojang.blaze3d.systems.RenderSystem;
import com.afterself.client.ClientHooks;
import com.afterself.client.PossessionClient;
import com.afterself.client.ScreamerDraw;
import com.afterself.client.ScreamerOverlay;
import com.afterself.client.SoulRenderer;
import com.afterself.entity.SoulEntity;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import org.joml.Matrix4fStack;

/** Only loaded on the client. */
final class AfterselfForgeClient {
    private AfterselfForgeClient() {}

    static void init(IEventBus modBus, Supplier<EntityType<SoulEntity>> soul) {
        ClientHooks.init();
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerEntityRenderer(soul.get(), SoulRenderer::new));
        IEventBus bus = MinecraftForge.EVENT_BUS;
        // Forge 1.21 has no event after the HUD with its GuiGraphics: this one runs after the whole frame.
        bus.addListener((TickEvent.RenderTickEvent.Post event) -> {
            if (!ScreamerOverlay.isActive()) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            var window = minecraft.getWindow();
            // The frame is finished: restores the depth the GUI projection expects, or everything gets clipped.
            Matrix4fStack view = RenderSystem.getModelViewStack();
            view.pushMatrix();
            view.translation(0f, 0f, -11000f);
            RenderSystem.applyModelViewMatrix();
            ScreamerOverlay.render(new ScreamerDraw(new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource())),
                    window.getGuiScaledWidth(), window.getGuiScaledHeight());
            view.popMatrix();
            RenderSystem.applyModelViewMatrix();
        });
        bus.addListener((TickEvent.ClientTickEvent.Pre event) -> PossessionClient.onClientTick());
        bus.addListener((ViewportEvent.ComputeCameraAngles event) -> {
            if (PossessionClient.isActive()) {
                float partial = (float) event.getPartialTick();
                event.setYaw(PossessionClient.cameraYaw(partial));
                event.setPitch(PossessionClient.cameraPitch(partial));
            }
        });
    }
}
