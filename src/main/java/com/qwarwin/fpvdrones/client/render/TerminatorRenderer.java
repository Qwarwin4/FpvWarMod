package com.qwarwin.fpvdrones.client.render;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.qwarwin.fpvdrones.client.ModModels;
import com.qwarwin.fpvdrones.entity.TerminatorEntity;
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
public class TerminatorRenderer extends EntityRenderer<TerminatorEntity> {
    public static final float SCALE = 3F;

    public TerminatorRenderer(EntityRendererManager mgr) {
        super(mgr);
    }

    @Override
    public void render(TerminatorEntity e, float entityYaw, float partial, MatrixStack ms, IRenderTypeBuffer buf, int light) {
        float hullYaw = MathHelper.rotLerp(partial, e.yRotO, e.yRot);
        ms.pushPose();
        ms.mulPose(Vector3f.YP.rotationDegrees(-hullYaw));
        Parts.draw(ModModels.BMPT_HULL, ms, buf, light, SCALE);

        float wheel = MathHelper.lerp(partial, e.trackAngleO, e.trackAngle);
        for (double z : TerminatorEntity.ROAD_WHEELS) {
            for (int side = -1; side <= 1; side += 2) {
                ms.pushPose();
                ms.translate(side * TerminatorEntity.TRACK_X, TerminatorEntity.WHEEL_R + 0.12, z);
                ms.mulPose(Vector3f.XP.rotationDegrees(wheel));
                Parts.draw(ModModels.BMPT_WHEEL, ms, buf, light, 0.5F);
                ms.popPose();
            }
        }

        Vector3d p = TerminatorEntity.PIVOT;
        Vector3d t = TerminatorEntity.TRUNNION;
        ms.translate(p.x, p.y, p.z);
        ms.mulPose(Vector3f.YP.rotationDegrees(-MathHelper.wrapDegrees(e.getTurretYaw(partial) - hullYaw)));
        Parts.draw(ModModels.BMPT_TURRET, ms, buf, light, SCALE);
        ms.translate(t.x, t.y, t.z);
        ms.mulPose(Vector3f.XP.rotationDegrees(e.getGunPitch(partial)));
        Parts.draw(ModModels.BMPT_GUNS, ms, buf, light, SCALE);
        // loaded tubes show their front caps
        int fired = e.maxAmmo2() - e.getAmmo2();
        for (int i = fired; i < e.maxAmmo2(); i++) {
            Vector3d tube = TerminatorEntity.podTube(i);
            ms.pushPose();
            ms.translate(tube.x, tube.y, TerminatorEntity.POD_FRONT);
            Parts.draw(ModModels.ATAKA_CAP, ms, buf, light, 0.5F);
            ms.popPose();
        }
        ms.popPose();
        super.render(e, entityYaw, partial, ms, buf, light);
    }

    @Override
    public ResourceLocation getTextureLocation(TerminatorEntity e) {
        return AtlasTexture.LOCATION_BLOCKS;
    }
}
