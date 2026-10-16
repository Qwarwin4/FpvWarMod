package com.qwarwin.fpvdrones.client.render;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.qwarwin.fpvdrones.client.ModModels;
import com.qwarwin.fpvdrones.entity.MunitionEntity;
import com.qwarwin.fpvdrones.item.PayloadType;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class MunitionRenderer extends EntityRenderer<MunitionEntity> {
    public MunitionRenderer(EntityRendererManager mgr) {
        super(mgr);
    }

    @Override
    public void render(MunitionEntity e, float entityYaw, float partial, MatrixStack ms, IRenderTypeBuffer buf, int light) {
        PayloadType t = e.getPayloadType();
        float spin = MathHelper.lerp(partial, e.spinO, e.spin);
        ms.pushPose();
        ms.translate(0, 0.12, 0);
        if (t == PayloadType.RGD5) {
            ms.mulPose(Vector3f.XP.rotationDegrees(spin));
            ms.mulPose(Vector3f.YP.rotationDegrees(spin * 0.7F));
            ms.scale(0.38F, 0.38F, 0.38F);
        } else {
            ms.mulPose(Vector3f.YP.rotationDegrees(spin));
            ms.mulPose(Vector3f.XP.rotationDegrees(180F));
            ms.scale(0.33F, 0.33F, 0.33F);
        }
        ModModels.render(t.model(), ms, buf, light);
        ms.popPose();
        super.render(e, entityYaw, partial, ms, buf, light);
    }

    @Override
    public ResourceLocation getTextureLocation(MunitionEntity e) {
        return AtlasTexture.LOCATION_BLOCKS;
    }
}
