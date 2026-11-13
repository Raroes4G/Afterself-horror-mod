package com.afterself.neoforge;

import com.afterself.Compat;
import com.afterself.GameEvents;
import com.afterself.Afterself;
import com.afterself.AfterselfConfig;
import com.afterself.entity.SoulEntity;
import java.util.ArrayList;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(Afterself.MOD_ID)
public class AfterselfNeoForge {
    private static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Afterself.MOD_ID);
    private static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, Afterself.MOD_ID);

    private static final Supplier<EntityType<SoulEntity>> SOUL = ENTITIES.register("soul",
            () -> EntityType.Builder.<SoulEntity>of(SoulEntity::new, MobCategory.MONSTER)
                    .sized(0.6f, 1.8f).fireImmune().clientTrackingRange(10).build("afterself:soul"));
    private static final Supplier<SoundEvent> SCREAMER = SOUNDS.register("screamer",
            () -> SoundEvent.createVariableRangeEvent(Compat.id("screamer")));

    public AfterselfNeoForge(IEventBus modBus) {
        ENTITIES.register(modBus);
        SOUNDS.register(modBus);
        Afterself.soulType = SOUL;
        Afterself.screamerSound = SCREAMER;
        AfterselfConfig.load(FMLPaths.GAMEDIR.get());

        modBus.addListener((EntityAttributeCreationEvent event) ->
                event.put(SOUL.get(), SoulEntity.createAttributes().build()));

        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener((ServerStartedEvent event) -> GameEvents.onServerStarted(event.getServer()));
        bus.addListener((ServerStoppingEvent event) -> GameEvents.onServerStopping(event.getServer()));
        bus.addListener((ServerStoppedEvent event) -> GameEvents.onServerStopped());
        bus.addListener((ServerTickEvent.Post event) -> GameEvents.onServerTick());
        bus.addListener((RegisterCommandsEvent event) -> GameEvents.registerCommands(event.getDispatcher()));
        bus.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                GameEvents.onPlayerLogin(player);
            }
        });
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                GameEvents.onPlayerLogout(player);
            }
        });
        bus.addListener((LivingDeathEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player && GameEvents.onPlayerDeath(player, event.getSource())) {
                event.setCanceled(true);
            }
        });
        bus.addListener((LivingDropsEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player
                    && GameEvents.onPlayerDrops(player, new ArrayList<>(event.getDrops()))) {
                event.setCanceled(true);
            }
        });
        bus.addListener((ServerChatEvent event) -> {
            if (GameEvents.onPlayerChat(event.getPlayer())) {
                event.setCanceled(true);
            }
        });
        bus.addListener((CommandEvent event) -> {
            if (GameEvents.onCommand(event.getParseResults().getContext().getSource())) {
                event.setCanceled(true);
            }
        });

        if (FMLEnvironment.dist == Dist.CLIENT) {
            AfterselfNeoForgeClient.init(modBus, SOUL);
        }
    }
}
