package com.afterself.entity.goal;

import com.afterself.Compat;
import com.afterself.AfterselfConfig;
import com.afterself.entity.SoulEntity;
import com.afterself.entity.SoulGear;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * A hunting soul that cannot walk to its target builds its way there like a player: it crouches to bridge over a drop,
 * jumps and places under itself to climb, and digs a block out of the ground when its hands are empty.
 */
public class SoulBuildGoal extends Goal {
    private enum Plan { GATHER, BRIDGE, WALK, PILLAR, LAND }

    private static final int STUCK_TICKS = 25;
    private static final int MAX_BLOCKS_PER_RUN = 32;
    private static final float GATHER_MAX_HARDNESS = 3f;
    private static final int MAX_GATHER = 12;
    private final SoulEntity soul;
    private Plan plan;
    private BlockPos spot;
    private int wait;
    private int placed;
    private double baseY;

    public SoulBuildGoal(SoulEntity soul) {
        this.soul = soul;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = soul.getTarget();
        SoulEntity.Mode mode = soul.getMode();
        if (target == null || (mode != SoulEntity.Mode.HUNT && mode != SoulEntity.Mode.TRAP) || !AfterselfConfig.soulsPlaceBlocks
                || soul.getStuckTicks() < STUCK_TICKS || !soul.onGroundCompat()
                || !Compat.level(soul).getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            return false;
        }
        placed = 0;
        return decide(target);
    }

    /** Picks the next thing to build, or returns false when walking would do. */
    private boolean decide(LivingEntity target) {
        Level level = Compat.level(soul);
        BlockPos feet = soul.blockPosition();
        double dx = target.getX() - soul.getX();
        double dz = target.getZ() - soul.getZ();
        double dy = target.getY() - soul.getY();
        double flat = Math.sqrt(dx * dx + dz * dz);
        Direction facing = Direction.getNearest(dx, 0f, dz);
        BlockPos front = feet.relative(facing);

        Plan wanted = null;
        BlockPos where = null;
        boolean wayOpen = open(level, front) && open(level, front.above());
        boolean drop = open(level, front.below()) && (open(level, front.below(2)) || !level.getFluidState(front.below()).isEmpty());
        if (open(level, feet.below()) && soul.onGroundCompat() && dy > -4.0) {
            // Standing on the very edge, toes over the drop: the next block goes under its own feet.
            wanted = Plan.BRIDGE;
            where = feet.below();
        } else if (wayOpen && drop && dy > -4.0 && flat > 1.5) {
            wanted = Plan.BRIDGE;
            where = front.below();
        } else if (open(level, feet.above(2)) && ((dy >= 1.5 && flat < 6.0) || (!open(level, front.above()) && !AfterselfConfig.soulsBreakBlocks))) {
            wanted = Plan.PILLAR;
            where = feet;
        }
        if (wanted == null || placed >= MAX_BLOCKS_PER_RUN) {
            return false;
        }
        // Short of blocks for the whole job: it digs what it needs out of the ground first, while it still stands on it.
        int needed = Math.min(MAX_GATHER, wanted == Plan.PILLAR ? (int) Math.ceil(dy) : (int) Math.ceil(flat));
        if (soul.buildingBlockCount() < needed) {
            BlockPos source = findSource(level, feet, facing);
            if (source != null) {
                plan = Plan.GATHER;
                spot = source;
                wait = 20;
                return true;
            }
            if (!soul.hasBuildingBlock()) {
                return false;
            }
        }
        plan = wanted;
        spot = where;
        wait = wanted == Plan.BRIDGE ? 6 : 0;
        baseY = soul.getY();
        soul.holdBlock();
        return true;
    }

    /** A block of ground within reach that it can see the top of: never the one it stands on, never the way ahead. */
    private BlockPos findSource(Level level, BlockPos feet, Direction facing) {
        for (int ring = 1; ring <= 2; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring
                            || dx * facing.getStepX() + dz * facing.getStepZ() > 0) {
                        continue;
                    }
                    for (int depth = 1; depth <= 2; depth++) {
                        BlockPos pos = feet.offset(dx, -depth, dz);
                        BlockState state = level.getBlockState(pos);
                        float hardness = state.getDestroySpeed(level, pos);
                        if (open(level, pos.above()) && SoulGear.isBuildingBlock(state.getBlock())
                                && hardness >= 0f && hardness <= GATHER_MAX_HARDNESS) {
                            return pos;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean open(Level level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    @Override
    public boolean canContinueToUse() {
        return plan != null && soul.getTarget() != null && soul.getTarget().isAlive() && soul.isHunting();
    }

    @Override
    public void start() {
        soul.getNavigation().stop();
        soul.setSprinting(false);
    }

    @Override
    public void tick() {
        LivingEntity target = soul.getTarget();
        if (target == null || plan == null) {
            plan = null;
            return;
        }
        Level level = Compat.level(soul);
        switch (plan) {
            case GATHER -> {
                soul.getNavigation().stop();
                look(spot);
                if (--wait % 6 == 0) {
                    soul.swing(InteractionHand.MAIN_HAND);
                }
                if (wait <= 0) {
                    BlockState state = level.getBlockState(spot);
                    if (SoulGear.isBuildingBlock(state.getBlock())) {
                        level.destroyBlock(spot, false, soul);
                        soul.addBlock(new ItemStack(state.getBlock()));
                    }
                    again(target);
                }
            }
            case BRIDGE -> {
                soul.getNavigation().stop();
                soul.setPose(Pose.CROUCHING);
                look(spot);
                if (--wait <= 0) {
                    if (place(level, spot)) {
                        plan = Plan.WALK;
                        wait = 24;
                    } else {
                        plan = null;
                    }
                }
            }
            case WALK -> {
                double x = spot.getX() + 0.5;
                double z = spot.getZ() + 0.5;
                soul.getMoveControl().setWantedPosition(x, spot.getY() + 1.0, z, 0.8);
                double dx = soul.getX() - x;
                double dz = soul.getZ() - z;
                if (--wait <= 0 || dx * dx + dz * dz < 0.12) {
                    again(target);
                }
            }
            case PILLAR -> {
                soul.getNavigation().stop();
                soul.setPose(Pose.STANDING);
                soul.setDeltaMovement(0.0, soul.getDeltaMovement().y, 0.0);
                if (wait <= 0 && soul.onGroundCompat()) {
                    spot = soul.blockPosition();
                    baseY = soul.getY();
                    // Centred, or the block lands half under its feet.
                    soul.setPos(spot.getX() + 0.5, soul.getY(), spot.getZ() + 0.5);
                    soul.getJumpControl().jump();
                    wait = 14;
                } else if (wait > 0) {
                    wait--;
                    if (soul.getY() >= baseY + 1.0 && open(level, spot)) {
                        plan = place(level, spot) ? Plan.LAND : null;
                        wait = 10;
                    } else if (wait == 0 && soul.getY() < baseY + 0.5) {
                        // Something is in the way of the jump.
                        plan = null;
                    }
                }
            }
            case LAND -> {
                soul.setDeltaMovement(0.0, soul.getDeltaMovement().y, 0.0);
                if (soul.onGroundCompat() || --wait <= 0) {
                    again(target);
                }
            }
        }
    }

    private void again(LivingEntity target) {
        if (!decide(target)) {
            plan = null;
        }
    }

    private void look(BlockPos pos) {
        soul.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private boolean place(Level level, BlockPos pos) {
        ItemStack stack = soul.peekBuildingBlock();
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) {
            return false;
        }
        BlockState state = item.getBlock().defaultBlockState();
        BlockState current = level.getBlockState(pos);
        if (!current.getCollisionShape(level, pos).isEmpty() || current.getBlock() instanceof EntityBlock
                || (!current.isAir() && current.getDestroySpeed(level, pos) < 0f)
                || !level.isUnobstructed(state, pos, CollisionContext.empty())) {
            return false;
        }
        level.setBlock(pos, state, 3);
        SoundType sound = state.getSoundType();
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1f) / 2f, sound.getPitch() * 0.8f);
        soul.swing(InteractionHand.MAIN_HAND);
        soul.consumeOne(SoulGear::isBuildingBlock);
        placed++;
        return true;
    }

    @Override
    public void stop() {
        plan = null;
        if (soul.getPose() == Pose.CROUCHING) {
            soul.setPose(Pose.STANDING);
        }
        soul.holdMelee();
        soul.resetStuck();
    }
}
