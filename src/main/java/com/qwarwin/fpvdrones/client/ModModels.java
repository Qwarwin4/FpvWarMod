package com.qwarwin.fpvdrones.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import com.qwarwin.fpvdrones.FpvDrones;
import com.qwarwin.fpvdrones.entity.MissileType;
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
    public static final ResourceLocation ADGUN_BASE = FpvDrones.id("entity/adgun_base");
    public static final ResourceLocation ADGUN_TURRET = FpvDrones.id("entity/adgun_turret");
    public static final ResourceLocation ADGUN_GUN = FpvDrones.id("entity/adgun_gun");
    public static final ResourceLocation BMPT_HULL = FpvDrones.id("entity/bmpt_hull");
    public static final ResourceLocation BMPT_WHEEL = FpvDrones.id("entity/bmpt_wheel");
    public static final ResourceLocation BMPT_TURRET = FpvDrones.id("entity/bmpt_turret");
    public static final ResourceLocation BMPT_GUNS = FpvDrones.id("entity/bmpt_guns");
    public static final ResourceLocation ATAKA_CAP = FpvDrones.id("entity/ataka_cap");
    public static final ResourceLocation KORNET_TRIPOD = FpvDrones.id("entity/kornet_tripod");
    public static final ResourceLocation KORNET_HEAD = FpvDrones.id("entity/kornet_head");
    public static final ResourceLocation KORNET_SIGHT = FpvDrones.id("entity/kornet_sight");
    public static final ResourceLocation KORNET_TUBE = FpvDrones.id("entity/kornet_tube");
    public static final ResourceLocation TRACER = FpvDrones.id("entity/tracer");

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
        list.add(ADGUN_BASE);
        list.add(ADGUN_TURRET);
        list.add(ADGUN_GUN);
        list.add(BMPT_HULL);
        list.add(BMPT_WHEEL);
        list.add(BMPT_TURRET);
        list.add(BMPT_GUNS);
        list.add(ATAKA_CAP);
        list.add(KORNET_TRIPOD);
        list.add(KORNET_HEAD);
        list.add(KORNET_SIGHT);
        list.add(KORNET_TUBE);
        list.add(TRACER);
        for (MissileType t : MissileType.values()) {
            list.add(t.model());
        }
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
