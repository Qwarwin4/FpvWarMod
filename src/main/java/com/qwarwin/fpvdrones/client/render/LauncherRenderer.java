package com.qwarwin.fpvdrones.client.render;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.qwarwin.fpvdrones.block.LauncherTileEntity;
import com.qwarwin.fpvdrones.entity.StrikeType;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.tileentity.TileEntityRenderer;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class LauncherRenderer extends TileEntityRenderer<LauncherTileEntity> {
    public LauncherRenderer(TileEntityRendererDispatcher dispatcher) {
        super(dispatcher);
    }

    @Override
    public void render(LauncherTileEntity te, float partial, MatrixStack ms, IRenderTypeBuffer buf, int light, int overlay) {
        StrikeType t = te.getLoaded(0);
        if (t == null) {
            return;
        }
        ms.pushPose();
        ms.translate(0.5, 0.74, 0.5);
        ms.mulPose(Vector3f.YP.rotationDegrees(-te.getFacing().toYRot()));
        ms.mulPose(Vector3f.XP.rotationDegrees(-LauncherTileEntity.RAIL_ANGLE));
        StrikeDroneRenderer.renderBody(t, ms, buf, light, 15F, false);
        ms.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen(LauncherTileEntity te) {
        return true;
    }
}
