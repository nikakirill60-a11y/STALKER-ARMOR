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
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.EquipmentSlot;
import org.slf4j.Logger;

/**
 * Loads the ORIGINAL OBJ files from "armor MODEL" (shipped as-is inside the jar at
 * assets/stalkerarmor/geo/original/arm_&lt;family&gt;_&lt;part&gt;.obj) and converts them
 * to Minecraft model space at runtime. No pre-converted copies: what you put in
 * "armor MODEL" is exactly what the mod renders.
 *
 * Conversion (Blender rig space -> MC model space, 1 rig unit = 4 MC units = 1/4 block):
 *   mc = (4*x + T.x, -4*y + T.y, -4*z + T.z), then a small per-axis "inflate" about the
 *   mesh bounding box center lifts the armor off the skin to avoid z-fighting.
 *   Normals: (nx, -ny, -nz), renormalized. UVs are used as authored (v flipped at draw).
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

    /** Geometry of one model family; null part = the family has no such piece. */
    public record FamilyMeshes(Mesh head, Mesh chest, Mesh arm, Mesh leg, Mesh boot) {
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

    private static final Map<String, FamilyMeshes> FAMILIES = new HashMap<>();
    private static final Map<String, HumanoidModel<?>> MODELS = new HashMap<>();

    public static HumanoidModel<?> getModel(StalkerArmorSet set, EquipmentSlot slot) {
        return MODELS.computeIfAbsent(set.id() + "/" + slot, key -> create(set, slot));
    }

    private static HumanoidModel<?> create(StalkerArmorSet set, EquipmentSlot slot) {
        FamilyMeshes family = FAMILIES.computeIfAbsent(set.family(), StalkerArmorModels::loadFamily);
        return new StalkerArmorModel(buildRoot(), family, slot);
    }

    /**
     * Vanilla-compatible humanoid part skeleton with empty cubes. The cubes never render —
     * only the pivots/rotations (synced from the parent player model) are used to pose
     * the custom meshes.
     */
    private static ModelPart buildRoot() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("head", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, 0.0F));
        root.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, 0.0F));
        root.addOrReplaceChild("body", CubeListBuilder.create(), PartPose.offset(0.0F, 0.0F, 0.0F));
        root.addOrReplaceChild("right_arm", CubeListBuilder.create(), PartPose.offset(-5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create(), PartPose.offset(5.0F, 2.0F, 0.0F));
        root.addOrReplaceChild("right_leg", CubeListBuilder.create(), PartPose.offset(-1.9F, 12.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create(), PartPose.offset(1.9F, 12.0F, 0.0F));
        return LayerDefinition.create(mesh, 64, 32).bakeRoot();
    }

    private static FamilyMeshes loadFamily(String family) {
        return new FamilyMeshes(
                loadMesh(family, "head"),
                loadMesh(family, "chest"),
                loadMesh(family, "arm"),
                loadMesh(family, "leg"),
                loadMesh(family, "boot"));
    }

    private static Mesh loadMesh(String family, String part) {
        // The original, unmodified OBJ from "armor MODEL".
        ResourceLocation location = new ResourceLocation(StalkerArmorMod.MODID,
                "geo/original/arm_" + family + "_" + part + ".obj");
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) {
            LOGGER.error("[STALKER Armor] Missing armor model {}: {} — this armor piece will be invisible!",
                    family + "/" + part, location);
            return null;
        }

        List<float[]> positions = new ArrayList<>();
        List<float[]> uvs = new ArrayList<>();
        List<float[]> normals = new ArrayList<>();
        List<int[]> faces = new ArrayList<>();   // each entry: {vertexIdx, texIdx, normalIdx} (1-based)

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
                    for (int k = 1; k < corners.length - 1; k++) { // fan-triangulate polygons
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

        PartTransform tr = PART_TRANSFORMS.get(part);

        // Blender rig space -> MC model space
        for (float[] p : positions) {
            p[0] = 4.0F * p[0] + tr.tx();
            p[1] = -4.0F * p[1] + tr.ty();
            p[2] = -4.0F * p[2] + tr.tz();
        }

        // Inflate about the bounding box center so the armor sits off the skin.
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (float[] p : positions) {
            minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
            minZ = Math.min(minZ, p[2]); maxZ = Math.max(maxZ, p[2]);
        }
        float cx = (minX + maxX) / 2.0F, cy = (minY + maxY) / 2.0F, cz = (minZ + maxZ) / 2.0F;
        float sx = Math.max(maxX - minX, 1.0E-6F), sy = Math.max(maxY - minY, 1.0E-6F), sz = Math.max(maxZ - minZ, 1.0E-6F);
        float kx = (sx + 2.0F * tr.inflate()) / sx;
        float ky = (sy + 2.0F * tr.inflate()) / sy;
        float kz = (sz + 2.0F * tr.inflate()) / sz;
        for (float[] p : positions) {
            p[0] = cx + (p[0] - cx) * kx;
            p[1] = cy + (p[1] - cy) * ky;
            p[2] = cz + (p[2] - cz) * kz;
        }

        // Normals: (nx, -ny, -nz), renormalized.
        for (float[] n : normals) {
            n[1] = -n[1];
            n[2] = -n[2];
            float len = (float) Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
            if (len > 1.0E-6F) {
                n[0] /= len; n[1] /= len; n[2] /= len;
            }
        }

        // Expand to corner-parallel arrays.
        int n = faces.size();
        float[] x = new float[n], y = new float[n], z = new float[n];
        float[] u = new float[n], v = new float[n];
        float[] nx = new float[n], ny = new float[n], nz = new float[n];
        for (int i = 0; i < n; i++) {
            int[] c = faces.get(i);
            float[] p = positions.get(Math.max(0, c[0] - 1));
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
        return new Mesh(x, y, z, u, v, nx, ny, nz);
    }

    private StalkerArmorModels() {
    }
}
