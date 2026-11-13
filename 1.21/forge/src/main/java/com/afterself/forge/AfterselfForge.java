package com.afterself.forge;

import com.afterself.Compat;
import com.afterself.GameEvents;
import com.afterself.Afterself;
import com.afterself.AfterselfConfig;
import com.afterself.entity.SoulEntity;
import java.util.ArrayList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(Afterself.MOD_ID)
public class AfterselfForge {
    private static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Afterself.MOD_ID);
    private static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, Afterself.MOD_ID);

    private static final RegistryObject<EntityType<SoulEntity>> SOUL = ENTITIES.register("soul",
            () -> EntityType.Builder.<SoulEntity>of(SoulEntity::new, MobCategory.MONSTER)
                    .sized(0.6f, 1.8f).fireImmune().clientTrackingRange(10).build("afterself:soul"));
    private static final RegistryObject<SoundEvent> SCREAMER = SOUNDS.register("screamer",
            () -> SoundEvent.createVariableRangeEvent(Compat.id("screamer")));

    public AfterselfForge() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ENTITIES.register(modBus);
        SOUNDS.register(modBus);
        Afterself.soulType = SOUL;
        Afterself.screamerSound = SCREAMER;
        AfterselfConfig.load(FMLPaths.GAMEDIR.get());

        modBus.addListener((EntityAttributeCreationEvent event) ->
                event.put(SOUL.get(), SoulEntity.createAttributes().build()));

        IEventBus bus = MinecraftForge.EVENT_BUS;
        bus.addListener((ServerStartedEvent event) -> GameEvents.onServerStarted(event.getServer()));
        bus.addListener((ServerStoppingEvent event) -> GameEvents.onServerStopping(event.getServer()));
        bus.addListener((ServerStoppedEvent event) -> GameEvents.onServerStopped());
        bus.addListener((TickEvent.ServerTickEvent.Post event) -> GameEvents.onServerTick());
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
            AfterselfForgeClient.init(modBus, SOUL);
        }
    }
}
