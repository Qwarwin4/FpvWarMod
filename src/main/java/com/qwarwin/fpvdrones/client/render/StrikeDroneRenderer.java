package com.qwarwin.fpvdrones.client.render;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.qwarwin.fpvdrones.client.ModModels;
import com.qwarwin.fpvdrones.entity.StrikeDroneEntity;
import com.qwarwin.fpvdrones.entity.StrikeType;
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
public class StrikeDroneRenderer extends EntityRenderer<StrikeDroneEntity> {
    public static final float SCALE = 1.5F;

    public StrikeDroneRenderer(EntityRendererManager mgr) {
        super(mgr);
    }

    @Override
    public void render(StrikeDroneEntity e, float entityYaw, float partial, MatrixStack ms, IRenderTypeBuffer buf, int light) {
        ms.pushPose();
        ms.translate(0, StrikeDroneEntity.CENTER_HEIGHT, 0);
        ms.mulPose(Vector3f.YP.rotationDegrees(-MathHelper.rotLerp(partial, e.yRotO, e.yRot)));
        ms.mulPose(Vector3f.XP.rotationDegrees(MathHelper.lerp(partial, e.xRotO, e.xRot)));
        ms.mulPose(Vector3f.ZP.rotationDegrees(MathHelper.lerp(partial, e.rollO, e.roll)));
        ms.scale(SCALE, SCALE, SCALE);
        renderBody(e.getStrikeType(), ms, buf, light, MathHelper.lerp(partial, e.propO, e.prop), true);
        ms.popPose();
        super.render(e, entityYaw, partial, ms, buf, light);
    }

    /** Airframe centred on the origin, nose to +Z. Also used for the launcher and the truck rack. */
    public static void renderBody(StrikeType type, MatrixStack ms, IRenderTypeBuffer buf, int light, float propAngle, boolean spinning) {
        ms.pushPose();
        ms.translate(0, 0, type.getModelOffset() / 16F);
        ModModels.render(type.model(), ms, buf, light);
        if (type.hasPropeller()) {
            ms.pushPose();
            ms.translate(0, 0.25F / 16F, (-7.7F - 8F) / 16F);
            ms.mulPose(Vector3f.XP.rotationDegrees(90F));
            ms.mulPose(Vector3f.YP.rotationDegrees(propAngle));
            ms.scale(0.55F, 0.55F, 0.55F);
            ModModels.render(ModModels.PROP, ms, buf, light);
            if (spinning) {
                ModModels.render(ModModels.PROP_BLUR, ms, buf, light);
            }
            ms.popPose();
        }
        ms.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(StrikeDroneEntity e) {
        return AtlasTexture.LOCATION_BLOCKS;
    }
}
