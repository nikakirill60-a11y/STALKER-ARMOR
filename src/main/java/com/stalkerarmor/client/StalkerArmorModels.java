package com.stalkerarmor.client;

import com.mojang.logging.LogUtils;
import com.stalkerarmor.StalkerArmorMod;
import com.stalkerarmor.StalkerArmorSet;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.EquipmentSlot;
import org.slf4j.Logger;

/**
 * Geometry loading for the STALKER armor mod.
 *
 * Two formats:
 *  1) Per-piece: the ORIGINAL "armor MODEL" OBJ files (bone-local space), used by
 *     the per-slot armor items. Converted to Minecraft model space at runtime.
 *  2) Full-body: ONE OBJ per set (assets/stalkerarmor/geo/sets/&lt;id&gt;.obj) in the
 *     "mannequin" space of steve.obj, split into objects: head, chest, armL, armR,
 *     legL, legR (or arm / leg for mirrored pairs). Equipping the item in the
 *     chest slot renders the whole suit.
 *
 * Mannequin space: 1 unit = 4 MC pixels, Y up, -Z = front, feet at y=0.
 */
public final class StalkerArmorModels {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Flat triangle soup: corner-parallel arrays. Positions are pivot-relative MC model units. */
    public static final class Mesh {
        public final float[] x, y, z;
        public final float[] u, v;      // v is OBJ-style (0 = bottom), flipped at draw time
        public final float[] nx, ny, nz;

        Mesh(float[] x, float[] y, float[] z, float[] u, float[] v, float[] nx, float[] ny, float[] nz) {
            this.x = x; this.y = y; this.z = z;
            this.u = u; this.v = v;
            this.nx = nx; this.ny = ny; this.nz = nz;
        }

        public int corners() {
            return x.length;
        }
    }

    /** Per-piece geometry of one model family; null part = the family has no such piece. */
    public record FamilyMeshes(Mesh head, Mesh chest, Mesh arm, Mesh leg, Mesh boot) {
    }

    /** Full-body geometry; null = group absent from the file. */
    public record FullMeshes(Mesh head, Mesh chest, Mesh armL, Mesh armR,
                             Mesh legL, Mesh legR, Mesh armGeneric, Mesh legGeneric) {
    }

    /** Per-body-part placement (pivot-relative MC units) and inflate amount. */
    private record PartTransform(float tx, float ty, float tz, float inflate) {
    }

    private static final Map<String, PartTransform> PART_TRANSFORMS = Map.of(
            "head", new PartTransform(0.0F, -4.0F, 0.0F, 0.75F),
            "chest", new PartTransform(0.0F, 4.6F, 0.0F, 0.35F),
            "arm", new PartTransform(0.0F, 4.0F, 0.0F, 0.30F),
            "leg", new PartTransform(0.0F, 6.0F, 0.0F, 0.30F),
            "boot", new PartTransform(0.0F, 9.1F, -1.5F, 0.35F));

    /** Bone positions in mannequin space (must match tools/gen_full_sets.py BONES). */
    private static final Map<String, float[]> MANNEQUIN_BONES = Map.of(
            "head", new float[]{0.0F, 7.0F, 0.0F},
            "chest", new float[]{0.0F, 4.85F, 0.0F},
            "armL", new float[]{1.25F, 4.5F, 0.0F},
            "armR", new float[]{-1.25F, 4.5F, 0.0F},
            "legL", new float[]{0.475F, 1.5F, 0.0F},
            "legR", new float[]{-0.475F, 1.5F, 0.0F});

    private static final Map<String, FamilyMeshes> FAMILIES = new HashMap<>();
    private static final Map<String, HumanoidModel<?>> MODELS = new HashMap<>();
    private static final Map<String, HumanoidModel<?>> FULL_MODELS = new HashMap<>();
    private static final java.util.Set<String> TEXTURE_DIAGNOSTICS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private StalkerArmorModels() {
    }

    /** One-time diagnostic: does the armor texture resolve in the resource manager? */
    public static void logTextureOnce(String key, String textureFile) {
        if (TEXTURE_DIAGNOSTICS.add(key + "/" + textureFile)) {
            ResourceLocation tex = new ResourceLocation(StalkerArmorMod.MODID, "textures/armor/" + textureFile);
            boolean present = Minecraft.getInstance().getResourceManager().getResource(tex).isPresent();
            if (present) {
                LOGGER.info("[STALKER Armor] OK: texture {} found", tex);
            } else {
                LOGGER.error("[STALKER Armor] PROBLEM: texture {} NOT FOUND in the mod resources! "
                        + "The armor will render magenta/black. Report this line please.", tex);
            }
        }
    }

    // ------------------------------------------------------------ per-piece --

    public static HumanoidModel<?> getModel(StalkerArmorSet set, EquipmentSlot slot) {
        return MODELS.computeIfAbsent(set.id() + "/" + slot, key -> {
            FamilyMeshes family = FAMILIES.computeIfAbsent(set.family(), StalkerArmorModels::loadFamily);
            return new StalkerArmorModel(buildRoot(), family, slot);
        });
    }

    private static FamilyMeshes loadFamily(String family) {
        return new FamilyMeshes(
                loadPiece(family, "head"),
                loadPiece(family, "chest"),
                loadPiece(family, "arm"),
                loadPiece(family, "leg"),
                loadPiece(family, "boot"));
    }

    /** Loads an original bone-local OBJ and converts it to pivot-relative model space. */
    private static Mesh loadPiece(String family, String part) {
        ResourceLocation location = new ResourceLocation(StalkerArmorMod.MODID,
                "geo/original/arm_" + family + "_" + part + ".obj");
        PartTransform tr = PART_TRANSFORMS.get(part);
        if (tr == null) {
            LOGGER.error("[STALKER Armor] No part transform registered for '{}' — please report this!", part);
            return null;
        }
        return loadObj(location, tr);
    }

    // ----------------------------------------------------------- full suits --

    public static HumanoidModel<?> getFullModel(String setId) {
        return FULL_MODELS.computeIfAbsent(setId, id -> {
            try {
                FullMeshes full = loadFullObj(id);
                return new StalkerArmorModel(buildRoot(), full);
            } catch (Exception e) {
                LOGGER.error("[STALKER Armor] Failed to load full-body armor model {} — using vanilla armor shape. "
                        + "Please report this with your log!", id, e);
                return new StalkerArmorModel(buildRoot(),
                        new FullMeshes(null, null, null, null, null, null, null, null));
            }
        });
    }

    private static FullMeshes loadFullObj(String setId) {
        ResourceLocation location = new ResourceLocation(StalkerArmorMod.MODID, "geo/sets/" + setId + ".obj");
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) {
            LOGGER.error("[STALKER Armor] Missing full-body armor model {} — falling back to vanilla armor shape. "
                    + "Put your OBJ at assets/stalkerarmor/geo/sets/{}.obj (see steve.obj for the mannequin).",
                    location, setId);
            return new FullMeshes(null, null, null, null, null, null, null, null);
        }

        List<float[]> positions = new ArrayList<>();
        List<float[]> uvs = new ArrayList<>();
        List<float[]> normals = new ArrayList<>();
        Map<String, List<int[]>> groups = new HashMap<>();   // canonical group -> faces {vi,ti,ni}
        String[] current = {null};

        try (InputStream stream = resource.get().open()) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("o ") || line.startsWith("g ")) {
                    current[0] = canonicalGroup(line.substring(2).trim());
                    groups.computeIfAbsent(current[0], k -> new ArrayList<>());
                } else if (line.startsWith("v ")) {
                    String[] t = line.split(" ");
                    positions.add(new float[]{Float.parseFloat(t[1]), Float.parseFloat(t[2]), Float.parseFloat(t[3])});
                } else if (line.startsWith("vt ")) {
                    String[] t = line.split(" ");
                    uvs.add(new float[]{Float.parseFloat(t[1]), Float.parseFloat(t[2])});
                } else if (line.startsWith("vn ")) {
                    String[] t = line.split(" ");
                    normals.add(new float[]{Float.parseFloat(t[1]), Float.parseFloat(t[2]), Float.parseFloat(t[3])});
                } else if (line.startsWith("f ")) {
                    String target = current[0] != null ? current[0] : "chest";
                    List<int[]> faces = groups.computeIfAbsent(target, k -> new ArrayList<>());
                    String[] tokens = line.split(" ");
                    int[][] corners = new int[tokens.length - 1][];
                    for (int i = 1; i < tokens.length; i++) {
                        String[] idx = tokens[i].split("/");
                        int vi = Integer.parseInt(idx[0]);
                        int ti = idx.length > 1 && !idx[1].isEmpty() ? Integer.parseInt(idx[1]) : 0;
                        int ni = idx.length > 2 && !idx[2].isEmpty() ? Integer.parseInt(idx[2]) : 0;
                        corners[i - 1] = new int[]{vi, ti, ni};
                    }
                    for (int k = 1; k < corners.length - 1; k++) {
                        faces.add(corners[0]);
                        faces.add(corners[k]);
                        faces.add(corners[k + 1]);
                    }
                }
            }
        } catch (IOException | NumberFormatException e) {
            LOGGER.error("[STALKER Armor] Failed to parse full-body armor model {}", location, e);
            return new FullMeshes(null, null, null, null, null, null, null, null);
        }

        if (positions.isEmpty() || groups.isEmpty() || groups.values().stream().allMatch(List::isEmpty)) {
            LOGGER.error("[STALKER Armor] Armor model {} has no geometry!", location);
            return new FullMeshes(null, null, null, null, null, null, null, null);
        }

        LOGGER.info("[STALKER Armor] Loaded full-body armor {} (groups: {})", setId, groups.keySet());
        return new FullMeshes(
                buildGroup(groups, positions, uvs, normals, "head", "head", "head"),
                buildGroup(groups, positions, uvs, normals, "chest", "chest", "chest"),
                buildGroup(groups, positions, uvs, normals, "armL", "armL", "arm"),
                buildGroup(groups, positions, uvs, normals, "armR", "armR", "arm"),
                buildGroup(groups, positions, uvs, normals, "legL", "legL", "leg"),
                buildGroup(groups, positions, uvs, normals, "legR", "legR", "leg"),
                buildGroup(groups, positions, uvs, normals, "armX", "armL", "arm"),
                buildGroup(groups, positions, uvs, normals, "legX", "legL", "leg"));
    }

    /** Converts one group's geometry from mannequin space to pivot-relative model space. */
    private static Mesh buildGroup(Map<String, List<int[]>> groups, List<float[]> positions, List<float[]> uvs,
                                   List<float[]> normals, String group, String bone, String part) {
        List<int[]> faces = groups.get(group);
        if (faces == null || faces.isEmpty()) {
            return null;
        }
        PartTransform tr = PART_TRANSFORMS.get(part);
        float[] bonePos = MANNEQUIN_BONES.get(bone);

        // Convert all positions once (mannequin -> pivot-relative).
        float[][] pos = new float[positions.size()][];
        for (int i = 0; i < positions.size(); i++) {
            float[] p = positions.get(i);
            pos[i] = new float[]{
                    4.0F * (p[0] - bonePos[0]) + tr.tx(),
                    -4.0F * (p[1] - bonePos[1]) + tr.ty(),
                    -4.0F * (p[2] - bonePos[2]) + tr.tz()};
        }
        inflate(pos, tr.inflate());

        // Convert normals (only used when the corner references one).
        float[][] nrm = new float[normals.size()][];
        for (int i = 0; i < normals.size(); i++) {
            float[] n = normals.get(i);
            float ny = -n[1];
            float nz = -n[2];
            float len = (float) Math.sqrt(n[0] * n[0] + ny * ny + nz * nz);
            nrm[i] = len > 1.0E-6F ? new float[]{n[0] / len, ny / len, nz / len} : new float[]{0, -1, 0};
        }

        int n = faces.size();
        float[] x = new float[n], y = new float[n], z = new float[n];
        float[] u = new float[n], v = new float[n];
        float[] nx = new float[n], ny = new float[n], nz = new float[n];
        for (int i = 0; i < n; i++) {
            int[] c = faces.get(i);
            float[] p = pos[Math.max(0, c[0] - 1)];
            x[i] = p[0]; y[i] = p[1]; z[i] = p[2];
            if (c[1] > 0 && c[1] <= uvs.size()) {
                float[] uv = uvs.get(c[1] - 1);
                u[i] = uv[0]; v[i] = uv[1];
            }
            if (c[2] > 0 && c[2] <= nrm.length) {
                float[] nn = nrm[c[2] - 1];
                nx[i] = nn[0]; ny[i] = nn[1]; nz[i] = nn[2];
            }
        }
        // Fill flat normals for triangles that had none.
        for (int i = 0; i + 2 < n; i += 3) {
            if (nx[i] == 0.0F && ny[i] == 0.0F && nz[i] == 0.0F) {
                float ax = x[i + 1] - x[i], ay = y[i + 1] - y[i], az = z[i + 1] - z[i];
                float bx = x[i + 2] - x[i], by = y[i + 2] - y[i], bz = z[i + 2] - z[i];
                float cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
                float len = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
                if (len > 1.0E-6F) { cx /= len; cy /= len; cz /= len; } else { cx = 0; cy = -1; cz = 0; }
                for (int k = i; k < i + 3; k++) {
                    if (nx[k] == 0.0F && ny[k] == 0.0F && nz[k] == 0.0F) {
                        nx[k] = cx; ny[k] = cy; nz[k] = cz;
                    }
                }
            }
        }
        return new Mesh(x, y, z, u, v, nx, ny, nz);
    }

    /** Maps a user's object name to a canonical group. */
    private static String canonicalGroup(String raw) {
        String key = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        switch (key) {
            case "head": case "helmet": case "headwear": case "hat": case "cap": case "mask":
                return "head";
            case "chest": case "body": case "torso": case "suit":
                return "chest";
            case "arml": case "leftarm": case "armleft":
                return "armL";
            case "armr": case "rightarm": case "armright":
                return "armR";
            case "legl": case "leftleg": case "legleft":
                return "legL";
            case "legr": case "rightleg": case "legright":
                return "legR";
            case "arm": case "arms":
                return "armX";
            case "leg": case "legs": case "boot": case "boots": case "foot": case "feet":
                return "legX";
            default:
                LOGGER.warn("[STALKER Armor] Unknown object name '{}' in armor OBJ — attaching it to the body. "
                        + "Use objects named head, chest, armL, armR, legL, legR (or arm / leg).", raw);
                return "chest";
        }
    }

    // -------------------------------------------------------------- shared --

    /**
     * Loads an OBJ (bone-local space for pieces) and converts it to pivot-relative
     * model space. When {@code bone} is null the file is bone-local (per-piece format);
     * otherwise it is in mannequin space and offset by the bone first.
     */
    private static Mesh loadObj(ResourceLocation location, PartTransform tr) {
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) {
            LOGGER.error("[STALKER Armor] Missing armor model {} — this piece will use the vanilla armor shape!", location);
            return null;
        }

        List<float[]> positions = new ArrayList<>();
        List<float[]> uvs = new ArrayList<>();
        List<float[]> normals = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();

        try (InputStream stream = resource.get().open()) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("v ")) {
                    String[] t = line.split(" ");
                    positions.add(new float[]{Float.parseFloat(t[1]), Float.parseFloat(t[2]), Float.parseFloat(t[3])});
                } else if (line.startsWith("vt ")) {
                    String[] t = line.split(" ");
                    uvs.add(new float[]{Float.parseFloat(t[1]), Float.parseFloat(t[2])});
                } else if (line.startsWith("vn ")) {
                    String[] t = line.split(" ");
                    normals.add(new float[]{Float.parseFloat(t[1]), Float.parseFloat(t[2]), Float.parseFloat(t[3])});
                } else if (line.startsWith("f ")) {
                    String[] tokens = line.split(" ");
                    int[][] corners = new int[tokens.length - 1][];
                    for (int i = 1; i < tokens.length; i++) {
                        String[] idx = tokens[i].split("/");
                        int vi = Integer.parseInt(idx[0]);
                        int ti = idx.length > 1 && !idx[1].isEmpty() ? Integer.parseInt(idx[1]) : 0;
                        int ni = idx.length > 2 && !idx[2].isEmpty() ? Integer.parseInt(idx[2]) : 0;
                        corners[i - 1] = new int[]{vi, ti, ni};
                    }
                    for (int k = 1; k < corners.length - 1; k++) {
                        faces.add(corners[0]);
                        faces.add(corners[k]);
                        faces.add(corners[k + 1]);
                    }
                }
            }
        } catch (IOException | NumberFormatException e) {
            throw new IllegalStateException("Failed to parse armor model " + location, e);
        }

        if (positions.isEmpty() || faces.isEmpty()) {
            LOGGER.error("[STALKER Armor] Armor model {} has no geometry!", location);
            return null;
        }

        for (float[] p : positions) {
            p[0] = 4.0F * p[0] + tr.tx();
            p[1] = -4.0F * p[1] + tr.ty();
            p[2] = -4.0F * p[2] + tr.tz();
        }
        float[][] pos = positions.toArray(new float[0][]);
        inflate(pos, tr.inflate());

        for (float[] nrm : normals) {
            nrm[1] = -nrm[1];
            nrm[2] = -nrm[2];
            float len = (float) Math.sqrt(nrm[0] * nrm[0] + nrm[1] * nrm[1] + nrm[2] * nrm[2]);
            if (len > 1.0E-6F) { nrm[0] /= len; nrm[1] /= len; nrm[2] /= len; }
        }

        int n = faces.size();
        float[] x = new float[n], y = new float[n], z = new float[n];
        float[] u = new float[n], v = new float[n];
        float[] nx = new float[n], ny = new float[n], nz = new float[n];
        for (int i = 0; i < n; i++) {
            int[] c = faces.get(i);
            float[] p = pos[Math.max(0, c[0] - 1)];
            x[i] = p[0]; y[i] = p[1]; z[i] = p[2];
            if (c[1] > 0 && c[1] <= uvs.size()) {
                float[] uv = uvs.get(c[1] - 1);
                u[i] = uv[0]; v[i] = uv[1];
            }
            if (c[2] > 0 && c[2] <= normals.size()) {
                float[] nn = normals.get(c[2] - 1);
                nx[i] = nn[0]; ny[i] = nn[1]; nz[i] = nn[2];
            }
        }
        for (int i = 0; i + 2 < n; i += 3) {
            if (nx[i] == 0.0F && ny[i] == 0.0F && nz[i] == 0.0F) {
                float ax = x[i + 1] - x[i], ay = y[i + 1] - y[i], az = z[i + 1] - z[i];
                float bx = x[i + 2] - x[i], by = y[i + 2] - y[i], bz = z[i + 2] - z[i];
                float cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
                float len = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
                if (len > 1.0E-6F) { cx /= len; cy /= len; cz /= len; } else { cx = 0; cy = -1; cz = 0; }
                for (int k = i; k < i + 3; k++) {
                    if (nx[k] == 0.0F && ny[k] == 0.0F && nz[k] == 0.0F) {
                        nx[k] = cx; ny[k] = cy; nz[k] = cz;
                    }
                }
            }
        }
        return new Mesh(x, y, z, u, v, nx, ny, nz);
    }

    /** Inflates every axis of the mesh about its bounding box center (anti z-fighting). */
    private static void inflate(float[][] pos, float amount) {
        if (pos.length == 0 || amount == 0.0F) {
            return;
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (float[] p : pos) {
            minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
            minZ = Math.min(minZ, p[2]); maxZ = Math.max(maxZ, p[2]);
        }
        float cx = (minX + maxX) / 2.0F, cy = (minY + maxY) / 2.0F, cz = (minZ + maxZ) / 2.0F;
        float sx = Math.max(maxX - minX, 1.0E-6F), sy = Math.max(maxY - minY, 1.0E-6F), sz = Math.max(maxZ - minZ, 1.0E-6F);
        float kx = (sx + 2.0F * amount) / sx;
        float ky = (sy + 2.0F * amount) / sy;
        float kz = (sz + 2.0F * amount) / sz;
        for (float[] p : pos) {
            p[0] = cx + (p[0] - cx) * kx;
            p[1] = cy + (p[1] - cy) * ky;
            p[2] = cz + (p[2] - cz) * kz;
        }
    }

    /**
     * Vanilla humanoid skeleton with REAL armor boxes. The boxes are only rendered
     * as an emergency fallback when a custom mesh is missing — normally only the
     * part pivots/rotations are used to pose the custom meshes.
     */
    private static ModelPart buildRoot() {
        MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F);
        return LayerDefinition.create(mesh, 64, 32).bakeRoot();
    }
}
