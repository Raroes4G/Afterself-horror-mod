package com.afterself.client;

import com.afterself.entity.SoulEntity;
import com.afterself.entity.SoulGear;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Drives the player's own body from their own client, so the movement looks like any player's to everyone else.
 * The real keys and mouse do nothing, only Esc works. Stops by itself if the server stops confirming.
 * The body finds its way around walls and through doors, and it does not let itself be beaten: whatever attacks it,
 * a monster, another soul or a player, it turns on with the best weapon it carries, behind its shield if it has one.
 */
public final class PossessionClient {
    private static final int EXPIRY_TICKS = 120;
    private static final double ATTACK_REACH = 3.0;
    private static final float LOOK_TURN = 6f;
    private static final float WALK_TURN = 15f;
    private static final float FIGHT_TURN = 24f;
    private static final int THREAT_MEMORY = 200;
    private static final double THREAT_RANGE = 12.0;

    /** For tests: where the body must walk when nobody is around. */
    public static BlockPos testGoal;

    private static int ticksLeft;
    private static boolean active;
    private static int age;
    private static float previousYaw;
    private static float previousPitch;

    private static List<BlockPos> path = List.of();
    private static int pathIndex;
    private static BlockPos pathGoal;
    private static int pathMadeAt;
    private static int nextPathAt;

    private static BlockPos wander;
    private static int nextWanderAt;
    private static int wanderUntil;
    private static float idleYaw;
    private static int nextIdleLookAt;

    private static double checkX;
    private static double checkZ;
    private static int nextStuckCheckAt;
    private static int walkedTicks;
    private static int stuckChecks;
    private static int escapeUntil;
    private static float escapeYaw;

    private static LivingEntity threat;
    private static LivingEntity lastTarget;
    private static int threatUntil;
    private static int nextScanAt;
    private static int lastHurtTime;
    private static int nextDoorAt;
    private static int hitsLanded;

    private PossessionClient() {}

    public static boolean isActive() {
        return active;
    }

    /** What the body is fighting right now, if anything. */
    public static LivingEntity currentThreat() {
        return active ? threat : null;
    }

    /** Blows the body has struck since it was taken. */
    public static int hitsLanded() {
        return hitsLanded;
    }

    public static void onPossessed() {
        if (!active) {
            reset();
        }
        active = true;
        ticksLeft = EXPIRY_TICKS;
    }

    public static void onReleased() {
        if (active) {
            active = false;
            releaseKeys(Minecraft.getInstance().options);
        }
    }

    private static void reset() {
        age = 0;
        path = List.of();
        pathGoal = null;
        nextPathAt = 0;
        wander = null;
        nextWanderAt = 0;
        nextIdleLookAt = 0;
        nextStuckCheckAt = 0;
        walkedTicks = 0;
        stuckChecks = 0;
        escapeUntil = 0;
        threat = null;
        lastTarget = null;
        nextScanAt = 0;
        lastHurtTime = 0;
        nextDoorAt = 0;
        hitsLanded = 0;
    }

    /** Runs before the game reads its keys, every client tick. */
    public static void onClientTick() {
        if (!active) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || --ticksLeft <= 0) {
            onReleased();
            return;
        }
        // Esc and the death screen stay reachable. Nothing else that would let the player act or write.
        if (mc.screen != null && !(mc.screen instanceof PauseScreen) && !(mc.screen instanceof DeathScreen)) {
            mc.setScreen(null);
        }
        Options options = mc.options;
        swallowKeys(options);
        previousYaw = player.getYRot();
        previousPitch = player.getXRot();
        if (mc.screen == null) {
            act(mc, player, options);
        }
    }

    private static void act(Minecraft mc, LocalPlayer player, Options options) {
        age++;
        LivingEntity enemy = findThreat(mc, player);
        Player other = enemy == null ? nearestOtherPlayer(mc, player) : null;
        LivingEntity target = enemy != null ? enemy : other;
        // Weapon in hand before the first blow, and again now and then: what it carries can change.
        if (target != null && (target != lastTarget || age % 20 == 1)) {
            drawBestWeapon(player);
        }
        lastTarget = target;

        // Where to put the feet next.
        Vec3 aim = null;
        boolean higher = false;
        // Walking on a found path, or only straight at something.
        boolean blind = true;
        double stopAt = 0.3;
        boolean escaping = age < escapeUntil;
        if (escaping) {
            float radians = escapeYaw * Mth.DEG_TO_RAD;
            aim = player.position().add(-Mth.sin(radians) * 3.0, 0.0, Mth.cos(radians) * 3.0);
        } else if (target != null && flatDistance(player, target.position()) < 3.5 && Math.abs(target.getY() - player.getY()) < 1.3
                && player.hasLineOfSight(target)) {
            // Close and in plain sight: straight at them.
            aim = target.position();
            stopAt = enemy != null ? 1.7 : 2.2;
        } else {
            BlockPos goal = target != null ? cell(target) : testGoal != null ? testGoal : wanderGoal(mc, player);
            BlockPos node = goal == null ? null : nextNode(mc, player, goal, target != null);
            if (node != null) {
                aim = new Vec3(node.getX() + 0.5, node.getY(), node.getZ() + 0.5);
                higher = node.getY() - player.getY() > 0.6;
                blind = false;
                openDoors(mc, player, node);
            } else if (enemy != null && flatDistance(player, enemy.position()) < 6.0) {
                // No way found, but it is right there.
                aim = enemy.position();
                stopAt = 1.7;
            } else if (target == null && wander != null && pathGoal != null && pathGoal.distManhattan(wander) <= 2) {
                // Arrived, or nowhere to go: stand a while, then somewhere else.
                wander = null;
                nextWanderAt = age + 30 + player.getRandom().nextInt(90);
            }
        }

        // The eyes: on the enemy in a fight, otherwise on the way.
        double flat = aim == null ? 0.0 : flatDistance(player, aim);
        boolean walk = aim != null && flat > stopAt;
        Vec3 lookAt = enemy != null && !escaping ? enemy.position() : walk ? aim : target != null ? target.position() : null;
        float wantedYaw;
        if (lookAt != null) {
            wantedYaw = yawTo(player, lookAt);
        } else {
            if (age >= nextIdleLookAt) {
                nextIdleLookAt = age + 40 + player.getRandom().nextInt(80);
                idleYaw = player.getYRot() + (player.getRandom().nextFloat() - 0.5f) * 160f;
            }
            wantedYaw = idleYaw;
        }
        float walkYaw = walk ? yawTo(player, aim) : wantedYaw;
        float turn = enemy != null || escaping ? FIGHT_TURN : walk ? WALK_TURN : LOOK_TURN;
        // Slow, a little unsteady: a person looking around, not a machine.
        player.setYRot(Mth.approachDegrees(player.getYRot(), walk ? walkYaw : wantedYaw, turn) + (player.getRandom().nextFloat() - 0.5f) * 0.6f);
        float wantedPitch = target == null ? 5f : (float) -(Mth.atan2(target.getY(0.6) - player.getEyeY(),
                Math.max(flatDistance(player, target.position()), 0.1)) * Mth.RAD_TO_DEG);
        player.setXRot(Mth.approachDegrees(player.getXRot(), Mth.clamp(wantedPitch, -80f, 80f), turn * 0.6f));

        float off = Math.abs(Mth.wrapDegrees(walkYaw - player.getYRot()));
        boolean facing = off < 55f || player.isInWater();
        boolean grounded = player.verticalCollisionBelow;
        options.keyUp.setDown(walk && facing);
        options.keySprint.setDown(walk && facing && off < 20f && target != null && !higher
                && flatDistance(player, target.position()) > 7.0 && !player.isUsingItem());
        options.keyJump.setDown(player.isInWater() || (walk && facing && grounded
                && ((higher && flat < 1.6) || (blind && player.horizontalCollision))));
        watchForStuck(player, walk && facing);

        fight(mc, player, options, enemy, target);
    }

    // Fighting

    private static void fight(Minecraft mc, LocalPlayer player, Options options, LivingEntity enemy, LivingEntity target) {
        if (target == null || mc.gameMode == null) {
            return;
        }
        float strength = player.getAttackStrengthScale(0f);
        boolean inReach = reach(player, target) <= ATTACK_REACH && player.hasLineOfSight(target);
        if (enemy != null) {
            // Behind the shield while the arm recovers.
            ItemStack shield = player.getOffhandItem();
            ItemStack held = player.getMainHandItem();
            boolean canGuard = SoulGear.isShield(shield) && !player.getCooldowns().isOnCooldown(shield.getItem())
                    && (held.isEmpty() || held.getItem() instanceof SwordItem || held.getItem() instanceof AxeItem);
            float off = Math.abs(Mth.wrapDegrees(yawTo(player, enemy.position()) - player.getYRot()));
            options.keyUse.setDown(canGuard && strength < 0.85f && off < 60f && flatDistance(player, enemy.position()) < 5.0);
        }
        if (!inReach || strength < 0.95f) {
            return;
        }
        // An enemy gets every blow. Another player gets a friendly distance for a while, then is turned on.
        if (enemy != null || player.getRandom().nextInt(4) == 0) {
            options.keyUse.setDown(false);
            mc.gameMode.attack(player, target);
            player.swing(InteractionHand.MAIN_HAND);
            hitsLanded++;
        }
    }

    /** Whatever is attacking the body, or is about to. Kept in mind for a while after it was last seen at it. */
    private static LivingEntity findThreat(Minecraft mc, LocalPlayer player) {
        DamageSource source = player.getLastDamageSource();
        boolean justHurt = player.hurtTime > lastHurtTime;
        lastHurtTime = player.hurtTime;
        if (source != null && source.getEntity() instanceof LivingEntity attacker && isFightable(player, attacker)) {
            remember(attacker);
        } else if (justHurt && !ClientCompat.KNOWS_ATTACKER) {
            // Older game versions do not say who struck: the likeliest one close by.
            LivingEntity suspect = scan(mc, player, 6.0, true);
            if (suspect != null) {
                remember(suspect);
            }
        }
        if (threat != null && (!isFightable(player, threat) || age > threatUntil
                || threat.distanceToSqr(player) > 32.0 * 32.0)) {
            threat = null;
        }
        if (age >= nextScanAt) {
            nextScanAt = age + 5;
            LivingEntity seen = scan(mc, player, THREAT_RANGE, false);
            if (seen != null && (threat == null || seen == threat
                    || seen.distanceToSqr(player) + 9.0 < threat.distanceToSqr(player))) {
                remember(seen);
            }
        }
        return threat;
    }

    private static void remember(LivingEntity entity) {
        threat = entity;
        threatUntil = age + THREAT_MEMORY;
    }

    private static boolean isFightable(LocalPlayer player, LivingEntity entity) {
        if (entity == player || !entity.isAlive() || entity.isRemoved() || entity.isSpectator()) {
            return false;
        }
        // The soul inside this very body is not an enemy, and cannot be seen.
        return !(entity instanceof SoulEntity soul) || !soul.isPossessing();
    }

    /** The nearest thing that means harm. With "struck", it has just hit the body: anything close and able counts. */
    private static LivingEntity scan(Minecraft mc, LocalPlayer player, double range, boolean struck) {
        LivingEntity best = null;
        double bestDistance = range * range;
        AABB box = player.getBoundingBox().inflate(range, 6.0, range);
        for (LivingEntity entity : mc.level.getEntitiesOfClass(LivingEntity.class, box)) {
            double distance = entity.distanceToSqr(player);
            if (distance < bestDistance && isFightable(player, entity) && meansHarm(player, entity, distance, struck)) {
                best = entity;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean meansHarm(LocalPlayer player, LivingEntity entity, double distanceSqr, boolean struck) {
        if (entity instanceof SoulEntity soul) {
            return !soul.isInvisible() && (soul.isHunting() || struck);
        }
        if (entity instanceof Player) {
            return struck && distanceSqr < 4.5 * 4.5;
        }
        if (!(entity instanceof Mob mob)) {
            return false;
        }
        if (mob.isAggressive()) {
            return mob instanceof Enemy || distanceSqr < 4.5 * 4.5;
        }
        // Monsters that never show they are attacking (slimes, phantoms, blazes): being close is enough.
        return mob instanceof Enemy && !(mob instanceof NeutralMob) && !(mob instanceof AbstractPiglin)
                && distanceSqr < 7.0 * 7.0 && player.hasLineOfSight(mob);
    }

    private static void drawBestWeapon(LocalPlayer player) {
        int best = player.getInventory().selected;
        float bestScore = SoulGear.meleeScore(player.getInventory().getItem(best));
        for (int slot = 0; slot < 9; slot++) {
            float score = SoulGear.meleeScore(player.getInventory().getItem(slot));
            if (score > bestScore) {
                best = slot;
                bestScore = score;
            }
        }
        // The game tells the server by itself when the slot changes.
        player.getInventory().selected = best;
    }

    private static Player nearestOtherPlayer(Minecraft mc, LocalPlayer self) {
        Player best = null;
        double bestDistance = 64.0 * 64.0;
        for (Player other : mc.level.players()) {
            double distance = other.distanceToSqr(self);
            if (other != self && other.isAlive() && !other.isSpectator() && distance < bestDistance) {
                best = other;
                bestDistance = distance;
            }
        }
        return best;
    }

    // Walking

    /** The next cell to walk into on the way to the goal, or null when there is no way or the body has arrived. */
    private static BlockPos nextNode(Minecraft mc, LocalPlayer player, BlockPos goal, boolean moving) {
        boolean goalMoved = pathGoal == null || pathGoal.distManhattan(goal) > 2;
        boolean stale = moving && age - pathMadeAt > 60;
        boolean lost = pathIndex < path.size() && isOffPath(player, path.get(pathIndex));
        if ((path.isEmpty() || pathIndex >= path.size() || goalMoved || stale || lost) && age >= nextPathAt) {
            nextPathAt = age + 10;
            pathMadeAt = age;
            pathGoal = goal;
            pathIndex = 0;
            // Standing on the very edge of a block, its feet are over the gap: the block it stands on is beside.
            BlockPos start = PossessionPath.ground(mc.level, cell(player), player.verticalCollisionBelow ? player.position() : null);
            BlockPos end = PossessionPath.ground(mc.level, goal, null);
            path = start == null ? List.of() : PossessionPath.find(mc.level, start, end == null ? goal : end);
        }
        while (pathIndex < path.size() && isReached(player, pathIndex)) {
            pathIndex++;
        }
        return pathIndex < path.size() ? path.get(pathIndex) : null;
    }

    private static boolean isReached(LocalPlayer player, int index) {
        BlockPos node = path.get(index);
        double up = player.getY() - node.getY();
        if (!player.isInWater() && (up < -0.6 || up > 1.6)) {
            return false;
        }
        double distance = flatDistance(player, new Vec3(node.getX() + 0.5, 0.0, node.getZ() + 0.5));
        if (distance < 0.45) {
            return true;
        }
        // Already past it and nearer the one after, on flat ground: no going back to touch it.
        if (index + 1 < path.size()) {
            BlockPos next = path.get(index + 1);
            return next.getY() == node.getY() && Math.abs(up) < 0.6
                    && flatDistance(player, new Vec3(next.getX() + 0.5, 0.0, next.getZ() + 0.5)) < 0.9;
        }
        return false;
    }

    private static boolean isOffPath(LocalPlayer player, BlockPos node) {
        return flatDistance(player, new Vec3(node.getX() + 0.5, 0.0, node.getZ() + 0.5)) > 3.5
                || Math.abs(player.getY() - node.getY()) > 4.5;
    }

    /** A shut wooden door in the way is opened by hand, and left open. */
    private static void openDoors(Minecraft mc, LocalPlayer player, BlockPos node) {
        if (age < nextDoorAt || mc.gameMode == null) {
            return;
        }
        for (BlockPos pos : new BlockPos[] {node, node.above(), cell(player)}) {
            if (PossessionPath.isShutDoor(mc.level, pos) && player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) < 3.5 * 3.5) {
                nextDoorAt = age + 12;
                mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
                player.swing(InteractionHand.MAIN_HAND);
                return;
            }
        }
    }

    /** With nobody around, it goes from place to place: somewhere it can stand, a short walk away. */
    private static BlockPos wanderGoal(Minecraft mc, LocalPlayer player) {
        if (wander != null && age >= wanderUntil) {
            wander = null;
        }
        if (wander == null && age >= nextWanderAt) {
            for (int tries = 0; tries < 8 && wander == null; tries++) {
                double angle = player.getRandom().nextDouble() * Math.PI * 2.0;
                double distance = 6.0 + player.getRandom().nextDouble() * 12.0;
                wander = PossessionPath.ground(mc.level, new BlockPos(Mth.floor(player.getX() + Math.cos(angle) * distance),
                        Mth.floor(player.getY() + 0.5), Mth.floor(player.getZ() + Math.sin(angle) * distance)), null);
            }
            wanderUntil = age + 240 + player.getRandom().nextInt(200);
            nextWanderAt = age + 20;
        }
        return wander;
    }

    /**
     * Walking and going nowhere: first another way is looked for, then the body backs out in some other direction.
     * Without this it would push against a wall for as long as the soul holds it.
     */
    private static void watchForStuck(LocalPlayer player, boolean walking) {
        if (walking) {
            walkedTicks++;
        }
        if (age < nextStuckCheckAt) {
            return;
        }
        double moved = Math.abs(player.getX() - checkX) + Math.abs(player.getZ() - checkZ);
        boolean stuck = walkedTicks >= 14 && moved < 0.5;
        checkX = player.getX();
        checkZ = player.getZ();
        nextStuckCheckAt = age + 20;
        walkedTicks = 0;
        if (!stuck) {
            stuckChecks = 0;
            return;
        }
        path = List.of();
        nextPathAt = age;
        if (++stuckChecks >= 2) {
            stuckChecks = 0;
            wander = null;
            escapeYaw = player.getYRot() + 100f + player.getRandom().nextFloat() * 160f;
            escapeUntil = age + 12 + player.getRandom().nextInt(12);
        }
    }

    private static BlockPos cell(LivingEntity entity) {
        return new BlockPos(Mth.floor(entity.getX()), Mth.floor(entity.getY() + 0.5), Mth.floor(entity.getZ()));
    }

    private static double flatDistance(LivingEntity from, Vec3 to) {
        double dx = to.x - from.getX();
        double dz = to.z - from.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static float yawTo(LivingEntity from, Vec3 to) {
        return (float) (Mth.atan2(to.z - from.getZ(), to.x - from.getX()) * Mth.RAD_TO_DEG) - 90f;
    }

    /** From the eyes to the nearest point of the other's body, the way the game measures a blow. */
    private static double reach(LocalPlayer player, LivingEntity target) {
        Vec3 eye = player.getEyePosition();
        AABB box = target.getBoundingBox();
        double dx = Math.max(Math.max(box.minX - eye.x, eye.x - box.maxX), 0.0);
        double dy = Math.max(Math.max(box.minY - eye.y, eye.y - box.maxY), 0.0);
        double dz = Math.max(Math.max(box.minZ - eye.z, eye.z - box.maxZ), 0.0);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The real keyboard and mouse: everything already pressed is thrown away. */
    private static void swallowKeys(Options options) {
        KeyMapping[] all = {options.keyAttack, options.keyUse, options.keyPickItem, options.keyDrop, options.keyChat,
                options.keyCommand, options.keyInventory, options.keySwapOffhand, options.keyTogglePerspective,
                options.keyUp, options.keyDown, options.keyLeft, options.keyRight, options.keyJump, options.keyShift, options.keySprint};
        for (KeyMapping key : all) {
            swallow(key);
        }
        for (KeyMapping key : options.keyHotbarSlots) {
            swallow(key);
        }
    }

    private static void swallow(KeyMapping key) {
        while (key.consumeClick()) {
            // Empties the queue of presses.
        }
        key.setDown(false);
    }

    /** Nothing stays stuck down when the player gets the controls back. */
    private static void releaseKeys(Options options) {
        KeyMapping[] held = {options.keyUp, options.keyDown, options.keyLeft, options.keyRight, options.keyJump, options.keyShift,
                options.keySprint, options.keyUse};
        for (KeyMapping key : held) {
            key.setDown(false);
        }
    }

    /** Called from the camera event: the player's own view follows the body, whatever the mouse does. */
    public static float cameraYaw(float partial) {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? 0f : Mth.rotLerp(partial, previousYaw, player.getYRot());
    }

    public static float cameraPitch(float partial) {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? 0f : Mth.lerp(partial, previousPitch, player.getXRot());
    }
}
