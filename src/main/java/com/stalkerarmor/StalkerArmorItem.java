package com.stalkerarmor;

import java.util.function.Consumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import com.stalkerarmor.client.StalkerArmorModels;

/**
 * Armor item rendered with the custom STALKER geometry instead of the vanilla
 * armor boxes. The 3D mesh and texture are chosen by the {@link StalkerArmorSet}.
 */
public class StalkerArmorItem extends ArmorItem {
    private final StalkerArmorSet set;

    public StalkerArmorItem(StalkerArmorSet set, Type type, Properties properties) {
        super(set.material(), type, properties);
        this.set = set;
    }

    public StalkerArmorSet set() {
        return set;
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(new IClientItemExtensions() {
            @Override
            public HumanoidModel<?> getHumanoidArmorModel(LivingEntity livingEntity, ItemStack itemStack,
                                                           EquipmentSlot equipmentSlot, HumanoidModel<?> original) {
                return StalkerArmorModels.getModel(set, equipmentSlot);
            }
        });
    }

    @Override
    public String getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot, String type) {
        return set.textureLocation().toString();
    }
}
