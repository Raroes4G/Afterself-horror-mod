package com.afterself.entity;

import com.afterself.Compat;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/** How a soul judges items: only what helps it reach and kill players is worth stealing. */
public final class SoulGear {
    private static final int MAX_TOTEMS = 3;
    private static final int MAX_ARROWS = 64;
    private static final int MAX_PEARLS = 8;
    private static final int MAX_FOOD = 16;
    public static final int MAX_BLOCKS = 64;

    private SoulGear() {}

    /** 0 means not a melee weapon. */
    public static float meleeScore(ItemStack stack) {
        float base;
        if (stack.getItem() instanceof SwordItem) {
            base = 4f;
        } else if (stack.getItem() instanceof TridentItem) {
            base = 8f;
        } else if (stack.getItem() instanceof AxeItem) {
            base = 3.5f;
        } else if (stack.getItem() instanceof TieredItem) {
            base = 0.5f;
        } else {
            return 0f;
        }
        float tier = stack.getItem() instanceof TieredItem tiered ? tiered.getTier().getAttackDamageBonus() : 0f;
        return base + tier + (stack.isEnchanted() ? 1f : 0f);
    }

    /** 0 means not armor. */
    public static float armorScore(ItemStack stack) {
        if (stack.getItem() instanceof ArmorItem armor) {
            return armor.getDefense() + armor.getToughness() + (stack.isEnchanted() ? 1f : 0f);
        }
        return 0f;
    }

    public static boolean isBow(ItemStack stack) {
        return stack.getItem() instanceof BowItem;
    }

    public static boolean isArrow(ItemStack stack) {
        return stack.is(Items.ARROW);
    }

    public static boolean isTotem(ItemStack stack) {
        return stack.is(Items.TOTEM_OF_UNDYING);
    }

    public static boolean isShield(ItemStack stack) {
        return stack.getItem() instanceof ShieldItem;
    }

    public static boolean isPearl(ItemStack stack) {
        return stack.is(Items.ENDER_PEARL);
    }

    public static boolean isFood(ItemStack stack) {
        return !stack.isEmpty() && Compat.foodValue(stack) > 0;
    }

    /** Plain full blocks a player would bridge or pillar with. */
    public static boolean isBuildingBlock(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && isBuildingBlock(item.getBlock());
    }

    public static boolean isBuildingBlock(Block block) {
        if (block instanceof EntityBlock || block instanceof FallingBlock || block instanceof LeavesBlock
                || block == Blocks.TNT || block.asItem() == Items.AIR) {
            return false;
        }
        BlockState state = block.defaultBlockState();
        return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                && state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) >= 0f;
    }

    /** Is this better than everything of its kind the soul already has? */
    public static boolean isWorthTaking(ItemStack stack, List<ItemStack> carried) {
        if (stack.isEmpty()) {
            return false;
        }
        if (isTotem(stack)) {
            return count(carried, SoulGear::isTotem) < MAX_TOTEMS;
        }
        if (isArrow(stack)) {
            return count(carried, SoulGear::isArrow) < MAX_ARROWS;
        }
        if (isPearl(stack)) {
            return count(carried, SoulGear::isPearl) < MAX_PEARLS;
        }
        if (isBow(stack)) {
            return carried.stream().noneMatch(SoulGear::isBow);
        }
        if (isShield(stack)) {
            return carried.stream().noneMatch(SoulGear::isShield);
        }
        float melee = meleeScore(stack);
        if (melee > 0f) {
            return melee > carried.stream().map(SoulGear::meleeScore).max(Float::compare).orElse(0f);
        }
        if (stack.getItem() instanceof ArmorItem armor) {
            EquipmentSlot slot = Compat.armorSlot(armor);
            float best = 0f;
            for (ItemStack other : carried) {
                if (other.getItem() instanceof ArmorItem otherArmor && Compat.armorSlot(otherArmor) == slot) {
                    best = Math.max(best, armorScore(other));
                }
            }
            return armorScore(stack) > best;
        }
        if (isBuildingBlock(stack)) {
            return count(carried, SoulGear::isBuildingBlock) < MAX_BLOCKS;
        }
        if (isFood(stack)) {
            return count(carried, SoulGear::isFood) < MAX_FOOD;
        }
        return false;
    }

    /** What a thief grabs from a player's bag: gear first, then anything valuable-looking. */
    public static float theftValue(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0f;
        }
        return 1f + meleeScore(stack) + armorScore(stack) + (isTotem(stack) ? 20f : 0f) + (isBow(stack) ? 6f : 0f)
                + (isPearl(stack) ? 4f : 0f) + (isShield(stack) ? 3f : 0f) + (stack.isEnchanted() ? 2f : 0f);
    }

    public static int count(List<ItemStack> stacks, Predicate<ItemStack> kind) {
        int total = 0;
        for (ItemStack stack : stacks) {
            if (kind.test(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }
}
