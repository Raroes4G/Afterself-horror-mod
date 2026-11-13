package com.afterself.entity;

import com.afterself.Compat;
import com.afterself.Possession;
import com.afterself.Afterself;
import com.afterself.AfterselfConfig;
import com.afterself.SoulStore;
import com.afterself.VoiceMemory;
import com.afterself.entity.goal.SoulBreakBlockGoal;
import com.afterself.entity.goal.SoulBuildGoal;
import com.afterself.entity.goal.SoulCombatGoal;
import com.afterself.entity.goal.SoulDarkenGoal;
import com.afterself.entity.goal.SoulLoomGoal;
import com.afterself.entity.goal.SoulLootGoal;
import com.afterself.entity.goal.SoulLureGoal;
import com.afterself.entity.goal.SoulRetreatGoal;
import com.afterself.entity.goal.SoulSearchGoal;
import com.afterself.entity.goal.SoulStalkGoal;
import com.afterself.entity.goal.SoulStealGoal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What is left of a dead player, trying to pass for one. It watches from afar, follows, steals in silence, talks with
 * the owner's voice, and only fights when provoked, when it hears too much, or when it has waited long enough.
 */
public class SoulEntity extends SoulBase implements RangedAttackMob {
    public static final byte EVENT_SCREAMER = 60;
    /**
     * A face on the screen for a fraction of a second, without a sound. 61 and 62 belong to Possession. Not 63:
     * from 1.20 the client takes that one for the sniffer before any entity sees it, like 21 and 35.
     */
    public static final byte EVENT_FLASH = 70;

    /** What the soul is doing right now. Saved as a byte and synced for animations. */
    public enum Mode {
        STALK, LOOT, STEAL, STRIKE, LURE, TRAP, HUNT, POSSESSING, SEARCH, BEHIND, DARKEN, RETREAT;

        public static Mode of(int index) {
            Mode[] all = values();
            return index >= 0 && index < all.length ? all[index] : STALK;
        }
    }

    /**
     * The poses a soul holds, each with its Blockbench animation. The lures are the rare scenes played for players who
     * spot it from afar: during one, the soul looks exactly like the living player. The others are short poses.
     */
    public enum LureScene {
        NONE(null, false),
        WAVE("lure_wave", true), KNEEL("lure_kneel", true), LIE("lure_lie", true), STARE("lure_stare", true),
        BECKON("lure_beckon", true), CRY("lure_cry", true), BACK("lure_back", true),
        /** Walks around like a busy player: no pose, the normal walk and idle animations. */
        MIMIC(null, true),
        /** Caught watching from afar: it stands up straight and stares back. */
        WATCH("watch", false),
        /** Right behind the player. */
        LOOM("loom", false),
        EAT("eat", false);

        public final String animation;
        public final boolean lure;

        LureScene(String animation, boolean lure) {
            this.animation = animation;
            this.lure = lure;
        }

        public static LureScene of(int index) {
            LureScene[] all = values();
            return index >= 0 && index < all.length ? all[index] : NONE;
        }

        /** By name, for the debug command. Null when there is no such scene. */
        public static LureScene named(String name) {
            for (LureScene scene : values()) {
                if (scene != NONE && scene.name().equalsIgnoreCase(name)) {
                    return scene;
                }
            }
            return null;
        }

        public static LureScene[] lures() {
            return new LureScene[] {WAVE, KNEEL, LIE, STARE, BECKON, CRY, BACK, MIMIC};
        }
    }

    private static final int SEE_RANGE = 48;
    private static final int SURPRISE_RANGE = 12;
    private static final int CONTACT_RANGE = 3;
    private static final int LURE_MIN_RANGE = 15;
    private static final int LURE_CHANCE_DIVISOR = 40;
    private static final int LURE_COOLDOWN_TICKS = 6000;
    private static final int STRIKE_TICKS = 60;
    private static final int TRAP_TICKS = 200;
    private static final int HUNT_TICKS = 400;
    private static final int SEEN_BEFORE_VANISH_TICKS = 60;
    /** The silent face is not the screamer, but it must not become a habit either. */
    private static final int FLASH_COOLDOWN_TICKS = 6000;
    private static final int FOLLOW_MIN_DISTANCE = 48;
    private static final int FOLLOW_CHECK_TICKS = 100;
    private static final int FOLLOW_COOLDOWN_TICKS = 400;
    private static final int VANISH_MIN = 22;
    private static final int VANISH_MAX = 36;
    /** Noise adds up: enough of it, and the soul stops hiding. */
    private static final int ANGER_LIMIT = 150;
    private static final int SEARCH_TICKS = 400;
    private static final int SPOT_RANGE = 24;
    private static final int RETREAT_TICKS = 200;
    private static final int RETREAT_COOLDOWN_TICKS = 2400;
    private static final float RETREAT_HEALTH = 0.35f;
    /** Per player, so several souls do not stack screamers. */
    private static final Map<UUID, Long> LAST_SCREAMER = new HashMap<>();
    /** How many screamers each player has had since the server started: every one makes the next rarer. */
    private static final Map<UUID, Integer> SCREAMERS_SHOWN = new HashMap<>();
    private static final Map<UUID, Long> LAST_FLASH = new HashMap<>();

    private final List<ItemStack> carried = new ArrayList<>();
    private BlockPos heardPos;
    private int heardAt = -10000;
    private int anger;
    private int modeUntil;
    private int seenTicks;
    private int nextLureAt = 600;
    private int nextSpeechAt = 600;
    private int nextFollow = FOLLOW_CHECK_TICKS;
    private int followAllowedAt;
    /** Night time a soul will stalk before it comes for you anyway. */
    private int patienceUntil = 4800 + (int) (Math.random() * 7200);
    private UUID stalkedPlayer;
    private Player body;
    private boolean holdingTotem;
    private final SoulHaunt haunt = new SoulHaunt(this);
    /** Ticks without getting any closer to where it is trying to go: what makes it dig or build. */
    private int stuckTicks;
    private int progressAt;
    private double progressDistance = Double.MAX_VALUE;
    private Vec3 lastSeenPos;
    private Vec3 searchPos;
    private boolean searchArrived;
    private int retreatAllowedAt;
    private int nextRegenAt;
    private int nextDreadAt;
    /** This strike started from right behind the player, or from their bedside. */
    private boolean ambush;

    public SoulEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setCanPickUpLoot(false);
        if (this.getNavigation() instanceof GroundPathNavigation navigation) {
            navigation.setCanOpenDoors(true);
            navigation.setCanPassDoors(true);
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            this.setDropChance(slot, 0f);
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 30.0)
                .add(Attributes.MOVEMENT_SPEED, 0.27)
                .add(Attributes.ATTACK_DAMAGE, 5.0)
                .add(Attributes.FOLLOW_RANGE, 64.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.5);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        this.goalSelector.addGoal(2, new SoulLoomGoal(this));
        this.goalSelector.addGoal(2, new SoulRetreatGoal(this));
        this.goalSelector.addGoal(3, new SoulBuildGoal(this));
        this.goalSelector.addGoal(4, new SoulBreakBlockGoal(this));
        this.goalSelector.addGoal(5, new SoulCombatGoal(this));
        this.goalSelector.addGoal(5, new SoulSearchGoal(this));
        this.goalSelector.addGoal(6, new SoulLureGoal(this));
        this.goalSelector.addGoal(7, new SoulStealGoal(this));
        this.goalSelector.addGoal(8, new SoulLootGoal(this));
        this.goalSelector.addGoal(9, new SoulDarkenGoal(this));
        this.goalSelector.addGoal(10, new SoulStalkGoal(this));
    }

    /** Turns a dead player into a soul that carries their items. */
    public static SoulEntity fromPlayer(ServerPlayer player, List<ItemStack> items) {
        Level level = Compat.level(player);
        SoulEntity soul = Afterself.soulType.get().create(level);
        if (soul == null) {
            return null;
        }
        soul.moveTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), 0f);
        soul.setOwner(player.getUUID(), player.getGameProfile().getName());
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) {
                soul.carried.add(stack.copy());
            }
        }
        soul.refreshEquipment();
        return soul;
    }

    public void setOwner(UUID id, String name) {
        setOwnerData(id, name);
        this.setCustomName(Component.translatable("afterself.soul.named", name));
        this.setCustomNameVisible(false);
    }

    // Gear

    private static ItemStack one(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.setCount(1);
        return copy;
    }

    /** Wears the best of what it has. The stacks stay in carried, so nothing can be duplicated. */
    public void refreshEquipment() {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            this.setItemSlot(slot, ItemStack.EMPTY);
        }
        float[] armorBest = new float[EquipmentSlot.values().length];
        for (ItemStack stack : carried) {
            if (stack.getItem() instanceof ArmorItem armor) {
                EquipmentSlot slot = Compat.armorSlot(armor);
                float score = SoulGear.armorScore(stack);
                if (score > armorBest[slot.ordinal()]) {
                    armorBest[slot.ordinal()] = score;
                    this.setItemSlot(slot, one(stack));
                }
            }
        }
        holdMelee();
        holdingTotem = false;
        for (ItemStack stack : carried) {
            if (SoulGear.isTotem(stack)) {
                // Vanilla saves any living thing holding a totem, so this is all it takes.
                this.setItemSlot(EquipmentSlot.OFFHAND, one(stack));
                holdingTotem = true;
                break;
            }
        }
        if (!holdingTotem) {
            for (ItemStack stack : carried) {
                if (SoulGear.isShield(stack)) {
                    this.setItemSlot(EquipmentSlot.OFFHAND, one(stack));
                    break;
                }
            }
        }
    }

    /** Takes one item of a kind out of what it carries. False when it has none. */
    public boolean consumeOne(Predicate<ItemStack> kind) {
        for (int i = 0; i < carried.size(); i++) {
            if (kind.test(carried.get(i))) {
                carried.get(i).shrink(1);
                if (carried.get(i).isEmpty()) {
                    carried.remove(i);
                }
                return true;
            }
        }
        return false;
    }

    public boolean hasBuildingBlock() {
        return !peekBuildingBlock().isEmpty();
    }

    public int buildingBlockCount() {
        return SoulGear.count(carried, SoulGear::isBuildingBlock);
    }

    public ItemStack peekBuildingBlock() {
        for (ItemStack stack : carried) {
            if (SoulGear.isBuildingBlock(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /** A block it dug out. Stacks with what it has, and does not touch what it wears. */
    public void addBlock(ItemStack block) {
        for (ItemStack stack : carried) {
            if (stack.is(block.getItem()) && stack.getCount() < stack.getMaxStackSize()) {
                stack.grow(1);
                return;
            }
        }
        carried.add(block);
    }

    public void holdBlock() {
        ItemStack block = peekBuildingBlock();
        if (!block.isEmpty()) {
            this.setItemSlot(EquipmentSlot.MAINHAND, one(block));
        }
    }

    public void holdMelee() {
        ItemStack best = ItemStack.EMPTY;
        float bestScore = 0f;
        for (ItemStack stack : carried) {
            float score = SoulGear.meleeScore(stack);
            if (score > bestScore) {
                best = stack;
                bestScore = score;
            }
        }
        this.setItemSlot(EquipmentSlot.MAINHAND, best.isEmpty() ? ItemStack.EMPTY : one(best));
    }

    public boolean holdBow() {
        ItemStack bow = ItemStack.EMPTY;
        boolean hasArrows = false;
        for (ItemStack stack : carried) {
            if (SoulGear.isBow(stack) && bow.isEmpty()) {
                bow = stack;
            }
            hasArrows |= SoulGear.isArrow(stack);
        }
        if (bow.isEmpty() || !hasArrows) {
            return false;
        }
        this.setItemSlot(EquipmentSlot.MAINHAND, one(bow));
        return true;
    }

    public void addLoot(ItemStack stack) {
        if (!stack.isEmpty()) {
            carried.add(stack);
            refreshEquipment();
        }
    }

    public List<ItemStack> getCarried() {
        return carried;
    }

    /** A totem in the off hand was just used up: the same one leaves carried, and the next takes its place. */
    private void checkTotemUsed() {
        if (holdingTotem && !SoulGear.isTotem(this.getOffhandItem())) {
            consumeOne(SoulGear::isTotem);
            refreshEquipment();
            // Saved from death: it runs.
            setMode(Mode.STALK, 0);
            this.setTarget(null);
            vanish();
        }
    }

    // Modes

    public Mode getMode() {
        return Mode.of(getModeData());
    }

    public void setMode(Mode mode, int ticks) {
        setModeData(mode.ordinal());
        modeUntil = this.tickCount + ticks;
    }

    public boolean modeExpired() {
        return this.tickCount >= modeUntil;
    }

    public LureScene getLureScene() {
        return LureScene.of(getLureData());
    }

    public void setLureScene(LureScene scene) {
        boolean wasDisguised = isDisguised();
        setLureData(scene.ordinal());
        if (wasDisguised != scene.lure && getOwnerId() != null) {
            // In disguise it carries the living player's name, plain, the way their name tag reads.
            this.setCustomName(scene.lure ? Component.literal(getOwnerName())
                    : Component.translatable("afterself.soul.named", getOwnerName()));
        }
    }

    /** During a lure it looks like the living player: solid, with their name tag. */
    public boolean isDisguised() {
        return getLureScene().lure;
    }

    public boolean isPossessing() {
        return getMode() == Mode.POSSESSING;
    }

    public boolean isHunting() {
        return getMode() == Mode.HUNT || getMode() == Mode.TRAP || getMode() == Mode.STRIKE;
    }

    /** A kill while this is true takes the body instead. */
    public boolean isTrapActive() {
        return getMode() == Mode.TRAP;
    }

    public void startHunting(LivingEntity target) {
        this.setTarget(target);
        this.setPose(Pose.STANDING);
        setLureScene(LureScene.NONE);
        setMode(Mode.HUNT, HUNT_TICKS);
        lastSeenPos = target.position();
    }

    /** It has decided by itself that the waiting is over: the player feels it before they see it. */
    private void announceHunt(Player player) {
        Level level = Compat.level(this);
        level.playSound(null, this.getX(), this.getY(), this.getZ(), Compat.caveSound(), SoundSource.HOSTILE, 1.4f, 0.6f);
        if (AfterselfConfig.dreadCues && this.distanceToSqr(player) < 32.0 * 32.0) {
            player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100, 0, false, false));
        }
        speak(0.7f);
    }

    // Searching

    public void rememberSeen(Vec3 pos) {
        lastSeenPos = pos;
    }

    /** It lost its prey: it goes to where it last saw them and looks around. */
    public void startSearching() {
        LivingEntity target = this.getTarget();
        if (target instanceof Player player) {
            stalkedPlayer = player.getUUID();
        }
        searchPos = lastSeenPos != null ? lastSeenPos : target != null ? target.position() : this.position();
        searchArrived = false;
        this.setTarget(null);
        this.setPose(Pose.STANDING);
        setLureScene(LureScene.NONE);
        setMode(Mode.SEARCH, SEARCH_TICKS);
    }

    public Vec3 getSearchPos() {
        return searchPos;
    }

    public void setSearchArrived(boolean arrived) {
        searchArrived = arrived;
    }

    /** Where it is trying to get to right now, or null. What it digs toward. */
    public Vec3 pursuitPos() {
        Mode mode = getMode();
        if (mode == Mode.HUNT || mode == Mode.TRAP || mode == Mode.STRIKE) {
            LivingEntity target = this.getTarget();
            return target == null ? null : target.position();
        }
        return mode == Mode.SEARCH && !searchArrived ? searchPos : null;
    }

    public int getStuckTicks() {
        return stuckTicks;
    }

    public void resetStuck() {
        stuckTicks = 0;
        progressAt = this.tickCount;
        progressDistance = Double.MAX_VALUE;
    }

    /** Is there a floor to land on this far ahead? Keeps it from running or jumping off a cliff. */
    public boolean groundAhead(double distance) {
        // The way it faces, the way it is actually moving, and the way to its target: on a slow server these can
        // differ, and a jump follows the movement, not the face.
        boolean ground = groundToward(Vec3.directionFromRotation(0f, this.getYRot()), distance);
        Vec3 motion = this.getDeltaMovement();
        if (motion.horizontalDistanceSqr() > 0.0025) {
            ground &= groundToward(new Vec3(motion.x, 0.0, motion.z).normalize(), distance);
        }
        LivingEntity target = this.getTarget();
        if (target != null) {
            Vec3 toTarget = new Vec3(target.getX() - this.getX(), 0.0, target.getZ() - this.getZ());
            if (toTarget.lengthSqr() > distance * distance) {
                ground &= groundToward(toTarget.normalize(), distance);
            }
        }
        return ground;
    }

    private boolean groundToward(Vec3 heading, double distance) {
        Level level = Compat.level(this);
        BlockPos ahead = new BlockPos((int) Math.floor(this.getX() + heading.x * distance), (int) Math.floor(this.getY() + 0.2),
                (int) Math.floor(this.getZ() + heading.z * distance));
        for (int down = 1; down <= 3; down++) {
            BlockPos pos = ahead.below(down);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Can the soul itself see this player: in front of it, in range, nothing in between. Sneaking halves the range. */
    public boolean canSpot(Player player) {
        double range = player.isCrouching() ? SPOT_RANGE / 2.0 : SPOT_RANGE;
        Vec3 toPlayer = player.getEyePosition().subtract(this.getEyePosition());
        double distance = toPlayer.length();
        if (distance > range || !this.hasLineOfSight(player)) {
            return false;
        }
        return distance <= 4.0 || toPlayer.normalize().dot(this.getViewVector(1f)) > 0.3;
    }

    // Retreat

    /** Hurt badly, with no totem: it runs to eat and comes back later. */
    private void startRetreat(Player threat) {
        stalkedPlayer = threat.getUUID();
        this.setTarget(null);
        retreatAllowedAt = this.tickCount + RETREAT_COOLDOWN_TICKS;
        setMode(Mode.RETREAT, RETREAT_TICKS);
    }

    /** True when it has something to eat: the eating pose starts. */
    public boolean startEating() {
        for (ItemStack stack : carried) {
            if (SoulGear.isFood(stack)) {
                this.setItemSlot(EquipmentSlot.MAINHAND, one(stack));
                setLureScene(LureScene.EAT);
                return true;
            }
        }
        return false;
    }

    public void finishEating() {
        for (ItemStack stack : carried) {
            if (SoulGear.isFood(stack)) {
                this.heal(Math.max(6f, Compat.foodValue(stack) * 2f));
                break;
            }
        }
        consumeOne(SoulGear::isFood);
        Compat.level(this).playSound(null, this.blockPosition(), SoundEvents.PLAYER_BURP, SoundSource.HOSTILE, 0.3f, 0.8f);
        holdMelee();
        returnToStalking();
    }

    // Standing behind

    /** Appears right behind the player, silent and still. False when there is no room to stand there. */
    public boolean startLoom(ServerPlayer player) {
        if (!(Compat.level(player) instanceof ServerLevel level) || !isValidVictim(player)) {
            return false;
        }
        Vec3 view = player.getViewVector(1f);
        Vec3 back = new Vec3(-view.x, 0.0, -view.z);
        back = back.lengthSqr() < 0.0001 ? new Vec3(0.0, 0.0, -1.0) : back.normalize();
        for (double distance : new double[] {2.2, 2.8, 1.6}) {
            Vec3 at = player.position().add(back.scale(distance));
            for (int dy = 0; dy >= -1; dy--) {
                BlockPos pos = new BlockPos((int) Math.floor(at.x), (int) Math.floor(player.getY() + 0.2) + dy, (int) Math.floor(at.z));
                if (canStandAt(level, pos)) {
                    standBeside(player, pos, 120 + this.random.nextInt(80));
                    return true;
                }
            }
        }
        return false;
    }

    /** A sleeping player wakes with it standing over the bed. False when there is no room around the bed. */
    public boolean hauntBed(ServerPlayer player) {
        if (!(Compat.level(player) instanceof ServerLevel level)) {
            return false;
        }
        BlockPos bed = player.blockPosition();
        for (int ring = 1; ring <= 2; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    for (int dy = 0; dy >= -1; dy--) {
                        BlockPos pos = bed.offset(dx, dy, dz);
                        if (canStandAt(level, pos)) {
                            standBeside(player, pos, 160);
                            player.stopSleepInBed(true, true);
                            player.displayClientMessage(Component.translatable("afterself.sleep.haunted"), true);
                            if (AfterselfConfig.dreadCues) {
                                player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 80, 0, false, false));
                            }
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private void standBeside(Player player, BlockPos pos, int ticks) {
        double x = pos.getX() + 0.5;
        double z = pos.getZ() + 0.5;
        float yaw = (float) (Math.atan2(player.getZ() - z, player.getX() - x) * 180.0 / Math.PI) - 90f;
        this.setTarget(null);
        this.getNavigation().stop();
        this.moveTo(x, pos.getY(), z, yaw, 0f);
        this.setYHeadRot(yaw);
        this.setYBodyRot(yaw);
        this.setPose(Pose.STANDING);
        this.setSprinting(false);
        stalkedPlayer = player.getUUID();
        setLureScene(LureScene.LOOM);
        setMode(Mode.BEHIND, ticks);
    }

    /** Every few ticks while it stands behind someone. */
    private void loomTick() {
        Player player = getStalkedPlayer();
        if (player == null || this.distanceToSqr(player) > 7.0 * 7.0) {
            returnToStalking();
            vanish();
        } else if (isSeenBy(player)) {
            // They turned around.
            startStrike(player);
            ambush = true;
        } else if (modeExpired()) {
            speak();
            returnToStalking();
            vanish();
        }
    }

    /** Caught in the act: one blow, then gone. */
    public void startStrike(Player player) {
        this.setTarget(player);
        this.setPose(Pose.STANDING);
        setLureScene(LureScene.NONE);
        setMode(Mode.STRIKE, STRIKE_TICKS);
    }

    public void startLure(Player player, LureScene scene) {
        this.setTarget(null);
        stalkedPlayer = player.getUUID();
        this.getNavigation().stop();
        this.setPose(Pose.STANDING);
        setLureScene(scene);
        setMode(Mode.LURE, 900);
        nextLureAt = this.tickCount + LURE_COOLDOWN_TICKS;
    }

    /** The bait worked. A kill from here on means possession. */
    public void springTrap(Player player) {
        this.setTarget(player);
        setLureScene(LureScene.NONE);
        setMode(Mode.TRAP, TRAP_TICKS);
    }

    public void returnToStalking() {
        ambush = false;
        this.setTarget(null);
        if (this.isUsingItem()) {
            this.stopUsingItem();
        }
        setLureScene(LureScene.NONE);
        setMode(Mode.STALK, 0);
    }

    public Player getStalkedPlayer() {
        Level level = Compat.level(this);
        Player player = stalkedPlayer == null ? null : level.getPlayerByUUID(stalkedPlayer);
        if (player != null && isValidVictim(player)) {
            return player;
        }
        UUID owner = getOwnerId();
        player = owner == null ? null : level.getPlayerByUUID(owner);
        return player != null && isValidVictim(player) ? player : nearestVictim(SEE_RANGE);
    }

    private Player nearestVictim(double range) {
        Player best = null;
        double bestDistance = range * range;
        for (Player player : Compat.level(this).getEntitiesOfClass(Player.class, this.getBoundingBox().inflate(range))) {
            double distance = this.distanceToSqr(player);
            if (isValidVictim(player) && distance < bestDistance) {
                best = player;
                bestDistance = distance;
            }
        }
        return best;
    }

    public static boolean isValidVictim(Player player) {
        return player.isAlive() && !player.isCreative() && !player.isSpectator();
    }

    /** Is the player actually looking this way, with nothing in between? */
    public boolean isSeenBy(Player player) {
        Vec3 toSoul = this.getEyePosition().subtract(player.getEyePosition());
        double distance = toSoul.length();
        if (distance < 0.5) {
            return true;
        }
        if (toSoul.normalize().dot(player.getViewVector(1f)) < 0.82) {
            return false;
        }
        return canSee(Compat.level(this), player.getEyePosition(), this.getEyePosition(), player);
    }

    public boolean isHiddenFrom(Player player, Vec3 spot) {
        return !canSee(Compat.level(this), player.getEyePosition(), spot.add(0.0, 1.5, 0.0), player);
    }

    private static boolean canSee(Level level, Vec3 from, Vec3 to, Entity viewer) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer))
                .getType() == HitResult.Type.MISS;
    }

    // Hearing

    /** Tells every soul that can hear it that this player just made a noise. */
    public static void broadcastNoise(ServerPlayer player, int radius) {
        Level level = Compat.level(player);
        if (player.isCreative() || player.isSpectator() || Possession.isPossessed(player.getUUID())) {
            return;
        }
        AABB box = player.getBoundingBox().inflate(radius);
        for (SoulEntity soul : level.getEntitiesOfClass(SoulEntity.class, box)) {
            if (soul.distanceToSqr(player) <= radius * radius) {
                soul.hear(player, radius);
            }
        }
    }

    /** A shout gives you away at once. Anything else only draws it closer, until it has heard enough. */
    public void hear(Player player, int radius) {
        heardPos = player.blockPosition();
        heardAt = this.tickCount;
        stalkedPlayer = player.getUUID();
        anger += radius;
        Mode mode = getMode();
        if (mode == Mode.POSSESSING || mode == Mode.LURE || mode == Mode.TRAP || mode == Mode.STRIKE
                || mode == Mode.BEHIND || mode == Mode.RETREAT) {
            return;
        }
        // While it searches, any sound at all is enough.
        if (mode == Mode.SEARCH || radius >= 48 || anger >= ANGER_LIMIT) {
            anger = 0;
            startHunting(player);
        }
    }

    public BlockPos getHeardPos() {
        return heardPos;
    }

    public boolean heardRecently(int ticks) {
        return heardPos != null && this.tickCount - heardAt < ticks;
    }

    public void forgetHeardPos() {
        heardPos = null;
    }

    // Each tick

    @Override
    public void aiStep() {
        super.aiStep();
        Level level = Compat.level(this);
        if (level.isClientSide) {
            if (!isPossessing() && !isDisguised() && this.random.nextInt(8) == 0) {
                level.addParticle(ParticleTypes.SOUL,
                        this.getRandomX(0.5), this.getRandomY(), this.getRandomZ(0.5), 0.0, 0.02, 0.0);
            }
            return;
        }
        if (isPossessing()) {
            return;
        }
        checkTotemUsed();
        trackStuck();
        if (this.tickCount % 10 == 0) {
            think(level);
        }
        Mode mode = getMode();
        if (mode == Mode.BEHIND && this.tickCount % 4 == 0) {
            loomTick();
            mode = getMode();
        }
        if ((mode == Mode.HUNT || mode == Mode.TRAP || mode == Mode.STRIKE) && modeExpired()) {
            LivingEntity target = this.getTarget();
            if (mode == Mode.STRIKE) {
                // It missed its chance: gone anyway.
                returnToStalking();
                vanish();
            } else if (target != null && target.isAlive() && this.distanceToSqr(target) < 100.0) {
                setMode(mode, 100);
            } else if (mode == Mode.HUNT && target != null && target.isAlive()) {
                startSearching();
            } else {
                returnToStalking();
            }
        } else if (mode == Mode.SEARCH && modeExpired()) {
            // Nobody there. It goes back to watching.
            returnToStalking();
            vanish();
        }
        if (level instanceof ServerLevel server) {
            haunt.tick(server);
        }
        dread();
        if (mode == Mode.STALK && this.tickCount >= nextRegenAt) {
            // Like a player left alone: it mends.
            nextRegenAt = this.tickCount + 80;
            this.heal(1f);
        }
        LivingEntity target = this.getTarget();
        if (target != null && (!target.isAlive() || (target instanceof Player p && !isValidVictim(p)))) {
            returnToStalking();
        }
        if (this.tickCount >= nextSpeechAt) {
            speakOnSchedule(level);
        }
        if (this.tickCount >= nextFollow) {
            nextFollow = this.tickCount + FOLLOW_CHECK_TICKS;
            followOwner(level);
        }
    }

    /** Sight and touch. */
    private void think(Level level) {
        Mode mode = getMode();
        if (mode == Mode.HUNT || mode == Mode.TRAP || mode == Mode.STRIKE || mode == Mode.LURE
                || mode == Mode.BEHIND || mode == Mode.RETREAT) {
            return;
        }
        Player player = nearestVictim(SEE_RANGE);
        if (player == null) {
            seenTicks = 0;
            stopWatching();
            return;
        }
        double distance = Math.sqrt(this.distanceToSqr(player));
        if (mode == Mode.SEARCH) {
            if (canSpot(player) || (distance <= CONTACT_RANGE && this.hasLineOfSight(player))) {
                startHunting(player);
            }
            return;
        }
        boolean seen = isSeenBy(player);
        if ((distance <= CONTACT_RANGE && this.hasLineOfSight(player)) || (seen && distance <= SURPRISE_RANGE)) {
            startStrike(player);
            return;
        }
        if (seen) {
            seenTicks += 10;
            if (distance >= LURE_MIN_RANGE && this.tickCount >= nextLureAt && this.random.nextInt(LURE_CHANCE_DIVISOR) == 0) {
                startLure(player, randomScene());
                seenTicks = 0;
            } else if (seenTicks >= SEEN_BEFORE_VANISH_TICKS) {
                seenTicks = 0;
                stopWatching();
                if (AfterselfConfig.soulsHaunt && this.random.nextInt(3) == 0 && player instanceof ServerPlayer watcher) {
                    tryFlash(watcher);
                }
                vanish();
            } else if (mode == Mode.STALK && getLureScene() == LureScene.NONE) {
                // Caught watching: it stops hiding, stands up straight and stares back until it is gone.
                stalkedPlayer = player.getUUID();
                this.getNavigation().stop();
                this.setPose(Pose.STANDING);
                setLureScene(LureScene.WATCH);
            }
        } else {
            seenTicks = 0;
            stopWatching();
            // Sprinting is loud. Sneaking is the only silent way to move.
            if (player.isSprinting() && distance <= 14) {
                hear(player, 14);
            }
        }
        if (this.tickCount >= patienceUntil) {
            patienceUntil = this.tickCount + 2400 + this.random.nextInt(4800);
            stopWatching();
            startHunting(player);
            announceHunt(player);
        }
    }

    private void stopWatching() {
        if (getLureScene() == LureScene.WATCH) {
            setLureScene(LureScene.NONE);
        }
    }

    private LureScene randomScene() {
        LureScene[] scenes = LureScene.lures();
        return scenes[this.random.nextInt(scenes.length)];
    }

    /**
     * Counts the ticks it spends getting no closer to who it is after. Running in circles under a player on a
     * ledge counts as stuck, which standing still alone would miss.
     */
    private void trackStuck() {
        Vec3 goal = pursuitPos();
        double distance = goal == null ? 0.0 : Math.sqrt(this.distanceToSqr(goal));
        if (goal == null || distance < 3.0) {
            resetStuck();
            return;
        }
        if (distance < progressDistance - 0.75) {
            progressDistance = distance;
            progressAt = this.tickCount;
            stuckTicks = 0;
            return;
        }
        if (distance > progressDistance + 4.0) {
            // They got away: progress is measured from here now.
            progressDistance = distance;
        }
        stuckTicks = this.tickCount - progressAt;
    }

    /** A heartbeat only the player hears, when it is very close and they have not seen it. */
    private void dread() {
        Mode mode = getMode();
        if (!AfterselfConfig.dreadCues || this.tickCount < nextDreadAt || (mode != Mode.BEHIND && mode != Mode.STEAL)) {
            return;
        }
        nextDreadAt = this.tickCount + 24;
        if (getStalkedPlayer() instanceof ServerPlayer player && this.distanceToSqr(player) < 7.0 * 7.0) {
            player.playNotifySound(SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 0.7f, 0.9f);
        }
    }

    /** Repeats something the owner said, if they allowed it. */
    private void speakOnSchedule(Level level) {
        nextSpeechAt = this.tickCount + 500 + this.random.nextInt(900);
        Player listener = nearestVictim(32);
        if (listener == null) {
            return;
        }
        if (!speak()) {
            level.playSound(null, this.blockPosition(), Compat.soulEscapeSound(), SoundSource.HOSTILE, 1.2f, 0.5f);
        }
    }

    /** Plays one of the owner's voice clips right now. False when there is nothing to play. */
    public boolean speak() {
        return speak(AfterselfConfig.voicePitch);
    }

    /** Same, at a chosen pitch: 1 is the real voice, lower is slower and deeper. */
    public boolean speak(float pitch) {
        UUID owner = getOwnerId();
        Afterself.VoiceOutput output = Afterself.voiceOutput;
        List<byte[]> clip = owner == null || output == null ? null : VoiceMemory.pick(owner);
        if (clip == null) {
            return false;
        }
        output.play(this, clip, pitch);
        return true;
    }

    /** Tes morts te suivent: left too far behind, it reappears out of sight near its owner. */
    private void followOwner(Level level) {
        UUID owner = getOwnerId();
        if (owner == null || this.isHunting() || getMode() == Mode.LURE || !(level instanceof ServerLevel server)) {
            return;
        }
        Player player = level.getPlayerByUUID(owner);
        if (player == null || !player.isAlive() || this.distanceToSqr(player) < FOLLOW_MIN_DISTANCE * FOLLOW_MIN_DISTANCE) {
            return;
        }
        if (this.tickCount < followAllowedAt && this.distanceToSqr(player) < 96.0 * 96.0) {
            return;
        }
        BlockPos spot = findHiddenSpot(server, player, 20, 32);
        if (spot != null) {
            this.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
            this.getNavigation().stop();
            followAllowedAt = this.tickCount + FOLLOW_COOLDOWN_TICKS;
        }
    }

    /** Disappears and reappears out of sight. Used after a strike and when caught staring. */
    public void vanish() {
        Level level = Compat.level(this);
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        Player player = nearestVictim(64);
        server.sendParticles(ParticleTypes.SOUL, this.getX(), this.getY() + 1.0, this.getZ(), 16, 0.3, 0.6, 0.3, 0.03);
        if (player != null) {
            BlockPos spot = findHiddenSpot(server, player, VANISH_MIN, VANISH_MAX);
            if (spot != null) {
                this.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
                this.getNavigation().stop();
                followAllowedAt = this.tickCount + FOLLOW_COOLDOWN_TICKS;
            }
        }
    }

    /** A place to stand near this player that they cannot see, or null. */
    public static BlockPos findHiddenSpot(ServerLevel level, Player player, int minRange, int maxRange) {
        BlockPos fallback = null;
        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = level.getRandom().nextDouble() * Math.PI * 2.0;
            double range = minRange + level.getRandom().nextDouble() * (maxRange - minRange);
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * range);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * range);
            for (int dy = 8; dy >= -8; dy--) {
                BlockPos pos = new BlockPos(x, (int) player.getY() + dy, z);
                if (!level.hasChunkAt(pos)) {
                    continue;
                }
                if (canStandAt(level, pos)) {
                    Vec3 head = new Vec3(pos.getX() + 0.5, pos.getY() + 1.6, pos.getZ() + 0.5);
                    if (!canSee(level, player.getEyePosition(), head, player)) {
                        return pos;
                    }
                    if (fallback == null && player.getViewVector(1f).dot(head.subtract(player.getEyePosition()).normalize()) < 0.0) {
                        // In the open, but behind them.
                        fallback = pos;
                    }
                    break;
                }
            }
        }
        return fallback;
    }

    /** Solid ground, two blocks of air, no water. */
    public static boolean canStandAt(Level level, BlockPos pos) {
        return level.hasChunkAt(pos)
                && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
                && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
                && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                && level.getFluidState(pos).isEmpty();
    }

    // Combat

    /** Hit while it was only watching, it fights back. Hit as bait, the trap closes. Nearly dead, it runs. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (!hurt || Compat.level(this).isClientSide || !this.isAlive() || !(source.getEntity() instanceof Player attacker)
                || !isValidVictim(attacker)) {
            return hurt;
        }
        Mode mode = getMode();
        if (mode == Mode.LURE) {
            springTrap(attacker);
        } else if (mode == Mode.HUNT && !holdingTotem && this.tickCount >= retreatAllowedAt
                && this.getHealth() < this.getMaxHealth() * RETREAT_HEALTH) {
            startRetreat(attacker);
        } else if (mode != Mode.HUNT && mode != Mode.TRAP && mode != Mode.STRIKE && mode != Mode.POSSESSING
                && mode != Mode.RETREAT) {
            startHunting(attacker);
        }
        return hurt;
    }

    /** Closes the distance the way players do. The pearl carries it to where it lands. */
    public boolean throwPearl(LivingEntity target) {
        if (!consumeOne(SoulGear::isPearl)) {
            return false;
        }
        Level level = Compat.level(this);
        ThrownEnderpearl pearl = new ThrownEnderpearl(level, this);
        pearl.setItem(new ItemStack(Items.ENDER_PEARL));
        double dx = target.getX() - this.getX();
        double dz = target.getZ() - this.getZ();
        double dy = target.getY(0.5) - pearl.getY();
        double flat = Math.sqrt(dx * dx + dz * dz);
        pearl.shoot(dx, dy + flat * flat * 0.0075, dz, 1.5f, 2f);
        this.playSound(SoundEvents.ENDER_PEARL_THROW, 0.5f, 0.5f);
        this.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        level.addFreshEntity(pearl);
        return true;
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit && target instanceof ServerPlayer player) {
            // Not every blow: the moments that deserve it. A fight is not one of them.
            Mode mode = getMode();
            if (mode == Mode.TRAP || (mode == Mode.STRIKE
                    && (ambush || this.random.nextInt(100) < AfterselfConfig.screamerSurpriseChance))) {
                tryScreamer(player);
            }
            if (getMode() == Mode.STRIKE && this.isAlive()) {
                returnToStalking();
                vanish();
            }
        }
        return hit;
    }

    @Override
    public void performRangedAttack(LivingEntity target, float power) {
        Compat.shootArrow(this, target, power);
        consumeOne(SoulGear::isArrow);
    }

    /**
     * Sent to the victim alone: a vanilla packet, so no network code differs between loaders. A screamer only works
     * while it is rare, so each one a player gets pushes their next one further away, up to three times the wait.
     * Returns whether it was sent.
     */
    private boolean tryScreamer(ServerPlayer player) {
        if (!screamerReady(player)) {
            return false;
        }
        sendScreamer(player);
        return true;
    }

    /** Has this player been left alone long enough for another screamer? */
    public static boolean screamerReady(ServerPlayer player) {
        Long last = LAST_SCREAMER.get(player.getUUID());
        long now = Compat.level(player).getGameTime();
        return last == null || now < last || now - last >= screamerWaitTicks(player);
    }

    /** The wait this player has before their next screamer, in ticks. */
    public static long screamerWaitTicks(ServerPlayer player) {
        int shown = SCREAMERS_SHOWN.getOrDefault(player.getUUID(), 0);
        return (long) (AfterselfConfig.screamerCooldownSeconds * 20L * Math.min(3f, 0.5f + 0.5f * shown));
    }

    private void sendScreamer(ServerPlayer player) {
        LAST_SCREAMER.put(player.getUUID(), Compat.level(player).getGameTime());
        SCREAMERS_SHOWN.merge(player.getUUID(), 1, Integer::sum);
        player.connection.send(new ClientboundEntityEventPacket(this, EVENT_SCREAMER));
    }

    /** The moment a body is taken: always, whatever the wait. It still counts toward the next one. */
    public void screamOnPossession(ServerPlayer player) {
        Long last = LAST_SCREAMER.get(player.getUUID());
        long now = Compat.level(player).getGameTime();
        // Unless the trap that led here screamed a moment ago: twice in half a minute is one too many.
        if (last == null || now < last || now - last >= 600L) {
            sendScreamer(player);
        }
    }

    /** Test command: triggers the screamer without waiting, and without making the next one rarer. */
    public void forceScreamer(ServerPlayer player) {
        player.connection.send(new ClientboundEntityEventPacket(this, EVENT_SCREAMER));
    }

    /** Tests and admins: as if this player had never had a screamer. */
    public static void forgetScreamers(ServerPlayer player) {
        LAST_SCREAMER.remove(player.getUUID());
        SCREAMERS_SHOWN.remove(player.getUUID());
        LAST_FLASH.remove(player.getUUID());
    }

    /** The silent face, at most once in a while for the same player. */
    private void tryFlash(ServerPlayer player) {
        long now = Compat.level(player).getGameTime();
        Long last = LAST_FLASH.get(player.getUUID());
        if (last != null && now >= last && now - last < FLASH_COOLDOWN_TICKS) {
            return;
        }
        LAST_FLASH.put(player.getUUID(), now);
        sendFlash(player);
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == EVENT_SCREAMER || id == EVENT_FLASH || id == Possession.EVENT_POSSESS || id == Possession.EVENT_RELEASE) {
            Afterself.clientEvents.onSoulEvent(this, id);
            return;
        }
        super.handleEntityEvent(id);
    }

    @Override
    public void die(DamageSource source) {
        if (!Compat.level(this).isClientSide) {
            haunt.cleanUp();
            for (ItemStack stack : carried) {
                this.spawnAtLocation(stack);
            }
            carried.clear();
        }
        super.die(source);
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    /** Footsteps would give it away. */
    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        if (isHunting()) {
            super.playStepSound(pos, state);
        }
    }

    // Possession

    /** Inside the body it is an invisible marker: the server's way to reach the possessed player's client. */
    public void enterBody(Player player) {
        body = player;
        this.setTarget(null);
        this.getNavigation().stop();
        setLureScene(LureScene.NONE);
        setMode(Mode.POSSESSING, 0);
        this.setInvisible(true);
        this.setInvulnerable(true);
        this.setNoAi(true);
        this.noPhysics = true;
        followBody(player);
    }

    public void followBody(Player player) {
        this.setPos(player.getX(), player.getY(), player.getZ());
        this.setDeltaMovement(Vec3.ZERO);
    }

    /** Thrown out of the body. It keeps what it carried. */
    public void leaveBody(ServerPlayer player) {
        body = null;
        this.setInvisible(false);
        this.setInvulnerable(false);
        this.setNoAi(false);
        this.noPhysics = false;
        returnToStalking();
        if (player != null) {
            vanish();
        } else {
            SoulStore.sendToSleep(this);
        }
    }

    @Override
    public boolean isPushable() {
        return !isPossessing() && super.isPushable();
    }

    @Override
    public boolean isPickable() {
        return !isPossessing() && super.isPickable();
    }

    // Merging

    /** Takes in another soul: all its items, and a bigger body. */
    public void absorbData(CompoundTag other) {
        ListTag items = other.getList("AfterselfItems", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = Compat.loadItem(items.getCompound(i), Compat.level(this));
            if (!stack.isEmpty()) {
                carried.add(stack);
            }
        }
        refreshEquipment();
        applySoulScale(SoulStore.mergedScale(getSoulScale(), other.getFloat("AfterselfScale")));
        this.setHealth(this.getMaxHealth());
        if (Compat.level(this) instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.SOUL, this.getX(), this.getY() + 1.0, this.getZ(), 40, 0.6, 0.9, 0.6, 0.05);
        }
    }

    public void applySoulScale(float scale) {
        setSoulScale(scale);
        this.refreshDimensions();
        var health = this.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(30.0 * scale);
        }
        var damage = this.getAttribute(Attributes.ATTACK_DAMAGE);
        if (damage != null) {
            damage.setBaseValue(5.0 * Math.sqrt(scale));
        }
    }

    // Saving

    /** Everything that makes this soul itself, without where it stands. Used for sleeping and saving. */
    public void writeSoulData(CompoundTag tag) {
        UUID owner = getOwnerId();
        if (owner != null) {
            tag.putUUID("AfterselfOwner", owner);
        }
        tag.putString("AfterselfOwnerName", getOwnerName());
        tag.putFloat("AfterselfScale", getSoulScale());
        ListTag items = new ListTag();
        for (ItemStack stack : carried) {
            if (!stack.isEmpty()) {
                items.add(Compat.saveItem(stack, Compat.level(this)));
            }
        }
        tag.put("AfterselfItems", items);
    }

    public void readSoulData(CompoundTag tag) {
        if (tag.hasUUID("AfterselfOwner")) {
            setOwner(tag.getUUID("AfterselfOwner"), tag.getString("AfterselfOwnerName"));
        }
        carried.clear();
        ListTag items = tag.getList("AfterselfItems", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = Compat.loadItem(items.getCompound(i), Compat.level(this));
            if (!stack.isEmpty()) {
                carried.add(stack);
            }
        }
        refreshEquipment();
        float scale = tag.contains("AfterselfScale") ? tag.getFloat("AfterselfScale") : 1f;
        applySoulScale(Math.max(1f, scale));
        this.setHealth(this.getMaxHealth());
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        writeSoulData(tag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        readSoulData(tag);
    }

    /** Used by the stalk goal: where the closest player worth hiding from is looking from. */
    public Vec3 nearestPlayerEyes() {
        Player player = nearestVictim(48);
        return player == null ? null : player.getEyePosition();
    }

    public SoulHaunt getHaunt() {
        return haunt;
    }

    /** Sends the short silent face to one player. Used by the debug command. */
    public void sendFlash(ServerPlayer player) {
        player.connection.send(new ClientboundEntityEventPacket(this, EVENT_FLASH));
    }

    /** Totem check helper for tests. */
    public int carriedCount(net.minecraft.world.item.Item item) {
        int total = 0;
        for (ItemStack stack : carried) {
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    @Override
    protected String animationName() {
        return SoulAnimations.nameFor(this);
    }
}
