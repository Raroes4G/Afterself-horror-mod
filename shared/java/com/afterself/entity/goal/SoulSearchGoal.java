package com.afterself.entity.goal;

import com.afterself.entity.SoulEntity;
import java.util.EnumSet;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

/**
 * It lost sight of its prey. It runs to where it last saw them, then walks the place slowly, turning its head.
 * Whoever stays hidden and silent long enough is left alone. Seeing or hearing them starts the hunt again
 * (decided in SoulEntity).
 */
public class SoulSearchGoal extends Goal {
    private static final double ARRIVED_RANGE = 3.0;
    private static final double ROAM_RANGE = 12.0;
    private final SoulEntity soul;
    private boolean arrived;
    private int startedAt;
    private int nextMoveAt;
    private int nextLookAt;

    public SoulSearchGoal(SoulEntity soul) {
        this.soul = soul;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return soul.getMode() == SoulEntity.Mode.SEARCH && soul.getSearchPos() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        arrived = false;
        startedAt = soul.tickCount;
        nextMoveAt = 0;
        soul.setSearchArrived(false);
    }

    @Override
    public void tick() {
        Vec3 pos = soul.getSearchPos();
        if (pos == null) {
            return;
        }
        if (!arrived) {
            soul.setSprinting(true);
            if (soul.tickCount >= nextMoveAt) {
                nextMoveAt = soul.tickCount + 20;
                soul.getNavigation().moveTo(pos.x, pos.y, pos.z, 1.2);
            }
            soul.getLookControl().setLookAt(pos.x, pos.y + 1.5, pos.z);
            if (soul.distanceToSqr(pos) <= ARRIVED_RANGE * ARRIVED_RANGE || soul.tickCount - startedAt > 300) {
                arrived = true;
                nextMoveAt = soul.tickCount + 30;
                soul.setSearchArrived(true);
                soul.setSprinting(false);
                soul.getNavigation().stop();
            }
            return;
        }
        if (soul.tickCount >= nextLookAt) {
            nextLookAt = soul.tickCount + 15 + soul.getRandom().nextInt(30);
            double angle = soul.getRandom().nextDouble() * Math.PI * 2.0;
            soul.getLookControl().setLookAt(soul.getX() + Math.cos(angle) * 6.0,
                    soul.getEyeY() - 0.6 + soul.getRandom().nextDouble() * 1.2, soul.getZ() + Math.sin(angle) * 6.0);
        }
        if (soul.tickCount >= nextMoveAt && soul.getNavigation().isDone()) {
            nextMoveAt = soul.tickCount + 40 + soul.getRandom().nextInt(60);
            Vec3 spot = DefaultRandomPos.getPos(soul, 8, 3);
            if (spot != null && spot.distanceToSqr(pos) <= ROAM_RANGE * ROAM_RANGE) {
                soul.getNavigation().moveTo(spot.x, spot.y, spot.z, 0.8);
            }
        }
    }

    @Override
    public void stop() {
        soul.setSprinting(false);
        soul.getNavigation().stop();
    }
}
