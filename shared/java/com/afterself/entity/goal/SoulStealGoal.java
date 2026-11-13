package com.afterself.entity.goal;

import com.afterself.AfterselfConfig;
import com.afterself.entity.SoulEntity;
import com.afterself.entity.SoulGear;
import java.util.EnumSet;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Crouches up behind a player who is not looking, lifts one item from their bag, and is gone. Being spotted on the way
 * turns into a strike (decided in SoulEntity). It never takes what is held or worn: the player notices later.
 */
public class SoulStealGoal extends Goal {
    private static final double REACH = 2.6;
    private static final int COOLDOWN_TICKS = 3600;
    private static final int APPROACH_TICKS = 400;
    private final SoulEntity soul;
    private Player victim;
    private int giveUp;
    private int nextTheftAt = 1200;

    public SoulStealGoal(SoulEntity soul) {
        this.soul = soul;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!AfterselfConfig.soulsStealFromPlayers || soul.getMode() != SoulEntity.Mode.STALK || soul.tickCount < nextTheftAt) {
            return false;
        }
        Player player = soul.getStalkedPlayer();
        if (player == null || soul.isSeenBy(player) || soul.distanceToSqr(player) > 40.0 * 40.0 || bestSlot(player) < 0) {
            return false;
        }
        victim = player;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return victim != null && victim.isAlive() && soul.getMode() == SoulEntity.Mode.STEAL && giveUp > 0;
    }

    @Override
    public void start() {
        soul.setMode(SoulEntity.Mode.STEAL, 0);
        soul.setPose(Pose.CROUCHING);
        soul.setSprinting(false);
        giveUp = APPROACH_TICKS;
    }

    @Override
    public void tick() {
        giveUp--;
        if (victim == null) {
            return;
        }
        soul.getLookControl().setLookAt(victim.getX(), victim.getEyeY(), victim.getZ());
        if (soul.distanceToSqr(victim) <= REACH * REACH) {
            steal();
            return;
        }
        if (soul.tickCount % 10 == 0) {
            soul.getNavigation().moveTo(victim, 0.75);
        }
    }

    /** Public so tests can skip the walk. Returns whether something was taken. */
    public boolean steal() {
        Player target = victim != null ? victim : soul.getStalkedPlayer();
        if (target == null) {
            return false;
        }
        int slot = bestSlot(target);
        nextTheftAt = soul.tickCount + COOLDOWN_TICKS;
        if (slot < 0) {
            finish();
            return false;
        }
        Inventory inventory = target.getInventory();
        ItemStack stolen = inventory.removeItemNoUpdate(slot);
        inventory.setChanged();
        soul.addLoot(stolen);
        finish();
        soul.vanish();
        return true;
    }

    private void finish() {
        victim = null;
        if (soul.getMode() == SoulEntity.Mode.STEAL) {
            soul.setMode(SoulEntity.Mode.STALK, 0);
        }
    }

    /** Only the bag: the hotbar slot in hand and the armor slots are left alone. */
    private int bestSlot(Player player) {
        Inventory inventory = player.getInventory();
        int best = -1;
        float bestValue = 0f;
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            if (slot == inventory.selected) {
                continue;
            }
            float value = SoulGear.theftValue(inventory.items.get(slot));
            if (value > bestValue) {
                best = slot;
                bestValue = value;
            }
        }
        return best;
    }

    @Override
    public void stop() {
        victim = null;
        if (soul.getPose() == Pose.CROUCHING) {
            soul.setPose(Pose.STANDING);
        }
        if (soul.getMode() == SoulEntity.Mode.STEAL) {
            soul.setMode(SoulEntity.Mode.STALK, 0);
        }
    }
}
