package com.afterself.test;

import com.afterself.Compat;
import com.afterself.ConsentStore;
import com.afterself.GameEvents;
import com.afterself.Possession;
import com.afterself.Afterself;
import com.afterself.AfterselfConfig;
import com.afterself.SoulStore;
import com.afterself.VoiceMemory;
import com.afterself.client.ClientCompat;
import com.afterself.client.PossessionClient;
import com.afterself.client.ScreamerOverlay;
import com.afterself.entity.SoulGear;
import com.afterself.entity.SoulHaunt;
import com.afterself.entity.goal.SoulDarkenGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import com.afterself.client.SoulModel;
import com.afterself.entity.SoulAnimations;
import com.afterself.entity.SoulEntity;
import com.afterself.entity.goal.SoulLootGoal;
import com.afterself.entity.goal.SoulStealGoal;
import com.afterself.voice.AfterselfPlugin;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

/**
 * Drives one real client through the mod and prints AFTERSELFTEST lines. Dev-only: this folder is only compiled
 * with -PafterselfTest and never reaches a released jar.
 */
public final class AfterselfTestRunner {
    private static final int START_TIMEOUT = 20 * 180;
    private static final float LOOK_AT_SOULS = -90f;
    private static final float LOOK_AWAY = 90f;
    private static final List<Step> STEPS = new ArrayList<>();
    private static int clientTicks;
    private static int index;
    private static int stepStartedAt;
    private static boolean built;
    private static boolean pollPending;
    private static volatile Boolean pollResult;
    private static int passes;
    private static int fails;
    private static boolean startedSetup;
    private static boolean creating;
    private static boolean finished;

    private static BlockPos center;
    private static SoulEntity soulA;
    private static SoulEntity soulB;
    private static UUID playerId;

    private static final class Step {
        final String name;
        final int timeout;
        final Runnable once;
        final BooleanSupplier until;
        final boolean onClient;

        Step(String name, int timeout, Runnable once, BooleanSupplier until, boolean onClient) {
            this.name = name;
            this.timeout = timeout;
            this.once = once;
            this.until = until;
            this.onClient = onClient;
        }
    }

    private AfterselfTestRunner() {}

    private static void server(String name, Runnable task) {
        STEPS.add(new Step(name, 200, task, null, false));
    }

    private static void client(String name, Runnable task) {
        STEPS.add(new Step(name, 200, task, null, true));
    }

    private static void until(String name, int timeout, BooleanSupplier condition) {
        STEPS.add(new Step(name, timeout, null, condition, false));
    }

    private static void untilClient(String name, int timeout, BooleanSupplier condition) {
        STEPS.add(new Step(name, timeout, null, condition, true));
    }

    private static void pause(int ticks) {
        int[] end = {-1};
        STEPS.add(new Step("wait", ticks + 40, null, () -> {
            if (end[0] < 0) {
                end[0] = clientTicks + ticks;
            }
            return clientTicks >= end[0];
        }, true));
    }

    private static synchronized void check(String name, boolean ok, String detail) {
        if (ok) {
            passes++;
        } else {
            fails++;
        }
        System.out.println("AFTERSELFTEST " + (ok ? "PASS " : "FAIL ") + name + " | " + detail);
    }

    public static void clientTick() {
        Minecraft mc = Minecraft.getInstance();
        clientTicks++;
        if (!startedSetup) {
            // Sound is nearly off through options.txt, written by run-client-test.sh before the game starts.
            mc.options.pauseOnLostFocus = false;
            startedSetup = true;
        }
        if (clientTicks > START_TIMEOUT && !built) {
            System.out.println("AFTERSELFTEST FAIL startup | no world after timeout, screen=" + mc.screen);
            finish(mc);
            return;
        }
        if (mc.getOverlay() != null) {
            return;
        }
        if (mc.level == null || mc.player == null) {
            openWorld(mc);
            return;
        }
        if (mc.screen != null) {
            mc.setScreen(null);
        }
        if (mc.player.isDeadOrDying()) {
            mc.player.respawn();
        }
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        if (!built) {
            built = true;
            playerId = mc.player.getUUID();
            buildSteps();
        }
        runSteps(mc, server);
    }

    private static void openWorld(Minecraft mc) {
        if (mc.screen instanceof TitleScreen && !creating) {
            creating = true;
            CreateWorldScreen.openFresh(mc, mc.screen);
        } else if (mc.screen instanceof CreateWorldScreen screen) {
            try {
                Method create = CreateWorldScreen.class.getDeclaredMethod("onCreate");
                create.setAccessible(true);
                create.invoke(screen);
            } catch (ReflectiveOperationException e) {
                System.out.println("AFTERSELFTEST FAIL startup | " + e);
                finish(mc);
            }
        } else if (mc.screen != null && !creating) {
            // NeoForge lists mod warnings on a screen of its own.
            for (var child : mc.screen.children()) {
                if (child instanceof AbstractButton button && button.getMessage().getString().toLowerCase().contains("proceed")) {
                    button.onPress();
                    return;
                }
            }
        }
    }

    private static void runSteps(Minecraft mc, MinecraftServer server) {
        if (index >= STEPS.size()) {
            if (!finished) {
                finished = true;
                System.out.println("AFTERSELFTEST SUMMARY pass=" + passes + " fail=" + fails);
                finish(mc);
            }
            return;
        }
        Step step = STEPS.get(index);
        if (stepStartedAt == 0) {
            stepStartedAt = clientTicks;
        }
        if (clientTicks - stepStartedAt > step.timeout) {
            check("timeout:" + step.name, false, "step did not finish in " + step.timeout + " ticks");
            next();
            return;
        }
        try {
            if (step.once != null) {
                if (step.onClient) {
                    step.once.run();
                } else {
                    server.execute(guard(step.name, step.once));
                }
                next();
            } else if (step.onClient) {
                if (step.until.getAsBoolean()) {
                    next();
                }
            } else if (!pollPending) {
                pollPending = true;
                pollResult = null;
                server.execute(() -> {
                    try {
                        pollResult = step.until.getAsBoolean();
                    } catch (RuntimeException e) {
                        check("error:" + step.name, false, e.toString());
                        pollResult = true;
                    }
                });
            } else if (pollResult != null) {
                pollPending = false;
                if (pollResult) {
                    next();
                }
            }
        } catch (RuntimeException e) {
            check("error:" + step.name, false, e.toString());
            next();
        }
    }

    private static Runnable guard(String name, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                t.printStackTrace();
                check("error:" + name, false, t.toString());
            }
        };
    }

    private static void next() {
        index++;
        stepStartedAt = 0;
        pollPending = false;
    }

    private static void finish(Minecraft mc) {
        mc.stop();
    }

    private static MinecraftServer srv() {
        return Minecraft.getInstance().getSingleplayerServer();
    }

    private static ServerPlayer player() {
        return srv().getPlayerList().getPlayer(playerId);
    }

    private static ServerLevel level() {
        return (ServerLevel) Compat.level(player());
    }

    private static List<ItemEntity> groundItems(BlockPos around) {
        return level().getEntitiesOfClass(ItemEntity.class, new AABB(around).inflate(8.0));
    }

    private static List<SoulEntity> souls() {
        return SoulStore.activeSouls(playerId);
    }

    private static void removeAllSouls() {
        for (SoulEntity soul : SoulStore.soulsIn(level())) {
            soul.discard();
        }
        while (SoulStore.dormantCount(playerId) > 0) {
            // A test never wants leftovers: wake is impossible without a night, so clear through the debug path.
            clearDormant();
        }
    }

    private static void setScreamersShown(int count) {
        try {
            var field = SoulEntity.class.getDeclaredField("SCREAMERS_SHOWN");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<UUID, Integer> shown = (java.util.Map<UUID, Integer>) field.get(null);
            shown.put(playerId, count);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void clearDormant() {
        try {
            var field = SoulStore.class.getDeclaredField("DORMANT");
            field.setAccessible(true);
            ((java.util.Map<?, ?>) field.get(null)).remove(playerId);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void removeGroundItems() {
        level().getEntitiesOfClass(ItemEntity.class, player().getBoundingBox().inflate(100.0)).forEach(Entity::discard);
    }

    private static void placePlayer(double dx, double dz, float yaw) {
        ServerPlayer p = player();
        p.connection.teleport(center.getX() + dx + 0.5, center.getY(), center.getZ() + dz + 0.5, yaw, 10f);
        p.setDeltaMovement(0.0, 0.0, 0.0);
    }

    /** Stands the player at one spot of the arena, looking at another. */
    private static void lookFrom(double dx, double dz, double atX, double atZ) {
        ServerPlayer p = player();
        float yaw = (float) (Math.atan2(atZ - dz, atX - dx) * 180.0 / Math.PI) - 90f;
        p.connection.teleport(center.getX() + dx + 0.5, center.getY(), center.getZ() + dz + 0.5, yaw, 8f);
        p.setDeltaMovement(0.0, 0.0, 0.0);
    }

    /** The game's own model holding an axe and a shield, arms straight down: what a soul's hands must look like. */
    private static net.minecraft.world.entity.decoration.ArmorStand referenceStand(double dx, double dz) {
        net.minecraft.world.entity.decoration.ArmorStand stand = net.minecraft.world.entity.EntityType.ARMOR_STAND.create(level());
        CompoundTag tag = stand.saveWithoutId(new CompoundTag());
        tag.putBoolean("ShowArms", true);
        tag.putBoolean("NoBasePlate", true);
        tag.putBoolean("NoGravity", true);
        CompoundTag pose = new CompoundTag();
        for (String arm : new String[] {"LeftArm", "RightArm"}) {
            net.minecraft.nbt.ListTag angles = new net.minecraft.nbt.ListTag();
            for (int i = 0; i < 3; i++) {
                angles.add(net.minecraft.nbt.FloatTag.valueOf(0f));
            }
            pose.put(arm, angles);
        }
        tag.put("Pose", pose);
        stand.load(tag);
        stand.moveTo(center.getX() + dx + 0.5, center.getY(), center.getZ() + dz + 0.5, 90f, 0f);
        stand.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        stand.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        level().addFreshEntity(stand);
        return stand;
    }

    private static SoulEntity spawnSoul(double dx, double dz) {
        SoulEntity soul = Afterself.soulType.get().create(level());
        soul.moveTo(center.getX() + dx + 0.5, center.getY(), center.getZ() + dz + 0.5, 90f, 0f);
        soul.setOwner(playerId, player().getGameProfile().getName());
        level().addFreshEntity(soul);
        return soul;
    }

    private static void clearAndFloor(int radius) {
        ServerLevel level = level();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                level.setBlock(center.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = 0; y < 5; y++) {
                    level.setBlock(center.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    private static void shot(String name) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, "afterself_" + name + ".png", mc.getMainRenderTarget(), message -> {});
        System.out.println("AFTERSELFTEST SHOT " + name);
    }

    private static SoulEntity soulClientSide(Minecraft mc) {
        List<SoulEntity> found = mc.level.getEntitiesOfClass(SoulEntity.class, mc.player.getBoundingBox().inflate(48.0));
        return found.isEmpty() ? null : found.get(0);
    }

    private static void giveKit() {
        ServerPlayer p = player();
        p.getInventory().clearContent();
        p.getInventory().add(new ItemStack(Items.DIAMOND_SWORD));
        p.getInventory().add(new ItemStack(Items.IRON_CHESTPLATE));
        p.getInventory().add(new ItemStack(Items.DIRT, 5));
    }

    /**
     * With AFTERSELF_LOGO set, the client only photographs the screamer for the mod's logo: a tall window, so the
     * screaming face, as wide as the window, fits from hair to chin,
     * a soul whose owner has Steve's skin, nothing on screen but the screamer.
     */
    private static void logoSteps() {
        UUID[] steve = new UUID[1];
        pause(40);
        client("logo window", () -> {
            Minecraft mc = Minecraft.getInstance();
            org.lwjgl.glfw.GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 680, 1024);
            mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            // Whoever is not online wears a default skin chosen from their id: one that gives Steve.
            java.util.Random random = new java.util.Random(7L);
            UUID id = new UUID(random.nextLong(), random.nextLong());
            while (!ClientCompat.skinOf(id).getPath().contains("steve")) {
                id = new UUID(random.nextLong(), random.nextLong());
            }
            steve[0] = id;
            System.out.println("AFTERSELFTEST INFO logo owner " + id + " skin " + ClientCompat.skinOf(id));
        });
        server("logo soul", () -> {
            ServerPlayer p = player();
            center = p.blockPosition();
            p.getAbilities().invulnerable = true;
            level().setDayTime(18000L);
            level().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, srv());
            soulA = spawnSoul(3, 0);
            soulA.setNoAi(true);
            // Held still: a soul that is looked at from close strikes and goes, even without its AI.
            soulA.startLure(p, SoulEntity.LureScene.NONE);
            AfterselfConfig.soulsOnlyAtNight = false;
        });
        pause(40);
        for (int run = 0; run < 4; run++) {
            int number = run;
            client("logo clear " + run, () -> {
                Minecraft mc = Minecraft.getInstance();
                mc.getToasts().clear();
                mc.gui.getChat().clearMessages(false);
                ScreamerOverlay.forcedVariant = ScreamerOverlay.Variant.LUNGE;
                // Only on this screen: on the server it stays the player's, or it would be put away for an absent owner.
                SoulEntity client = soulClientSide(mc);
                check("logo_soul_on_client", client != null, "found=" + (client != null));
                if (client != null) {
                    client.setOwner(steve[0], "Steve");
                }
            });
            server("logo scream " + run, () -> soulA.forceScreamer(player()));
            untilClient("logo starts " + run, 40, ScreamerOverlay::isActive);
            pause(7);
            for (int shot = 0; shot < 16; shot++) {
                int at = shot;
                pause(1);
                client("logo shot " + run + " " + shot, () -> {
                    Minecraft mc = Minecraft.getInstance();
                    check("logo_skin_is_steve", ClientCompat.skinOf(steve[0]).getPath().contains("steve"), "" + ClientCompat.skinOf(steve[0]));
                    shot("logo_" + number + "_" + at);
                    System.out.println("AFTERSELFTEST INFO logo size " + mc.getMainRenderTarget().width + "x" + mc.getMainRenderTarget().height);
                });
            }
            pause(60);
        }
        client("logo done", () -> ScreamerOverlay.forcedVariant = null);
    }

    private static void buildSteps() {
        if (System.getenv("AFTERSELF_LOGO") != null) {
            logoSteps();
            return;
        }
        pause(40);
        server("arena", () -> {
            ServerPlayer p = player();
            // Far above any hill: in a desert, sand from a slope cut open above the arena rains down on it.
            center = new BlockPos(p.blockPosition().getX(), Math.max(p.blockPosition().getY() + 40, 240), p.blockPosition().getZ());
            level().setDayTime(18000L);
            p.setHealth(20f);
            clearAndFloor(36);
            placePlayer(0, 0, LOOK_AWAY);
            ConsentStore.set(playerId, false);
            AfterselfConfig.maxSouls = 10;
            AfterselfConfig.soulsOnlyAtNight = false;
            // No zombies on the dark arena: one of them hitting the player ruins whichever test is running.
            level().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, srv());
            for (Monster monster : level().getEntitiesOfClass(Monster.class, p.getBoundingBox().inflate(96.0))) {
                if (!(monster instanceof SoulEntity)) {
                    monster.discard();
                }
            }
            // Random frights would get in the way of every other test: they have their own.
            AfterselfConfig.soulsHaunt = false;
            AfterselfConfig.soulsHauntSleep = false;
        });
        pause(40);

        deathAndGear();
        lookAndScreamer();
        hearingAndStalking();
        doorsAndBlocks();
        looting();
        totems();
        stealing();
        surpriseStrike();
        following();
        killingGivesItemsBack();
        nightAndDay();
        merging();
        consentAndVoice();
        building();
        searching();
        combatGear();
        haunting();
        sleeping();
        lureAndPossession();
        possessedBody();
    }

    /** What the body does once it is taken: it finds its way, opens doors, and does not let itself be beaten. */
    private static void possessedBody() {
        // Shut in a room with one wooden door, a wall right outside it, and somewhere to be beyond the wall.
        server("room", () -> {
            removeAllSouls();
            clearAndFloor(36);
            ServerLevel level = level();
            ServerPlayer p = player();
            level.setDayTime(18000L);
            p.getInventory().clearContent();
            p.getAbilities().invulnerable = false;
            p.setHealth(20f);
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    for (int y = 0; y <= 3; y++) {
                        boolean wall = Math.abs(x) == 3 || Math.abs(z) == 3;
                        if (wall || y == 3) {
                            level.setBlock(center.offset(x, y, z), Blocks.STONE_BRICKS.defaultBlockState(), 2);
                        }
                    }
                }
            }
            BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
            level.setBlock(center.offset(3, 0, 0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 2);
            level.setBlock(center.offset(3, 1, 0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
            for (int z = -5; z <= 5; z++) {
                for (int y = 0; y <= 2; y++) {
                    level.setBlock(center.offset(7, y, z), Blocks.STONE_BRICKS.defaultBlockState(), 2);
                }
            }
            // Facing a wall, like the body that walked into one for as long as it was held.
            placePlayer(0, 0, LOOK_AWAY);
            soulA = spawnSoul(1, 0);
            Possession.begin(p, soulA);
        });
        client("goal", () -> PossessionClient.testGoal = center.offset(12, 0, 0));
        untilClient("client takes the body", 120, PossessionClient::isActive);
        untilClient("out of the room", 500, () -> Minecraft.getInstance().player.getX() > center.getX() + 4.2);
        server("door check", () -> {
            boolean open = level().getBlockState(center.offset(3, 0, 0)).getValue(DoorBlock.OPEN);
            check("possessed_body_opens_doors", open && player().getX() > center.getX() + 4.0, "open=" + open + " x=" + (player().getX() - center.getX()));
        });
        untilClient("around the wall", 600, () -> Minecraft.getInstance().player.getX() > center.getX() + 10.5);
        client("wall check", () -> {
            Minecraft mc = Minecraft.getInstance();
            double x = mc.player.getX() - center.getX();
            double z = mc.player.getZ() - center.getZ();
            check("possessed_body_walks_around_walls", x > 10.5, "x=" + x + " z=" + z);
            shot("possessed_past_the_wall");
        });
        untilClient("arrives", 200, () -> {
            Minecraft mc = Minecraft.getInstance();
            return Math.abs(mc.player.getX() - center.getX() - 12.5) < 1.0 && Math.abs(mc.player.getZ() - center.getZ() - 0.5) < 1.0;
        });
        client("arrival check", () -> {
            Minecraft mc = Minecraft.getInstance();
            double x = mc.player.getX() - center.getX();
            double z = mc.player.getZ() - center.getZ();
            check("possessed_body_reaches_where_it_goes", Math.abs(x - 12.5) < 1.0 && Math.abs(z - 0.5) < 1.0, "x=" + x + " z=" + z);
        });

        // A zombie comes for the body: it draws the sword it carries and strikes back.
        net.minecraft.world.entity.monster.Zombie[] zombie = new net.minecraft.world.entity.monster.Zombie[1];
        server("zombie", () -> {
            ServerPlayer p = player();
            clearAndFloor(36);
            p.getInventory().clearContent();
            p.getInventory().setItem(4, new ItemStack(Items.IRON_SWORD));
            p.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            p.setHealth(20f);
            net.minecraft.world.entity.monster.Zombie z = net.minecraft.world.entity.EntityType.ZOMBIE.create(level());
            z.moveTo(p.getX() + 5.0, center.getY(), p.getZ(), 90f, 0f);
            z.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
            z.setPersistenceRequired();
            level().addFreshEntity(z);
            z.setTarget(p);
            zombie[0] = z;
        });
        until("zombie struck", 400, () -> !zombie[0].isAlive() || zombie[0].getLastHurtByMob() == player());
        server("zombie check", () -> {
            ServerPlayer p = player();
            boolean struck = !zombie[0].isAlive() || zombie[0].getLastHurtByMob() == p;
            check("possessed_body_fights_monsters", struck, "zombie health=" + zombie[0].getHealth() + " alive=" + zombie[0].isAlive());
            check("possessed_body_draws_its_weapon", p.getMainHandItem().is(Items.IRON_SWORD), "holding=" + p.getMainHandItem());
        });
        until("zombie dead", 600, () -> !zombie[0].isAlive());
        server("zombie dead check", () -> {
            check("possessed_body_kills_what_attacks_it", !zombie[0].isAlive() && player().isAlive() && Possession.isPossessed(playerId),
                    "zombie alive=" + zombie[0].isAlive() + " body health=" + player().getHealth());
            zombie[0].discard();
        });
        client("shot fight", () -> shot("possessed_after_fight"));

        // Another soul hunts the body: the one inside does not let it.
        server("other soul", () -> {
            ServerPlayer p = player();
            p.setHealth(20f);
            soulB = spawnSoul(p.getX() - center.getX() + 5.0, p.getZ() - center.getZ());
            soulB.startHunting(p);
        });
        until("soul struck", 400, () -> !soulB.isAlive() || soulB.getLastHurtByMob() == player());
        server("soul check", () -> {
            boolean struck = !soulB.isAlive() || soulB.getLastHurtByMob() == player();
            check("possessed_body_fights_other_souls", struck, "soul health=" + soulB.getHealth() + " mode=" + soulB.getMode() + " alive=" + soulB.isAlive());
        });
        client("threat seen", () -> check("possessed_body_counts_its_blows", PossessionClient.hitsLanded() >= 2, "hits=" + PossessionClient.hitsLanded()));
        server("body release", () -> {
            Possession.end(player());
            removeAllSouls();
            player().getAbilities().invulnerable = true;
            player().getInventory().clearContent();
        });
        untilClient("body let go", 120, () -> !PossessionClient.isActive());
        client("goal off", () -> PossessionClient.testGoal = null);
    }

    private static void carveGap(int fromX, int toX) {
        for (int x = fromX; x <= toX; x++) {
            for (int z = -36; z <= 36; z++) {
                level().setBlock(center.offset(x, -1, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }

    private static void rehunt() {
        if (soulA.getMode() != SoulEntity.Mode.HUNT) {
            soulA.startHunting(player());
        }
    }

    private static void building() {
        server("bridge setup", () -> {
            removeAllSouls();
            clearAndFloor(36);
            carveGap(3, 7);
            AfterselfConfig.soulsPlaceBlocks = true;
            placePlayer(11, 0, LOOK_AWAY);
            soulA = spawnSoul(0, 0);
            soulA.addLoot(new ItemStack(Items.COBBLESTONE, 32));
            soulA.startHunting(player());
        });
        until("mid bridge", 500, () -> {
            rehunt();
            return soulA.getX() > center.getX() + 5.0;
        });
        client("bridge shot", () -> shot("bridge"));
        until("bridge crossed", 500, () -> {
            rehunt();
            return soulA.getX() > center.getX() + 8.2;
        });
        server("bridge check", () -> {
            int placed = 0;
            for (int x = 3; x <= 7; x++) {
                for (int z = -3; z <= 3; z++) {
                    if (!level().getBlockState(center.offset(x, -1, z)).isAir()) {
                        placed++;
                    }
                }
            }
            check("bridges_over_a_gap", soulA.getX() > center.getX() + 8.2 && soulA.getY() > center.getY() - 0.5,
                    "x=" + (soulA.getX() - center.getX()) + " y=" + (soulA.getY() - center.getY()));
            check("bridge_uses_carried_blocks", placed >= 5 && soulA.buildingBlockCount() == 32 - placed,
                    "placed=" + placed + " left=" + soulA.buildingBlockCount());
        });

        server("pillar setup", () -> {
            removeAllSouls();
            clearAndFloor(36);
            for (int y = 0; y < 4; y++) {
                level().setBlock(center.offset(6, y, 0), Blocks.OBSIDIAN.defaultBlockState(), 2);
            }
            // Real ground is thick. Without this, a soul that steps into the hole it just dug falls out of the arena.
            for (int x = -4; x <= 12; x++) {
                for (int z = -8; z <= 8; z++) {
                    level().setBlock(center.offset(x, -2, z), Blocks.STONE.defaultBlockState(), 2);
                    level().setBlock(center.offset(x, -3, z), Blocks.STONE.defaultBlockState(), 2);
                }
            }
            ServerPlayer p = player();
            p.connection.teleport(center.getX() + 6.5, center.getY() + 4.0, center.getZ() + 0.5, LOOK_AWAY, 40f);
            soulA = spawnSoul(0, 0);
            soulA.startHunting(p);
        });
        until("pillar built", 700, () -> {
            rehunt();
            // Standing on it: at the top of a jump from two blocks high, it is already this high.
            return soulA.onGroundCompat() && soulA.getY() >= center.getY() + 2.9;
        });
        client("pillar shot", () -> shot("pillar"));
        server("pillar check", () -> {
            int holes = 0;
            for (int x = 0; x <= 9; x++) {
                for (int z = -4; z <= 4; z++) {
                    // It may dig two deep in the same place: every missing block counts.
                    for (int depth = 1; depth <= 2; depth++) {
                        if (level().getBlockState(center.offset(x, -depth, z)).isAir()) {
                            holes++;
                        }
                    }
                }
            }
            check("pillars_up_to_a_player_above", soulA.getY() >= center.getY() + 2.9, "y=" + (soulA.getY() - center.getY()));
            check("digs_blocks_when_empty_handed", holes >= 3, "holes=" + holes);
            for (int x = -4; x <= 12; x++) {
                for (int z = -8; z <= 8; z++) {
                    level().setBlock(center.offset(x, -2, z), Blocks.AIR.defaultBlockState(), 2);
                    level().setBlock(center.offset(x, -3, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
            placePlayer(0, 0, LOOK_AWAY);
        });
    }

    private static void searching() {
        server("search setup", () -> {
            removeAllSouls();
            clearAndFloor(36);
            // A sealed box no soul can dig into, with the player inside.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = 0; dy <= 2; dy++) {
                        if (dx != 0 || dz != 0 || dy == 2) {
                            level().setBlock(center.offset(12 + dx, dy, dz), Blocks.OBSIDIAN.defaultBlockState(), 2);
                        }
                    }
                }
            }
            placePlayer(12, 0, LOOK_AWAY);
            soulA = spawnSoul(0, 0);
            soulA.startHunting(player());
        });
        until("loses sight", 300, () -> soulA.getMode() == SoulEntity.Mode.SEARCH);
        server("search starts", () -> check("searches_when_sight_is_lost", soulA.getMode() == SoulEntity.Mode.SEARCH && soulA.getTarget() == null,
                "mode=" + soulA.getMode()));
        until("reaches last seen", 300, () -> soulA.distanceToSqr(player()) < 36.0);
        server("search place", () -> check("search_goes_to_where_it_last_saw_them", soulA.distanceToSqr(player()) < 36.0 && soulA.getMode() == SoulEntity.Mode.SEARCH,
                "dist=" + Math.sqrt(soulA.distanceToSqr(player())) + " mode=" + soulA.getMode()));
        pause(10);
        client("search look", () -> {
            SoulEntity client = soulClientSide(Minecraft.getInstance());
            check("search_mode_reaches_client", client != null && client.getMode() == SoulEntity.Mode.SEARCH, client == null ? "no client soul" : "mode=" + client.getMode());
        });
        until("gives up", 600, () -> soulA.getMode() == SoulEntity.Mode.STALK);
        server("search over", () -> {
            check("hidden_silent_player_is_left_alone", soulA.getMode() == SoulEntity.Mode.STALK && soulA.getTarget() == null, "mode=" + soulA.getMode());
            soulA.startHunting(player());
            soulA.startSearching();
            soulA.hear(player(), 6);
            check("any_noise_during_search_restarts_the_hunt", soulA.getMode() == SoulEntity.Mode.HUNT && soulA.getTarget() == player(), "mode=" + soulA.getMode());
        });
        server("spot", () -> {
            removeAllSouls();
            clearAndFloor(36);
            ServerPlayer p = player();
            placePlayer(16, 0, LOOK_AWAY);
            soulA = spawnSoul(0, 0);
            soulA.setNoAi(true);
        });
        pause(5);
        server("spot check", () -> {
            ServerPlayer p = player();
            soulA.setYRot(-90f);
            soulA.setYHeadRot(-90f);
            boolean front = soulA.canSpot(p);
            p.setPose(Pose.CROUCHING);
            boolean sneaking = soulA.canSpot(p);
            p.setPose(Pose.STANDING);
            soulA.setYRot(90f);
            soulA.setYHeadRot(90f);
            boolean behind = soulA.canSpot(p);
            check("spots_a_player_in_front_of_it", front, "front=" + front);
            check("misses_a_sneaking_player_at_that_range", !sneaking, "sneaking=" + sneaking);
            check("does_not_see_behind_itself", !behind, "behind=" + behind);
        });
    }

    private static void combatGear() {
        server("bow setup", () -> {
            removeAllSouls();
            clearAndFloor(36);
            placePlayer(-14, 0, LOOK_AT_SOULS);
            soulA = spawnSoul(8, 0);
            soulA.addLoot(new ItemStack(Items.IRON_SWORD));
            soulA.addLoot(new ItemStack(Items.BOW));
            soulA.addLoot(new ItemStack(Items.ARROW, 8));
            soulA.startHunting(player());
        });
        boolean[] arrowSeen = {false};
        until("arrow shot", 300, () -> {
            rehunt();
            arrowSeen[0] |= !level().getEntitiesOfClass(AbstractArrow.class, player().getBoundingBox().inflate(48.0)).isEmpty();
            return soulA.carriedCount(Items.ARROW) < 8 && arrowSeen[0];
        });
        client("bow shot", () -> shot("bow"));
        server("bow check", () -> {
            check("shoots_its_bow_from_afar", soulA.carriedCount(Items.ARROW) < 8, "arrows=" + soulA.carriedCount(Items.ARROW));
            check("arrow_flies", arrowSeen[0], "seen=" + arrowSeen[0]);
            level().getEntitiesOfClass(AbstractArrow.class, player().getBoundingBox().inflate(64.0)).forEach(Entity::discard);
        });

        server("pearl setup", () -> {
            removeAllSouls();
            clearAndFloor(36);
            carveGap(3, 7);
            AfterselfConfig.soulsPlaceBlocks = false;
            placePlayer(20, 0, LOOK_AWAY);
            soulA = spawnSoul(0, 0);
            soulA.addLoot(new ItemStack(Items.ENDER_PEARL, 2));
            soulA.startHunting(player());
        });
        until("pearl lands", 300, () -> {
            rehunt();
            return soulA.getX() > center.getX() + 8.2;
        });
        server("pearl check", () -> {
            check("throws_pearl_to_cross", soulA.getX() > center.getX() + 8.2 && soulA.isAlive(),
                    "x=" + (soulA.getX() - center.getX()) + " alive=" + soulA.isAlive());
            check("pearl_is_used_up", soulA.carriedCount(Items.ENDER_PEARL) < 2, "pearls=" + soulA.carriedCount(Items.ENDER_PEARL));
            AfterselfConfig.soulsPlaceBlocks = true;
        });

        server("shield setup", () -> {
            removeAllSouls();
            clearAndFloor(36);
            placePlayer(0, 0, LOOK_AT_SOULS);
            soulA = spawnSoul(4, 0);
            soulA.addLoot(new ItemStack(Items.IRON_SWORD));
            soulA.addLoot(new ItemStack(Items.SHIELD));
            check("carries_shield_in_off_hand", soulA.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.SHIELD), String.valueOf(soulA.getItemBySlot(EquipmentSlot.OFFHAND)));
            soulA.startHunting(player());
        });
        boolean[] raised = {false};
        until("shield raised", 300, () -> {
            rehunt();
            raised[0] |= soulA.isUsingItem() && soulA.isBlocking();
            return raised[0];
        });
        server("shield check", () -> check("raises_shield_between_blows", raised[0], "raised=" + raised[0]));

        server("retreat setup", () -> {
            removeAllSouls();
            clearAndFloor(36);
            ServerPlayer p = player();
            placePlayer(0, 0, LOOK_AWAY);
            soulA = spawnSoul(5, 0);
            soulA.addLoot(new ItemStack(Items.BREAD, 2));
            soulA.startHunting(p);
            soulA.setHealth(8f);
            p.attack(soulA);
            check("runs_when_badly_hurt", soulA.getMode() == SoulEntity.Mode.RETREAT, "mode=" + soulA.getMode() + " health=" + soulA.getHealth());
        });
        double[] farthest = {0.0};
        boolean[] ate = {false};
        boolean[] seen = {false};
        until("ate", 500, () -> {
            if (soulA.getMode() == SoulEntity.Mode.RETREAT) {
                farthest[0] = Math.max(farthest[0], Math.sqrt(soulA.distanceToSqr(player())));
            }
            ate[0] |= soulA.getLureScene() == SoulEntity.LureScene.EAT;
            seen[0] |= soulA.isSeenBy(player());
            return soulA.getMode() == SoulEntity.Mode.STALK;
        });
        server("retreat check", () -> {
            check("eats_to_heal_then_returns", soulA.getMode() == SoulEntity.Mode.STALK && soulA.getHealth() > 12f && soulA.carriedCount(Items.BREAD) == 1,
                    "mode=" + soulA.getMode() + " health=" + soulA.getHealth() + " bread=" + soulA.carriedCount(Items.BREAD)
                            + " farthest=" + (int) farthest[0] + " ate=" + ate[0] + " seen=" + seen[0]);
        });

        server("fight back", () -> {
            removeAllSouls();
            clearAndFloor(36);
            ServerPlayer p = player();
            placePlayer(0, 0, LOOK_AWAY);
            soulA = spawnSoul(20, 0);
            p.attack(soulA);
            check("fights_back_when_hurt_from_afar", soulA.getMode() == SoulEntity.Mode.HUNT && soulA.getTarget() == p, "mode=" + soulA.getMode());
        });
    }

    private static void haunting() {
        BlockPos[] door = new BlockPos[1];
        server("haunt setup", () -> {
            removeAllSouls();
            removeGroundItems();
            clearAndFloor(36);
            AfterselfConfig.soulsHaunt = true;
            ServerPlayer p = player();
            placePlayer(0, 0, LOOK_AT_SOULS);
            door[0] = center.offset(-4, 0, 1);
            BlockState bottom = Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(DoorBlock.FACING, Direction.EAST).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            level().setBlock(door[0], bottom, 2);
            level().setBlock(door[0].above(), bottom.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
            level().setBlock(center.offset(-4, 0, -2), Blocks.CHEST.defaultBlockState(), 3);
            soulA = spawnSoul(-20, 0);
            soulA.setNoAi(true);
        });
        pause(5);
        server("haunt events", () -> {
            ServerPlayer p = player();
            SoulHaunt haunt = soulA.getHaunt();
            check("footsteps_behind_player", haunt.play(SoulHaunt.Event.STEPS, p), "played");
            check("cave_noise_behind_player", haunt.play(SoulHaunt.Event.CAVE, p), "played");
            boolean moved = haunt.play(SoulHaunt.Event.DOOR, p);
            check("door_moves_by_itself", moved && level().getBlockState(door[0]).getValue(DoorBlock.OPEN),
                    "moved=" + moved + " open=" + level().getBlockState(door[0]).getValue(DoorBlock.OPEN));
            check("chest_lid_lifts", haunt.play(SoulHaunt.Event.CHEST, p), "played");
        });
        pause(60);

        // Standing right behind them.
        float[] health = new float[1];
        int[] screamsBeforeLoom = new int[1];
        client("screams before loom", () -> screamsBeforeLoom[0] = ScreamerOverlay.screamsShown);
        server("loom", () -> {
            ServerPlayer p = player();
            SoulEntity.forgetScreamers(p);
            p.getAbilities().invulnerable = false;
            p.setHealth(20f);
            health[0] = p.getHealth();
            soulA.setNoAi(false);
            boolean ok = soulA.getHaunt().play(SoulHaunt.Event.BEHIND, p);
            double dot = soulA.position().subtract(p.position()).normalize().dot(p.getViewVector(1f));
            check("appears_right_behind_player", ok && soulA.distanceToSqr(p) < 3.6 * 3.6 && dot < -0.5,
                    "ok=" + ok + " dist=" + Math.sqrt(soulA.distanceToSqr(p)) + " dot=" + dot);
            check("stands_in_loom_pose", soulA.getMode() == SoulEntity.Mode.BEHIND && soulA.getLureScene() == SoulEntity.LureScene.LOOM,
                    "mode=" + soulA.getMode() + " scene=" + soulA.getLureScene());
        });
        pause(30);
        server("loom holds", () -> check("stays_still_while_unseen", soulA.getMode() == SoulEntity.Mode.BEHIND && player().getHealth() >= health[0],
                "mode=" + soulA.getMode() + " health=" + player().getHealth()));
        server("turn around", () -> placePlayer(0, 0, LOOK_AWAY));
        until("struck", 200, () -> player().getHealth() < health[0] && soulA.getMode() == SoulEntity.Mode.STALK);
        server("loom strike", () -> {
            ServerPlayer p = player();
            check("turning_around_gets_you_hit", p.getHealth() < health[0], "health=" + p.getHealth());
            check("then_no_screamer_for_a_long_time", !SoulEntity.screamerReady(p) && SoulEntity.screamerWaitTicks(p) == AfterselfConfig.screamerCooldownSeconds * 20L,
                    "ready=" + SoulEntity.screamerReady(p) + " wait=" + SoulEntity.screamerWaitTicks(p) / 1200 + " min");
            check("then_it_is_gone_again", soulA.distanceToSqr(p) > 15.0 * 15.0, "dist=" + (int) Math.sqrt(soulA.distanceToSqr(p)));
            p.getAbilities().invulnerable = true;
            p.setHealth(20f);
            placePlayer(0, 0, LOOK_AT_SOULS);
        });
        pause(5);
        client("ambush scream", () -> check("soul_behind_you_always_screams", ScreamerOverlay.screamsShown == screamsBeforeLoom[0] + 1,
                "shown=" + (ScreamerOverlay.screamsShown - screamsBeforeLoom[0])));
        server("loom unnoticed", () -> {
            ServerPlayer p = player();
            soulA.returnToStalking();
            boolean ok = soulA.startLoom(p);
            soulA.setMode(SoulEntity.Mode.BEHIND, 0);
            check("loom_again", ok, "ok=" + ok);
        });
        pause(15);
        server("loom leaves", () -> check("leaves_when_never_noticed", soulA.getMode() == SoulEntity.Mode.STALK && soulA.distanceToSqr(player()) > 15.0 * 15.0,
                "mode=" + soulA.getMode() + " dist=" + (int) Math.sqrt(soulA.distanceToSqr(player()))));

        // The face, for a blink. Waits for the screamer of the strike above to be over: a flash never interrupts one.
        pause(50);
        int[] flashesBefore = new int[1];
        client("flash count", () -> flashesBefore[0] = ScreamerOverlay.flashesShown);
        server("flash", () -> {
            check("flash_soul_is_there", soulA.isAlive() && soulA.distanceToSqr(player()) < 60.0 * 60.0,
                    "alive=" + soulA.isAlive() + " dist=" + (int) Math.sqrt(soulA.distanceToSqr(player())));
            soulA.sendFlash(player());
        });
        boolean[] flashed = {false};
        untilClient("flash seen", 60, () -> {
            flashed[0] |= ScreamerOverlay.flashesShown > flashesBefore[0];
            return flashed[0];
        });
        pause(12);
        client("flash check", () -> {
            check("silent_face_flashes", flashed[0], "flashed=" + flashed[0] + " client soul=" + (soulClientSide(Minecraft.getInstance()) != null));
            check("flash_is_over_in_a_blink", !ScreamerOverlay.isActive(), "active=" + ScreamerOverlay.isActive());
        });

        // Torches.
        server("darken", () -> {
            removeAllSouls();
            removeGroundItems();
            clearAndFloor(36);
            placePlayer(-30, 0, LOOK_AWAY);
            BlockPos torch = center.offset(3, 0, 0);
            BlockPos lamp = center.offset(3, 0, 3);
            level().setBlock(torch, Blocks.TORCH.defaultBlockState(), 3);
            level().setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), 3);
            soulA = spawnSoul(0, 0);
            soulA.setNoAi(true);
            SoulDarkenGoal goal = new SoulDarkenGoal(soulA);
            boolean found = false;
            for (int i = 0; i < 4000 && !found; i++) {
                found = goal.findLight();
            }
            boolean snuffed = found && goal.snuff();
            check("knocks_down_a_torch", snuffed && level().getBlockState(torch).isAir(), "found=" + found + " block=" + level().getBlockState(torch));
            check("leaves_full_light_blocks", !SoulDarkenGoal.isSmallLight(level(), lamp) && level().getBlockState(lamp).is(Blocks.GLOWSTONE), "lamp kept");
        });
        pause(5);
        server("darken drop", () -> {
            boolean dropped = groundItems(center).stream().anyMatch(item -> item.getItem().is(Items.TORCH));
            check("torch_lies_on_the_floor", dropped, "dropped=" + dropped);
            removeGroundItems();
        });

        // Caught watching from afar.
        server("watch setup", () -> {
            removeAllSouls();
            clearAndFloor(36);
            placePlayer(0, 0, LOOK_AT_SOULS);
            soulA = spawnSoul(20, 0);
        });
        until("stares back", 80, () -> soulA.getLureScene() == SoulEntity.LureScene.WATCH);
        pause(6);
        client("watch look", () -> {
            SoulEntity client = soulClientSide(Minecraft.getInstance());
            check("stares_back_when_watched", client != null && "watch".equals(SoulAnimations.nameFor(client)),
                    client == null ? "no client soul" : SoulAnimations.nameFor(client));
        });
        server("haunt done", () -> AfterselfConfig.soulsHaunt = false);
    }

    private static void sleeping() {
        BlockPos[] bed = new BlockPos[1];
        server("sleep setup", () -> {
            removeAllSouls();
            clearAndFloor(36);
            level().setDayTime(18000L);
            AfterselfConfig.soulsHauntSleep = true;
            ServerPlayer p = player();
            placePlayer(0, 0, LOOK_AWAY);
            bed[0] = center.offset(1, 0, 0);
            BlockState foot = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT);
            level().setBlock(bed[0], foot, 3);
            level().setBlock(bed[0].north(), foot.setValue(BedBlock.PART, BedPart.HEAD), 3);
            // Anything that spawned in the dark would refuse the sleep before the mod has a say.
            for (Monster monster : level().getEntitiesOfClass(Monster.class, p.getBoundingBox().inflate(24.0))) {
                monster.discard();
            }
            soulA = spawnSoul(10, 0);
            soulA.addLoot(new ItemStack(Items.IRON_SWORD));
            SoulStore.sendToSleep(soulA);
            check("soul_waits_dormant", SoulStore.dormantCount(playerId) == 1 && souls().isEmpty(),
                    "dormant=" + SoulStore.dormantCount(playerId) + " active=" + souls().size());
            var result = p.startSleepInBed(bed[0].north());
            check("player_falls_asleep", p.isSleeping(), "result=" + result);
        });
        until("woken", 200, () -> !player().isSleeping() && !souls().isEmpty());
        server("sleep check", () -> {
            ServerPlayer p = player();
            SoulEntity soul = souls().isEmpty() ? null : souls().get(0);
            check("sleeper_is_woken_by_their_soul", !p.isSleeping() && soul != null, "sleeping=" + p.isSleeping() + " souls=" + souls().size());
            check("soul_stands_over_the_bed", soul != null && soul.distanceToSqr(p) < 4.5 * 4.5,
                    soul == null ? "no soul" : "dist=" + Math.sqrt(soul.distanceToSqr(p)) + " mode=" + soul.getMode());
            check("woken_soul_keeps_its_items", soul != null && soul.carriedCount(Items.IRON_SWORD) == 1, soul == null ? "no soul" : "sword=" + soul.carriedCount(Items.IRON_SWORD));
            if (soul != null) {
                float yaw = (float) (Math.atan2(soul.getZ() - p.getZ(), soul.getX() - p.getX()) * 180.0 / Math.PI) - 90f;
                p.connection.teleport(p.getX(), p.getY(), p.getZ(), yaw, 0f);
            }
        });
        pause(4);
        client("bedside shot", () -> shot("bedside"));
        server("sleep done", () -> AfterselfConfig.soulsHauntSleep = false);
    }

    private static void deathAndGear() {
        server("death", () -> {
            removeAllSouls();
            removeGroundItems();
            giveKit();
            player().kill();
        });
        pause(40);
        server("death check", () -> {
            List<SoulEntity> found = souls();
            check("soul_created", found.size() == 1, "souls=" + found.size());
            if (found.isEmpty()) {
                return;
            }
            soulA = found.get(0);
            check("soul_owner", playerId.equals(soulA.getOwnerId()), "owner=" + soulA.getOwnerId() + " name=" + soulA.getOwnerName());
            check("soul_carries_3_stacks", soulA.getCarried().size() == 3, "carried=" + soulA.getCarried());
            check("no_items_on_ground", groundItems(soulA.blockPosition()).isEmpty(), "ground=" + groundItems(soulA.blockPosition()).size());
            check("wears_chestplate", soulA.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), String.valueOf(soulA.getItemBySlot(EquipmentSlot.CHEST)));
            check("holds_sword", soulA.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.DIAMOND_SWORD), String.valueOf(soulA.getItemBySlot(EquipmentSlot.MAINHAND)));
        });
        pause(40);
        server("save", () -> {
            player().getAbilities().invulnerable = true;
            CompoundTag tag = new CompoundTag();
            soulA.saveWithoutId(tag);
            SoulEntity copy = Afterself.soulType.get().create(level());
            copy.load(tag);
            check("save_roundtrip", copy.getCarried().size() == 3 && playerId.equals(copy.getOwnerId()),
                    "carried=" + copy.getCarried().size() + " owner=" + copy.getOwnerId());
            copy.discard();
        });
    }

    private static void lookAndScreamer() {
        server("pose", () -> {
            soulA.setNoAi(true);
            placePlayer(0, 0, LOOK_AT_SOULS);
            soulA.moveTo(center.getX() + 3.5, center.getY(), center.getZ() + 0.5, 90f, 0f);
            soulA.setYHeadRot(90f);
            soulA.setYBodyRot(90f);
            soulA.setPose(Pose.STANDING);
            level().setDayTime(6000L);
        });
        pause(30);
        client("shot soul", () -> shot("soul"));
        client("model files", () -> {
            Minecraft mc = Minecraft.getInstance();
            SoulEntity client = soulClientSide(mc);
            boolean expectedSlim = ClientCompat.isSlim(playerId);
            boolean modelSlim = client != null && new SoulModel().getModelResource(client).getPath().contains("slim");
            check("arm_model_matches_skin", client != null && modelSlim == expectedSlim, "skinIsSlim=" + expectedSlim + " modelIsSlim=" + modelSlim);
            for (String file : new String[] {"geo/entity/soul.geo.json", "geo/entity/soul_slim.geo.json", "animations/entity/soul.animation.json"}) {
                check("resource_" + file.substring(file.lastIndexOf('/') + 1), mc.getResourceManager().getResource(Compat.id(file)).isPresent(), file);
            }
        });
        server("crouch", () -> soulA.setPose(Pose.CROUCHING));
        pause(20);
        client("shot crouch", () -> {
            shot("soul_crouching");
            SoulEntity client = soulClientSide(Minecraft.getInstance());
            // Just teleported, so it may still count as walking for a moment: both are crouched.
            check("anim_crouch", client != null && SoulAnimations.nameFor(client).startsWith(SoulAnimations.CROUCH),
                    client == null ? "no client soul" : SoulAnimations.nameFor(client));
        });
        // An axe and a shield in its hands, next to the game's own way of holding them: an armour stand with
        // straight arms. Seen from the front, from the axe side, from the shield side and from behind.
        net.minecraft.world.entity.decoration.ArmorStand[] stand = new net.minecraft.world.entity.decoration.ArmorStand[1];
        server("shield pose", () -> {
            // By now it has struck at the player staring at it and gone: bring it back, and hold it still.
            removeAllSouls();
            soulA = spawnSoul(3, -1);
            soulA.setNoAi(true);
            soulA.startLure(player(), SoulEntity.LureScene.NONE);
            soulA.setYHeadRot(90f);
            soulA.setYBodyRot(90f);
            soulA.addLoot(new ItemStack(Items.IRON_AXE));
            soulA.addLoot(new ItemStack(Items.SHIELD));
            stand[0] = referenceStand(3, 1);
            lookFrom(0, 0, 3, 0);
        });
        pause(20);
        client("shot shield", () -> shot("soul_shield"));
        server("axe side", () -> lookFrom(3, -4, 3, 0));
        pause(15);
        client("shot axe side", () -> shot("soul_axe_side"));
        server("shield side", () -> lookFrom(3, 4, 3, 0));
        pause(15);
        client("shot shield side", () -> shot("soul_shield_side"));
        server("behind", () -> lookFrom(6, 0, 3, 0));
        pause(15);
        client("shot behind", () -> shot("soul_shield_behind"));
        server("shield up", () -> {
            lookFrom(0, 0, 3, 0);
            soulA.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND);
        });
        pause(20);
        client("shot blocking", () -> {
            shot("soul_blocking");
            SoulEntity client = soulClientSide(Minecraft.getInstance());
            check("guard_reaches_client", client != null && SoulAnimations.isGuarding(client), client == null ? "no client soul" : "guarding=" + SoulAnimations.isGuarding(client));
        });
        server("blocking side", () -> lookFrom(1, 2, 3, -1));
        pause(15);
        client("shot blocking side", () -> shot("soul_blocking_side"));
        server("shield down", () -> {
            soulA.stopUsingItem();
            stand[0].discard();
            soulA.moveTo(center.getX() + 3.5, center.getY(), center.getZ() + 0.5, 90f, 0f);
            placePlayer(0, 0, LOOK_AT_SOULS);
        });
        pause(10);
        client("guard down", () -> {
            SoulEntity client = soulClientSide(Minecraft.getInstance());
            check("guard_ends_with_the_shield", client != null && !SoulAnimations.isGuarding(client), client == null ? "no client soul" : "guarding=" + SoulAnimations.isGuarding(client));
        });
        // Each of the three ways it can arrive, caught just after the lunge.
        int[] screamsBefore = new int[1];
        client("screamers start", () -> screamsBefore[0] = ScreamerOverlay.screamsShown);
        for (ScreamerOverlay.Variant variant : ScreamerOverlay.Variant.values()) {
            String name = variant.name().toLowerCase(java.util.Locale.ROOT);
            client("screamer " + name, () -> {
                ScreamerOverlay.forcedVariant = variant;
                check("sound_" + variant.sound(), Minecraft.getInstance().getSoundManager().getSoundEvent(Compat.id(variant.sound())) != null,
                        "afterself:" + variant.sound());
            });
            server("send " + name, () -> soulA.forceScreamer(player()));
            untilClient(name + " starts", 40, ScreamerOverlay::isActive);
            // What it shows before the lunge, for those that take their time. The pitch can stretch it by a tenth.
            int before = (int) (variant.lungeAt() * 0.75 / 50.0);
            if (variant.lungeAt() >= 450) {
                pause(before);
                client("shot before " + name, () -> shot("screamer_" + name + "_before"));
            } else {
                before = 0;
            }
            pause((int) Math.ceil((variant.lungeAt() * 1.14 + 330.0) / 50.0) - before);
            client("shot " + name, () -> shot("screamer_" + name));
            pause(50);
            client("ended " + name, () -> check("screamer_" + name + "_ends_by_itself", !ScreamerOverlay.isActive(), "active=" + ScreamerOverlay.isActive()));
        }
        client("variants done", () -> {
            shot("after_screamer");
            ScreamerOverlay.forcedVariant = null;
            check("all_six_screamers_were_shown", ScreamerOverlay.screamsShown == screamsBefore[0] + 6, "shown=" + (ScreamerOverlay.screamsShown - screamsBefore[0]));
        });
        // Left to choose, it never comes the same way twice in a row.
        ScreamerOverlay.Variant[] picked = new ScreamerOverlay.Variant[2];
        server("screamer random one", () -> soulA.forceScreamer(player()));
        untilClient("random one starts", 40, ScreamerOverlay::isActive);
        client("random one", () -> picked[0] = ScreamerOverlay.currentVariant());
        pause(60);
        server("screamer random two", () -> soulA.forceScreamer(player()));
        untilClient("random two starts", 40, ScreamerOverlay::isActive);
        client("random two", () -> {
            picked[1] = ScreamerOverlay.currentVariant();
            check("never_the_same_screamer_twice_in_a_row", picked[0] != picked[1] && picked[0] != ScreamerOverlay.Variant.SLOW,
                    picked[0] + " then " + picked[1] + " (after SLOW)");
        });
        pause(60);
        server("screamer rarity", () -> {
            ServerPlayer p = player();
            SoulEntity.forgetScreamers(p);
            check("debug_screamers_do_not_count", SoulEntity.screamerReady(p), "ready=" + SoulEntity.screamerReady(p));
            // A blow in a plain fight never screams.
            p.getAbilities().invulnerable = false;
            p.setHealth(20f);
            soulA.startHunting(p);
            boolean hit = soulA.doHurtTarget(p);
            check("fight_blows_never_scream", hit && SoulEntity.screamerReady(p), "hit=" + hit + " ready=" + SoulEntity.screamerReady(p));
            soulA.returnToStalking();
            p.getAbilities().invulnerable = true;
            p.setHealth(20f);
            long base = AfterselfConfig.screamerCooldownSeconds * 20L;
            setScreamersShown(1);
            long first = SoulEntity.screamerWaitTicks(p);
            setScreamersShown(3);
            long third = SoulEntity.screamerWaitTicks(p);
            setScreamersShown(40);
            long many = SoulEntity.screamerWaitTicks(p);
            check("each_screamer_makes_the_next_rarer", first == base && third == base * 2 && many == base * 3,
                    "waits in minutes: " + first / 1200 + ", " + third / 1200 + ", " + many / 1200);
            SoulEntity.forgetScreamers(p);
        });
        server("login warning", () -> {
            ServerPlayer p = player();
            removeAllSouls();
            String none = GameEvents.loginWarning(p).getString();
            soulA = spawnSoul(20, 0);
            String one = GameEvents.loginWarning(p).getString();
            soulB = spawnSoul(22, 0);
            String two = GameEvents.loginWarning(p).getString();
            check("login_warning_counts_souls", !none.equals(one) && !one.equals(two) && two.contains("2"), "none / one / two differ: " + two);
            removeAllSouls();
        });
        client("login warning text", () -> {
            boolean translated = true;
            for (String key : new String[] {"afterself.warning.login", "afterself.warning.login_one", "afterself.warning.login_souls"}) {
                translated &= net.minecraft.client.resources.language.I18n.exists(key);
            }
            check("login_warning_is_translated", translated, "keys exist=" + translated);
            // What the mod list shows: the logo, the licence and the real version.
            String toml = "";
            for (String name : new String[] {"/META-INF/mods.toml", "/META-INF/neoforge.mods.toml"}) {
                try (var in = AfterselfTestRunner.class.getResourceAsStream(name)) {
                    if (in != null && toml.isEmpty()) {
                        toml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                } catch (java.io.IOException e) {
                    toml = "unreadable: " + e;
                }
            }
            boolean logo = AfterselfTestRunner.class.getResource("/afterself_logo.png") != null;
            boolean license = AfterselfTestRunner.class.getResource("/LICENSE_afterself") != null;
            java.util.regex.Matcher version = java.util.regex.Pattern.compile("(?m)^version = \"([^\"]*)\"").matcher(toml);
            String shown = version.find() ? version.group(1) : "none";
            check("mod_info_logo_license_version", logo && license && toml.contains("license = \"GPL-3.0-only\"")
                    && toml.contains("logoFile = \"afterself_logo.png\"") && shown.matches("\\d+\\.\\d+\\.\\d+"),
                    "logo=" + logo + " licenseFile=" + license + " version=" + shown);
        });
    }

    private static void hearingAndStalking() {
        server("hearing setup", () -> {
            removeAllSouls();
            level().setDayTime(18000L);
            placePlayer(0, 0, LOOK_AWAY);
            soulA = spawnSoul(11, 0);
        });
        pause(40);
        server("quiet check", () -> check("stalks_without_attacking", soulA.getTarget() == null && soulA.getMode() != SoulEntity.Mode.HUNT, "mode=" + soulA.getMode()));
        server("too far", () -> {
            soulA.teleportTo(center.getX() + 20.5, center.getY(), center.getZ() + 0.5);
            SoulEntity.broadcastNoise(player(), 5);
        });
        pause(5);
        server("whisper check", () -> check("too_far_to_hear_whisper", !soulA.heardRecently(40), "heard=" + soulA.heardRecently(40)));
        server("talk", () -> SoulEntity.broadcastNoise(player(), 24));
        pause(5);
        server("talk check", () -> {
            check("talking_draws_attention", soulA.heardRecently(40), "heard=" + soulA.heardRecently(40));
            check("talking_once_does_not_trigger_hunt", soulA.getMode() != SoulEntity.Mode.HUNT, "mode=" + soulA.getMode());
        });
        server("keep talking", () -> {
            for (int i = 0; i < 8; i++) {
                SoulEntity.broadcastNoise(player(), 24);
            }
        });
        pause(5);
        server("anger check", () -> check("too_much_noise_triggers_hunt", soulA.getMode() == SoulEntity.Mode.HUNT, "mode=" + soulA.getMode()));
        server("shout", () -> {
            removeAllSouls();
            soulA = spawnSoul(20, 0);
            SoulEntity.broadcastNoise(player(), 48);
        });
        pause(5);
        server("shout check", () -> check("shout_triggers_hunt_at_once", soulA.getTarget() == player() && soulA.getMode() == SoulEntity.Mode.HUNT, "mode=" + soulA.getMode()));
        until("soul reaches player", 300, () -> {
            soulA.startHunting(player());
            return soulA.distanceToSqr(player()) < 12.0;
        });
        server("reach check", () -> check("sprints_to_player", soulA.distanceToSqr(player()) < 12.0, "distSqr=" + soulA.distanceToSqr(player())));
        server("stalk setup", () -> {
            removeAllSouls();
            soulA = spawnSoul(18, 0);
        });
        until("crouches", 900, () -> soulA.getPose() == Pose.CROUCHING);
        server("stalk check", () -> {
            check("stalker_crouches", soulA.getPose() == Pose.CROUCHING, "pose=" + soulA.getPose());
            check("stalker_keeps_distance", soulA.distanceToSqr(player()) > 8.0 * 8.0, "dist=" + (int) Math.sqrt(soulA.distanceToSqr(player())));
            check("stalker_has_no_target", soulA.getTarget() == null, "target=" + soulA.getTarget());
        });
    }

    private static void doorsAndBlocks() {
        BlockPos[] door = new BlockPos[1];
        server("door setup", () -> {
            removeAllSouls();
            clearAndFloor(36);
            // Wall to wall, so the door is the only way through.
            for (int z = -36; z <= 36; z++) {
                for (int y = 0; y < 4; y++) {
                    level().setBlock(center.offset(6, y, z), Blocks.STONE.defaultBlockState(), 2);
                }
            }
            AfterselfConfig.soulsBreakBlocks = false;
            door[0] = center.offset(6, 0, 0);
            // Facing east, the closed door fills the gap in the north-south wall.
            BlockState bottom = Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(DoorBlock.FACING, Direction.EAST).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            level().setBlock(door[0], bottom, 2);
            level().setBlock(door[0].above(), bottom.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
            placePlayer(10, 0, LOOK_AWAY);
            soulA = spawnSoul(2, 0);
            soulA.startHunting(player());
        });
        boolean[] opened = {false};
        until("through door", 500, () -> {
            soulA.startHunting(player());
            opened[0] |= level().getBlockState(door[0]).getValue(DoorBlock.OPEN);
            return soulA.getX() > center.getX() + 7.0;
        });
        server("door check", () -> {
            check("opens_door", opened[0], "opened=" + opened[0]);
            check("crosses_door", soulA.getX() > center.getX() + 7.0, "x=" + (soulA.getX() - center.getX()));
            AfterselfConfig.soulsBreakBlocks = true;
        });

        BlockPos[] wall = new BlockPos[1];
        server("break setup", () -> {
            removeAllSouls();
            clearAndFloor(36);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    for (int y = 0; y < 3; y++) {
                        if (x != 0 || z != 0 || y == 2) {
                            level().setBlock(center.offset(x, y, z), Blocks.DIRT.defaultBlockState(), 2);
                        }
                    }
                }
            }
            wall[0] = center.offset(1, 1, 0);
            soulA = spawnSoul(0, 0);
            placePlayer(8, 0, LOOK_AWAY);
            soulA.startHunting(player());
        });
        until("wall broken", 500, () -> {
            soulA.startHunting(player());
            return level().getBlockState(wall[0]).isAir();
        });
        server("break check", () -> check("breaks_blocks", level().getBlockState(wall[0]).isAir(), "block=" + level().getBlockState(wall[0])));
    }

    private static void looting() {
        SoulLootGoal[] goal = new SoulLootGoal[1];
        BlockPos[] chest = new BlockPos[1];
        server("loot setup", () -> {
            removeAllSouls();
            clearAndFloor(36);
            chest[0] = center.offset(3, 0, 0);
            level().setBlock(chest[0], Blocks.CHEST.defaultBlockState(), 3);
            Container container = (Container) level().getBlockEntity(chest[0]);
            container.setItem(0, new ItemStack(Items.DIAMOND_SWORD));
            container.setItem(1, new ItemStack(Items.STONE_SWORD));
            container.setItem(2, new ItemStack(Items.DIAMOND_CHESTPLATE));
            container.setItem(3, new ItemStack(Items.LEATHER_CHESTPLATE));
            container.setItem(4, new ItemStack(Items.BOW));
            container.setItem(5, new ItemStack(Items.ARROW, 16));
            container.setItem(6, new ItemStack(Items.TOTEM_OF_UNDYING));
            container.setItem(7, new ItemStack(Items.DIRT, 32));
            container.setItem(8, new ItemStack(Items.STICK, 4));
            placePlayer(-30, 0, LOOK_AWAY);
            soulA = spawnSoul(0, 0);
            soulA.setNoAi(true);
            soulA.addLoot(new ItemStack(Items.IRON_SWORD));
            soulA.addLoot(new ItemStack(Items.IRON_CHESTPLATE));
            goal[0] = new SoulLootGoal(soulA);
            boolean found = false;
            for (int i = 0; i < 4000 && !found; i++) {
                found = goal[0].findContainer();
            }
            check("finds_chest_with_loot", found, "found=" + found);
            goal[0].start();
            soulA.moveTo(center.getX() + 2.0, center.getY(), center.getZ() + 0.5, 0f, 0f);
            for (int i = 0; i < 45; i++) {
                goal[0].tick();
            }
            goal[0].stop();
        });
        pause(5);
        server("loot check", () -> {
            Container container = (Container) level().getBlockEntity(chest[0]);
            check("steals_better_sword_and_equips_it", soulA.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.DIAMOND_SWORD), String.valueOf(soulA.getItemBySlot(EquipmentSlot.MAINHAND)));
            check("steals_better_armor_and_equips_it", soulA.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE), String.valueOf(soulA.getItemBySlot(EquipmentSlot.CHEST)));
            check("steals_bow_arrows_totem", soulA.carriedCount(Items.BOW) == 1 && soulA.carriedCount(Items.ARROW) == 16 && soulA.carriedCount(Items.TOTEM_OF_UNDYING) == 1,
                    "bow=" + soulA.carriedCount(Items.BOW) + " arrows=" + soulA.carriedCount(Items.ARROW) + " totems=" + soulA.carriedCount(Items.TOTEM_OF_UNDYING));
            check("leaves_useless_items", container.getItem(1).is(Items.STONE_SWORD) && container.getItem(3).is(Items.LEATHER_CHESTPLATE) && container.getItem(8).is(Items.STICK),
                    container.getItem(1) + " " + container.getItem(3) + " " + container.getItem(8));
            check("takes_blocks_to_build_with", soulA.carriedCount(Items.DIRT) == 32 && container.getItem(7).isEmpty(), "dirt=" + soulA.carriedCount(Items.DIRT));
            check("nothing_duplicated", container.getItem(0).isEmpty() && container.getItem(2).isEmpty() && container.getItem(6).isEmpty(), container.getItem(0) + " " + container.getItem(2));
            check("holds_totem_in_offhand", soulA.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.TOTEM_OF_UNDYING), String.valueOf(soulA.getItemBySlot(EquipmentSlot.OFFHAND)));
        });
    }

    private static void totems() {
        server("totem", () -> {
            removeAllSouls();
            clearAndFloor(36);
            player().getInventory().clearContent();
            player().getAbilities().invulnerable = false;
            player().setHealth(20f);
            placePlayer(0, 0, LOOK_AWAY);
            soulA = spawnSoul(1.5, 0);
            soulA.setNoAi(true);
            soulA.addLoot(new ItemStack(Items.TOTEM_OF_UNDYING));
            soulA.addLoot(new ItemStack(Items.TOTEM_OF_UNDYING));
            soulA.setHealth(0.5f);
            player().attack(soulA);
        });
        pause(10);
        server("totem check", () -> {
            check("totem_saves_soul", soulA.isAlive() && soulA.getHealth() > 0f, "alive=" + soulA.isAlive() + " health=" + soulA.getHealth());
            check("totem_used_up", soulA.carriedCount(Items.TOTEM_OF_UNDYING) == 1, "left=" + soulA.carriedCount(Items.TOTEM_OF_UNDYING));
            check("next_totem_ready", soulA.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.TOTEM_OF_UNDYING), String.valueOf(soulA.getItemBySlot(EquipmentSlot.OFFHAND)));
            player().getAbilities().invulnerable = true;
        });
    }

    private static void stealing() {
        server("steal", () -> {
            removeAllSouls();
            clearAndFloor(36);
            ServerPlayer p = player();
            p.getInventory().clearContent();
            p.getInventory().selected = 0;
            p.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
            p.getInventory().setItem(20, new ItemStack(Items.DIAMOND_SWORD));
            p.getInventory().setItem(21, new ItemStack(Items.DIRT, 3));
            p.getInventory().armor.set(2, new ItemStack(Items.IRON_CHESTPLATE));
            placePlayer(0, 0, LOOK_AWAY);
            soulA = spawnSoul(2, 0);
            soulA.setNoAi(true);
            SoulStealGoal goal = new SoulStealGoal(soulA);
            boolean took = goal.steal();
            check("steals_from_bag", took && soulA.carriedCount(Items.DIAMOND_SWORD) == 1 && p.getInventory().getItem(20).isEmpty(),
                    "took=" + took + " slot20=" + p.getInventory().getItem(20));
            check("takes_most_useful_first", soulA.getItemBySlot(EquipmentSlot.MAINHAND).is(Items.DIAMOND_SWORD), String.valueOf(soulA.getItemBySlot(EquipmentSlot.MAINHAND)));
            check("leaves_held_item", p.getMainHandItem().is(Items.IRON_PICKAXE), String.valueOf(p.getMainHandItem()));
            check("leaves_worn_armor", p.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE), String.valueOf(p.getItemBySlot(EquipmentSlot.CHEST)));
            check("vanishes_after_theft", soulA.distanceToSqr(p) > 15.0 * 15.0, "dist=" + (int) Math.sqrt(soulA.distanceToSqr(p)));
            p.getInventory().clearContent();
        });
    }

    private static void surpriseStrike() {
        double[] healthBefore = new double[1];
        server("strike setup", () -> {
            removeAllSouls();
            clearAndFloor(36);
            ServerPlayer p = player();
            p.getAbilities().invulnerable = false;
            p.setHealth(20f);
            healthBefore[0] = p.getHealth();
            placePlayer(0, 0, LOOK_AT_SOULS);
            soulA = spawnSoul(7, 0);
        });
        until("strike lands", 200, () -> player().getHealth() < healthBefore[0] && soulA.getMode() != SoulEntity.Mode.STRIKE);
        server("strike check", () -> {
            check("caught_soul_strikes_once", player().getHealth() < healthBefore[0], "health=" + player().getHealth());
            check("then_teleports_away", soulA.distanceToSqr(player()) > 15.0 * 15.0, "dist=" + (int) Math.sqrt(soulA.distanceToSqr(player())));
            check("not_hunting_after_strike", soulA.getMode() == SoulEntity.Mode.STALK, "mode=" + soulA.getMode());
            player().getAbilities().invulnerable = true;
            player().setHealth(20f);
        });
    }

    private static void following() {
        server("follow", () -> {
            removeAllSouls();
            clearAndFloor(36);
            placePlayer(-30, 0, LOOK_AWAY);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    for (int y = 0; y < 4; y++) {
                        if (x != 0 || z != 0 || y >= 2) {
                            level().setBlock(center.offset(-30 + x, y, z), Blocks.STONE.defaultBlockState(), 2);
                        }
                    }
                }
            }
            soulA = spawnSoul(30, 0);
            double before = Math.sqrt(soulA.distanceToSqr(player()));
            try {
                Method follow = SoulEntity.class.getDeclaredMethod("followOwner", net.minecraft.world.level.Level.class);
                follow.setAccessible(true);
                for (int i = 0; i < 8 && soulA.distanceToSqr(player()) > 40.0 * 40.0; i++) {
                    follow.invoke(soulA, level());
                }
                double after = Math.sqrt(soulA.distanceToSqr(player()));
                check("follows_owner", after < 40.0, "before=" + (int) before + " after=" + (int) after);
                soulA.teleportTo(center.getX() + 30.5, center.getY(), center.getZ() + 0.5);
                follow.invoke(soulA, level());
                double medium = Math.sqrt(soulA.distanceToSqr(player()));
                check("follow_waits_at_medium_range", medium > 40.0, "distance=" + (int) medium);
                soulA.teleportTo(center.getX() + 90.5, center.getY(), center.getZ() + 0.5);
                for (int i = 0; i < 8 && soulA.distanceToSqr(player()) > 40.0 * 40.0; i++) {
                    follow.invoke(soulA, level());
                }
                double far = Math.sqrt(soulA.distanceToSqr(player()));
                check("follow_ignores_cooldown_when_far", far < 40.0, "distance=" + (int) far);
            } catch (ReflectiveOperationException e) {
                check("follow_called", false, e.toString());
            }
        });
    }

    private static void killingGivesItemsBack() {
        server("kill", () -> {
            removeAllSouls();
            removeGroundItems();
            clearAndFloor(36);
            placePlayer(-10, 0, LOOK_AWAY);
            giveKit();
            List<ItemStack> kit = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                kit.add(player().getInventory().getItem(i).copy());
            }
            player().getInventory().clearContent();
            soulA = SoulEntity.fromPlayer(player(), kit);
            soulA.moveTo(center.getX() + 0.5, center.getY(), center.getZ() + 0.5, 0f, 0f);
            level().addFreshEntity(soulA);
            soulA.kill();
        });
        pause(30);
        server("kill check", () -> {
            int count = 0;
            for (ItemEntity item : groundItems(center)) {
                count += item.getItem().getCount();
            }
            check("drops_exact_items_when_killed", count == 7, "items=" + count + " (sword 1, chestplate 1, dirt 5)");
        });
    }

    private static void nightAndDay() {
        server("night setup", () -> {
            removeAllSouls();
            removeGroundItems();
            clearAndFloor(36);
            placePlayer(0, 0, LOOK_AWAY);
            AfterselfConfig.soulsOnlyAtNight = true;
            level().setDayTime(18000L);
            soulA = spawnSoul(10, 0);
            soulA.addLoot(new ItemStack(Items.DIAMOND_SWORD));
            soulA.addLoot(new ItemStack(Items.DIRT, 5));
        });
        pause(30);
        server("night check", () -> check("soul_stays_at_night", soulA.isAlive() && souls().size() == 1, "active=" + souls().size()));
        server("sunrise", () -> level().setDayTime(1000L));
        until("gone at sunrise", 100, () -> souls().isEmpty());
        server("day check", () -> {
            check("soul_vanishes_by_day", souls().isEmpty(), "active=" + souls().size());
            check("soul_waits_with_items", SoulStore.dormantCount(playerId) == 1, "dormant=" + SoulStore.dormantCount(playerId));
        });
        server("death by day", () -> {
            player().getAbilities().invulnerable = false;
            giveKit();
            player().kill();
        });
        pause(40);
        server("death by day check", () -> {
            check("no_soul_appears_by_day", souls().isEmpty(), "active=" + souls().size());
            check("day_death_soul_waits", SoulStore.dormantCount(playerId) == 2, "dormant=" + SoulStore.dormantCount(playerId));
            check("day_death_drops_nothing", groundItems(center).isEmpty(), "ground=" + groundItems(center).size());
            player().getAbilities().invulnerable = true;
            placePlayer(0, 0, LOOK_AWAY);
        });
        pause(20);
        server("nightfall", () -> level().setDayTime(18000L));
        until("back at night", 500, () -> souls().size() == 2);
        server("back check", () -> {
            List<SoulEntity> back = souls();
            check("souls_come_back_at_night", back.size() == 2 && SoulStore.dormantCount(playerId) == 0, "active=" + back.size() + " dormant=" + SoulStore.dormantCount(playerId));
            int swords = 0;
            int dirt = 0;
            for (SoulEntity soul : back) {
                swords += soul.carriedCount(Items.DIAMOND_SWORD);
                dirt += soul.carriedCount(Items.DIRT);
            }
            check("items_survive_the_day", swords == 2 && dirt == 10, "swords=" + swords + " dirt=" + dirt);
            boolean near = true;
            for (SoulEntity soul : back) {
                near &= soul.distanceToSqr(player()) < 48.0 * 48.0;
            }
            check("souls_return_near_owner", near, "near=" + near);
        });
        server("save and reload store", () -> {
            for (SoulEntity soul : souls()) {
                SoulStore.sendToSleep(soul);
            }
            SoulStore.save();
            SoulStore.load(srv());
            check("store_survives_restart", SoulStore.dormantCount(playerId) == 2, "dormant=" + SoulStore.dormantCount(playerId));
            removeAllSouls();
            AfterselfConfig.soulsOnlyAtNight = false;
        });
    }

    private static void merging() {
        server("merge", () -> {
            removeAllSouls();
            clearAndFloor(36);
            placePlayer(0, 0, LOOK_AT_SOULS);
            AfterselfConfig.maxSouls = 2;
            soulA = spawnSoul(8, -2);
            soulA.setNoAi(true);
            soulA.addLoot(new ItemStack(Items.DIAMOND_SWORD));
            soulB = spawnSoul(8, 2);
            soulB.setNoAi(true);
            soulB.addLoot(new ItemStack(Items.DIRT, 4));
            SoulEntity third = spawnSoul(8, 0);
            third.setNoAi(true);
            third.addLoot(new ItemStack(Items.IRON_CHESTPLATE));
            SoulStore.enforceLimit(playerId, level());
        });
        pause(10);
        server("merge check", () -> {
            List<SoulEntity> left = souls();
            check("over_limit_souls_merge", left.size() == 2, "souls=" + left.size());
            float biggest = 1f;
            double tallest = 0.0;
            double mostHealth = 0.0;
            int sword = 0;
            int dirt = 0;
            int plate = 0;
            for (SoulEntity soul : left) {
                biggest = Math.max(biggest, soul.getSoulScale());
                tallest = Math.max(tallest, soul.getBbHeight());
                mostHealth = Math.max(mostHealth, soul.getMaxHealth());
                sword += soul.carriedCount(Items.DIAMOND_SWORD);
                dirt += soul.carriedCount(Items.DIRT);
                plate += soul.carriedCount(Items.IRON_CHESTPLATE);
            }
            check("merged_soul_is_bigger", biggest > 1.1f && tallest > 1.9, "scale=" + biggest + " height=" + tallest);
            check("merged_soul_is_stronger", mostHealth > 30.0, "maxHealth=" + mostHealth);
            check("merge_loses_no_item", sword == 1 && dirt == 4 && plate == 1, "sword=" + sword + " dirt=" + dirt + " plate=" + plate);
            AfterselfConfig.maxSouls = 10;
        });
        pause(10);
        client("shot merged", () -> shot("merged_souls"));
    }

    private static void consentAndVoice() {
        server("consent", () -> {
            removeGroundItems();
            VoiceMemory.forget(playerId);
            ConsentStore.set(playerId, false);
            var source = player().createCommandSourceStack();
            srv().getCommands().performPrefixedCommand(source, "afterself voice accept");
            check("command_accepts", ConsentStore.accepted(playerId), "accepted=" + ConsentStore.accepted(playerId));
            srv().getCommands().performPrefixedCommand(source, "afterself voice refuse");
            VoiceMemory.endPhrase(playerId);
            check("command_refuses_and_erases", !ConsentStore.accepted(playerId) && VoiceMemory.count(playerId) == 0,
                    "accepted=" + ConsentStore.accepted(playerId) + " clips=" + VoiceMemory.count(playerId));
        });
        server("voice", AfterselfTestRunner::voiceTest);
        pause(100);
        server("voice end", () -> check("voice_clip_played_without_error", AfterselfTestVoice.lastError == null, String.valueOf(AfterselfTestVoice.lastError)));

        // End to end, over the real voice network: microphone packets in, audio from the soul out.
        client("finish voice setup", FakeMicrophone::finishFirstLaunchScreen);
        pause(40);
        untilClient("voice connection", 600, FakeMicrophone::connected);
        byte[][][] frames = new byte[1][][];
        server("network voice setup", () -> {
            check("voice_network_connected", FakeMicrophone.connected(), "connected=" + FakeMicrophone.connected());
            removeAllSouls();
            clearAndFloor(36);
            placePlayer(0, 0, LOOK_AWAY);
            soulA = spawnSoul(14, 0);
            VoiceMemory.forget(playerId);
            ConsentStore.set(playerId, true);
            frames[0] = encodeTone(75);
            AfterselfTestVoice.micPackets = 0;
        });
        pause(5);
        client("speak", () -> FakeMicrophone.speak(frames[0]));
        // 1.5 s of speech, then a pause so the phrase closes.
        pause(70);
        server("network voice check", () -> {
            check("network_mic_packets_reach_server", AfterselfTestVoice.micPackets >= 60, "packets=" + AfterselfTestVoice.micPackets + " of 75");
            check("network_voice_stored_as_clip", VoiceMemory.count(playerId) >= 1, "clips=" + VoiceMemory.count(playerId));
            check("network_voice_alerts_soul", soulA.heardRecently(200), "heard=" + soulA.heardRecently(200));
        });

        // The debug command, through the real command system, then measured at the client.
        server("speak command", () -> {
            soulA.setNoAi(true);
            soulA.setTarget(null);
            soulA.moveTo(center.getX() + 4.5, center.getY(), center.getZ() + 0.5, 90f, 0f);
            AfterselfTestVoice.listenTo = soulA.getUUID();
            AfterselfTestVoice.framesFromEntity = 0;
            AfterselfTestVoice.loudestSample = 0;
            AfterselfTestVoice.serverEntityFrames = 0;
            srv().getCommands().performPrefixedCommand(player().createCommandSourceStack().withPermission(4), "afterself debug speak");
        });
        // 2 s of playback, slowed down, plus the echo.
        pause(70);
        client("hear soul", () -> {
            System.out.println("AFTERSELFTEST INFO voice path: server sent " + AfterselfTestVoice.serverEntityFrames
                    + " frames, client events " + AfterselfTestVoice.clientEvents + ", client frames with audio " + AfterselfTestVoice.framesFromEntity);
            check("debug_speak_command_makes_soul_talk", AfterselfTestVoice.serverEntityFrames >= 60, "server frames=" + AfterselfTestVoice.serverEntityFrames);
            check("client_hears_soul_voice", AfterselfTestVoice.framesFromEntity >= 60, "frames=" + AfterselfTestVoice.framesFromEntity);
            check("heard_voice_is_not_silence", AfterselfTestVoice.loudestSample > 1000, "loudest=" + AfterselfTestVoice.loudestSample);
            check("voice_is_slowed_down", AfterselfTestVoice.framesFromEntity >= 76, "frames=" + AfterselfTestVoice.framesFromEntity + " from 75 spoken");
        });

        // Clips live on disk now: "wesh c'était il y a 5 mille ans".
        server("disk", () -> {
            AfterselfTestVoice.listenTo = null;
            // Under load the phrase can still be open: close it first, or its file is written after the count.
            VoiceMemory.endPhrase(playerId);
            VoiceMemory.flushDisk();
            Path folder = srv().getWorldPath(LevelResource.ROOT).resolve("afterself_voice").resolve(playerId.toString());
            int before = VoiceMemory.count(playerId);
            VoiceMemory.forgetMemoryOnly(playerId);
            int after = VoiceMemory.count(playerId);
            check("clips_are_on_disk", Files.isDirectory(folder), String.valueOf(folder));
            check("clips_reload_from_disk", before >= 1 && after == before, "before=" + before + " after=" + after);
            ConsentStore.set(playerId, false);
            VoiceMemory.flushDisk();
            check("refusing_erases_disk", !Files.exists(folder) && VoiceMemory.count(playerId) == 0, "exists=" + Files.exists(folder));
            ConsentStore.set(playerId, true);
            VoiceMemory.frame(playerId, new byte[] {1}, System.currentTimeMillis());
            VoiceMemory.forget(playerId);
            VoiceMemory.flushDisk();
            check("forget_command_path_erases", VoiceMemory.count(playerId) == 0, "clips=" + VoiceMemory.count(playerId));
            ConsentStore.set(playerId, false);
        });
    }

    private static byte[][] encodeTone(int count) {
        OpusEncoder encoder = AfterselfTestVoice.api.createEncoder();
        short[] tone = new short[960];
        for (int i = 0; i < tone.length; i++) {
            tone[i] = (short) (Math.sin(i * 0.12) * 12000);
        }
        byte[][] encoded = new byte[count][];
        for (int i = 0; i < count; i++) {
            encoded[i] = encoder.encode(tone);
        }
        encoder.close();
        return encoded;
    }

    private static void voiceTest() {
        if (AfterselfTestVoice.api == null || Afterself.voiceOutput == null) {
            check("voice_plugin_started", false, "api=" + AfterselfTestVoice.api + " output=" + Afterself.voiceOutput);
            return;
        }
        check("voice_plugin_started", true, "voice server running");
        removeAllSouls();
        clearAndFloor(36);
        placePlayer(0, 0, LOOK_AWAY);
        soulA = spawnSoul(14, 0);
        AfterselfPlugin plugin = new AfterselfPlugin();
        OpusDecoder decoder = AfterselfTestVoice.api.createDecoder();
        byte[][] frames = encodeTone(60);

        // Without consent the noise is heard, but nothing is stored.
        ConsentStore.set(playerId, false);
        for (int i = 0; i < 40; i++) {
            plugin.handleFrame(player(), frames[i], false, decoder);
        }
        VoiceMemory.endPhrase(playerId);
        check("no_clip_without_consent", VoiceMemory.count(playerId) == 0, "clips=" + VoiceMemory.count(playerId));

        // With consent, one phrase is kept.
        ConsentStore.set(playerId, true);
        for (byte[] frame : frames) {
            plugin.handleFrame(player(), frame, false, decoder);
        }
        VoiceMemory.endPhrase(playerId);
        check("clip_stored_with_consent", VoiceMemory.count(playerId) == 1, "clips=" + VoiceMemory.count(playerId));
        decoder.close();

        List<byte[]> clip = VoiceMemory.pick(playerId);
        try {
            Afterself.voiceOutput.play(soulA, clip, AfterselfConfig.voicePitch);
            AfterselfTestVoice.lastError = null;
        } catch (Throwable t) {
            t.printStackTrace();
            AfterselfTestVoice.lastError = t;
        }
        // The noise reaches souls through server.execute, which runs right after this task.
        srv().execute(() -> check("voice_noise_is_heard", soulA.heardRecently(40), "heard=" + soulA.heardRecently(40)));
    }

    private static void lureAndPossession() {
        // Close and in daylight first, only to see the scenes: one screenshot each.
        SoulEntity.LureScene[] scenes = {SoulEntity.LureScene.WAVE, SoulEntity.LureScene.KNEEL, SoulEntity.LureScene.LIE, SoulEntity.LureScene.STARE,
                SoulEntity.LureScene.BECKON, SoulEntity.LureScene.CRY, SoulEntity.LureScene.BACK,
                SoulEntity.LureScene.WATCH, SoulEntity.LureScene.LOOM, SoulEntity.LureScene.EAT};
        for (SoulEntity.LureScene scene : scenes) {
            server("scene " + scene, () -> {
                removeAllSouls();
                clearAndFloor(36);
                level().setDayTime(6000L);
                placePlayer(0, 0, LOOK_AT_SOULS);
                soulA = spawnSoul(4, 0);
                soulA.setNoAi(true);
                // Through startLure: otherwise it sees itself being watched this close, strikes and drops the scene.
                soulA.startLure(player(), scene);
                soulA.setYHeadRot(90f);
                soulA.setYBodyRot(90f);
            });
            pause(30);
            client("scene shot " + scene, () -> shot("scene_" + scene.animation));
        }
        server("disguise", () -> {
            String name = player().getGameProfile().getName();
            soulA.startLure(player(), SoulEntity.LureScene.MIMIC);
            check("lure_wears_the_living_players_name", soulA.isDisguised() && soulA.getCustomName() != null && soulA.getCustomName().getString().equals(name),
                    "disguised=" + soulA.isDisguised() + " name=" + (soulA.getCustomName() == null ? null : soulA.getCustomName().getString()));
        });
        pause(10);
        client("disguise look", () -> {
            SoulEntity client = soulClientSide(Minecraft.getInstance());
            check("disguise_reaches_client", client != null && client.isDisguised(), client == null ? "no client soul" : "disguised=" + client.isDisguised());
            shot("disguise_mimic");
        });
        server("disguise off", () -> {
            String name = player().getGameProfile().getName();
            soulA.returnToStalking();
            check("soul_looks_dead_again_after", !soulA.isDisguised() && soulA.getCustomName() != null && !soulA.getCustomName().getString().equals(name),
                    "disguised=" + soulA.isDisguised());
            check("building_blocks_are_plain_full_blocks", SoulGear.isBuildingBlock(new ItemStack(Items.COBBLESTONE)) && !SoulGear.isBuildingBlock(new ItemStack(Items.SAND))
                    && !SoulGear.isBuildingBlock(new ItemStack(Items.CHEST)) && !SoulGear.isBuildingBlock(new ItemStack(Items.TORCH)), "cobble yes, sand chest torch no");
        });
        server("lure", () -> {
            removeAllSouls();
            clearAndFloor(36);
            ServerPlayer p = player();
            p.getInventory().clearContent();
            p.getAbilities().invulnerable = false;
            p.setHealth(20f);
            placePlayer(0, 0, LOOK_AT_SOULS);
            soulA = spawnSoul(25, 0);
            soulA.startLure(p, SoulEntity.LureScene.WAVE);
        });
        pause(40);
        client("lure look", () -> {
            SoulEntity client = soulClientSide(Minecraft.getInstance());
            check("lure_scene_reaches_client", client != null && SoulEntity.LureScene.WAVE.animation.equals(SoulAnimations.nameFor(client)),
                    client == null ? "no client soul" : SoulAnimations.nameFor(client));
        });
        server("lure holds", () -> check("lure_waits_for_player", soulA.getMode() == SoulEntity.Mode.LURE && soulA.distanceToSqr(player()) > 20.0 * 20.0, "mode=" + soulA.getMode()));
        server("walk into trap", () -> placePlayer(21, 0, LOOK_AT_SOULS));
        until("trap springs", 80, () -> soulA.getMode() == SoulEntity.Mode.TRAP);
        server("trap check", () -> check("coming_close_springs_trap", soulA.isTrapActive(), "mode=" + soulA.getMode()));

        // The killing blow takes the body.
        int[] screamsBeforePossession = new int[1];
        client("screams before possession", () -> screamsBeforePossession[0] = ScreamerOverlay.screamsShown);
        server("fatal blow", () -> {
            ServerPlayer p = player();
            SoulEntity.forgetScreamers(p);
            p.setHealth(1f);
            soulA.doHurtTarget(p);
        });
        pause(10);
        server("possession check", () -> {
            ServerPlayer p = player();
            check("trap_kill_possesses_instead", Possession.isPossessed(playerId) && p.isAlive(), "possessed=" + Possession.isPossessed(playerId) + " alive=" + p.isAlive());
            check("body_gets_full_health", p.getHealth() >= p.getMaxHealth() - 1f, "health=" + p.getHealth());
            check("possessed_chat_is_cut", GameEvents.onPlayerChat(p), "cancelled=" + GameEvents.onPlayerChat(p));
            check("possessed_commands_are_cut", GameEvents.onCommand(p.createCommandSourceStack().withPermission(0)), "cancelled");
            check("admins_keep_commands", !GameEvents.onCommand(p.createCommandSourceStack().withPermission(2)), "kept");
        });
        untilClient("client takes control", 120, PossessionClient::isActive);
        client("possession scream", () -> check("possession_starts_with_one_screamer", ScreamerOverlay.screamsShown == screamsBeforePossession[0] + 1,
                "shown=" + (ScreamerOverlay.screamsShown - screamsBeforePossession[0])));
        client("client control", () -> check("client_goes_on_autopilot", PossessionClient.isActive(), "active=" + PossessionClient.isActive()));

        // The real keys do nothing.
        double[] start = new double[3];
        client("press keys", () -> {
            Minecraft mc = Minecraft.getInstance();
            KeyMapping.click(mc.options.keyInventory.getDefaultKey());
            KeyMapping.click(mc.options.keyChat.getDefaultKey());
            start[0] = mc.player.getX();
            start[1] = mc.player.getZ();
        });
        pause(5);
        client("keys check", () -> check("keys_open_nothing", Minecraft.getInstance().screen == null, "screen=" + Minecraft.getInstance().screen));
        byte[][][] frames = new byte[1][][];
        server("muted setup", () -> {
            frames[0] = encodeTone(30);
            AfterselfTestVoice.micPackets = 0;
            ConsentStore.set(playerId, true);
            VoiceMemory.forget(playerId);
        });
        pause(2);
        client("muted speech", () -> FakeMicrophone.speak(frames[0]));
        pause(60);
        server("muted check", () -> {
            VoiceMemory.endPhrase(playerId);
            check("possessed_voice_is_cut", VoiceMemory.count(playerId) == 0, "clips=" + VoiceMemory.count(playerId) + " packets=" + AfterselfTestVoice.micPackets);
            ConsentStore.set(playerId, false);
        });
        pause(60);
        client("body moved", () -> {
            Minecraft mc = Minecraft.getInstance();
            double moved = Math.abs(mc.player.getX() - start[0]) + Math.abs(mc.player.getZ() - start[1]);
            check("body_walks_by_itself", moved > 1.0, "moved=" + moved);
            shot("possessed_view");
        });

        // Way out number one: a totem.
        SoulEntity[] attacker = new SoulEntity[1];
        server("totem out", () -> {
            ServerPlayer p = player();
            p.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        });
        pause(5);
        server("totem blow", () -> {
            ServerPlayer p = player();
            // Back to the middle: the body has been walking around, maybe to the edge of an arena high in the air,
            // and the blow knocks it back.
            placePlayer(0, 0, LOOK_AWAY);
            p.setHealth(0.5f);
            attacker[0] = spawnSoul(p.getX() - center.getX() + 1.0, p.getZ() - center.getZ());
            attacker[0].setNoAi(true);
            attacker[0].doHurtTarget(p);
        });
        pause(20);
        server("totem out check", () -> {
            check("totem_frees_the_player", !Possession.isPossessed(playerId) && player().isAlive(), "possessed=" + Possession.isPossessed(playerId));
            attacker[0].discard();
        });
        untilClient("client lets go", 120, () -> !PossessionClient.isActive());
        client("client released", () -> check("client_gets_control_back", !PossessionClient.isActive(), "active=" + PossessionClient.isActive()));

        // Way out number two: dying for real.
        server("second possession", () -> {
            removeAllSouls();
            ServerPlayer p = player();
            p.setHealth(20f);
            placePlayer(0, 0, LOOK_AWAY);
            soulA = spawnSoul(4, 0);
            Possession.begin(p, soulA);
        });
        untilClient("client takes control again", 120, PossessionClient::isActive);
        server("real death", () -> player().kill());
        pause(40);
        server("real death check", () -> check("death_frees_the_player", !Possession.isPossessed(playerId), "possessed=" + Possession.isPossessed(playerId)));
        pause(20);

        // The safety nets: the time limit and the admin command.
        server("third possession", () -> {
            removeAllSouls();
            ServerPlayer p = player();
            p.setHealth(20f);
            placePlayer(0, 0, LOOK_AWAY);
            soulA = spawnSoul(4, 0);
            AfterselfConfig.possessionMaxSeconds = 1;
            Possession.begin(p, soulA);
        });
        pause(60);
        server("limit check", () -> {
            check("time_limit_frees_the_player", !Possession.isPossessed(playerId), "possessed=" + Possession.isPossessed(playerId));
            AfterselfConfig.possessionMaxSeconds = 0;
            removeAllSouls();
            soulA = spawnSoul(4, 0);
            Possession.begin(player(), soulA);
            srv().getCommands().performPrefixedCommand(player().createCommandSourceStack().withPermission(4), "afterself debug release");
            check("admin_command_frees_the_player", !Possession.isPossessed(playerId), "possessed=" + Possession.isPossessed(playerId));
            AfterselfConfig.possessionMaxSeconds = 600;
            player().getAbilities().invulnerable = true;
        });
    }
}
