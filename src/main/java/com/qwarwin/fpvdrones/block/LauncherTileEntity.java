package com.qwarwin.fpvdrones.block;

import com.qwarwin.fpvdrones.entity.StrikeType;
import com.qwarwin.fpvdrones.launch.LaunchConfig;
import com.qwarwin.fpvdrones.launch.LaunchSite;
import com.qwarwin.fpvdrones.launch.Launches;
import com.qwarwin.fpvdrones.registry.ModTileEntities;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SUpdateTileEntityPacket;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.UUID;

public class LauncherTileEntity extends TileEntity implements LaunchSite {
    public static final float RAIL_ANGLE = 22.5F;

    private final LaunchConfig config = new LaunchConfig();
    @Nullable
    private StrikeType loaded;
    @Nullable
    private UUID lastLaunched;

    public LauncherTileEntity() {
        super(ModTileEntities.LAUNCHER.get());
    }

    public Direction getFacing() {
        BlockState state = getBlockState();
        return state.hasProperty(LauncherBlock.FACING) ? state.getValue(LauncherBlock.FACING) : Direction.NORTH;
    }

    @Override
    public LaunchConfig getLaunchConfig() {
        return config;
    }

    @Override
    public int getCapacity() {
        return 1;
    }

    @Nullable
    @Override
    public StrikeType getLoaded(int slot) {
        return loaded;
    }

    @Override
    public void setLoaded(int slot, @Nullable StrikeType type) {
        loaded = type;
    }

    @Override
    public Vector3d getLaunchPos(int slot) {
        return Vector3d.atCenterOf(worldPosition).add(0, 0.24, 0);
    }

    @Override
    public float getLaunchYaw() {
        return getFacing().toYRot();
    }

    @Override
    public float getLaunchPitch() {
        return -RAIL_ANGLE;
    }

    @Override
    public World getSiteLevel() {
        return level;
    }

    @Nullable
    @Override
    public UUID getLastLaunched() {
        return lastLaunched;
    }

    @Override
    public void setLastLaunched(UUID id) {
        lastLaunched = id;
    }

    @Override
    public ITextComponent getSiteName() {
        return new TranslationTextComponent("block.fpvdrones.launcher").append(new StringTextComponent(
                " " + worldPosition.getX() + " " + worldPosition.getY() + " " + worldPosition.getZ()));
    }

    @Override
    public CompoundNBT createLink() {
        CompoundNBT tag = new CompoundNBT();
        if (level != null) {
            tag.putString("Dim", level.dimension().location().toString());
        }
        tag.putLong("Pos", worldPosition.asLong());
        return tag;
    }

    @Override
    public Launches.Result fireAll(@Nullable UUID owner) {
        return Launches.fireNext(this, owner);
    }

    @Override
    public void onSiteChanged() {
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState st = getBlockState();
            level.sendBlockUpdated(worldPosition, st, st, 3);
        }
    }

    @Override
    public void load(BlockState state, CompoundNBT tag) {
        super.load(state, tag);
        config.load(tag);
        loaded = tag.contains("Loaded") ? StrikeType.byIdOrNull(tag.getInt("Loaded")) : null;
        lastLaunched = tag.hasUUID("Last") ? tag.getUUID("Last") : null;
    }

    @Override
    public CompoundNBT save(CompoundNBT tag) {
        super.save(tag);
        config.save(tag);
        if (loaded != null) {
            tag.putInt("Loaded", loaded.ordinal());
        }
        if (lastLaunched != null) {
            tag.putUUID("Last", lastLaunched);
        }
        return tag;
    }

    @Override
    public CompoundNBT getUpdateTag() {
        return save(new CompoundNBT());
    }

    @Nullable
    @Override
    public SUpdateTileEntityPacket getUpdatePacket() {
        return new SUpdateTileEntityPacket(worldPosition, 0, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SUpdateTileEntityPacket pkt) {
        load(getBlockState(), pkt.getTag());
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        return new AxisAlignedBB(worldPosition).inflate(2.5);
    }
}
