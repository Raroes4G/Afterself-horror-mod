package com.afterself;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.afterself.entity.SoulHaunt;
import net.minecraft.world.item.Items;
import com.afterself.entity.SoulEntity;
import com.afterself.entity.SoulEntity.LureScene;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/** Game logic that every loader entry point calls from its own event classes. */
public final class GameEvents {
    private static MinecraftServer server;

    private GameEvents() {}

    public static void onServerStarted(MinecraftServer current) {
        server = current;
        ConsentStore.load(current);
        VoiceMemory.init(current);
        SoulStore.load(current);
    }

    public static void onServerStopping(MinecraftServer current) {
        Possession.onServerStopping(current);
        SoulStore.onServerStopping();
    }

    public static void onServerStopped() {
        server = null;
        SoulStore.unload();
        VoiceMemory.shutdown();
        ConsentStore.unload();
    }

    public static void onServerTick() {
        if (server != null) {
            Possession.onServerTick(server);
            SoulStore.onServerTick();
        }
    }

    /** Called with the items a dying player is about to drop. Returns true when a soul took them. */
    public static boolean onPlayerDrops(ServerPlayer player, List<ItemEntity> drops) {
        if (!AfterselfConfig.soulsEnabled) {
            return false;
        }
        List<ItemStack> items = new ArrayList<>();
        for (ItemEntity drop : drops) {
            items.add(drop.getItem());
        }
        SoulEntity soul = SoulEntity.fromPlayer(player, items);
        if (soul == null) {
            return false;
        }
        SoulStore.add(soul, player);
        return true;
    }

    /**
     * Called when a player is about to die. A kill by a soul whose trap has sprung takes the body instead.
     * Returns true when the death must be cancelled.
     */
    public static boolean onPlayerDeath(ServerPlayer player, DamageSource source) {
        if (Possession.isPossessed(player.getUUID())) {
            // The body died: that frees its owner, and the normal death follows.
            Possession.onBodyDied(player);
            return false;
        }
        if (source.getEntity() instanceof SoulEntity soul && soul.isTrapActive()) {
            return Possession.begin(player, soul);
        }
        return false;
    }

    /** Asks once, per world. Without an answer, nothing is recorded. */
    public static void onPlayerLogin(ServerPlayer player) {
        if (AfterselfConfig.loginWarning) {
            player.sendSystemMessage(loginWarning(player).withStyle(ChatFormatting.DARK_RED));
        }
        if (!AfterselfConfig.voiceClips) {
            return;
        }
        if (ConsentStore.hasAnswered(player.getUUID())) {
            if (ConsentStore.accepted(player.getUUID())) {
                VoiceMemory.loadPlayer(player.getUUID());
            }
            return;
        }
        MutableComponent accept = Component.translatable("afterself.consent.accept").withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/afterself voice accept")));
        MutableComponent refuse = Component.translatable("afterself.consent.refuse").withStyle(style -> style
                .withColor(ChatFormatting.RED)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/afterself voice refuse")));
        player.sendSystemMessage(Component.translatable("afterself.consent.prompt").withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(accept.append(Component.literal(" ")).append(refuse));
    }

    /**
     * What every player is told each time they join: dying has a price here. Those who already have souls waiting
     * are told how many.
     */
    public static MutableComponent loginWarning(ServerPlayer player) {
        int souls = SoulStore.activeSouls(player.getUUID()).size() + SoulStore.dormantCount(player.getUUID());
        return souls == 0 ? Component.translatable("afterself.warning.login")
                : souls == 1 ? Component.translatable("afterself.warning.login_one")
                : Component.translatable("afterself.warning.login_souls", souls);
    }

    public static void onPlayerLogout(ServerPlayer player) {
        VoiceMemory.endPhrase(player.getUUID());
        Possession.onPlayerLogout(player);
    }

    /** A possessed player's words go nowhere. Returns true when the message must be cancelled. */
    public static boolean onPlayerChat(ServerPlayer player) {
        return Possession.isPossessed(player.getUUID());
    }

    /** Commands too, or /kill would be a way out. Admins keep theirs. Returns true when the command must be cancelled. */
    public static boolean onCommand(CommandSourceStack source) {
        ServerPlayer player = source.getEntity() instanceof ServerPlayer found ? found : null;
        return player != null && Possession.isPossessed(player.getUUID()) && !source.hasPermission(2);
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("afterself")
                .then(Commands.literal("voice")
                        .then(Commands.literal("accept").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            ConsentStore.set(player.getUUID(), true);
                            player.sendSystemMessage(Component.translatable("afterself.consent.accepted"));
                            return 1;
                        }))
                        .then(Commands.literal("refuse").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            ConsentStore.set(player.getUUID(), false);
                            player.sendSystemMessage(Component.translatable("afterself.consent.refused"));
                            return 1;
                        }))
                        .then(Commands.literal("forget").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            VoiceMemory.forget(player.getUUID());
                            player.sendSystemMessage(Component.translatable("afterself.consent.forgotten"));
                            return 1;
                        }))
                        .then(Commands.literal("status").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            player.sendSystemMessage(Component.translatable(ConsentStore.accepted(player.getUUID())
                                    ? "afterself.consent.status_on" : "afterself.consent.status_off"));
                            return 1;
                        })))
                .then(Commands.literal("debug").requires(source -> source.hasPermission(2))
                        .then(Commands.literal("spawn").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            SoulEntity soul = SoulEntity.fromPlayer(player, new ArrayList<>());
                            if (soul != null) {
                                Compat.level(player).addFreshEntity(soul);
                                player.sendSystemMessage(Component.translatable("afterself.command.spawned"));
                            }
                            return 1;
                        }))
                        .then(Commands.literal("speak").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            SoulEntity soul = nearestSoul(player);
                            String key = soul == null ? "afterself.command.no_soul"
                                    : soul.speak() ? "afterself.command.speaking" : "afterself.command.no_clip";
                            player.sendSystemMessage(Component.translatable(key));
                            return 1;
                        }))
                        .then(Commands.literal("lure")
                                .then(Commands.argument("scene", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            for (LureScene scene : LureScene.values()) {
                                                if (scene != LureScene.NONE) {
                                                    builder.suggest(scene.name().toLowerCase(java.util.Locale.ROOT));
                                                }
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                                            SoulEntity soul = nearestSoul(player);
                                            LureScene scene = LureScene.named(StringArgumentType.getString(ctx, "scene"));
                                            if (soul == null || scene == null) {
                                                player.sendSystemMessage(Component.translatable(
                                                        soul == null ? "afterself.command.no_soul" : "afterself.command.no_scene"));
                                            } else {
                                                soul.startLure(player, scene);
                                            }
                                            return 1;
                                        }))
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    SoulEntity soul = nearestSoul(player);
                                    if (soul == null) {
                                        player.sendSystemMessage(Component.translatable("afterself.command.no_soul"));
                                    } else {
                                        LureScene[] scenes = LureScene.lures();
                                        soul.startLure(player, scenes[player.getRandom().nextInt(scenes.length)]);
                                    }
                                    return 1;
                                }))
                        .then(Commands.literal("haunt")
                                .then(Commands.argument("event", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            for (SoulHaunt.Event event : SoulHaunt.Event.values()) {
                                                builder.suggest(event.name().toLowerCase(java.util.Locale.ROOT));
                                            }
                                            builder.suggest("flash");
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                                            SoulEntity soul = nearestSoul(player);
                                            String name = StringArgumentType.getString(ctx, "event");
                                            boolean done = false;
                                            if (soul != null && name.equalsIgnoreCase("flash")) {
                                                soul.sendFlash(player);
                                                done = true;
                                            } else if (soul != null) {
                                                for (SoulHaunt.Event event : SoulHaunt.Event.values()) {
                                                    if (event.name().equalsIgnoreCase(name)) {
                                                        done = soul.getHaunt().play(event, player);
                                                    }
                                                }
                                            }
                                            if (!done) {
                                                player.sendSystemMessage(Component.translatable(
                                                        soul == null ? "afterself.command.no_soul" : "afterself.command.no_haunt"));
                                            }
                                            return 1;
                                        })))
                        .then(Commands.literal("search").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            SoulEntity soul = nearestSoul(player);
                            if (soul == null) {
                                player.sendSystemMessage(Component.translatable("afterself.command.no_soul"));
                            } else {
                                soul.startHunting(player);
                                soul.startSearching();
                            }
                            return 1;
                        }))
                        .then(Commands.literal("kit").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            SoulEntity soul = nearestSoul(player);
                            if (soul == null) {
                                player.sendSystemMessage(Component.translatable("afterself.command.no_soul"));
                            } else {
                                soul.addLoot(new ItemStack(Items.COBBLESTONE, 32));
                                soul.addLoot(new ItemStack(Items.IRON_SWORD));
                                soul.addLoot(new ItemStack(Items.SHIELD));
                                soul.addLoot(new ItemStack(Items.BOW));
                                soul.addLoot(new ItemStack(Items.ARROW, 16));
                                soul.addLoot(new ItemStack(Items.ENDER_PEARL, 2));
                                soul.addLoot(new ItemStack(Items.BREAD, 4));
                                player.sendSystemMessage(Component.translatable("afterself.command.kit"));
                            }
                            return 1;
                        }))
                        .then(Commands.literal("hunt").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            SoulEntity soul = nearestSoul(player);
                            if (soul == null) {
                                player.sendSystemMessage(Component.translatable("afterself.command.no_soul"));
                            } else {
                                soul.startHunting(player);
                            }
                            return 1;
                        }))
                        .then(Commands.literal("possess").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            SoulEntity soul = nearestSoul(player);
                            if (soul == null) {
                                player.sendSystemMessage(Component.translatable("afterself.command.no_soul"));
                            } else {
                                Possession.begin(player, soul);
                            }
                            return 1;
                        }))
                        .then(Commands.literal("release")
                                .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                                    Possession.end(EntityArgument.getPlayer(ctx, "player"));
                                    return 1;
                                }))
                                .executes(ctx -> {
                                    Possession.end(ctx.getSource().getPlayerOrException());
                                    return 1;
                                }))
                        .then(Commands.literal("sleep").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            for (SoulEntity soul : SoulStore.activeSouls(player.getUUID())) {
                                SoulStore.sendToSleep(soul);
                            }
                            return 1;
                        }))
                        .then(Commands.literal("screamer").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            SoulEntity soul = nearestSoul(player);
                            if (soul == null) {
                                soul = SoulEntity.fromPlayer(player, new ArrayList<>());
                                if (soul != null) {
                                    Compat.level(player).addFreshEntity(soul);
                                }
                            }
                            if (soul != null) {
                                soul.forceScreamer(player);
                            }
                            return 1;
                        }))
                        .then(Commands.literal("info").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            int active = SoulStore.activeSouls(player.getUUID()).size();
                            player.sendSystemMessage(Component.translatable("afterself.command.count", active,
                                    SoulStore.dormantCount(player.getUUID()), VoiceMemory.count(player.getUUID())));
                            return 1;
                        }))));
    }

    private static SoulEntity nearestSoul(ServerPlayer player) {
        SoulEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (SoulEntity soul : SoulStore.soulsIn((ServerLevel) Compat.level(player))) {
            double distance = soul.distanceToSqr(player);
            if (!soul.isPossessing() && distance < bestDistance) {
                best = soul;
                bestDistance = distance;
            }
        }
        return best;
    }
}
