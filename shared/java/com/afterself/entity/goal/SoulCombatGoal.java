package com.afterself.entity.goal;

import com.afterself.entity.SoulEntity;
import com.afterself.entity.SoulGear;
import java.util.EnumSet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Fights the way players do: sprints in, jumps to land critical hits, circles and strafes while its weapon recharges,
 * raises a shield between blows, throws an ender pearl to close the distance, and switches to a bow when the target
 * is out of reach. It only knows where its target is while it can see it: out of sight for a few seconds, and it has
 * to go and search.
 */
public class SoulCombatGoal extends Goal {
    private static final double SPRINT_SPEED = 1.35;
    private static final double MELEE_RANGE = 3.2;
    private static final double BOW_RANGE = 9.0;
    private static final double PEARL_RANGE = 16.0;
    private static final int ATTACK_COOLDOWN = 14;
    private static final int BOW_COOLDOWN = 30;
    private static final int PEARL_COOLDOWN = 160;
    private static final int LOST_SIGHT_TICKS = 80;
    private final SoulEntity soul;
    private int attackReadyAt;
    private int repathAt;
    private int strafeUntil;
    private int pearlReadyAt;
    private int lastSeenAt;
    private float strafeSide = 1f;
    private boolean usingBow;

    public SoulCombatGoal(SoulEntity soul) {
        this.soul = soul;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return soul.isHunting() && soul.getTarget() != null && soul.getTarget().isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        repathAt = 0;
        usingBow = false;
        lastSeenAt = soul.tickCount;
        soul.holdMelee();
        LivingEntity target = soul.getTarget();
        if (target != null) {
            soul.rememberSeen(target.position());
        }
    }

    @Override
    public void tick() {
        LivingEntity target = soul.getTarget();
        if (target == null) {
            return;
        }
        soul.getLookControl().setLookAt(target, 30f, 30f);
        double distance = Math.sqrt(soul.distanceToSqr(target));
        boolean canSee = soul.getSensing().hasLineOfSight(target);
        if (canSee) {
            lastSeenAt = soul.tickCount;
            soul.rememberSeen(target.position());
        } else if (soul.getMode() == SoulEntity.Mode.HUNT && soul.tickCount - lastSeenAt >= LOST_SIGHT_TICKS) {
            // It lost them. It goes to where it last saw them, and looks.
            soul.startSearching();
            return;
        }

        if (canSee && distance > PEARL_RANGE && soul.tickCount >= pearlReadyAt && soul.getHealth() > 8f
                && soul.getMode() == SoulEntity.Mode.HUNT && soul.throwPearl(target)) {
            pearlReadyAt = soul.tickCount + PEARL_COOLDOWN;
        }

        boolean wantBow = distance > BOW_RANGE && canSee && soul.getMode() == SoulEntity.Mode.HUNT;
        if (wantBow != usingBow) {
            usingBow = wantBow && soul.holdBow();
            if (!usingBow) {
                soul.holdMelee();
            }
        }
        if (usingBow) {
            lowerShield();
            soul.setSprinting(false);
            soul.getNavigation().stop();
            soul.getMoveControl().strafe(0f, strafeSide * 0.4f);
            if (soul.tickCount % 50 == 0) {
                strafeSide = -strafeSide;
            }
            if (soul.tickCount >= attackReadyAt) {
                attackReadyAt = soul.tickCount + BOW_COOLDOWN;
                soul.swing(InteractionHand.MAIN_HAND);
                soul.performRangedAttack(target, 1f);
            }
            return;
        }

        if (distance > MELEE_RANGE + 1.5) {
            // Sprint-jumping toward the target, like any player in a hurry.
            lowerShield();
            // At the edge of a drop it walks, the way a careful player does: a sprint would carry it over.
            boolean edge = !soul.groundAhead(1.2) || !soul.groundAhead(2.4);
            if (edge == soul.isSprinting()) {
                // The pace changes now, not at the next look at the path.
                repathAt = 0;
            }
            soul.setSprinting(!edge);
            if (soul.tickCount >= repathAt) {
                repathAt = soul.tickCount + 8;
                soul.getNavigation().moveTo(target, edge ? 0.9 : SPRINT_SPEED);
            }
            // A sprint-jump carries four blocks: all of them must have a floor.
            if (!edge && soul.onGroundCompat() && soul.getRandom().nextInt(12) == 0 && !soul.getNavigation().isDone()
                    && soul.groundAhead(3.6) && soul.groundAhead(4.8)) {
                soul.getJumpControl().jump();
            }
            return;
        }

        soul.getNavigation().stop();
        boolean ready = soul.tickCount >= attackReadyAt;
        if (ready) {
            // Close in and jump: a falling hit is a critical hit for players.
            lowerShield();
            soul.setSprinting(false);
            soul.getMoveControl().strafe(0.8f, strafeSide * 0.2f);
            if (soul.onGroundCompat() && soul.getRandom().nextInt(3) == 0 && soul.groundAhead(1.6)) {
                soul.getJumpControl().jump();
            }
            double reach = soul.getBbWidth() * 2f + target.getBbWidth() + 1.2;
            if (soul.distanceToSqr(target) <= reach * reach) {
                attackReadyAt = soul.tickCount + ATTACK_COOLDOWN;
                soul.swing(InteractionHand.MAIN_HAND);
                soul.doHurtTarget(target);
                strafeUntil = soul.tickCount + 8 + soul.getRandom().nextInt(10);
                strafeSide = soul.getRandom().nextBoolean() ? 1f : -1f;
            }
        } else {
            // Shield up while the weapon recharges.
            if (!soul.isUsingItem() && SoulGear.isShield(soul.getOffhandItem())) {
                soul.startUsingItem(InteractionHand.OFF_HAND);
            }
            if (soul.tickCount < strafeUntil) {
                // Back off and circle.
                soul.getMoveControl().strafe(-0.5f, strafeSide * 0.7f);
            } else {
                soul.getMoveControl().strafe(0.3f, strafeSide * 0.6f);
            }
        }
    }

    private void lowerShield() {
        if (soul.isUsingItem()) {
            soul.stopUsingItem();
        }
    }

    @Override
    public void stop() {
        lowerShield();
        soul.setSprinting(false);
        soul.getNavigation().stop();
        soul.holdMelee();
    }
}
