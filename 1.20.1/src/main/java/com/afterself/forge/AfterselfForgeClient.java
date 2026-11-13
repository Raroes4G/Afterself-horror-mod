package com.afterself.forge;

import com.afterself.client.ClientHooks;
import com.afterself.client.PossessionClient;
import com.afterself.client.ScreamerDraw;
import com.afterself.client.ScreamerOverlay;
import com.afterself.client.SoulRenderer;
import com.afterself.entity.SoulEntity;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;

/** Only loaded on the client. */
final class AfterselfForgeClient {
    private AfterselfForgeClient() {}

    static void init(IEventBus modBus, Supplier<EntityType<SoulEntity>> soul) {
        ClientHooks.init();
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerEntityRenderer(soul.get(), SoulRenderer::new));
        IEventBus bus = MinecraftForge.EVENT_BUS;
        bus.addListener((RenderGuiEvent.Post event) -> {
            var window = Minecraft.getInstance().getWindow();
            ScreamerOverlay.render(new ScreamerDraw(event.getGuiGraphics()),
                    window.getGuiScaledWidth(), window.getGuiScaledHeight());
        });
        bus.addListener((TickEvent.ClientTickEvent event) -> {
            if (event.phase == TickEvent.Phase.START) {
                PossessionClient.onClientTick();
            }
        });
        bus.addListener((ViewportEvent.ComputeCameraAngles event) -> {
            if (PossessionClient.isActive()) {
                float partial = (float) event.getPartialTick();
                event.setYaw(PossessionClient.cameraYaw(partial));
                event.setPitch(PossessionClient.cameraPitch(partial));
            }
        });
    }
}
