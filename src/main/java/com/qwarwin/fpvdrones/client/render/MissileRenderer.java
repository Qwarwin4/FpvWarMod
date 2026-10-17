package com.qwarwin.fpvdrones.client.render;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.qwarwin.fpvdrones.client.ModModels;
import com.qwarwin.fpvdrones.entity.MissileEntity;
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
public class MissileRenderer extends EntityRenderer<MissileEntity> {
    public MissileRenderer(EntityRendererManager mgr) {
        super(mgr);
    }

    @Override
    public void render(MissileEntity e, float entityYaw, float partial, MatrixStack ms, IRenderTypeBuffer buf, int light) {
        ms.pushPose();
        ms.translate(0, 0.15, 0);
        ms.mulPose(Vector3f.YP.rotationDegrees(-MathHelper.rotLerp(partial, e.yRotO, e.yRot)));
        ms.mulPose(Vector3f.XP.rotationDegrees(MathHelper.lerp(partial, e.xRotO, e.xRot)));
        // slow roll like the real thing
        ms.mulPose(Vector3f.ZP.rotationDegrees((e.tickCount + partial) * 12F));
        ModModels.render(e.getMissileType().model(), ms, buf, light);
        ms.popPose();
        super.render(e, entityYaw, partial, ms, buf, light);
    }

    @Override
    public ResourceLocation getTextureLocation(MissileEntity e) {
        return AtlasTexture.LOCATION_BLOCKS;
    }
}
