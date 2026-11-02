package com.qwarwin.fpvdrones.client.render;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.qwarwin.fpvdrones.client.ModModels;
import com.qwarwin.fpvdrones.entity.InterceptorEntity;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** The interceptor: fuselage nose-first along its path, four props on the arms spinning while it flies. */
@OnlyIn(Dist.CLIENT)
public class InterceptorRenderer extends EntityRenderer<InterceptorEntity> {
    // the motors' tops in blocks from the centre of the fuselage (tools/gen_air.py)
    private static final float[][] ROTORS = {{0.19F, 0.07F, 0.13F}, {-0.19F, 0.07F, 0.13F}, {0.19F, 0.07F, -0.13F}, {-0.19F, 0.07F, -0.13F}};
    public static final float BODY_Y = 0.11F;

    public InterceptorRenderer(EntityRendererManager mgr) {
        super(mgr);
    }

    @Override
    public void render(InterceptorEntity e, float entityYaw, float partial, MatrixStack ms, IRenderTypeBuffer buf, int light) {
        ms.pushPose();
        ms.translate(0, BODY_Y, 0);
        ms.mulPose(Vector3f.YP.rotationDegrees(-MathHelper.rotLerp(partial, e.yRotO, e.yRot)));
        if (e.isFlying()) {
            ms.mulPose(Vector3f.XP.rotationDegrees(MathHelper.lerp(partial, e.xRotO, e.xRot)));
        }
        ms.pushPose();
        ms.translate(0, -BODY_Y, 0);
        Parts.draw(ModModels.INTERCEPTOR, ms, buf, light, 1F);
        ms.popPose();
        float angle = MathHelper.lerp(partial, e.propAngleO, e.propAngle);
        for (int i = 0; i < ROTORS.length; i++) {
            float[] r = ROTORS[i];
            ms.pushPose();
            ms.translate(r[0], r[1], r[2]);
            boolean cw = i == 0 || i == 3;
            ms.mulPose(Vector3f.YP.rotationDegrees((cw ? angle : -angle) + i * 41F));
            ms.scale(0.28F, 0.28F, 0.28F);
            ModModels.render(ModModels.PROP, ms, buf, light);
            if (e.isFlying()) {
                ModModels.render(ModModels.PROP_BLUR, ms, buf, light);
            }
            ms.popPose();
        }
        ms.popPose();
        super.render(e, entityYaw, partial, ms, buf, light);
    }

    @Override
    public ResourceLocation getTextureLocation(InterceptorEntity e) {
        return AtlasTexture.LOCATION_BLOCKS;
    }
}
