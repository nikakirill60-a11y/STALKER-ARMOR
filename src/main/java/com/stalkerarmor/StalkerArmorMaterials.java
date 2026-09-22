package com.stalkerarmor;

import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * Gameplay tiers of the suits. Add more entries here when you add heavier armor.
 */
public enum StalkerArmorMaterials implements ArmorMaterial {
    JACKET(11, new int[]{1, 3, 2, 1}, 0.0F, 0.00F, 15, SoundEvents.ARMOR_EQUIP_LEATHER, () -> Ingredient.of(Items.LEATHER));

    /** Vanilla multiplier per ArmorItem.Type ordinal (HELMET, CHESTPLATE, LEGGINGS, BOOTS). */
    private static final int[] DURABILITY_PER_TYPE = {11, 16, 15, 13};

    private final int durabilityBase;
    private final int[] defense;
    private final float toughness;
    private final float knockbackResistance;
    private final int enchantmentValue;
    private final SoundEvent equipSound;
    private final Supplier<Ingredient> repairIngredient;

    StalkerArmorMaterials(int durabilityBase, int[] defense, float toughness, float knockbackResistance,
                          int enchantmentValue, SoundEvent equipSound, Supplier<Ingredient> repairIngredient) {
        this.durabilityBase = durabilityBase;
        this.defense = defense;
        this.toughness = toughness;
        this.knockbackResistance = knockbackResistance;
        this.enchantmentValue = enchantmentValue;
        this.equipSound = equipSound;
        this.repairIngredient = repairIngredient;
    }

    @Override
    public int getDurabilityForType(ArmorItem.Type type) {
        return durabilityBase * DURABILITY_PER_TYPE[type.ordinal()];
    }

    @Override
    public int getDefenseForType(ArmorItem.Type type) {
        return defense[type.ordinal()];
    }

    @Override
    public int getEnchantmentValue() {
        return enchantmentValue;
    }

    @Override
    public SoundEvent getEquipSound() {
        return equipSound;
    }

    @Override
    public Ingredient getRepairIngredient() {
        return repairIngredient.get();
    }

    @Override
    public String getName() {
        return StalkerArmorMod.MODID + ":" + name().toLowerCase(Locale.ROOT);
    }

    @Override
    public float getToughness() {
        return toughness;
    }

    @Override
    public float getKnockbackResistance() {
        return knockbackResistance;
    }
}
