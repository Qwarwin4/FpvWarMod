package com.qwarwin.fpvdrones.launch;

import com.qwarwin.fpvdrones.Config;
import com.qwarwin.fpvdrones.entity.LaunchTruckEntity;
import com.qwarwin.fpvdrones.entity.StrikeDroneEntity;
import com.qwarwin.fpvdrones.entity.StrikeType;
import com.qwarwin.fpvdrones.item.StrikeDroneItem;
import com.qwarwin.fpvdrones.network.Network;
import com.qwarwin.fpvdrones.network.OpenLauncherPacket;
import com.qwarwin.fpvdrones.registry.ModEntities;
import com.qwarwin.fpvdrones.registry.ModSounds;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;

import javax.annotation.Nullable;
import java.util.UUID;

public final class Launches {
    public static final double MIN_RANGE = 32.0;

    public enum Result {
        OK("msg.fpvdrones.launch_ok"),
        EMPTY("msg.fpvdrones.launch_empty"),
        NO_TARGET("msg.fpvdrones.launch_no_target"),
        TOO_CLOSE("msg.fpvdrones.launch_too_close"),
        TOO_FAR("msg.fpvdrones.launch_too_far"),
        BUSY("msg.fpvdrones.launch_busy");

        public final String key;

        Result(String key) {
            this.key = key;
        }
    }

    public static Result check(LaunchSite site) {
        int slot = site.firstLoaded();
        if (slot < 0) {
            return Result.EMPTY;
        }
        LaunchConfig c = site.getLaunchConfig();
        if (!c.hasTarget()) {
            return Result.NO_TARGET;
        }
        Vector3d from = site.getLaunchPos(slot);
        double dx = c.getX() + 0.5 - from.x;
        double dz = c.getZ() + 0.5 - from.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < MIN_RANGE) {
            return Result.TOO_CLOSE;
        }
        if (dist > Config.STRIKE_MAX_RANGE.get()) {
            return Result.TOO_FAR;
        }
        return Result.OK;
    }

    public static Result fireNext(LaunchSite site, @Nullable UUID owner) {
        Result r = check(site);
        if (r == Result.OK) {
            fire(site, site.firstLoaded(), owner);
        }
        return r;
    }

    private static void fire(LaunchSite site, int slot, @Nullable UUID owner) {
        World world = site.getSiteLevel();
        StrikeType type = site.getLoaded(slot);
        if (!(world instanceof ServerWorld) || type == null) {
            return;
        }
        StrikeDroneEntity drone = ModEntities.STRIKE_DRONE.get().create(world);
        if (drone == null) {
            return;
        }
        Vector3d pos = site.getLaunchPos(slot);
        LaunchConfig c = site.getLaunchConfig();
        drone.moveTo(pos.x, pos.y - StrikeDroneEntity.CENTER_HEIGHT, pos.z, site.getLaunchYaw(), site.getLaunchPitch());
        drone.setup(type, c, pos.y, owner);
        world.addFreshEntity(drone);
        drone.primeChunks((ServerWorld) world);

        site.setLoaded(slot, null);
        site.setLastLaunched(drone.getUUID());
        site.onSiteChanged();
        world.playSound(null, pos.x, pos.y, pos.z, ModSounds.SHAHED_LAUNCH.get(), SoundCategory.BLOCKS, 6.0F, 1.0F);
    }

    public static void load(ItemStack stack, PlayerEntity player, LaunchSite site) {
        StrikeType type = StrikeDroneItem.typeOf(stack);
        int slot = site.firstFree();
        if (type == null) {
            return;
        }
        if (slot < 0) {
            player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.site_full"), true);
            return;
        }
        site.setLoaded(slot, type);
        site.onSiteChanged();
        if (!player.abilities.instabuild) {
            stack.shrink(1);
        }
        player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.loaded",
                new TranslationTextComponent(type.translationKey())), true);
        Vector3d p = site.getLaunchPos(slot);
        site.getSiteLevel().playSound(null, p.x, p.y, p.z, SoundEvents.ANVIL_PLACE, SoundCategory.BLOCKS, 0.5F, 1.4F);
    }

    public static void unload(PlayerEntity player, LaunchSite site) {
        boolean any = false;
        for (int i = 0; i < site.getCapacity(); i++) {
            StrikeType t = site.getLoaded(i);
            if (t != null) {
                site.setLoaded(i, null);
                ItemStack s = new ItemStack(t.item());
                if (!player.inventory.add(s)) {
                    player.drop(s, false);
                }
                any = true;
            }
        }
        if (any) {
            site.onSiteChanged();
            player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.unloaded"), true);
        }
    }

    public static void openMenu(ServerPlayerEntity player, LaunchSite site) {
        Network.sendTo(player, new OpenLauncherPacket(site));
    }

    public static boolean sameLink(CompoundNBT a, CompoundNBT b) {
        if (a.hasUUID("Truck") || b.hasUUID("Truck")) {
            return a.hasUUID("Truck") && b.hasUUID("Truck") && a.getUUID("Truck").equals(b.getUUID("Truck"));
        }
        return a.getLong("Pos") == b.getLong("Pos") && a.getString("Dim").equals(b.getString("Dim"));
    }

    /** Finds the site behind a link tag. Launcher chunks get loaded, trucks have to be loaded already. */
    @Nullable
    public static LaunchSite resolve(@Nullable MinecraftServer server, CompoundNBT link) {
        if (server == null) {
            return null;
        }
        if (link.hasUUID("Truck")) {
            Entity e = findEntity(server, link.getUUID("Truck"));
            return e instanceof LaunchTruckEntity && e.isAlive() ? (LaunchTruckEntity) e : null;
        }
        if (!link.contains("Pos")) {
            return null;
        }
        RegistryKey<World> dim = RegistryKey.create(Registry.DIMENSION_REGISTRY, new ResourceLocation(link.getString("Dim")));
        ServerWorld world = server.getLevel(dim);
        if (world == null) {
            return null;
        }
        BlockPos pos = BlockPos.of(link.getLong("Pos"));
        world.getChunkAt(pos);
        TileEntity te = world.getBlockEntity(pos);
        return te instanceof LaunchSite ? (LaunchSite) te : null;
    }

    @Nullable
    public static Entity findEntity(MinecraftServer server, UUID id) {
        for (ServerWorld w : server.getAllLevels()) {
            Entity e = w.getEntity(id);
            if (e != null) {
                return e;
            }
        }
        return null;
    }

    private Launches() {
    }
}
