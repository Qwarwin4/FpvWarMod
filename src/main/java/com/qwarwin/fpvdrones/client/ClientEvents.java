package com.qwarwin.fpvdrones.client;

import com.qwarwin.fpvdrones.FpvDrones;
import com.qwarwin.fpvdrones.control.ControlState;
import com.qwarwin.fpvdrones.entity.AbstractDroneEntity;
import com.qwarwin.fpvdrones.entity.DroneKind;
import com.qwarwin.fpvdrones.entity.KornetEntity;
import com.qwarwin.fpvdrones.entity.LaunchTruckEntity;
import com.qwarwin.fpvdrones.entity.MountedWeaponEntity;
import com.qwarwin.fpvdrones.entity.StrikeDroneEntity;
import com.qwarwin.fpvdrones.item.JavelinItem;
import com.qwarwin.fpvdrones.network.DroneActionPacket;
import com.qwarwin.fpvdrones.network.Network;
import com.qwarwin.fpvdrones.network.WeaponInputPacket;
import net.minecraft.client.GameSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.util.MovementInput;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.InputUpdateEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = FpvDrones.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientEvents {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onInputUpdate(InputUpdateEvent event) {
        if (!ClientSession.active) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        MovementInput in = event.getMovementInput();
        if (ClientSession.kind == DroneKind.STRIKE && in.shiftKeyDown && !ClientSession.exitSent) {
            // nothing to fly on the tablet, so sneak works as "exit" like in vanilla spectating
            ClientSession.exitSent = true;
            Network.CHANNEL.sendToServer(new DroneActionPacket(DroneActionPacket.TOGGLE_VIEW));
        }
        if (ClientSession.kind != DroneKind.STRIKE && mc.player != null) {
            ControlState.forward = in.forwardImpulse;
            ControlState.strafe = in.leftImpulse;
            ControlState.vertical = (in.jumping ? 1F : 0F) - (in.shiftKeyDown ? 1F : 0F);
            ControlState.boost = mc.options.keySprint.isDown();
            ControlState.yaw = mc.player.yRot;
            ControlState.pitch = mc.player.xRot;
        }
        // the body stays put, and sneaking would drop us out of spectating
        in.forwardImpulse = 0F;
        in.leftImpulse = 0F;
        in.up = in.down = in.left = in.right = false;
        in.jumping = false;
        in.shiftKeyDown = false;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            Sensitivity.restore(mc);
            if (ClientSession.active) {
                ClientSession.reset();
                ControlState.reset();
            }
            return;
        }
        if (event.phase == TickEvent.Phase.START) {
            if (ClientSession.active) {
                swallowVanillaKeys(mc.options);
                Entity cam = mc.level.getEntity(ClientSession.entityId);
                if (cam != null && mc.getCameraEntity() != cam) {
                    mc.setCameraEntity(cam);
                }
                if (ClientSession.kind == DroneKind.MAVIC) {
                    mc.player.xRot = MathHelper.clamp(mc.player.xRot, -30F, 90F);
                    for (int n = KeyBinds.presses(KeyBinds.ZOOM_IN); n > 0; n--) {
                        ClientSession.zoom = Math.min(28F, ClientSession.zoom * 1.5F);
                    }
                    for (int n = KeyBinds.presses(KeyBinds.ZOOM_OUT); n > 0; n--) {
                        ClientSession.zoom = Math.max(1F, ClientSession.zoom / 1.5F);
                    }
                    for (int n = KeyBinds.presses(KeyBinds.RETURN_HOME); n > 0; n--) {
                        Network.CHANNEL.sendToServer(new DroneActionPacket(DroneActionPacket.RETURN_HOME, ClientSession.aim));
                    }
                }
                for (int n = KeyBinds.presses(KeyBinds.ACTION); n > 0; n--) {
                    Network.CHANNEL.sendToServer(new DroneActionPacket(DroneActionPacket.PRIMARY));
                }
            } else if (mc.player.getVehicle() instanceof MountedWeaponEntity) {
                gunnerInput(mc);
            } else {
                lastTrigger = false;
                GunnerHud.aiming = false;
                if (aimingJavelin(mc)) {
                    // RMB holds the seeker up, LMB launches
                    while (mc.options.keyAttack.consumeClick()) {
                        Network.CHANNEL.sendToServer(new DroneActionPacket(DroneActionPacket.JAVELIN_FIRE));
                    }
                }
                for (int n = KeyBinds.presses(KeyBinds.ACTION); n > 0; n--) {
                    if (mc.player.getVehicle() instanceof LaunchTruckEntity) {
                        Network.CHANNEL.sendToServer(new DroneActionPacket(DroneActionPacket.VEHICLE_MENU));
                    }
                }
            }
            if (KeyBinds.pressed(KeyBinds.TOGGLE_VIEW)) {
                Network.CHANNEL.sendToServer(new DroneActionPacket(DroneActionPacket.TOGGLE_VIEW));
            }
            KeyBinds.clearRaw();
        } else {
            if (ClientSession.active) {
                ClientSession.ticks++;
                updateAim(mc);
                // the stop packet got lost or arrived out of order: don't keep the camera glued to the drone
                ClientSession.desyncTicks = mc.player.isSpectator() ? 0 : ClientSession.desyncTicks + 1;
                if (ClientSession.desyncTicks > 40) {
                    ClientSession.reset();
                    ControlState.reset();
                    mc.setCameraEntity(mc.player);
                }
            }
            if (ClientSession.lostTicks > 0) {
                ClientSession.lostTicks--;
            }
            Sensitivity.update(mc, currentZoom(mc));
        }
    }

    /** How much the view is magnified right now, for mouse sensitivity. */
    private static float currentZoom(Minecraft mc) {
        if (ClientSession.active) {
            return ClientSession.kind == DroneKind.MAVIC ? ClientSession.zoom : 1F;
        }
        if (atGun(mc)) {
            return GunnerHud.viewZoom((MountedWeaponEntity) mc.player.getVehicle());
        }
        return aimingJavelin(mc) ? JAVELIN_ZOOM : 1F;
    }

    private static final float JAVELIN_ZOOM = 4F;

    private static boolean lastTrigger;

    /** Sitting at a gun: LMB is the trigger, RMB the second weapon, G the mode switch. */
    private static void gunnerInput(Minecraft mc) {
        GameSettings o = mc.options;
        boolean trigger = mc.screen == null && o.keyAttack.isDown();
        boolean secondary = false, action = false;
        boolean kornet = mc.player.getVehicle() instanceof KornetEntity;
        while (o.keyAttack.consumeClick()) {
            // handled through isDown
        }
        while (o.keyUse.consumeClick()) {
            // on the Kornet RMB is "look through the sight", held rather than clicked
            secondary = mc.screen == null && !kornet;
        }
        GunnerHud.aiming = kornet && mc.screen == null && o.keyUse.isDown();
        for (int n = KeyBinds.presses(KeyBinds.ACTION); n > 0; n--) {
            action = true;
        }
        for (int n = KeyBinds.presses(KeyBinds.ZOOM_IN); n > 0; n--) {
            GunnerHud.zoomBy(1.5F);
        }
        for (int n = KeyBinds.presses(KeyBinds.ZOOM_OUT); n > 0; n--) {
            GunnerHud.zoomBy(1F / 1.5F);
        }
        if (trigger != lastTrigger || secondary || action) {
            lastTrigger = trigger;
            Network.CHANNEL.sendToServer(new WeaponInputPacket(trigger, secondary, action));
        }
    }

    private static boolean atGun(Minecraft mc) {
        return mc.player != null && mc.player.getVehicle() instanceof MountedWeaponEntity;
    }

    private static boolean aimingJavelin(Minecraft mc) {
        return mc.player != null && mc.player.isUsingItem() && mc.player.getUseItem().getItem() instanceof JavelinItem;
    }

    private static void swallowVanillaKeys(GameSettings o) {
        for (KeyBinding k : o.keyHotbarSlots) {
            while (k.consumeClick()) {
                // spectator menu
            }
        }
        while (o.keyInventory.consumeClick()) {
            // no inventory while flying
        }
        while (o.keySwapOffhand.consumeClick()) {
            // nope
        }
        while (o.keyDrop.consumeClick()) {
            // nope
        }
    }

    private static void updateAim(Minecraft mc) {
        Entity cam = mc.getCameraEntity();
        if (cam == null || mc.level == null || ClientSession.kind == DroneKind.FPV) {
            ClientSession.aim = null;
            return;
        }
        ActiveRenderInfo info = mc.gameRenderer.getMainCamera();
        Vector3d from = info.getPosition();
        float yaw = info.getYRot() * 0.017453292F;
        float pitch = info.getXRot() * 0.017453292F;
        Vector3d dir = new Vector3d(-MathHelper.sin(yaw) * MathHelper.cos(pitch), -MathHelper.sin(pitch),
                MathHelper.cos(yaw) * MathHelper.cos(pitch));
        RayTraceResult r = mc.level.clip(new RayTraceContext(from, from.add(dir.scale(512)), RayTraceContext.BlockMode.OUTLINE,
                RayTraceContext.FluidMode.NONE, cam));
        ClientSession.aim = r.getType() == RayTraceResult.Type.BLOCK ? ((BlockRayTraceResult) r).getBlockPos() : null;
        if (ClientSession.aim != null && ClientSession.kind == DroneKind.MAVIC) {
            ClientSession.lastAim = ClientSession.aim;
        }
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.KeyInputEvent event) {
        KeyBinds.onRawKey(event.getKey(), event.getScanCode(), event.getAction());
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (!ClientSession.active && atGun(mc) && mc.screen == null) {
            GunnerHud.zoomBy(event.getScrollDelta() > 0 ? 1.25F : 1F / 1.25F);
            event.setCanceled(true);
            return;
        }
        if (!ClientSession.active || mc.screen != null) {
            return;
        }
        if (ClientSession.kind == DroneKind.MAVIC) {
            ClientSession.zoom = event.getScrollDelta() > 0
                    ? Math.min(28F, ClientSession.zoom * 1.25F)
                    : Math.max(1F, ClientSession.zoom / 1.25F);
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onClickInput(InputEvent.ClickInputEvent event) {
        if (ClientSession.active || atGun(Minecraft.getInstance())) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    @SubscribeEvent
    public static void onCameraSetup(EntityViewRenderEvent.CameraSetup event) {
        if (!ClientSession.active) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Entity cam = mc.getCameraEntity();
        if (cam == null || mc.player == null || cam.getId() != ClientSession.entityId) {
            return;
        }
        float pt = (float) event.getRenderPartialTicks();
        switch (ClientSession.kind) {
            case FPV:
                event.setYaw(mc.player.getViewYRot(pt));
                event.setPitch(mc.player.getViewXRot(pt));
                if (cam instanceof AbstractDroneEntity) {
                    AbstractDroneEntity d = (AbstractDroneEntity) cam;
                    event.setRoll(MathHelper.lerp(pt, d.tiltRollO, d.tiltRoll) * 0.8F);
                }
                break;
            case MAVIC:
                event.setYaw(mc.player.getViewYRot(pt));
                event.setPitch(MathHelper.clamp(mc.player.getViewXRot(pt), -30F, 90F));
                event.setRoll(0F);
                break;
            case STRIKE:
                event.setPitch(event.getPitch() + 6F);
                if (cam instanceof StrikeDroneEntity) {
                    StrikeDroneEntity s = (StrikeDroneEntity) cam;
                    event.setRoll(MathHelper.lerp(pt, s.rollO, s.roll) * 0.6F);
                }
                break;
            default:
                break;
        }
    }

    @SubscribeEvent
    public static void onFov(EntityViewRenderEvent.FOVModifier event) {
        Minecraft mc = Minecraft.getInstance();
        if (ClientSession.active) {
            if (ClientSession.kind == DroneKind.MAVIC) {
                event.setFOV(event.getFOV() / ClientSession.zoom);
            } else if (ClientSession.kind == DroneKind.FPV) {
                event.setFOV(Math.min(event.getFOV() * 1.2, 120.0));
            }
        } else if (atGun(mc)) {
            event.setFOV(event.getFOV() / GunnerHud.viewZoom((MountedWeaponEntity) mc.player.getVehicle()));
        } else if (aimingJavelin(mc)) {
            event.setFOV(event.getFOV() / JAVELIN_ZOOM);
        }
        Projection.fov = event.getFOV();
    }

    @SubscribeEvent
    public static void onOverlayPre(RenderGameOverlayEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getType() == RenderGameOverlayEvent.ElementType.CROSSHAIRS && (atGun(mc) || aimingJavelin(mc))) {
            event.setCanceled(true);
            return;
        }
        if (!ClientSession.active && ClientSession.lostTicks <= 0) {
            return;
        }
        switch (event.getType()) {
            case HOTBAR:
            case CROSSHAIRS:
            case HEALTH:
            case FOOD:
            case ARMOR:
            case AIR:
            case EXPERIENCE:
            case JUMPBAR:
            case HEALTHMOUNT:
            case POTION_ICONS:
            case BOSSHEALTH:
                event.setCanceled(true);
                break;
            default:
                break;
        }
    }

    @SubscribeEvent
    public static void onOverlayPost(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (ClientSession.active || ClientSession.lostTicks > 0) {
            HudRenderer.render(event.getMatrixStack(), event.getPartialTicks());
        } else if (mc.player != null && mc.player.getVehicle() instanceof LaunchTruckEntity) {
            HudRenderer.renderTruck(event.getMatrixStack(), (LaunchTruckEntity) mc.player.getVehicle());
        } else if (atGun(mc)) {
            GunnerHud.render(event.getMatrixStack(), (MountedWeaponEntity) mc.player.getVehicle(), event.getPartialTicks());
        } else if (aimingJavelin(mc)) {
            GunnerHud.javelin(event.getMatrixStack(), mc.player);
        }
    }

    @SubscribeEvent
    public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
        if (ClientSession.active && event.getPlayer() == Minecraft.getInstance().player) {
            event.setCanceled(true);
        } else if (event.getPlayer().getVehicle() instanceof MountedWeaponEntity) {
            // the crew sits inside, only the optics stick out
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (ClientSession.active || atGun(Minecraft.getInstance())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggedOutEvent event) {
        Sensitivity.restore(Minecraft.getInstance());
        ClientSession.reset();
        ControlState.reset();
    }

    private ClientEvents() {
    }
}
