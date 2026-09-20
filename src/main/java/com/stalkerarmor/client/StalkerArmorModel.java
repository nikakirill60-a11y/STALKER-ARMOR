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
    private final StalkerArmorModels.Mesh head;
    private final StalkerArmorModels.Mesh chest;
    private final StalkerArmorModels.Mesh arm;
    private final StalkerArmorModels.Mesh leg;
    private final StalkerArmorModels.Mesh boot;

    private final boolean drawHead;
    private final boolean drawChest;
    private final boolean drawArms;
    private final boolean drawLegs;
    private final boolean drawBoots;

    public StalkerArmorModel(ModelPart root, StalkerArmorModels.FamilyMeshes family, EquipmentSlot slot) {
        super(root);
        this.head = family.head();
        this.chest = family.chest();
        this.arm = family.arm();
        this.leg = family.leg();
        this.boot = family.boot();
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
        if (drawHead && head != null) {
            draw(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha, this.head, this.head, false);
        }
        if (drawChest && chest != null) {
            draw(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha, this.body, chest, false);
        }
        if (drawArms && arm != null) {
            draw(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha, this.leftArm, arm, false);
            draw(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha, this.rightArm, arm, true);
        }
        if (drawLegs && leg != null) {
            draw(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha, this.leftLeg, leg, false);
            draw(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha, this.rightLeg, leg, true);
        }
        if (drawBoots && boot != null) {
            draw(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha, this.leftLeg, boot, false);
            draw(poseStack, buffer, packedLight, packedOverlay, red, green, blue, alpha, this.rightLeg, boot, true);
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
