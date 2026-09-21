package com.stalkerarmor.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;

/**
 * HumanoidModel that renders the custom STALKER armor meshes instead of cubes.
 *
 * Two modes:
 *  - per-piece (one armor piece per slot; right arm/leg/boot mirrored from the left mesh)
 *  - full-body (ONE OBJ covers the whole player; equipped in a single chest slot)
 *
 * Part poses (walk, crouch, head turn...) arrive from the vanilla player model via
 * {@code copyPropertiesTo}; triangles are drawn in pivot-relative model space
 * (1 unit = 1/16 block) after each part's transform, exactly like vanilla cubes.
 *
 * If no custom geometry is available the model falls back to the vanilla armor
 * boxes so armor is never invisible.
 */
public class StalkerArmorModel extends HumanoidModel<LivingEntity> {

    private record Entry(ModelPart part, StalkerArmorModels.Mesh mesh, boolean mirror) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final boolean vanillaFallback;

    /** Per-piece mode: draws only the parts belonging to the given slot. */
    public StalkerArmorModel(ModelPart root, StalkerArmorModels.FamilyMeshes family, EquipmentSlot slot) {
        super(root);
        boolean any = false;
        if (slot == EquipmentSlot.HEAD && family.head() != null) {
            entries.add(new Entry(this.head, family.head(), false)); any = true;
        }
        if (slot == EquipmentSlot.CHEST) {
            if (family.chest() != null) { entries.add(new Entry(this.body, family.chest(), false)); any = true; }
            if (family.arm() != null) {
                entries.add(new Entry(this.leftArm, family.arm(), false));
                entries.add(new Entry(this.rightArm, family.arm(), true));
                any = true;
            }
        }
        if (slot == EquipmentSlot.LEGS && family.leg() != null) {
            entries.add(new Entry(this.leftLeg, family.leg(), false));
            entries.add(new Entry(this.rightLeg, family.leg(), true));
            any = true;
        }
        if (slot == EquipmentSlot.FEET && family.boot() != null) {
            entries.add(new Entry(this.leftLeg, family.boot(), false));
            entries.add(new Entry(this.rightLeg, family.boot(), true));
            any = true;
        }
        this.vanillaFallback = !any;
    }

    /** Full-body mode: one OBJ (objects head/chest/armL/armR/legL/legR) drawn on the whole player. */
    public StalkerArmorModel(ModelPart root, StalkerArmorModels.FullMeshes full) {
        super(root);
        if (full.head() != null) entries.add(new Entry(this.head, full.head(), false));
        if (full.chest() != null) entries.add(new Entry(this.body, full.chest(), false));
        if (full.armL() != null) entries.add(new Entry(this.leftArm, full.armL(), false));
        if (full.armR() != null) entries.add(new Entry(this.rightArm, full.armR(), false));
        if (full.armGeneric() != null) {
            entries.add(new Entry(this.leftArm, full.armGeneric(), false));
            entries.add(new Entry(this.rightArm, full.armGeneric(), true));
        }
        if (full.legL() != null) entries.add(new Entry(this.leftLeg, full.legL(), false));
        if (full.legR() != null) entries.add(new Entry(this.rightLeg, full.legR(), false));
        if (full.legGeneric() != null) {
            entries.add(new Entry(this.leftLeg, full.legGeneric(), false));
            entries.add(new Entry(this.rightLeg, full.legGeneric(), true));
        }
        this.vanillaFallback = entries.isEmpty();
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay,
                               float red, float green, float blue, float alpha) {
        if (vanillaFallback) {
            // Emergency fallback: vanilla armor boxes with the mod texture (never invisible).
            super.renderToBuffer(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha);
            return;
        }
        boolean baby = this.young;
        if (baby) {
            poseStack.pushPose();
            poseStack.translate(0.0F, 0.75F, 0.0F);
            poseStack.scale(0.5F, 0.5F, 0.5F);
        }
        for (Entry entry : entries) {
            draw(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha, entry);
        }
        if (baby) {
            poseStack.popPose();
        }
    }

    private void draw(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay,
                      float red, float green, float blue, float alpha, Entry entry) {
        ModelPart part = entry.part();
        StalkerArmorModels.Mesh mesh = entry.mesh();
        poseStack.pushPose();
        part.translateAndRotate(poseStack);
        PoseStack.Pose pose = poseStack.last();

        float[] x = mesh.x, y = mesh.y, z = mesh.z;
        float[] u = mesh.u, v = mesh.v;
        float[] nx = mesh.nx, ny = mesh.ny, nz = mesh.nz;
        int corners = mesh.corners();

        for (int i = 0; i + 2 < corners; i += 3) {
            for (int k = i; k < i + 3; k++) {
                float px = x[k] / 16.0F;
                float py = y[k] / 16.0F;
                float pz = z[k] / 16.0F;
                float pnx = nx[k];
                float pny = ny[k];
                float pnz = nz[k];
                if (entry.mirror()) {
                    px = -px;
                    pnx = -pnx;
                }
                buffer.vertex(pose.pose(), px, py, pz)
                        .color(red, green, blue, alpha)
                        .uv(u[k], 1.0F - v[k])
                        .overlayCoords(packedOverlay)
                        .uv2(packedLight)
                        .normal(pose.normal(), pnx, pny, pnz)
                        .endVertex();
            }
        }
        poseStack.popPose();
    }
}
