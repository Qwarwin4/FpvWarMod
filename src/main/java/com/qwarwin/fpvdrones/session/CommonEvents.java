package com.qwarwin.fpvdrones.session;

import com.qwarwin.fpvdrones.FpvDrones;
import com.qwarwin.fpvdrones.entity.TerminatorEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.DamageSource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
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

    /** Inside the BMPT the crew only gets hurt when the vehicle itself goes. */
    @SubscribeEvent
    public static void onCrewAttacked(LivingAttackEvent event) {
        Entity vehicle = event.getEntityLiving().getVehicle();
        DamageSource src = event.getSource();
        if (vehicle instanceof TerminatorEntity && vehicle.isAlive() && !src.isBypassInvul()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(FMLServerStoppingEvent event) {
        SessionManager.onServerStopping(event.getServer());
    }

    private CommonEvents() {
    }
}
