package com.qwarwin.fpvdrones.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import com.qwarwin.fpvdrones.FpvDrones;
import com.qwarwin.fpvdrones.entity.StrikeType;
import com.qwarwin.fpvdrones.item.PayloadType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Atlases;
import net.minecraft.client.renderer.IRenderTypeBuffer;
import net.minecraft.client.renderer.model.BakedQuad;
import net.minecraft.client.renderer.model.IBakedModel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Direction;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Plain JSON models drawn by entity renderers. Registered as special models so they get baked. */
@OnlyIn(Dist.CLIENT)
public final class ModModels {
    public static final ResourceLocation FPV = FpvDrones.id("entity/fpv_drone");
    public static final ResourceLocation MAVIC = FpvDrones.id("entity/mavic3");
    public static final ResourceLocation PROP = FpvDrones.id("entity/propeller");
    public static final ResourceLocation PROP_BLUR = FpvDrones.id("entity/prop_blur");
    public static final ResourceLocation TRUCK = FpvDrones.id("entity/truck");
    public static final ResourceLocation TRUCK_WHEEL = FpvDrones.id("entity/truck_wheel");
    public static final ResourceLocation TRUCK_RACK = FpvDrones.id("entity/truck_rack");

    private static final Random RANDOM = new Random();

    public static List<ResourceLocation> all() {
        List<ResourceLocation> list = new ArrayList<>();
        list.add(FPV);
        list.add(MAVIC);
        list.add(PROP);
        list.add(PROP_BLUR);
        list.add(TRUCK);
        list.add(TRUCK_WHEEL);
        list.add(TRUCK_RACK);
        for (StrikeType t : StrikeType.values()) {
            list.add(t.model());
        }
        for (PayloadType t : PayloadType.values()) {
            if (t != PayloadType.NONE) {
                list.add(t.model());
            }
        }
        return list;
    }

    /** Draws the model with its (8,8,8) centre on the current origin. */
    public static void render(ResourceLocation id, MatrixStack ms, IRenderTypeBuffer buffers, int light) {
        IBakedModel model = Minecraft.getInstance().getModelManager().getModel(id);
        IVertexBuilder vb = buffers.getBuffer(Atlases.translucentCullBlockSheet());
        ms.pushPose();
        ms.translate(-0.5, -0.5, -0.5);
        MatrixStack.Entry entry = ms.last();
        for (Direction d : Direction.values()) {
            RANDOM.setSeed(42L);
            for (BakedQuad q : model.getQuads(null, d, RANDOM)) {
                vb.addVertexData(entry, q, 1F, 1F, 1F, light, OverlayTexture.NO_OVERLAY, true);
            }
        }
        RANDOM.setSeed(42L);
        for (BakedQuad q : model.getQuads(null, null, RANDOM)) {
            vb.addVertexData(entry, q, 1F, 1F, 1F, light, OverlayTexture.NO_OVERLAY, true);
        }
        ms.popPose();
    }

    private ModModels() {
    }
}
