package com.qwarwin.fpvdrones.client.render;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.qwarwin.fpvdrones.client.ModModels;
import com.qwarwin.fpvdrones.entity.KornetEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class KornetRenderer extends EntityRenderer<KornetEntity> {
    public static final float SCALE = 1F;

    public KornetRenderer(EntityRendererManager mgr) {
        super(mgr);
    }

    @Override
    public void render(KornetEntity e, float entityYaw, float partial, MatrixStack ms, IRenderTypeBuffer buf, int light) {
        ms.pushPose();
        ms.mulPose(Vector3f.YP.rotationDegrees(-e.yRot));
        Parts.draw(ModModels.KORNET_TRIPOD, ms, buf, light, SCALE);
        ms.popPose();

        // the gunner's eye sits right where the launcher is; drawing it would just put a wall in front of the sight
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && e.hasPassenger(mc.player) && mc.options.getCameraType().isFirstPerson()) {
            super.render(e, entityYaw, partial, ms, buf, light);
            return;
        }

        Vector3d p = KornetEntity.PIVOT;
        Vector3d t = KornetEntity.TRUNNION;
        ms.pushPose();
        ms.translate(p.x, p.y, p.z);
        ms.mulPose(Vector3f.YP.rotationDegrees(-e.getTurretYaw(partial)));
        Parts.draw(ModModels.KORNET_HEAD, ms, buf, light, SCALE);
        ms.translate(t.x, t.y, t.z);
        ms.mulPose(Vector3f.XP.rotationDegrees(e.getGunPitch(partial)));
        Parts.draw(ModModels.KORNET_SIGHT, ms, buf, light, SCALE);
        if (e.getAmmo() > 0) {
            Parts.draw(ModModels.KORNET_TUBE, ms, buf, light, SCALE);
        }
        ms.popPose();
        super.render(e, entityYaw, partial, ms, buf, light);
    }

    @Override
    public ResourceLocation getTextureLocation(KornetEntity e) {
        return AtlasTexture.LOCATION_BLOCKS;
    }
}
