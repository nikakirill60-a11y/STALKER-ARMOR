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
 * Loads the converted OBJ geometry (assets/stalkerarmor/geo/armor/&lt;family&gt;/&lt;part&gt;.obj)
 * and hands out cached {@link StalkerArmorModel} instances per set + slot.
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
        ResourceLocation location = new ResourceLocation(StalkerArmorMod.MODID,
                "geo/armor/" + family + "/" + part + ".obj");
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
        if (resource.isEmpty()) {
            LOGGER.error("[STALKER Armor] Missing armor geometry: {} — the armor piece will be invisible!", location);
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
                    for (int k = 1; k < corners.length - 1; k++) { // fan-triangulate polygons
                        faces.add(corners[0]);
                        faces.add(corners[k]);
                        faces.add(corners[k + 1]);
                    }
                }
            }
        } catch (IOException | NumberFormatException e) {
            throw new IllegalStateException("Failed to parse armor geometry " + location, e);
        }

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
