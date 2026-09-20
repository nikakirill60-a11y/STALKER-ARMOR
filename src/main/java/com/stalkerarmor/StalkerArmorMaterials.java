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
 * Gameplay tiers of the suits, roughly following S.T.A.L.K.E.R. progression:
 * leather jacket -> stalker jumpsuits -> Berill/Bulat -> SKAT / exoskeleton.
 */
public enum StalkerArmorMaterials implements ArmorMaterial {
    JACKET   (11, new int[]{1, 3, 2, 1}, 0.0F, 0.00F, 15, SoundEvents.ARMOR_EQUIP_LEATHER, () -> Ingredient.of(Items.LEATHER)),
    CAPE     (12, new int[]{1, 3, 2, 1}, 0.0F, 0.00F, 15, SoundEvents.ARMOR_EQUIP_LEATHER, () -> Ingredient.of(Items.LEATHER)),
    KOMBEZ   (18, new int[]{2, 5, 4, 2}, 0.0F, 0.00F, 12, SoundEvents.ARMOR_EQUIP_IRON,    () -> Ingredient.of(Items.IRON_INGOT)),
    SCIENTIST(20, new int[]{2, 6, 5, 2}, 0.5F, 0.00F, 12, SoundEvents.ARMOR_EQUIP_IRON,    () -> Ingredient.of(Items.IRON_INGOT)),
    SEVA     (20, new int[]{3, 6, 5, 2}, 1.0F, 0.00F, 12, SoundEvents.ARMOR_EQUIP_IRON,    () -> Ingredient.of(Items.IRON_INGOT)),
    ZARYA    (21, new int[]{2, 6, 5, 2}, 1.0F, 0.00F, 12, SoundEvents.ARMOR_EQUIP_IRON,    () -> Ingredient.of(Items.IRON_INGOT)),
    BERILL   (25, new int[]{3, 7, 5, 2}, 2.0F, 0.00F, 10, SoundEvents.ARMOR_EQUIP_IRON,    () -> Ingredient.of(Items.IRON_INGOT)),
    BULAT    (33, new int[]{3, 8, 6, 3}, 2.0F, 0.05F, 10, SoundEvents.ARMOR_EQUIP_DIAMOND, () -> Ingredient.of(Items.IRON_INGOT)),
    HEAVY    (33, new int[]{3, 8, 6, 3}, 2.5F, 0.10F, 10, SoundEvents.ARMOR_EQUIP_DIAMOND, () -> Ingredient.of(Items.IRON_INGOT)),
    EXO      (37, new int[]{4, 8, 6, 3}, 3.0F, 0.15F, 10, SoundEvents.ARMOR_EQUIP_DIAMOND, () -> Ingredient.of(Items.DIAMOND));

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
