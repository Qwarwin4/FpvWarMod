package com.qwarwin.fpvdrones.client.render;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.qwarwin.fpvdrones.client.ModModels;
import com.qwarwin.fpvdrones.entity.RadarEntity;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** The radar mast and, on top, the array turning while it's switched on. */
@OnlyIn(Dist.CLIENT)
public class RadarRenderer extends EntityRenderer<RadarEntity> {
    public RadarRenderer(EntityRendererManager mgr) {
        super(mgr);
    }

    @Override
    public void render(RadarEntity e, float entityYaw, float partial, MatrixStack ms, IRenderTypeBuffer buf, int light) {
        if (e.isWrecked()) {
            light = LightTexture.pack(1, Math.min(4, LightTexture.sky(light)));
        }
        ms.pushPose();
        ms.mulPose(Vector3f.YP.rotationDegrees(-MathHelper.rotLerp(partial, e.yRotO, e.yRot)));
        Parts.draw(ModModels.RADAR, ms, buf, light, 1F);
        ms.translate(0, RadarEntity.ANTENNA_Y, 0);
        if (e.isActive() && !e.isWrecked()) {
            float t = (e.level.getGameTime() % 360L + partial) * RadarEntity.SPIN;
            ms.mulPose(Vector3f.YP.rotationDegrees(t % 360F));
        }
        Parts.draw(ModModels.RADAR_ANTENNA, ms, buf, light, 1F);
        ms.popPose();
        super.render(e, entityYaw, partial, ms, buf, light);
    }

    @Override
    public ResourceLocation getTextureLocation(RadarEntity e) {
        return AtlasTexture.LOCATION_BLOCKS;
    }
}
