package com.qwarwin.fpvdrones.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.qwarwin.fpvdrones.FpvDrones;
import com.qwarwin.fpvdrones.item.FpvGogglesItem;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.entity.IEntityRenderer;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;
import net.minecraft.client.renderer.entity.model.BipedModel;
import net.minecraft.entity.LivingEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * The FPV goggles as a real 3D model on the wearer's head (models/entity/goggles.mesh, made in head space: blocks,
 * y up, face towards +z). The armour layer textures are empty, so this is all there is of them.
 */
@OnlyIn(Dist.CLIENT)
public class GogglesLayer<T extends LivingEntity, M extends BipedModel<T>> extends LayerRenderer<T, M> {
    private static final ResourceLocation MODEL = FpvDrones.id("entity/goggles");

    public GogglesLayer(IEntityRenderer<T, M> parent) {
        super(parent);
    }

    @Override
    public void render(MatrixStack ms, IRenderTypeBuffer buf, int light, T player, float limbSwing,
                       float limbSwingAmount, float partial, float ageInTicks, float netHeadYaw, float headPitch) {
        if (!(player.getItemBySlot(EquipmentSlotType.HEAD).getItem() instanceof FpvGogglesItem) || player.isInvisible()) {
            return;
        }
        MeshModel mesh = MeshModel.get(MODEL);
        if (mesh == null) {
            return;
        }
        ms.pushPose();
        getParentModel().head.translateAndRotate(ms);
        // model parts have y down and the face towards -z: turn that round to the mesh's y up, face +z
        ms.scale(1F, -1F, -1F);
        mesh.render(ms, buf, light);
        ms.popPose();
    }
}
