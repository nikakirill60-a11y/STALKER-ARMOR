package com.stalkerarmor;

import com.stalkerarmor.client.StalkerArmorModels;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Client setup: eagerly loads every armor model once and prints a summary to the
 * log, so any problem (missing file, parse error, fallback) is visible in
 * logs/latest.log right after startup — no need to equip anything.
 */
@Mod.EventBusSubscriber(modid = StalkerArmorMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            int ok = 0;
            for (StalkerFullSet set : StalkerFullSet.ALL) {
                try {
                    StalkerArmorModels.getFullModel(set);
                    ok++;
                } catch (Exception ignored) {
                    // already logged inside
                }
            }
            com.mojang.logging.LogUtils.getLogger().info(
                    "[STALKER Armor] Model check at startup: {}/{} armor models loaded. "
                            + "If a number is lower than expected, search this log for '[STALKER Armor]' errors.",
                    ok, StalkerFullSet.ALL.length);
        });
    }

    private ClientSetup() {
    }
}
