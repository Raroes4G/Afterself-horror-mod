package com.afterself.entity.goal;

import com.afterself.Compat;
import com.afterself.AfterselfConfig;
import com.afterself.entity.SoulEntity;
import com.afterself.entity.SoulGear;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Walks to a chest or barrel while nobody is watching, opens it, and takes what helps it kill: better weapons, better
 * armor, bows and arrows, totems. It equips anything better at once, and leaves the rest.
 */
public class SoulLootGoal extends Goal {
    private static final int OPEN_TICKS = 40;
    private static final int SEARCH_RANGE = 16;
    private static final int SEARCH_TRIES = 120;
    private final SoulEntity soul;
    private BlockPos container;
    private int openFor;
    private int giveUp;
    private int nextSearchAt;

    public SoulLootGoal(SoulEntity soul) {
        this.soul = soul;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!AfterselfConfig.soulsLootContainers || soul.getMode() != SoulEntity.Mode.STALK || soul.tickCount < nextSearchAt) {
            return false;
        }
        nextSearchAt = soul.tickCount + 200;
        return findContainer();
    }

    /** Public so tests can skip the wait. */
    public boolean findContainer() {
        Level level = Compat.level(soul);
        BlockPos origin = soul.blockPosition();
        for (int i = 0; i < SEARCH_TRIES; i++) {
            BlockPos pos = origin.offset(soul.getRandom().nextInt(SEARCH_RANGE * 2 + 1) - SEARCH_RANGE,
                    soul.getRandom().nextInt(9) - 4, soul.getRandom().nextInt(SEARCH_RANGE * 2 + 1) - SEARCH_RANGE);
            BlockState state = level.getBlockState(pos);
            if ((state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock) && hasSomethingWorthTaking(pos)) {
                container = pos;
                return true;
            }
        }
        return false;
    }

    private Container containerAt(BlockPos pos) {
        Level level = Compat.level(soul);
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity instanceof Container found) {
            // Chests from structures only get their items once opened.
            Compat.unpackLoot(entity);
            return found;
        }
        return null;
    }

    private boolean hasSomethingWorthTaking(BlockPos pos) {
        Container found = containerAt(pos);
        if (found == null) {
            return false;
        }
        for (int slot = 0; slot < found.getContainerSize(); slot++) {
            if (SoulGear.isWorthTaking(found.getItem(slot), soul.getCarried())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return container != null && soul.getMode() == SoulEntity.Mode.LOOT && giveUp > 0;
    }

    @Override
    public void start() {
        soul.setMode(SoulEntity.Mode.LOOT, 0);
        openFor = 0;
        giveUp = 400;
        soul.getNavigation().moveTo(container.getX() + 0.5, container.getY(), container.getZ() + 0.5, 0.9);
    }

    @Override
    public void tick() {
        giveUp--;
        if (container == null) {
            return;
        }
        soul.getLookControl().setLookAt(container.getX() + 0.5, container.getY() + 0.5, container.getZ() + 0.5);
        if (openFor > 0) {
            if (--openFor == 0) {
                takeLoot();
                setOpen(false);
                container = null;
            }
            return;
        }
        if (soul.distanceToSqr(container.getX() + 0.5, container.getY() + 0.5, container.getZ() + 0.5) < 6.25) {
            soul.getNavigation().stop();
            openFor = OPEN_TICKS;
            setOpen(true);
        }
    }

    private void takeLoot() {
        Container found = containerAt(container);
        if (found == null) {
            return;
        }
        boolean took = false;
        for (int slot = 0; slot < found.getContainerSize(); slot++) {
            ItemStack stack = found.getItem(slot);
            if (SoulGear.isWorthTaking(stack, soul.getCarried())) {
                soul.addLoot(found.removeItemNoUpdate(slot));
                took = true;
            }
        }
        if (took) {
            found.setChanged();
        }
    }

    @Override
    public void stop() {
        if (container != null && openFor > 0) {
            setOpen(false);
        }
        openFor = 0;
        container = null;
        if (soul.getMode() == SoulEntity.Mode.LOOT) {
            soul.setMode(SoulEntity.Mode.STALK, 0);
        }
    }

    private void setOpen(boolean open) {
        Level level = Compat.level(soul);
        BlockState state = level.getBlockState(container);
        if (state.getBlock() instanceof ChestBlock) {
            level.blockEvent(container, state.getBlock(), 1, open ? 1 : 0);
            level.playSound(null, container, open ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.25f, 0.8f);
        } else if (state.getBlock() instanceof BarrelBlock) {
            level.setBlock(container, state.setValue(BarrelBlock.OPEN, open), 3);
            level.playSound(null, container, open ? SoundEvents.BARREL_OPEN : SoundEvents.BARREL_CLOSE, SoundSource.BLOCKS, 0.25f, 0.8f);
        }
    }
}
