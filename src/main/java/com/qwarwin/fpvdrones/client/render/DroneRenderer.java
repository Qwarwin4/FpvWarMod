package com.qwarwin.fpvdrones.client.render;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.qwarwin.fpvdrones.client.ModModels;
import com.qwarwin.fpvdrones.entity.AbstractDroneEntity;
import com.qwarwin.fpvdrones.entity.FpvDroneEntity;
import com.qwarwin.fpvdrones.entity.MavicEntity;
import com.qwarwin.fpvdrones.item.PayloadType;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Body + spinning props + whatever hangs underneath. The entity origin is the bottom of the
 * payload, so the frame is lifted by {@link AbstractDroneEntity#getHang()}.
 * Numbers in model pixels match tools/gen_assets.py.
 */
@OnlyIn(Dist.CLIENT)
public class DroneRenderer extends EntityRenderer<AbstractDroneEntity> {
    private static final float FPV_SCALE = 0.9F;
    private static final float FPV_BOTTOM = 6.5F;
    // on the motors of the real models (tools/gen_meshes.py prints them)
    private static final float[][] FPV_PROPS = {{2, 8.25F, 2}, {14, 8.25F, 2}, {2, 8.25F, 14}, {14, 8.25F, 14}};

    private static final float MAVIC_SCALE = 0.85F;
    private static final float MAVIC_BOTTOM = 6.0F;
    private static final float[][] MAVIC_PROPS = {
            {2.63F, 7.69F, 13.33F}, {13.37F, 7.69F, 13.33F}, {2.17F, 7.69F, 2.87F}, {13.83F, 7.69F, 2.87F}};

    private final boolean mavic;

    private DroneRenderer(EntityRendererManager mgr, boolean mavic) {
        super(mgr);
        this.mavic = mavic;
    }

    public static DroneRenderer fpv(EntityRendererManager mgr) {
        return new DroneRenderer(mgr, false);
    }

    public static DroneRenderer mavic(EntityRendererManager mgr) {
        return new DroneRenderer(mgr, true);
    }

    @Override
    public void render(AbstractDroneEntity e, float entityYaw, float partial, MatrixStack ms, IRenderTypeBuffer buf, int light) {
        float scale = mavic ? MAVIC_SCALE : FPV_SCALE;
        float bottom = mavic ? MAVIC_BOTTOM : FPV_BOTTOM;
        float[][] props = mavic ? MAVIC_PROPS : FPV_PROPS;

        ms.pushPose();
        ms.translate(0, e.getHang(), 0);
        ms.mulPose(Vector3f.YP.rotationDegrees(-MathHelper.rotLerp(partial, e.yRotO, e.yRot)));
        ms.mulPose(Vector3f.XP.rotationDegrees(MathHelper.lerp(partial, e.tiltPitchO, e.tiltPitch)));
        ms.mulPose(Vector3f.ZP.rotationDegrees(MathHelper.lerp(partial, e.tiltRollO, e.tiltRoll)));

        ms.pushPose();
        ms.translate(0, (8F - bottom) / 16F * scale, 0);
        ms.scale(scale, scale, scale);
        ModModels.render(mavic ? ModModels.MAVIC : ModModels.FPV, ms, buf, light);
        float angle = MathHelper.lerp(partial, e.propAngleO, e.propAngle);
        for (int i = 0; i < props.length; i++) {
            float[] p = props[i];
            ms.pushPose();
            ms.translate((p[0] - 8F) / 16F, (p[1] - 8F) / 16F, (p[2] - 8F) / 16F);
            boolean cw = i == 0 || i == 3;
            ms.mulPose(Vector3f.YP.rotationDegrees((cw ? angle : -angle) + i * 37F));
            float ps = mavic ? 0.32F : 0.5F;
            ms.scale(ps, ps, ps);
            ModModels.render(mavic ? ModModels.PROP_MAVIC : ModModels.PROP, ms, buf, light);
            if (e.isPowered()) {
                ModModels.render(ModModels.PROP_BLUR, ms, buf, light);
            }
            ms.popPose();
        }
        ms.popPose();

        if (e instanceof FpvDroneEntity) {
            renderFpvPayload(((FpvDroneEntity) e).getPayload(), ms, buf, light);
        } else if (e instanceof MavicEntity) {
            MavicEntity m = (MavicEntity) e;
            for (int i = 0; i < MavicEntity.SLOTS; i++) {
                renderMavicPayload(m.getSlot(i), i, ms, buf, light);
            }
        }
        ms.popPose();
        super.render(e, entityYaw, partial, ms, buf, light);
    }

    // origin here = centre of the frame's bottom plate
    private static void renderFpvPayload(PayloadType t, MatrixStack ms, IRenderTypeBuffer buf, int light) {
        float s, dy, dz;
        switch (t) {
            case PG7VS:
                s = 0.55F;
                dy = -0.065F;
                dz = 0.24F;
                break;
            case OG7:
                s = 0.55F;
                dy = -0.06F;
                dz = 0.22F;
                break;
            case CANISTER:
                s = 0.5F;
                dy = -0.083F;
                dz = 0.03F;
                break;
            default:
                return;
        }
        ms.pushPose();
        ms.translate(0, dy, dz);
        if (t == PayloadType.CANISTER) {
            // strapped on its side like the RPG warheads, cap forward
            ms.mulPose(Vector3f.XP.rotationDegrees(90F));
        }
        ms.scale(s, s, s);
        ModModels.render(t.model(), ms, buf, light);
        ms.popPose();
    }

    private static void renderMavicPayload(PayloadType t, int slot, MatrixStack ms, IRenderTypeBuffer buf, int light) {
        if (t == PayloadType.NONE) {
            return;
        }
        boolean vog = t == PayloadType.VOG17M;
        ms.pushPose();
        ms.translate(slot == 0 ? -MavicEntity.HOOK_X : MavicEntity.HOOK_X, vog ? -0.118 : -0.07, 0);
        if (vog) {
            // hangs fuze down, printed tail up
            ms.mulPose(Vector3f.XP.rotationDegrees(180F));
        }
        float s = vog ? 0.33F : 0.38F;
        ms.scale(s, s, s);
        ModModels.render(t.model(), ms, buf, light);
        ms.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(AbstractDroneEntity e) {
        return AtlasTexture.LOCATION_BLOCKS;
    }
}
