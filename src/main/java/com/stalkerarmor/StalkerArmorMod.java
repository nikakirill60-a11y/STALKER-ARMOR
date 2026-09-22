package com.stalkerarmor;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(StalkerArmorMod.MODID)
public class StalkerArmorMod {
    public static final String MODID = "stalkerarmor";

    public StalkerArmorMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        StalkerItems.ITEMS.register(modBus);
        StalkerItems.TABS.register(modBus);
    }
}
