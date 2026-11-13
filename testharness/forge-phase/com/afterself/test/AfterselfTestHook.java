package com.afterself.test;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Dev-only hook for Forge 1.19.2 and 1.20.1: found by the mod scan, so the mod's own code never references it. */
@Mod.EventBusSubscriber(modid = "afterself", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AfterselfTestHook {
    private AfterselfTestHook() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            AfterselfTestRunner.clientTick();
        }
    }
}
