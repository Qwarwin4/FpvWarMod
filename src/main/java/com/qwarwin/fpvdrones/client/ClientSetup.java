package com.qwarwin.fpvdrones.client;

import com.qwarwin.fpvdrones.FpvDrones;
import com.qwarwin.fpvdrones.client.render.DroneRenderer;
import com.qwarwin.fpvdrones.client.render.LauncherRenderer;
import com.qwarwin.fpvdrones.client.render.MunitionRenderer;
import com.qwarwin.fpvdrones.client.render.OperatorRenderer;
import com.qwarwin.fpvdrones.client.render.StrikeDroneRenderer;
import com.qwarwin.fpvdrones.client.render.TruckRenderer;
import com.qwarwin.fpvdrones.registry.ModBlocks;
import com.qwarwin.fpvdrones.registry.ModEntities;
import com.qwarwin.fpvdrones.registry.ModTileEntities;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.RenderTypeLookup;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = FpvDrones.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientSetup {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        RenderingRegistry.registerEntityRenderingHandler(ModEntities.FPV_DRONE.get(), DroneRenderer::fpv);
        RenderingRegistry.registerEntityRenderingHandler(ModEntities.MAVIC.get(), DroneRenderer::mavic);
        RenderingRegistry.registerEntityRenderingHandler(ModEntities.STRIKE_DRONE.get(), StrikeDroneRenderer::new);
        RenderingRegistry.registerEntityRenderingHandler(ModEntities.LAUNCH_TRUCK.get(), TruckRenderer::new);
        RenderingRegistry.registerEntityRenderingHandler(ModEntities.MUNITION.get(), MunitionRenderer::new);
        RenderingRegistry.registerEntityRenderingHandler(ModEntities.OPERATOR.get(), OperatorRenderer::new);
        ClientRegistry.bindTileEntityRenderer(ModTileEntities.LAUNCHER.get(), LauncherRenderer::new);
        event.enqueueWork(() -> {
            RenderTypeLookup.setRenderLayer(ModBlocks.LAUNCHER.get(), RenderType.cutout());
            RenderTypeLookup.setRenderLayer(ModBlocks.LAUNCH_PAD.get(), RenderType.cutout());
        });
        KeyBinds.register();
    }

    @SubscribeEvent
    public static void onModelRegistry(ModelRegistryEvent event) {
        for (ResourceLocation rl : ModModels.all()) {
            ModelLoader.addSpecialModel(rl);
        }
    }

    private ClientSetup() {
    }
}
