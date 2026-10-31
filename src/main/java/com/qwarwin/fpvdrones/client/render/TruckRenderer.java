package com.qwarwin.fpvdrones.client.render;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.qwarwin.fpvdrones.client.ModModels;
import com.qwarwin.fpvdrones.entity.LaunchTruckEntity;
import com.qwarwin.fpvdrones.entity.StrikeType;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

// truck models use 3/16 block per pixel, hence the scale(3)
@OnlyIn(Dist.CLIENT)
public class TruckRenderer extends EntityRenderer<LaunchTruckEntity> {
    public TruckRenderer(EntityRendererManager mgr) {
        super(mgr);
    }

    @Override
    public void render(LaunchTruckEntity e, float entityYaw, float partial, MatrixStack ms, IRenderTypeBuffer buf, int light) {
        if (e.isWrecked()) {
            // burnt out: soot-black whatever the light
            light = LightTexture.pack(1, Math.min(4, LightTexture.sky(light)));
        }
        ms.pushPose();
        ms.mulPose(Vector3f.YP.rotationDegrees(-MathHelper.rotLerp(partial, e.yRotO, e.yRot)));

        ms.pushPose();
        ms.translate(0, 1.5, 0);
        ms.scale(3F, 3F, 3F);
        ModModels.render(ModModels.TRUCK, ms, buf, light);
        ms.popPose();

        float wheel = MathHelper.lerp(partial, e.wheelAngleO, e.wheelAngle);
        for (double[] w : LaunchTruckEntity.WHEELS) {
            ms.pushPose();
            ms.translate(w[0], LaunchTruckEntity.WHEEL_RADIUS, w[1]);
            ms.mulPose(Vector3f.XP.rotationDegrees(wheel));
            ms.scale(3F, 3F, 3F);
            ModModels.render(ModModels.TRUCK_WHEEL, ms, buf, light);
            ms.popPose();
        }

        Vector3d pivot = LaunchTruckEntity.RACK_PIVOT;
        ms.pushPose();
        ms.translate(pivot.x, pivot.y, pivot.z);
        ms.mulPose(Vector3f.XP.rotationDegrees(LaunchTruckEntity.RACK_ANGLE));
        ms.pushPose();
        ms.scale(3F, 3F, 3F);
        ms.translate(0, 0.5, -1);
        ModModels.render(ModModels.TRUCK_RACK, ms, buf, light);
        ms.popPose();
        for (int i = 0; i < LaunchTruckEntity.CAPACITY; i++) {
            StrikeType t = e.isWrecked() ? null : e.getLoaded(i);
            if (t == null) {
                continue;
            }
            ms.pushPose();
            ms.translate(0, LaunchTruckEntity.RACK_FIRST + i * LaunchTruckEntity.RACK_STEP, LaunchTruckEntity.RACK_DRONE_Z);
            ms.mulPose(Vector3f.YP.rotationDegrees(180F));
            float s = LaunchTruckEntity.DRONE_SCALE;
            ms.scale(s, s, s);
            StrikeDroneRenderer.renderBody(t, ms, buf, light, i * 40F, false);
            ms.popPose();
        }
        ms.popPose();

        ms.popPose();
        super.render(e, entityYaw, partial, ms, buf, light);
    }

    @Override
    public ResourceLocation getTextureLocation(LaunchTruckEntity e) {
        return AtlasTexture.LOCATION_BLOCKS;
    }
}
