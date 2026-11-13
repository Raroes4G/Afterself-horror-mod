package com.afterself.entity.goal;

import com.afterself.Compat;
import com.afterself.entity.SoulEntity;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.Level;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Badly hurt with no totem, it does what a player would: runs out of sight, eats what it carries, and comes back
 * later. A soul that got away is not a soul you are rid of.
 */
public class SoulRetreatGoal extends Goal {
    private static final int EAT_TICKS = 32;
    private static final double SAFE_RANGE = 14.0;
    private final SoulEntity soul;
    private int nextMoveAt;
    private int eatingUntil;

    public SoulRetreatGoal(SoulEntity soul) {
        this.soul = soul;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return soul.getMode() == SoulEntity.Mode.RETREAT;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        nextMoveAt = 0;
        eatingUntil = 0;
    }

    @Override
    public void tick() {
        if (eatingUntil > 0) {
            soul.getNavigation().stop();
            if (soul.tickCount % 4 == 0) {
                Compat.level(soul).playSound(null, soul.blockPosition(), SoundEvents.GENERIC_EAT, SoundSource.HOSTILE, 0.4f, 0.9f);
            }
            if (soul.tickCount >= eatingUntil) {
                soul.finishEating();
            }
            return;
        }
        Player threat = soul.getStalkedPlayer();
        boolean safe = threat == null
                || (soul.distanceToSqr(threat) > SAFE_RANGE * SAFE_RANGE && !soul.isSeenBy(threat));
        if (safe || soul.modeExpired()) {
            soul.setSprinting(false);
            if (safe && soul.startEating()) {
                eatingUntil = soul.tickCount + EAT_TICKS;
            } else {
                soul.returnToStalking();
                if (!safe) {
                    soul.vanish();
                }
            }
            return;
        }
        soul.setSprinting(true);
        if (soul.tickCount >= nextMoveAt) {
            nextMoveAt = soul.tickCount + 20;
            Vec3 away = fleeSpot(threat);
            if (away != null) {
                soul.getNavigation().moveTo(away.x, away.y, away.z, 1.35);
            }
        }
    }

    /** Straight away from the threat, give or take, on ground it can stand on. */
    private Vec3 fleeSpot(Player threat) {
        Level level = Compat.level(soul);
        Vec3 away = new Vec3(soul.getX() - threat.getX(), 0.0, soul.getZ() - threat.getZ());
        away = away.lengthSqr() < 0.01 ? new Vec3(1.0, 0.0, 0.0) : away.normalize();
        away = away.yRot((soul.getRandom().nextFloat() - 0.5f) * 1.2f);
        int floor = (int) Math.floor(soul.getY() + 0.2);
        for (double reach : new double[] {14.0, 9.0, 5.0}) {
            int x = (int) Math.floor(soul.getX() + away.x * reach);
            int z = (int) Math.floor(soul.getZ() + away.z * reach);
            for (int dy = 0; dy <= 4; dy++) {
                for (int y : new int[] {floor + dy, floor - dy}) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (SoulEntity.canStandAt(level, pos)) {
                        return new Vec3(x + 0.5, y, z + 0.5);
                    }
                }
            }
        }
        return DefaultRandomPos.getPosAway(soul, 16, 7, threat.position());
    }

    @Override
    public void stop() {
        soul.setSprinting(false);
    }
}
