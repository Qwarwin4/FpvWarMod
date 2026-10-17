package com.qwarwin.fpvdrones.client.render;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.qwarwin.fpvdrones.client.ModModels;
import com.qwarwin.fpvdrones.entity.RadioBeaconEntity;
import com.qwarwin.fpvdrones.item.PayloadType;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class RadioBeaconRenderer extends EntityRenderer<RadioBeaconEntity> {
    public RadioBeaconRenderer(EntityRendererManager mgr) {
        super(mgr);
    }

    @Override
    public void render(RadioBeaconEntity e, float entityYaw, float partial, MatrixStack ms, IRenderTypeBuffer buf, int light) {
        ms.pushPose();
        ms.translate(0, 0.15, 0);
        ms.mulPose(Vector3f.YP.rotationDegrees(-e.yRot));
        ms.scale(0.38F, 0.38F, 0.38F);
        ModModels.render(PayloadType.RADIO_BEACON.model(), ms, buf, light);
        ms.popPose();
        super.render(e, entityYaw, partial, ms, buf, light);
    }

    @Override
    public ResourceLocation getTextureLocation(RadioBeaconEntity e) {
        return AtlasTexture.LOCATION_BLOCKS;
    }
}
