package com.afterself.entity.goal;

import com.afterself.Compat;
import com.afterself.AfterselfConfig;
import com.afterself.entity.SoulEntity;
import com.afterself.entity.SoulGear;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A soul that cannot reach who it is after digs through what is in the way: forward, up through a ceiling, or down
 * through a floor. It keeps the plain blocks it digs out, to build with later.
 */
public class SoulBreakBlockGoal extends Goal {
    private static final float MAX_HARDNESS = 6f;
    private static final int STUCK_TICKS = 40;
    private final SoulEntity soul;
    private BlockPos block;
    private int progress;
    private int needed;

    public SoulBreakBlockGoal(SoulEntity soul) {
        this.soul = soul;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Vec3 goal = soul.pursuitPos();
        if (goal == null || !AfterselfConfig.soulsBreakBlocks || soul.getStuckTicks() < STUCK_TICKS
                || soul.distanceToSqr(goal) < 4.0
                || !Compat.level(soul).getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            return false;
        }
        block = findBlocking(goal);
        return block != null;
    }

    /** Head height first, so the soul opens a passage it can walk through. */
    private BlockPos findBlocking(Vec3 goal) {
        double dx = goal.x - soul.getX();
        double dz = goal.z - soul.getZ();
        double dy = goal.y - soul.getY();
        Direction facing = Direction.getNearest(dx, 0f, dz);
        BlockPos feet = soul.blockPosition();
        BlockPos front = feet.relative(facing);
        List<BlockPos> candidates = new ArrayList<>();
        if (dy >= 1.5) {
            candidates.add(feet.above(2));
        }
        candidates.add(front.above());
        candidates.add(front);
        if (dy <= -1.5 && dx * dx + dz * dz < 16.0) {
            candidates.add(feet.below());
        }
        for (BlockPos pos : candidates) {
            if (canBreak(pos)) {
                return pos;
            }
        }
        return null;
    }

    private boolean canBreak(BlockPos pos) {
        Level level = Compat.level(soul);
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.is(BlockTags.WITHER_IMMUNE)) {
            return false;
        }
        float hardness = state.getDestroySpeed(level, pos);
        return hardness >= 0f && hardness <= MAX_HARDNESS && !state.getCollisionShape(level, pos).isEmpty();
    }

    @Override
    public boolean canContinueToUse() {
        return block != null && soul.pursuitPos() != null && canBreak(block);
    }

    @Override
    public void start() {
        Level level = Compat.level(soul);
        float hardness = level.getBlockState(block).getDestroySpeed(level, block);
        needed = Math.max(20, Math.min(200, (int) (hardness * 40f)));
        progress = 0;
        soul.getNavigation().stop();
    }

    @Override
    public void tick() {
        Level level = Compat.level(soul);
        soul.getLookControl().setLookAt(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5);
        progress++;
        if (progress % 10 == 0) {
            soul.swing(InteractionHand.MAIN_HAND);
        }
        level.destroyBlockProgress(soul.getId(), block, (int) (progress * 10f / needed));
        if (progress >= needed) {
            BlockState state = level.getBlockState(block);
            boolean keep = SoulGear.isBuildingBlock(state.getBlock()) && soul.buildingBlockCount() < SoulGear.MAX_BLOCKS;
            level.destroyBlock(block, !keep, soul);
            if (keep) {
                soul.addBlock(new ItemStack(state.getBlock()));
            }
            level.destroyBlockProgress(soul.getId(), block, -1);
            block = null;
        }
    }

    @Override
    public void stop() {
        if (block != null) {
            Compat.level(soul).destroyBlockProgress(soul.getId(), block, -1);
        }
        block = null;
        progress = 0;
    }
}
