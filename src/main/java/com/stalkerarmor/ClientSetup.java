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
            int fullOk = 0;
            for (StalkerFullSet set : StalkerFullSet.ALL) {
                try {
                    StalkerArmorModels.getFullModel(set.id());
                    fullOk++;
                } catch (Exception ignored) {
                    // already logged inside
                }
            }
            int pieceOk = 0;
            for (StalkerArmorSet set : StalkerArmorSets.ALL) {
                try {
                    for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
                        StalkerArmorModels.getModel(set, slot);
                    }
                    pieceOk++;
                } catch (Exception ignored) {
                    // already logged inside
                }
            }
            com.mojang.logging.LogUtils.getLogger().info(
                    "[STALKER Armor] Model check at startup: {}/{} full-body suits and {}/{} piece sets loaded. "
                            + "If a number is lower than expected, search this log for '[STALKER Armor]' errors.",
                    fullOk, StalkerFullSet.ALL.length, pieceOk, StalkerArmorSets.ALL.length);
        });
    }

    private ClientSetup() {
    }
}
