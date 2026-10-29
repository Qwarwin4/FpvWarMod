package com.qwarwin.fpvdrones.client.render;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.qwarwin.fpvdrones.client.ModModels;
import com.qwarwin.fpvdrones.entity.AirDefenseGunEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class AirDefenseGunRenderer extends EntityRenderer<AirDefenseGunEntity> {
    public static final float SCALE = 2F;

    public AirDefenseGunRenderer(EntityRendererManager mgr) {
        super(mgr);
    }

    @Override
    public void render(AirDefenseGunEntity e, float entityYaw, float partial, MatrixStack ms, IRenderTypeBuffer buf, int light) {
        ms.pushPose();
        ms.mulPose(Vector3f.YP.rotationDegrees(-e.yRot));
        Parts.draw(ModModels.ADGUN_BASE, ms, buf, light, SCALE);
        ms.popPose();

        // the gunner's eye is at the optics head on the turret: drawing it would wall off the sight
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && e.hasPassenger(mc.player) && mc.options.getCameraType().isFirstPerson()) {
            super.render(e, entityYaw, partial, ms, buf, light);
            return;
        }

        Vector3d p = AirDefenseGunEntity.PIVOT;
        Vector3d t = AirDefenseGunEntity.TRUNNION;
        ms.pushPose();
        ms.translate(p.x, p.y, p.z);
        ms.mulPose(Vector3f.YP.rotationDegrees(-e.getTurretYaw(partial)));
        Parts.draw(ModModels.ADGUN_TURRET, ms, buf, light, SCALE);
        ms.translate(t.x, t.y, t.z);
        ms.mulPose(Vector3f.XP.rotationDegrees(MathHelper.clamp(e.getGunPitch(partial), -80F, 10F)));
        Parts.draw(ModModels.ADGUN_GUN, ms, buf, light, SCALE);
        ms.popPose();
        super.render(e, entityYaw, partial, ms, buf, light);
    }

    @Override
    public ResourceLocation getTextureLocation(AirDefenseGunEntity e) {
        return AtlasTexture.LOCATION_BLOCKS;
    }
}
