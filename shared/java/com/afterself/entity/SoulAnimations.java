package com.afterself.entity;

/**
 * Which Blockbench animation a soul plays. The names here are the animations in soul.animation.json:
 * adding or redrawing one in Blockbench under the same name needs no code.
 */
public final class SoulAnimations {
    public static final String IDLE = "idle";
    public static final String WALK = "walk";
    public static final String SPRINT = "sprint";
    public static final String CROUCH = "crouch";
    public static final String CROUCH_WALK = "crouch_walk";
    public static final String ATTACK = "attack";
    public static final String SEARCH = "search";
    /** Played over the others while the shield is up: only the shield arm. */
    public static final String GUARD = "guard";

    private SoulAnimations() {}

    /** Shield raised. Known on the client too: the game tells it which hand is in use. */
    public static boolean isGuarding(net.minecraft.world.entity.LivingEntity soul) {
        return soul.isUsingItem() && SoulGear.isShield(soul.getUseItem());
    }

    public static String nameFor(SoulEntity soul) {
        SoulEntity.LureScene scene = soul.getLureScene();
        if (scene.animation != null) {
            return scene.animation;
        }
        boolean moving = soul.getDeltaMovement().horizontalDistanceSqr() > 0.0005 || soul.walkAnimationSpeed() > 0.05f;
        if (soul.isCrouching()) {
            return moving ? CROUCH_WALK : CROUCH;
        }
        if (!moving) {
            // Standing in the place it last saw someone, turning its head.
            return soul.getMode() == SoulEntity.Mode.SEARCH ? SEARCH : IDLE;
        }
        return soul.isSprinting() ? SPRINT : WALK;
    }
}
