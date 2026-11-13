package com.afterself.test;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Dev-only hook for Forge 1.21.1. */
@Mod.EventBusSubscriber(modid = "afterself", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AfterselfTestHookForge {
    private AfterselfTestHookForge() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        AfterselfTestRunner.clientTick();
    }
}
