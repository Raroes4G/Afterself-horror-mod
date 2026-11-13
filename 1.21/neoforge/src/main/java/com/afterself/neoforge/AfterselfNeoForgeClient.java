package com.afterself.neoforge;

import com.afterself.client.ClientHooks;
import com.afterself.client.PossessionClient;
import com.afterself.client.ScreamerDraw;
import com.afterself.client.ScreamerOverlay;
import com.afterself.client.SoulRenderer;
import com.afterself.entity.SoulEntity;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Only loaded on the client. */
final class AfterselfNeoForgeClient {
    private AfterselfNeoForgeClient() {}

    static void init(IEventBus modBus, Supplier<EntityType<SoulEntity>> soul) {
        ClientHooks.init();
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerEntityRenderer(soul.get(), SoulRenderer::new));
        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener((RenderGuiEvent.Post event) -> {
            var window = Minecraft.getInstance().getWindow();
            ScreamerOverlay.render(new ScreamerDraw(event.getGuiGraphics()),
                    window.getGuiScaledWidth(), window.getGuiScaledHeight());
        });
        bus.addListener((ClientTickEvent.Pre event) -> PossessionClient.onClientTick());
        bus.addListener((ViewportEvent.ComputeCameraAngles event) -> {
            if (PossessionClient.isActive()) {
                float partial = (float) event.getPartialTick();
                event.setYaw(PossessionClient.cameraYaw(partial));
                event.setPitch(PossessionClient.cameraPitch(partial));
            }
        });
    }
}
