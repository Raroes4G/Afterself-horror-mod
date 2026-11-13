package com.afterself.test;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Dev-only hook for NeoForge 1.21.1. */
@EventBusSubscriber(modid = "afterself", value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class AfterselfTestHookNeo {
    private AfterselfTestHookNeo() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        AfterselfTestRunner.clientTick();
    }
}
