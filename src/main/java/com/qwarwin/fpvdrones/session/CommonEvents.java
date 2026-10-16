package com.qwarwin.fpvdrones.session;

import com.qwarwin.fpvdrones.FpvDrones;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;

@Mod.EventBusSubscriber(modid = FpvDrones.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CommonEvents {

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.player instanceof ServerPlayerEntity) {
            SessionManager.tickPlayer((ServerPlayerEntity) event.player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getPlayer() instanceof ServerPlayerEntity) {
            SessionManager.onLogout((ServerPlayerEntity) event.getPlayer());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(FMLServerStoppingEvent event) {
        SessionManager.onServerStopping(event.getServer());
    }

    private CommonEvents() {
    }
}
