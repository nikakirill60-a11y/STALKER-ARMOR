package com.stalkerarmor.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;

/**
 * HumanoidModel that renders the custom STALKER armor meshes instead of cubes.
 *
 * Part poses (rotations, crouch offsets etc.) arrive from the vanilla player model
 * through {@code copyPropertiesTo}; the raw triangles are drawn in pivot-relative
 * model space (1 unit = 1/16 block) after each part's transform, exactly like
 * vanilla cube rendering.
 *
 * Slot visibility is baked per instance (one model per set + slot), so the vanilla
 * setPartVisibility flags (which are only applied to the original armor model)
 * do not matter here.
 */
public class StalkerArmorModel extends HumanoidModel<LivingEntity> {
    private final StalkerArmorModels.Mesh headMesh;
    private final StalkerArmorModels.Mesh chestMesh;
    private final StalkerArmorModels.Mesh armMesh;
    private final StalkerArmorModels.Mesh legMesh;
    private final StalkerArmorModels.Mesh bootMesh;

    private final boolean drawHead;
    private final boolean drawChest;
    private final boolean drawArms;
    private final boolean drawLegs;
    private final boolean drawBoots;

    public StalkerArmorModel(ModelPart root, StalkerArmorModels.FamilyMeshes family, EquipmentSlot slot) {
        super(root);
        this.headMesh = family.head();
        this.chestMesh = family.chest();
        this.armMesh = family.arm();
        this.legMesh = family.leg();
        this.bootMesh = family.boot();
        this.drawHead = slot == EquipmentSlot.HEAD;
        this.drawChest = slot == EquipmentSlot.CHEST;
        this.drawArms = slot == EquipmentSlot.CHEST;
        this.drawLegs = slot == EquipmentSlot.LEGS;
        this.drawBoots = slot == EquipmentSlot.FEET;
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay,
                               float red, float green, float blue, float alpha) {
        boolean baby = this.young;
        if (baby) {
            // Keep feet on the ground while shrinking to baby size (vanilla armor scales for babies too).
            poseStack.pushPose();
            poseStack.translate(0.0F, 0.75F, 0.0F);
            poseStack.scale(0.5F, 0.5F, 0.5F);
        }
        if (drawHead && headMesh != null) {
            draw(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha, this.head, headMesh, false);
        }
        if (drawChest && chestMesh != null) {
            draw(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha, this.body, chestMesh, false);
        }
        if (drawArms && armMesh != null) {
            draw(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha, this.leftArm, armMesh, false);
            draw(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha, this.rightArm, armMesh, true);
        }
        if (drawLegs && legMesh != null) {
            draw(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha, this.leftLeg, legMesh, false);
            draw(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha, this.rightLeg, legMesh, true);
        }
        if (drawBoots && bootMesh != null) {
            draw(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha, this.leftLeg, bootMesh, false);
            draw(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha, this.rightLeg, bootMesh, true);
        }
        if (baby) {
            poseStack.popPose();
        }
    }

    private void draw(PoseStack poseStack, VertexConsumer buffer, int packedLight, int packedOverlay,
                      float red, float green, float blue, float alpha,
                      ModelPart part, StalkerArmorModels.Mesh mesh, boolean mirror) {
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
                if (mirror) {
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
