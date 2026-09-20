package com.stalkerarmor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class StalkerItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, StalkerArmorMod.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, StalkerArmorMod.MODID);

    public static final Map<String, RegistryObject<Item>> BY_ID = new LinkedHashMap<>();
    public static final List<RegistryObject<Item>> ALL = new ArrayList<>();

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("stalker_armor",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.stalkerarmor"))
                    .icon(() -> new ItemStack(BY_ID.get("berill_military_helmet").get()))
                    .displayItems((parameters, output) -> ALL.forEach(item -> output.accept(item.get())))
                    .build());

    static {
        for (StalkerArmorSet set : StalkerArmorSets.ALL) {
            for (ArmorItem.Type type : ArmorItem.Type.values()) {
                if (set.has(type)) {
                    String id = set.itemId(type);
                    RegistryObject<Item> item = ITEMS.register(id,
                            () -> new StalkerArmorItem(set, type, new Item.Properties()));
                    BY_ID.put(id, item);
                    ALL.add(item);
                }
            }
        }
    }

    private StalkerItems() {
    }
}
