package com.qwarwin.fpvdrones.client.render;

import com.qwarwin.fpvdrones.entity.OperatorEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.play.NetworkPlayerInfo;
import net.minecraft.client.renderer.entity.EntityRendererManager;
import net.minecraft.client.renderer.entity.LivingRenderer;
import net.minecraft.client.renderer.entity.layers.BipedArmorLayer;
import net.minecraft.client.renderer.entity.layers.HeldItemLayer;
import net.minecraft.client.renderer.entity.model.BipedModel;
import net.minecraft.client.renderer.entity.model.PlayerModel;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.UUID;

@OnlyIn(Dist.CLIENT)
public class OperatorRenderer extends LivingRenderer<OperatorEntity, PlayerModel<OperatorEntity>> {
    public OperatorRenderer(EntityRendererManager mgr) {
        super(mgr, new PlayerModel<>(0.0F, false), 0.5F);
        addLayer(new BipedArmorLayer<>(this, new BipedModel<>(0.5F), new BipedModel<>(1.0F)));
        addLayer(new HeldItemLayer<>(this));
        addLayer(new com.qwarwin.fpvdrones.client.GogglesLayer<>(this));
    }

    @Override
    public ResourceLocation getTextureLocation(OperatorEntity e) {
        UUID owner = e.getOwner().orElse(e.getUUID());
        if (Minecraft.getInstance().getConnection() != null) {
            NetworkPlayerInfo info = Minecraft.getInstance().getConnection().getPlayerInfo(owner);
            if (info != null) {
                return info.getSkinLocation();
            }
        }
        return DefaultPlayerSkin.getDefaultSkin(owner);
    }
}
