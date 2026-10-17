package com.qwarwin.fpvdrones.session;

import com.qwarwin.fpvdrones.Config;
import com.qwarwin.fpvdrones.entity.AbstractDroneEntity;
import com.qwarwin.fpvdrones.entity.DroneKind;
import com.qwarwin.fpvdrones.entity.MavicEntity;
import com.qwarwin.fpvdrones.entity.OperatorEntity;
import com.qwarwin.fpvdrones.entity.StrikeDroneEntity;
import com.qwarwin.fpvdrones.item.DroneRemoteItem;
import com.qwarwin.fpvdrones.item.FpvGogglesItem;
import com.qwarwin.fpvdrones.network.Network;
import com.qwarwin.fpvdrones.network.SessionStatePacket;
import com.qwarwin.fpvdrones.registry.ModEntities;
import com.qwarwin.fpvdrones.registry.ModSounds;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.GameType;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Looking through a drone camera = vanilla spectating. The player goes into spectator with the drone as
 * camera, so chunks around it load the normal way, and an {@link OperatorEntity} keeps their spot.
 * Everything gets put back when the session ends. The original state is mirrored into persistent NBT
 * in case the server dies mid flight.
 */
public final class SessionManager {
    public static final String RESTORE_KEY = "fpvdrones_restore";

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Set<UUID> WEARING_GOGGLES = new HashSet<>();

    private static final class Session {
        UUID player;
        UUID target;
        RegistryKey<World> dim;
        double x, y, z;
        float yRot, xRot;
        GameType mode;
        boolean flying;
        UUID body;
        @Nullable
        UUID vehicle;
        boolean viaGoggles;
        // DJI RC: the remote itself is the screen, no goggles needed
        boolean handheld;
        boolean obstructed;
        int ticks;

        CompoundNBT save() {
            CompoundNBT t = new CompoundNBT();
            t.putString("Dim", dim.location().toString());
            t.putDouble("X", x);
            t.putDouble("Y", y);
            t.putDouble("Z", z);
            t.putFloat("YRot", yRot);
            t.putFloat("XRot", xRot);
            t.putInt("Mode", mode.getId());
            t.putBoolean("Flying", flying);
            return t;
        }
    }

    public static boolean isPiloting(ServerPlayerEntity p, Entity target) {
        Session s = SESSIONS.get(p.getUUID());
        return s != null && s.target.equals(target.getUUID());
    }

    public static boolean isBodyActive(UUID body) {
        for (Session s : SESSIONS.values()) {
            if (body.equals(s.body)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    public static Entity getTarget(ServerPlayerEntity p) {
        Session s = SESSIONS.get(p.getUUID());
        return s == null ? null : p.getLevel().getEntity(s.target);
    }

    public static boolean start(ServerPlayerEntity p, Entity target, DroneKind kind, boolean viaGoggles) {
        return start(p, target, kind, viaGoggles, false);
    }

    public static boolean start(ServerPlayerEntity p, Entity target, DroneKind kind, boolean viaGoggles, boolean handheld) {
        if (SESSIONS.containsKey(p.getUUID())) {
            p.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.already_viewing"), true);
            return false;
        }
        if (p.isSpectator()) {
            p.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.spectator"), true);
            return false;
        }
        if (target.level != p.level) {
            p.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.other_dim"), true);
            return false;
        }
        Session s = new Session();
        if (p.getVehicle() != null) {
            s.vehicle = p.getVehicle().getUUID();
            p.stopRiding();
        }
        s.player = p.getUUID();
        s.target = target.getUUID();
        s.dim = p.level.dimension();
        s.x = p.getX();
        s.y = p.getY();
        s.z = p.getZ();
        s.yRot = p.yRot;
        s.xRot = p.xRot;
        s.mode = p.gameMode.getGameModeForPlayer();
        s.flying = p.abilities.flying;
        s.viaGoggles = viaGoggles;
        s.handheld = handheld;

        OperatorEntity body = ModEntities.OPERATOR.get().create(p.level);
        if (body == null) {
            return false;
        }
        body.copyFrom(p);
        s.body = body.getUUID();
        SESSIONS.put(p.getUUID(), s);
        p.level.addFreshEntity(body);

        p.getPersistentData().put(RESTORE_KEY, s.save());
        p.closeContainer();
        p.setGameMode(GameType.SPECTATOR);
        p.setCamera(target);
        if (target instanceof AbstractDroneEntity) {
            ((AbstractDroneEntity) target).setPilot(p.getUUID());
        }

        Vector3d tgt = target instanceof StrikeDroneEntity ? ((StrikeDroneEntity) target).getTarget() : Vector3d.ZERO;
        Network.sendTo(p, SessionStatePacket.start(target.getId(), kind, s.x, s.y, s.z, tgt, handheld));
        p.level.playSound(null, s.x, s.y, s.z, ModSounds.BEEP.get(), SoundCategory.PLAYERS, 0.6F, 1.2F);
        return true;
    }

    public static void end(ServerPlayerEntity p, @Nullable String reason) {
        Session s = SESSIONS.remove(p.getUUID());
        if (s == null) {
            return;
        }
        Entity t = p.getLevel().getEntity(s.target);
        if (t instanceof AbstractDroneEntity) {
            ((AbstractDroneEntity) t).clearPilot(p.getUUID());
        }
        restore(p, s.dim, s.x, s.y, s.z, s.yRot, s.xRot, s.mode, s.flying);
        ServerWorld home = p.getServer() == null ? null : p.getServer().getLevel(s.dim);
        if (home != null) {
            Entity body = home.getEntity(s.body);
            if (body != null) {
                body.remove();
            }
            if (s.vehicle != null) {
                Entity vehicle = home.getEntity(s.vehicle);
                if (vehicle != null && vehicle.isAlive()) {
                    p.startRiding(vehicle, true);
                }
            }
        }
        p.getPersistentData().remove(RESTORE_KEY);
        Network.sendTo(p, SessionStatePacket.stop(reason == null ? "" : reason));
    }

    private static void restore(ServerPlayerEntity p, RegistryKey<World> dim, double x, double y, double z,
                                float yRot, float xRot, GameType mode, boolean flying) {
        p.setCamera(p);
        MinecraftServer server = p.getServer();
        ServerWorld world = server == null ? null : server.getLevel(dim);
        if (world == null) {
            world = p.getLevel();
        }
        p.teleportTo(world, x, y, z, yRot, xRot);
        p.setGameMode(mode);
        // spectator leaves flying switched on, and creative would keep it
        p.abilities.flying = flying && p.abilities.mayfly;
        p.onUpdateAbilities();
        p.fallDistance = 0F;
        p.invulnerableTime = Math.max(p.invulnerableTime, 10);
    }

    /** Ends every session looking at this entity, call before it blows up / disappears. */
    public static void endAllFor(Entity target, String reason) {
        if (target.level.isClientSide || target.getServer() == null) {
            return;
        }
        List<UUID> players = new ArrayList<>();
        for (Session s : SESSIONS.values()) {
            if (s.target.equals(target.getUUID())) {
                players.add(s.player);
            }
        }
        for (UUID u : players) {
            ServerPlayerEntity p = target.getServer().getPlayerList().getPlayer(u);
            if (p != null) {
                end(p, reason);
            } else {
                SESSIONS.remove(u);
            }
        }
    }

    public static void onBodyHurt(OperatorEntity body, DamageSource source, float amount) {
        if (body.getServer() == null) {
            return;
        }
        for (Session s : new ArrayList<>(SESSIONS.values())) {
            if (body.getUUID().equals(s.body)) {
                ServerPlayerEntity p = body.getServer().getPlayerList().getPlayer(s.player);
                if (p != null) {
                    end(p, "reason.fpvdrones.body_attacked");
                    p.invulnerableTime = 0;
                    p.hurt(source, amount);
                }
                return;
            }
        }
    }

    public static void tickPlayer(ServerPlayerEntity p) {
        Session s = SESSIONS.get(p.getUUID());
        if (s == null) {
            tickIdle(p);
            return;
        }
        s.ticks++;
        ServerWorld world = p.getLevel();
        Entity t = world.getEntity(s.target);
        if (t == null || !t.isAlive()) {
            end(p, "reason.fpvdrones.target_lost");
            return;
        }
        if (s.ticks > 3 && (p.getCamera() != t || !p.isSpectator())) {
            end(p, null);
            return;
        }
        if (s.viaGoggles && s.ticks % 5 == 0) {
            if (!(p.getItemBySlot(EquipmentSlotType.HEAD).getItem() instanceof FpvGogglesItem)) {
                end(p, "reason.fpvdrones.goggles_removed");
                return;
            }
            if (!DroneRemoteItem.hasRemoteFor(p, t.getUUID())) {
                end(p, "reason.fpvdrones.remote_lost");
                return;
            }
        }
        if (s.handheld && s.ticks % 5 == 0 && !DroneRemoteItem.hasRemoteFor(p, t.getUUID())) {
            end(p, "reason.fpvdrones.remote_lost");
            return;
        }
        if (s.ticks % 20 == 0 && s.ticks > 40) {
            ServerWorld home = p.getServer() == null ? null : p.getServer().getLevel(s.dim);
            if (home != null && home.isLoaded(new BlockPos(s.x, s.y, s.z)) && home.getEntity(s.body) == null) {
                // body got removed somehow (/kill etc)
                end(p, "reason.fpvdrones.body_attacked");
                return;
            }
        }
        if (t instanceof AbstractDroneEntity && s.ticks % 5 == 0) {
            AbstractDroneEntity drone = (AbstractDroneEntity) t;
            Vector3d home = new Vector3d(s.x, s.y + 1.6, s.z);
            double dist = home.distanceTo(t.position());
            if (Config.SIGNAL_OBSTRUCTION.get() && s.ticks % 10 == 0) {
                s.obstructed = dist > 24 && isObstructed(world, home, t);
            }
            double range = drone.signalRange() * (s.obstructed ? 0.55 : 1.0);
            float quality = (float) (1.0 - dist / range);
            if (quality <= 0F) {
                end(p, "reason.fpvdrones.signal_lost");
                return;
            }
            Network.sendTo(p, SessionStatePacket.signal(quality));
        }
    }

    private static boolean isObstructed(ServerWorld world, Vector3d from, Entity drone) {
        if (!world.isLoaded(new BlockPos(from))) {
            return false;
        }
        RayTraceResult r = world.clip(new RayTraceContext(from, drone.position().add(0, 0.2, 0),
                RayTraceContext.BlockMode.COLLIDER, RayTraceContext.FluidMode.NONE, drone));
        return r.getType() != RayTraceResult.Type.MISS;
    }

    private static void tickIdle(ServerPlayerEntity p) {
        CompoundNBT data = p.getPersistentData();
        if (data.contains(RESTORE_KEY)) {
            // left over from a crash
            CompoundNBT t = data.getCompound(RESTORE_KEY);
            data.remove(RESTORE_KEY);
            RegistryKey<World> dim = RegistryKey.create(Registry.DIMENSION_REGISTRY, new ResourceLocation(t.getString("Dim")));
            restore(p, dim, t.getDouble("X"), t.getDouble("Y"), t.getDouble("Z"), t.getFloat("YRot"), t.getFloat("XRot"),
                    GameType.byId(t.getInt("Mode")), t.getBoolean("Flying"));
            Network.sendTo(p, SessionStatePacket.stop(""));
            return;
        }
        boolean wearing = p.getItemBySlot(EquipmentSlotType.HEAD).getItem() instanceof FpvGogglesItem;
        boolean was = WEARING_GOGGLES.contains(p.getUUID());
        if (wearing && !was) {
            WEARING_GOGGLES.add(p.getUUID());
            connectGoggles(p);
        } else if (!wearing && was) {
            WEARING_GOGGLES.remove(p.getUUID());
        }
    }

    public static void connectGoggles(ServerPlayerEntity p) {
        UUID id = DroneRemoteItem.findBound(p);
        if (id == null) {
            p.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.no_remote"), true);
            return;
        }
        Entity e = p.getLevel().getEntity(id);
        if (!(e instanceof AbstractDroneEntity) || !e.isAlive()) {
            p.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.drone_not_found"), true);
            return;
        }
        AbstractDroneEntity drone = (AbstractDroneEntity) e;
        if (drone.position().distanceTo(p.position()) > drone.signalRange()) {
            p.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.out_of_range"), true);
            return;
        }
        if (start(p, drone, drone.kind(), true)) {
            p.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.connected", "V"), true);
        }
    }

    /** DJI RC with its own screen: RMB with the remote, no goggles. */
    public static void connectHandheld(ServerPlayerEntity p, ItemStack remote) {
        UUID id = DroneRemoteItem.getBound(remote);
        if (id == null) {
            p.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.rc_unbound"), true);
            return;
        }
        Entity e = p.getLevel().getEntity(id);
        if (!(e instanceof MavicEntity) || !e.isAlive()) {
            p.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.drone_not_found"), true);
            return;
        }
        MavicEntity drone = (MavicEntity) e;
        if (drone.position().distanceTo(p.position()) > drone.signalRange()) {
            p.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.out_of_range"), true);
            return;
        }
        if (start(p, drone, drone.kind(), false, true)) {
            p.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.connected", "V"), true);
        }
    }

    /** V key */
    public static void toggle(ServerPlayerEntity p) {
        if (SESSIONS.containsKey(p.getUUID())) {
            // leaving on purpose: straight back to the body, no "signal lost" screen
            end(p, null);
        } else if (p.getCamera() != p || p.getPersistentData().contains(RESTORE_KEY)) {
            // stuck looking through something with no session behind it (server reload, crash): unstick
            p.setCamera(p);
            if (p.getPersistentData().contains(RESTORE_KEY)) {
                // puts the player back where the session started
                tickIdle(p);
            }
            Network.sendTo(p, SessionStatePacket.stop(""));
        } else if (p.getItemBySlot(EquipmentSlotType.HEAD).getItem() instanceof FpvGogglesItem) {
            connectGoggles(p);
        }
    }

    public static void onLogout(ServerPlayerEntity p) {
        end(p, null);
        WEARING_GOGGLES.remove(p.getUUID());
    }

    public static void onServerStopping(MinecraftServer server) {
        for (UUID u : new ArrayList<>(SESSIONS.keySet())) {
            ServerPlayerEntity p = server.getPlayerList().getPlayer(u);
            if (p != null) {
                end(p, null);
            }
        }
        SESSIONS.clear();
        WEARING_GOGGLES.clear();
    }

    private SessionManager() {
    }
}
