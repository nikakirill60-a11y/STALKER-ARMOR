package com.stalkerarmor;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ArmorItem;

/**
 * Description of one armor set: which model family it uses, which texture,
 * gameplay stats tier and which of the four slots the set provides
 * (some suits have no helmet or no boots in the source models).
 */
public record StalkerArmorSet(String id, String family, String texture,
                              StalkerArmorMaterials material,
                              boolean helmet, boolean chest, boolean legs, boolean boots) {

    public ResourceLocation textureLocation() {
        return new ResourceLocation(StalkerArmorMod.MODID, "textures/armor/" + texture);
    }

    public boolean has(ArmorItem.Type type) {
        return switch (type) {
            case HELMET -> helmet;
            case CHESTPLATE -> chest;
            case LEGGINGS -> legs;
            case BOOTS -> boots;
        };
    }

    public String itemId(ArmorItem.Type type) {
        return id + "_" + suffix(type);
    }

    public static String suffix(ArmorItem.Type type) {
        return switch (type) {
            case HELMET -> "helmet";
            case CHESTPLATE -> "chestplate";
            case LEGGINGS -> "leggings";
            case BOOTS -> "boots";
        };
    }
}
