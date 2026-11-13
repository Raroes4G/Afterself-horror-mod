package com.afterself;

import com.afterself.entity.SoulEntity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A soul that killed a player through a trap takes over their body instead of letting them die. The player watches
 * their own screen move: their keys and mouse do nothing, and their chat and voice are cut so they cannot warn anyone.
 * Ends when the body dies, when a totem saves it, when they disconnect, on an admin command or at the time limit.
 * The client drives the body: the server only says who is possessed. It re-sends this all the time, and the client
 * stops by itself without it, so a lost packet can never leave a player stuck.
 */
public final class Possession {
    public static final byte EVENT_POSSESS = 61;
    public static final byte EVENT_RELEASE = 62;
    private static final int RESEND_TICKS = 40;

    private static final Map<UUID, State> ACTIVE = new HashMap<>();

    private static final class State {
        final SoulEntity soul;
        final long startedAt;
        int totemsHeld;
        int nextVoiceAt;

        State(SoulEntity soul, long startedAt) {
            this.soul = soul;
            this.startedAt = startedAt;
        }
    }

    private Possession() {}

    public static boolean isPossessed(UUID player) {
        return ACTIVE.containsKey(player);
    }

    /** Called on the blow that would have killed the player: the body lives on, and is no longer theirs. */
    public static boolean begin(ServerPlayer player, SoulEntity soul) {
        if (!AfterselfConfig.possessionEnabled || isPossessed(player.getUUID())) {
            return false;
        }
        player.setHealth(player.getMaxHealth());
        player.clearFire();
        player.closeContainer();
        soul.enterBody(player);
        soul.screamOnPossession(player);
        State state = new State(soul, Compat.level(player).getGameTime());
        state.totemsHeld = totems(player);
        state.nextVoiceAt = 200;
        ACTIVE.put(player.getUUID(), state);
        // Sent again by tick(): the marker soul may not exist on the client yet.
        return true;
    }

    /** Ends a possession. The soul comes back out, hidden, with the items it carried. */
    public static void end(ServerPlayer player) {
        State state = ACTIVE.remove(player.getUUID());
        if (state == null) {
            return;
        }
        player.connection.send(new ClientboundEntityEventPacket(state.soul, EVENT_RELEASE));
        state.soul.leaveBody(player);
    }

    /** The body died for real: the soul is thrown out, and the normal death rules apply. */
    public static void onBodyDied(ServerPlayer player) {
        end(player);
    }

    public static void onPlayerLogout(ServerPlayer player) {
        end(player);
    }

    public static void onServerTick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, State> entry : List.copyOf(ACTIVE.entrySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            State state = entry.getValue();
            if (player == null) {
                // Gone without a logout event: the soul must still come back out.
                ACTIVE.remove(entry.getKey());
                state.soul.leaveBody(null);
                continue;
            }
            tick(player, state);
        }
    }

    private static void tick(ServerPlayer player, State state) {
        ServerLevel level = (ServerLevel) Compat.level(player);
        long now = level.getGameTime();
        if (!state.soul.isAlive() || !player.isAlive()) {
            end(player);
            return;
        }
        // A totem that just saved the body drives the soul out.
        int totems = totems(player);
        if (totems < state.totemsHeld && player.hasEffect(MobEffects.REGENERATION)) {
            end(player);
            return;
        }
        state.totemsHeld = totems;
        int limit = AfterselfConfig.possessionMaxSeconds;
        if (limit > 0 && now - state.startedAt > limit * 20L) {
            end(player);
            return;
        }
        state.soul.followBody(player);
        if ((now - state.startedAt) % RESEND_TICKS == 5) {
            player.connection.send(new ClientboundEntityEventPacket(state.soul, EVENT_POSSESS));
        }
        // The body talks with its owner's real voice, so nobody suspects anything.
        if (--state.nextVoiceAt <= 0) {
            state.nextVoiceAt = 300 + level.getRandom().nextInt(500);
            Afterself.VoiceOutput output = Afterself.voiceOutput;
            List<byte[]> clip = output == null ? null : VoiceMemory.pick(player.getUUID());
            if (clip != null) {
                output.play(player, clip, 1f);
            }
        }
    }

    private static int totems(ServerPlayer player) {
        return count(player.getMainHandItem()) + count(player.getOffhandItem());
    }

    private static int count(ItemStack stack) {
        return stack.is(Items.TOTEM_OF_UNDYING) ? stack.getCount() : 0;
    }

    /** Where the body stands, for tests. */
    public static BlockPos bodyPosition(ServerPlayer player) {
        return player.blockPosition();
    }

    public static void onServerStopping(MinecraftServer server) {
        for (UUID id : List.copyOf(ACTIVE.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null) {
                end(player);
            }
        }
    }
}
