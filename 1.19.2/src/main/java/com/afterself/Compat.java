package com.afterself;

import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;

/** What differs in Minecraft 1.19.2. */
public final class Compat {
    private Compat() {}

    public static ResourceLocation id(String path) {
        return new ResourceLocation(Afterself.MOD_ID, path);
    }

    public static Level level(Entity entity) {
        return entity.level;
    }

    public static EquipmentSlot armorSlot(ArmorItem armor) {
        return armor.getSlot();
    }

    public static SoundEvent soulEscapeSound() {
        return SoundEvents.SOUL_ESCAPE;
    }

    public static CompoundTag saveItem(ItemStack stack, Level level) {
        return stack.save(new CompoundTag());
    }

    public static ItemStack loadItem(CompoundTag tag, Level level) {
        return ItemStack.of(tag);
    }

    public static CompoundTag readNbt(Path file) throws IOException {
        return NbtIo.readCompressed(file.toFile());
    }

    public static void writeNbt(CompoundTag tag, Path file) throws IOException {
        NbtIo.writeCompressed(tag, file.toFile());
    }

    public static SoundEvent caveSound() {
        return SoundEvents.AMBIENT_CAVE;
    }

    /** Hunger points this item restores, 0 when it is not food. */
    public static int foodValue(ItemStack stack) {
        net.minecraft.world.food.FoodProperties food = stack.getItem().getFoodProperties();
        return food == null ? 0 : food.getNutrition();
    }

    public static void unpackLoot(BlockEntity entity) {
        if (entity instanceof RandomizableContainerBlockEntity container) {
            container.unpackLootTable(null);
        }
    }

    public static void shootArrow(Mob shooter, LivingEntity target, float power) {
        AbstractArrow arrow = ProjectileUtil.getMobArrow(shooter, new ItemStack(Items.ARROW), power);
        double dx = target.getX() - shooter.getX();
        double dy = target.getY(0.3333) - arrow.getY();
        double dz = target.getZ() - shooter.getZ();
        double flat = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + flat * 0.2, dz, 1.6f, 6f);
        shooter.playSound(SoundEvents.ARROW_SHOOT, 1f, 0.9f);
        shooter.level.addFreshEntity(arrow);
    }
}
