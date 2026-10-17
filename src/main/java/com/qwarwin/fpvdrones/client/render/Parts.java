package com.qwarwin.fpvdrones.client.render;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.qwarwin.fpvdrones.client.ModModels;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Vehicle parts are authored with their origin at model (8, 0, 8) and {@code scale}/16 blocks per unit,
 * so the current matrix origin is the part's pivot.
 */
@OnlyIn(Dist.CLIENT)
final class Parts {
    static void draw(ResourceLocation model, MatrixStack ms, IRenderTypeBuffer buf, int light, float scale) {
        ms.pushPose();
        ms.translate(0, scale / 2F, 0);
        ms.scale(scale, scale, scale);
        ModModels.render(model, ms, buf, light);
        ms.popPose();
    }

    private Parts() {
    }
}
