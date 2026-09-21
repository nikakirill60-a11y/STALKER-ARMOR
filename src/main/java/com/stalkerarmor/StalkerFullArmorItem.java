package com.stalkerarmor;

import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import com.stalkerarmor.client.StalkerArmorModels;

/**
 * One-slot full-body armor: a single OBJ file (modeled over steve.obj) covers the
 * whole player when equipped in the chest slot.
 */
public class StalkerFullArmorItem extends ArmorItem {
    private final StalkerFullSet set;

    public StalkerFullArmorItem(StalkerFullSet set, Item.Properties properties) {
        super(StalkerArmorMaterials.valueOf(set.family().toUpperCase(Locale.ROOT)),
                ArmorItem.Type.CHESTPLATE, properties);
        this.set = set;
    }

    public StalkerFullSet set() {
        return set;
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            @Override
            public HumanoidModel<?> getHumanoidArmorModel(LivingEntity livingEntity, ItemStack itemStack,
                                                           EquipmentSlot equipmentSlot, HumanoidModel<?> original) {
                StalkerArmorModels.logTextureOnce("full", set.texture());
                return StalkerArmorModels.getFullModel(set.id());
            }
        });
    }

    @Override
    public String getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot, String type) {
        return new ResourceLocation(StalkerArmorMod.MODID, "textures/armor/" + set.texture()).toString();
    }
}
