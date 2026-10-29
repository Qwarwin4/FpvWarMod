package com.qwarwin.fpvdrones.client.render;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.qwarwin.fpvdrones.entity.RadioMachineEntity;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** The EW truck, the EW tower and the relay mast: one real model each, in blocks, on the entity's yaw. */
@OnlyIn(Dist.CLIENT)
public class RadioMachineRenderer<T extends RadioMachineEntity> extends EntityRenderer<T> {
    private final ResourceLocation model;

    public RadioMachineRenderer(EntityRendererManager mgr, ResourceLocation model) {
        super(mgr);
        this.model = model;
    }

    @Override
    public void render(T e, float entityYaw, float partial, MatrixStack ms, IRenderTypeBuffer buf, int light) {
        ms.pushPose();
        ms.mulPose(Vector3f.YP.rotationDegrees(-MathHelper.rotLerp(partial, e.yRotO, e.yRot)));
        Parts.draw(model, ms, buf, light, 1F);
        ms.popPose();
        super.render(e, entityYaw, partial, ms, buf, light);
    }

    @Override
    public ResourceLocation getTextureLocation(T e) {
        return AtlasTexture.LOCATION_BLOCKS;
    }
}
