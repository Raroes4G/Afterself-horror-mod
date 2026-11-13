package com.afterself.entity.goal;

import com.afterself.Compat;
import com.afterself.entity.SoulEntity;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The default behaviour: keep a player in reach without being seen. It moves between hidden spots at a distance,
 * crouches when it stops, looks around like someone checking they are alone, and comes closer to what it hears.
 */
public class SoulStalkGoal extends Goal {
    private static final double NEAR = 14.0;
    private static final double FAR = 30.0;
    private static final int CANDIDATES = 14;
    private final SoulEntity soul;
    private int nextMoveAt;
    private int nextLookAt;

    public SoulStalkGoal(SoulEntity soul) {
        this.soul = soul;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return soul.getMode() == SoulEntity.Mode.STALK;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        nextMoveAt = 0;
    }

    @Override
    public void tick() {
        Player player = soul.getStalkedPlayer();
        if (soul.getLureScene() == SoulEntity.LureScene.WATCH) {
            // Caught watching: perfectly still, staring back.
            soul.getNavigation().stop();
            if (player != null) {
                soul.getLookControl().setLookAt(player.getX(), player.getEyeY(), player.getZ(), 60f, 60f);
            }
            return;
        }
        boolean arrived = soul.getNavigation().isDone();
        if (arrived) {
            soul.setPose(Pose.CROUCHING);
        }
        if (soul.tickCount >= nextLookAt) {
            nextLookAt = soul.tickCount + 30 + soul.getRandom().nextInt(70);
            lookAround(player);
        }
        if (soul.tickCount >= nextMoveAt && (arrived || soul.tickCount % 40 == 0)) {
            nextMoveAt = soul.tickCount + 60 + soul.getRandom().nextInt(200);
            moveSomewhereHidden(player);
        }
    }

    private void lookAround(Player player) {
        if (player != null && soul.getRandom().nextInt(3) != 0) {
            // Mostly watching its prey.
            soul.getLookControl().setLookAt(player.getX(), player.getEyeY(), player.getZ());
        } else {
            double angle = soul.getRandom().nextDouble() * Math.PI * 2.0;
            soul.getLookControl().setLookAt(soul.getX() + Math.cos(angle) * 6.0, soul.getEyeY() - 0.4 + soul.getRandom().nextDouble(), soul.getZ() + Math.sin(angle) * 6.0);
        }
    }

    private void moveSomewhereHidden(Player player) {
        Level level = Compat.level(soul);
        Vec3 best = null;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < CANDIDATES; i++) {
            Vec3 spot;
            if (soul.heardRecently(200) && soul.getHeardPos() != null) {
                // Drawn to what it heard.
                BlockPos heard = soul.getHeardPos();
                spot = DefaultRandomPos.getPosTowards(soul, 16, 6, new Vec3(heard.getX(), heard.getY(), heard.getZ()), Math.PI / 2.0);
            } else if (player != null && soul.distanceToSqr(player) > FAR * FAR) {
                spot = DefaultRandomPos.getPosTowards(soul, 16, 6, player.position(), Math.PI / 2.0);
            } else {
                spot = DefaultRandomPos.getPos(soul, 14, 6);
            }
            if (spot == null) {
                continue;
            }
            double score = level.getMaxLocalRawBrightness(new BlockPos(Mth.floor(spot.x), Mth.floor(spot.y), Mth.floor(spot.z)));
            if (player != null) {
                double distance = spot.distanceTo(player.position());
                if (!soul.isHiddenFrom(player, spot)) {
                    score += 100.0;
                }
                if (distance < NEAR) {
                    score += 60.0 + (NEAR - distance) * 4.0;
                } else if (distance > FAR) {
                    score += distance - FAR;
                }
            }
            if (score < bestScore) {
                bestScore = score;
                best = spot;
            }
        }
        if (best != null) {
            soul.setPose(Pose.STANDING);
            soul.setSprinting(false);
            soul.getNavigation().moveTo(best.x, best.y, best.z, 0.85);
        }
    }

    @Override
    public void stop() {
        if (soul.getPose() == Pose.CROUCHING) {
            soul.setPose(Pose.STANDING);
        }
    }
}
