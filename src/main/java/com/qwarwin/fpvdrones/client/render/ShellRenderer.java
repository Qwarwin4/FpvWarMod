package com.qwarwin.fpvdrones.client.render;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.qwarwin.fpvdrones.client.ModModels;
import com.qwarwin.fpvdrones.entity.ShellEntity;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** Tracer streak, always full bright. */
@OnlyIn(Dist.CLIENT)
public class ShellRenderer extends EntityRenderer<ShellEntity> {
    private static final int FULL_BRIGHT = 0xF000F0;

    public ShellRenderer(EntityRendererManager mgr) {
        super(mgr);
    }

    @Override
    public void render(ShellEntity e, float entityYaw, float partial, MatrixStack ms, IRenderTypeBuffer buf, int light) {
        if (e.tickCount < 1) {
            return;
        }
        ms.pushPose();
        ms.mulPose(Vector3f.YP.rotationDegrees(-MathHelper.rotLerp(partial, e.yRotO, e.yRot)));
        ms.mulPose(Vector3f.XP.rotationDegrees(MathHelper.lerp(partial, e.xRotO, e.xRot)));
        ms.translate(0, 0, -1.0);
        ModModels.render(ModModels.TRACER, ms, buf, FULL_BRIGHT);
        ms.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(ShellEntity e) {
        return AtlasTexture.LOCATION_BLOCKS;
    }
}
