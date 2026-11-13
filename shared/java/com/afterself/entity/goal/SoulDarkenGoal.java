package com.afterself.entity.goal;

import com.afterself.Compat;
import com.afterself.AfterselfConfig;
import com.afterself.entity.SoulEntity;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Souls like the dark. While it stalks, it creeps up to a torch or a lantern near its prey and knocks it down:
 * the player comes back to a darker room and a torch lying on the floor.
 */
public class SoulDarkenGoal extends Goal {
    private static final int SEARCH_RANGE = 10;
    private static final int SEARCH_TRIES = 120;
    private static final double REACH = 3.0;
    private final SoulEntity soul;
    private BlockPos light;
    private int giveUp;
    private int nextSearchAt = 400;

    public SoulDarkenGoal(SoulEntity soul) {
        this.soul = soul;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!AfterselfConfig.soulsHaunt || !AfterselfConfig.soulsBreakBlocks || soul.getMode() != SoulEntity.Mode.STALK
                || soul.tickCount < nextSearchAt) {
            return false;
        }
        nextSearchAt = soul.tickCount + 300 + soul.getRandom().nextInt(300);
        Player player = soul.getStalkedPlayer();
        if (player == null || soul.distanceToSqr(player) > 40.0 * 40.0
                || !Compat.level(soul).getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            return false;
        }
        return findLight();
    }

    /** Public so tests can skip the wait. */
    public boolean findLight() {
        Level level = Compat.level(soul);
        BlockPos origin = soul.blockPosition();
        for (int i = 0; i < SEARCH_TRIES; i++) {
            BlockPos pos = origin.offset(soul.getRandom().nextInt(SEARCH_RANGE * 2 + 1) - SEARCH_RANGE,
                    soul.getRandom().nextInt(7) - 2, soul.getRandom().nextInt(SEARCH_RANGE * 2 + 1) - SEARCH_RANGE);
            if (isSmallLight(level, pos)) {
                light = pos;
                return true;
            }
        }
        return false;
    }

    /** Torches, lanterns, candles, end rods: never a full block, a fire or a machine. */
    public static boolean isSmallLight(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getLightEmission() < 10 || !state.getFluidState().isEmpty() || state.getBlock() instanceof EntityBlock
                || state.getBlock() instanceof BaseFireBlock || state.isCollisionShapeFullBlock(level, pos)) {
            return false;
        }
        float hardness = state.getDestroySpeed(level, pos);
        return hardness >= 0f && hardness <= 3.5f;
    }

    @Override
    public boolean canContinueToUse() {
        return light != null && soul.getMode() == SoulEntity.Mode.DARKEN && giveUp > 0
                && isSmallLight(Compat.level(soul), light);
    }

    @Override
    public void start() {
        soul.setMode(SoulEntity.Mode.DARKEN, 0);
        soul.setPose(Pose.CROUCHING);
        soul.setSprinting(false);
        giveUp = 200;
        soul.getNavigation().moveTo(light.getX() + 0.5, light.getY(), light.getZ() + 0.5, 0.8);
    }

    @Override
    public void tick() {
        giveUp--;
        if (light == null) {
            return;
        }
        soul.getLookControl().setLookAt(light.getX() + 0.5, light.getY() + 0.5, light.getZ() + 0.5);
        if (soul.distanceToSqr(light.getX() + 0.5, light.getY() + 0.5, light.getZ() + 0.5) <= REACH * REACH) {
            snuff();
        } else if (soul.getNavigation().isDone() && giveUp % 10 == 0) {
            soul.getNavigation().moveTo(light.getX() + 0.5, light.getY(), light.getZ() + 0.5, 0.8);
        }
    }

    /** Public so tests can skip the walk. */
    public boolean snuff() {
        if (light == null || !isSmallLight(Compat.level(soul), light)) {
            return false;
        }
        soul.swing(InteractionHand.MAIN_HAND);
        Compat.level(soul).destroyBlock(light, true, soul);
        light = null;
        return true;
    }

    @Override
    public void stop() {
        light = null;
        if (soul.getPose() == Pose.CROUCHING) {
            soul.setPose(Pose.STANDING);
        }
        if (soul.getMode() == SoulEntity.Mode.DARKEN) {
            soul.setMode(SoulEntity.Mode.STALK, 0);
        }
    }
}
