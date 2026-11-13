package com.afterself.entity;

import com.afterself.Compat;
import com.afterself.AfterselfConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/**
 * The small things a stalking soul does to the player it follows, so they never feel alone: footsteps that stop
 * when they stop, a door moving, a chest lid lifting, a cave noise at their back, their own voice, or the soul itself
 * standing right behind them. One at a time, never too often for the same player.
 */
public final class SoulHaunt {
    public enum Event { STEPS, DOOR, CHEST, CAVE, WHISPER, BEHIND }

    private static final int PLAYER_COOLDOWN_TICKS = 500;
    private static final int RANGE = 40;
    private static final int PROBE_RANGE = 8;
    /** Per player, so ten souls do not make ten times the noise. */
    private static final Map<UUID, Long> LAST = new HashMap<>();

    private final SoulEntity soul;
    private int nextAt;
    private int stepsLeft;
    private int nextStepAt;
    private UUID stepsFor;
    private BlockPos openChest;
    private int closeChestAt;

    SoulHaunt(SoulEntity soul) {
        this.soul = soul;
        this.nextAt = 300 + soul.getRandom().nextInt(600);
    }

    void tick(ServerLevel level) {
        if (stepsLeft > 0 && soul.tickCount >= nextStepAt) {
            step(level);
        }
        if (openChest != null && soul.tickCount >= closeChestAt) {
            setChest(level, openChest, false);
            openChest = null;
        }
        if (soul.tickCount < nextAt) {
            return;
        }
        nextAt = soul.tickCount + 300 + soul.getRandom().nextInt(900);
        if (!AfterselfConfig.soulsHaunt || soul.getMode() != SoulEntity.Mode.STALK) {
            return;
        }
        Player player = soul.getStalkedPlayer();
        if (!(player instanceof ServerPlayer target) || soul.distanceToSqr(player) > RANGE * RANGE) {
            return;
        }
        long now = level.getGameTime();
        Long last = LAST.get(target.getUUID());
        if (last != null && now >= last && now - last < PLAYER_COOLDOWN_TICKS) {
            return;
        }
        List<Event> order = new ArrayList<>(List.of(Event.values()));
        Collections.shuffle(order, new java.util.Random(soul.getRandom().nextLong()));
        for (Event event : order) {
            if (play(event, target)) {
                LAST.put(target.getUUID(), now);
                return;
            }
        }
    }

    /** Plays one event now. False when the place does not allow it (no door, no chest, no voice clip). */
    public boolean play(Event event, ServerPlayer player) {
        ServerLevel level = (ServerLevel) Compat.level(player);
        return switch (event) {
            case STEPS -> {
                stepsLeft = 4 + soul.getRandom().nextInt(4);
                nextStepAt = soul.tickCount;
                stepsFor = player.getUUID();
                yield true;
            }
            case DOOR -> moveDoor(level, player);
            case CHEST -> liftChestLid(level, player);
            case CAVE -> {
                Vec3 behind = behind(player, 6.0);
                level.playSound(null, behind.x, behind.y, behind.z, Compat.caveSound(), SoundSource.AMBIENT,
                        0.8f, 0.8f + soul.getRandom().nextFloat() * 0.3f);
                yield true;
            }
            case WHISPER -> soul.speak();
            case BEHIND -> soul.startLoom(player);
        };
    }

    private static Vec3 behind(Player player, double distance) {
        Vec3 view = player.getViewVector(1f);
        Vec3 flat = new Vec3(view.x, 0.0, view.z);
        flat = flat.lengthSqr() < 0.0001 ? new Vec3(0.0, 0.0, 1.0) : flat.normalize();
        return player.position().subtract(flat.scale(distance));
    }

    /** Someone walking up behind them, closer each step, on whatever the floor is made of. */
    private void step(ServerLevel level) {
        Player player = stepsFor == null ? null : level.getPlayerByUUID(stepsFor);
        if (player == null) {
            stepsLeft = 0;
            return;
        }
        Vec3 at = behind(player, 2.0 + stepsLeft * 0.9);
        BlockPos ground = new BlockPos((int) Math.floor(at.x), (int) Math.floor(player.getY()) - 1, (int) Math.floor(at.z));
        BlockState state = level.getBlockState(ground);
        if (state.isAir()) {
            state = Blocks.STONE.defaultBlockState();
        }
        SoundEvent sound = state.getSoundType().getStepSound();
        level.playSound(null, at.x, player.getY(), at.z, sound, SoundSource.HOSTILE, 0.5f, 0.85f);
        stepsLeft--;
        nextStepAt = soul.tickCount + 6 + soul.getRandom().nextInt(3);
    }

    private boolean moveDoor(ServerLevel level, ServerPlayer player) {
        List<BlockPos> doors = new ArrayList<>();
        for (BlockPos pos : around(player)) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof DoorBlock && state.is(BlockTags.WOODEN_DOORS)
                    && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER && !looksAt(player, pos)) {
                doors.add(pos.immutable());
            }
        }
        if (doors.isEmpty()) {
            return false;
        }
        BlockPos pos = doors.get(soul.getRandom().nextInt(doors.size()));
        BlockState state = level.getBlockState(pos);
        ((DoorBlock) state.getBlock()).setOpen(soul, level, state, pos, !state.getValue(DoorBlock.OPEN));
        return true;
    }

    private boolean liftChestLid(ServerLevel level, ServerPlayer player) {
        if (openChest != null) {
            return false;
        }
        List<BlockPos> chests = new ArrayList<>();
        for (BlockPos pos : around(player)) {
            if (level.getBlockState(pos).getBlock() instanceof ChestBlock) {
                chests.add(pos.immutable());
            }
        }
        if (chests.isEmpty()) {
            return false;
        }
        openChest = chests.get(soul.getRandom().nextInt(chests.size()));
        setChest(level, openChest, true);
        closeChestAt = soul.tickCount + 30 + soul.getRandom().nextInt(40);
        return true;
    }

    /** Every block close to the player. Looked at once per fright, so the cost does not matter. */
    private static Iterable<BlockPos> around(Player player) {
        BlockPos origin = player.blockPosition();
        return BlockPos.betweenClosed(origin.offset(-PROBE_RANGE, -2, -PROBE_RANGE), origin.offset(PROBE_RANGE, 2, PROBE_RANGE));
    }

    private static boolean looksAt(Player player, BlockPos pos) {
        Vec3 to = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5).subtract(player.getEyePosition());
        return to.lengthSqr() > 0.01 && to.normalize().dot(player.getViewVector(1f)) > 0.6;
    }

    private static void setChest(ServerLevel level, BlockPos pos, boolean open) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock) {
            level.blockEvent(pos, state.getBlock(), 1, open ? 1 : 0);
            level.playSound(null, pos, open ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5f, 0.7f);
        }
    }

    /** Leaves nothing half-done when the soul goes away. */
    void cleanUp() {
        if (openChest != null && Compat.level(soul) instanceof ServerLevel level) {
            setChest(level, openChest, false);
            openChest = null;
        }
        stepsLeft = 0;
    }
}
