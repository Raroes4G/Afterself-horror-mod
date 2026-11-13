package com.afterself.entity.goal;

import com.afterself.entity.SoulEntity;
import com.afterself.entity.SoulEntity.LureScene;
import java.util.EnumSet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The rare scenes. For as long as one lasts, the soul looks exactly like the living player: solid, with their name
 * above its head. It waves, beckons, kneels, plays dead, cries with its back turned, or just walks around like a
 * friend who is busy, and it speaks with their real voice. Whoever comes close enough springs the trap.
 */
public class SoulLureGoal extends Goal {
    private static final double SPRING_RANGE = 6.0;
    private static final double LOSE_INTEREST_RANGE = 70.0;
    private static final int SPEAK_EVERY_TICKS = 200;
    private final SoulEntity soul;
    private int nextSpeechAt;
    private int nextWanderAt;

    public SoulLureGoal(SoulEntity soul) {
        this.soul = soul;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return soul.getMode() == SoulEntity.Mode.LURE;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        soul.getNavigation().stop();
        nextSpeechAt = soul.tickCount + 40;
        nextWanderAt = soul.tickCount + 20;
    }

    @Override
    public void tick() {
        LureScene scene = soul.getLureScene();
        Player player = soul.getStalkedPlayer();
        if (player == null || soul.modeExpired() || soul.distanceToSqr(player) > LOSE_INTEREST_RANGE * LOSE_INTEREST_RANGE) {
            soul.returnToStalking();
            soul.vanish();
            return;
        }
        if (soul.distanceToSqr(player) <= SPRING_RANGE * SPRING_RANGE) {
            soul.springTrap(player);
            return;
        }
        if (scene == LureScene.MIMIC) {
            wander(player);
        } else {
            soul.getNavigation().stop();
            if (scene == LureScene.BACK || scene == LureScene.CRY) {
                // Its back to you. You have to come round to see its face.
                soul.getLookControl().setLookAt(soul.getX() * 2.0 - player.getX(), soul.getEyeY(), soul.getZ() * 2.0 - player.getZ());
            } else {
                soul.getLookControl().setLookAt(player.getX(), player.getEyeY(), player.getZ());
            }
        }
        if (soul.tickCount >= nextSpeechAt) {
            nextSpeechAt = soul.tickCount + SPEAK_EVERY_TICKS;
            // The real voice, not the slowed one: this is the moment it has to be believed.
            soul.speak(1f);
        }
    }

    /** Looks busy, the way a player does: a few steps, a jump, a swing at nothing, a glance over. */
    private void wander(Player player) {
        if (soul.getNavigation().isDone()) {
            if (soul.tickCount >= nextWanderAt) {
                nextWanderAt = soul.tickCount + 50 + soul.getRandom().nextInt(90);
                Vec3 spot = DefaultRandomPos.getPos(soul, 6, 2);
                if (spot != null && spot.distanceToSqr(player.position()) > SPRING_RANGE * SPRING_RANGE * 4.0) {
                    soul.getNavigation().moveTo(spot.x, spot.y, spot.z, 0.85);
                }
            } else if (soul.getRandom().nextInt(4) == 0) {
                soul.getLookControl().setLookAt(player.getX(), player.getEyeY(), player.getZ());
            }
            if (soul.getRandom().nextInt(25) == 0) {
                soul.swing(InteractionHand.MAIN_HAND);
            }
        } else if (soul.onGroundCompat() && soul.getRandom().nextInt(30) == 0) {
            soul.getJumpControl().jump();
        }
    }

    @Override
    public void stop() {
        if (soul.getMode() == SoulEntity.Mode.LURE) {
            soul.returnToStalking();
        }
    }
}
