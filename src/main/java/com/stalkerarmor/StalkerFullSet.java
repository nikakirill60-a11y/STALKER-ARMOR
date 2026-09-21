package com.stalkerarmor;

/**
 * The armor sets shipped with the mod. Each set is ONE OBJ model (modeled over
 * steve.obj, objects head/chest/armL/armR/legL/legR) rendered over the whole
 * player when equipped in a single chest slot.
 *
 * id       — item registry name (stalkerarmor:<id>)
 * model    — OBJ file at assets/stalkerarmor/geo/sets/<model>.obj
 * texture  — PNG at assets/stalkerarmor/textures/armor/<texture>
 * material — gameplay tier (see StalkerArmorMaterials)
 */
public record StalkerFullSet(String id, String model, String texture, StalkerArmorMaterials material) {

    public static final StalkerFullSet[] ALL = new StalkerFullSet[] {
            new StalkerFullSet("jacket_bandits",  "jacket", "2_jacket.bandits.png",  StalkerArmorMaterials.JACKET),
            new StalkerFullSet("jacket_cs",       "jacket", "2_jacket.cs.png",       StalkerArmorMaterials.JACKET),
            new StalkerFullSet("jacket_dolg",     "jacket", "2_jacket.dolg.png",     StalkerArmorMaterials.JACKET),
            new StalkerFullSet("jacket_free",     "jacket", "2_jacket.free.png",     StalkerArmorMaterials.JACKET),
            new StalkerFullSet("jacket_ren",      "jacket", "2_jacket.ren.png",      StalkerArmorMaterials.JACKET),
            new StalkerFullSet("jacket_stalker",  "jacket", "2_jacket.stalker.png",  StalkerArmorMaterials.JACKET),
            new StalkerFullSet("jacket_stalker2", "jacket", "2_jacket.stalker2.png", StalkerArmorMaterials.JACKET),
    };
}
