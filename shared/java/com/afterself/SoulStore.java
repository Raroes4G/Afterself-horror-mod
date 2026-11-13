package com.afterself;

import com.afterself.entity.SoulEntity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Souls waiting for the night, with their items. A soul exists in the world only while it is night and its owner
 * is there, so it can follow them everywhere and costs nothing the rest of the time.
 */
public final class SoulStore {
    private static final int MATERIALIZE_EVERY_TICKS = 100;
    private static final int SAVE_EVERY_TICKS = 1200;
    private static final int SLEEP_TICKS_BEFORE_HAUNT = 40;
    private static final float MAX_SCALE = 3f;

    private static final Map<UUID, List<CompoundTag>> DORMANT = new HashMap<>();
    private static final Map<UUID, Integer> NEXT_MATERIALIZE = new HashMap<>();
    private static MinecraftServer server;
    private static Path file;
    private static boolean dirty;

    private SoulStore() {}

    public static void load(MinecraftServer current) {
        server = current;
        DORMANT.clear();
        NEXT_MATERIALIZE.clear();
        dirty = false;
        file = current.getWorldPath(LevelResource.ROOT).resolve("afterself_souls.dat");
        if (!Files.exists(file)) {
            return;
        }
        try {
            CompoundTag root = Compat.readNbt(file);
            for (String key : root.getAllKeys()) {
                ListTag list = root.getList(key, Tag.TAG_COMPOUND);
                List<CompoundTag> souls = new ArrayList<>();
                for (int i = 0; i < list.size(); i++) {
                    souls.add(list.getCompound(i));
                }
                DORMANT.put(UUID.fromString(key), souls);
            }
        } catch (IOException | IllegalArgumentException e) {
            // Kept aside rather than overwritten: it holds players' items.
            try {
                Files.move(file, file.resolveSibling("afterself_souls.broken." + System.currentTimeMillis()));
            } catch (IOException ignored) {
                // Nothing more can be done here.
            }
        }
    }

    public static void save() {
        if (file == null) {
            return;
        }
        CompoundTag root = new CompoundTag();
        DORMANT.forEach((id, souls) -> {
            if (!souls.isEmpty()) {
                ListTag list = new ListTag();
                list.addAll(souls);
                root.put(id.toString(), list);
            }
        });
        try {
            Path temporary = file.resolveSibling("afterself_souls.tmp");
            Compat.writeNbt(root, temporary);
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException ignored) {
            // Retried at the next save.
        }
    }

    /** Puts every soul to sleep before the world closes, so none is left in a chunk nobody returns to. */
    public static void onServerStopping() {
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (SoulEntity soul : soulsIn(level)) {
                if (!soul.isPossessing()) {
                    sendToSleep(soul);
                }
            }
        }
        save();
    }

    public static void unload() {
        DORMANT.clear();
        NEXT_MATERIALIZE.clear();
        server = null;
        file = null;
    }

    public static int dormantCount(UUID owner) {
        List<CompoundTag> souls = DORMANT.get(owner);
        return souls == null ? 0 : souls.size();
    }

    public static boolean isNightFor(Level level) {
        if (!AfterselfConfig.soulsOnlyAtNight || level.dimensionType().hasFixedTime()) {
            return true;
        }
        long time = level.getDayTime() % 24000L;
        return time >= 13000L && time < 23000L;
    }

    public static List<SoulEntity> soulsIn(ServerLevel level) {
        List<SoulEntity> souls = new ArrayList<>();
        for (var entity : level.getAllEntities()) {
            if (entity instanceof SoulEntity soul && soul.isAlive()) {
                souls.add(soul);
            }
        }
        return souls;
    }

    /** Removes a soul from the world and keeps everything it is, for later. */
    public static void sendToSleep(SoulEntity soul) {
        UUID owner = soul.getOwnerId();
        if (owner == null) {
            soul.discard();
            return;
        }
        CompoundTag tag = new CompoundTag();
        soul.writeSoulData(tag);
        DORMANT.computeIfAbsent(owner, id -> new ArrayList<>()).add(tag);
        dirty = true;
        if (Compat.level(soul) instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.SOUL, soul.getX(), soul.getY() + 1.0, soul.getZ(), 12, 0.3, 0.6, 0.3, 0.02);
        }
        soul.discard();
    }

    /** A new soul: in the world if it is night, otherwise waiting. Items are safe either way. */
    public static void add(SoulEntity soul, ServerPlayer owner) {
        ServerLevel level = (ServerLevel) Compat.level(owner);
        if (isNightFor(level)) {
            level.addFreshEntity(soul);
        } else {
            sendToSleep(soul);
        }
        enforceLimit(owner.getUUID(), level);
    }

    /** Past the limit, the two weakest merge into one bigger soul that keeps all their items. */
    public static void enforceLimit(UUID owner, ServerLevel level) {
        List<SoulEntity> active = activeSouls(owner);
        List<CompoundTag> dormant = DORMANT.computeIfAbsent(owner, id -> new ArrayList<>());
        while (active.size() + dormant.size() > AfterselfConfig.maxSouls) {
            if (dormant.size() >= 2) {
                CompoundTag a = dormant.remove(weakest(dormant));
                CompoundTag b = dormant.remove(weakest(dormant));
                dormant.add(mergeTags(a, b));
                dirty = true;
            } else if (dormant.size() == 1 && !active.isEmpty()) {
                active.get(0).absorbData(dormant.remove(0));
                dirty = true;
            } else if (active.size() >= 2) {
                SoulEntity keep = active.get(0);
                SoulEntity gone = active.remove(active.size() - 1);
                CompoundTag tag = new CompoundTag();
                gone.writeSoulData(tag);
                keep.absorbData(tag);
                gone.discard();
            } else {
                return;
            }
        }
    }

    private static int weakest(List<CompoundTag> souls) {
        int best = 0;
        for (int i = 1; i < souls.size(); i++) {
            if (souls.get(i).getFloat("AfterselfScale") < souls.get(best).getFloat("AfterselfScale")) {
                best = i;
            }
        }
        return best;
    }

    public static float mergedScale(float a, float b) {
        float first = Math.max(a, 1f);
        float second = Math.max(b, 1f);
        // Two souls of the same size give one with twice the volume, not twice the height.
        return Math.min(MAX_SCALE, (float) Math.cbrt(first * first * first + second * second * second));
    }

    private static CompoundTag mergeTags(CompoundTag a, CompoundTag b) {
        CompoundTag merged = a.copy();
        ListTag items = merged.getList("AfterselfItems", Tag.TAG_COMPOUND);
        items.addAll(b.getList("AfterselfItems", Tag.TAG_COMPOUND));
        merged.put("AfterselfItems", items);
        merged.putFloat("AfterselfScale", mergedScale(a.getFloat("AfterselfScale"), b.getFloat("AfterselfScale")));
        return merged;
    }

    public static List<SoulEntity> activeSouls(UUID owner) {
        List<SoulEntity> found = new ArrayList<>();
        if (server != null) {
            for (ServerLevel level : server.getAllLevels()) {
                for (SoulEntity soul : soulsIn(level)) {
                    if (owner.equals(soul.getOwnerId()) && !soul.isPossessing()) {
                        found.add(soul);
                    }
                }
            }
        }
        return found;
    }

    public static void onServerTick() {
        if (server == null) {
            return;
        }
        int tick = server.getTickCount();
        if (tick % 20 == 0) {
            sleepOrWake(tick);
        }
        if (dirty && tick % SAVE_EVERY_TICKS == 0) {
            save();
        }
    }

    private static void sleepOrWake(int tick) {
        for (ServerLevel level : server.getAllLevels()) {
            for (SoulEntity soul : soulsIn(level)) {
                if (soul.isPossessing()) {
                    continue;
                }
                ServerPlayer owner = soul.getOwnerId() == null ? null : server.getPlayerList().getPlayer(soul.getOwnerId());
                // Gone with the sunrise, or with its owner: it comes back wherever they are next.
                if (owner == null || Compat.level(owner) != level || !isNightFor(level)) {
                    sendToSleep(soul);
                }
            }
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            if (!player.isAlive() || player.isSpectator() || Possession.isPossessed(id)) {
                continue;
            }
            ServerLevel level = (ServerLevel) Compat.level(player);
            if (!isNightFor(level)) {
                continue;
            }
            if (AfterselfConfig.soulsHauntSleep && player.isSleeping() && player.getSleepTimer() >= SLEEP_TICKS_BEFORE_HAUNT
                    && !player.isCreative()) {
                hauntSleeper(player, level);
                continue;
            }
            if (dormantCount(id) == 0 || tick < NEXT_MATERIALIZE.getOrDefault(id, 0)) {
                continue;
            }
            BlockPos spot = SoulEntity.findHiddenSpot(level, player, 24, 40);
            if (spot == null) {
                continue;
            }
            NEXT_MATERIALIZE.put(id, tick + MATERIALIZE_EVERY_TICKS);
            materialize(id, level, spot);
        }
    }

    /** Wakes the oldest dormant soul of this player at this spot. Null when they have none. */
    private static SoulEntity materialize(UUID owner, ServerLevel level, BlockPos spot) {
        List<CompoundTag> waiting = DORMANT.get(owner);
        if (waiting == null || waiting.isEmpty()) {
            return null;
        }
        CompoundTag tag = waiting.remove(0);
        dirty = true;
        SoulEntity soul = Afterself.soulType.get().create(level);
        if (soul != null) {
            soul.readSoulData(tag);
            soul.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0f, 0f);
            level.addFreshEntity(soul);
        }
        return soul;
    }

    /**
     * Nobody sleeps through the night with their dead around: one of their souls comes to stand over the bed and
     * wakes them. Returns the soul, or null when they have none or there is no room by the bed.
     */
    public static SoulEntity hauntSleeper(ServerPlayer player, ServerLevel level) {
        SoulEntity chosen = null;
        double best = Double.MAX_VALUE;
        for (SoulEntity soul : activeSouls(player.getUUID())) {
            double distance = soul.distanceToSqr(player);
            if (Compat.level(soul) == level && !soul.isHunting() && distance < best) {
                chosen = soul;
                best = distance;
            }
        }
        if (chosen == null) {
            chosen = materialize(player.getUUID(), level, player.blockPosition());
        }
        if (chosen == null) {
            return null;
        }
        if (!chosen.hauntBed(player)) {
            // No room to stand by this bed: it stays close, and the player keeps sleeping.
            chosen.vanish();
            return null;
        }
        return chosen;
    }
}
