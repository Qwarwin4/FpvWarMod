package com.qwarwin.fpvdrones.item;

import com.qwarwin.fpvdrones.entity.DroneKind;
import com.qwarwin.fpvdrones.entity.StrikeDroneEntity;
import com.qwarwin.fpvdrones.launch.LaunchSite;
import com.qwarwin.fpvdrones.launch.Launches;
import com.qwarwin.fpvdrones.session.SessionManager;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Bound to a launcher/truck it shows the last drone that left it,
 * or bound straight to a flying one (shift+RMB on it).
 */
public class TabletItem extends Item {
    private static final String TAG_SITE = "Site";
    private static final String TAG_SITE_NAME = "SiteName";
    private static final String TAG_FLYING = "Flying";

    public TabletItem(Properties props) {
        super(props);
    }

    public static void bindSite(ItemStack stack, PlayerEntity player, LaunchSite site) {
        CompoundNBT tag = stack.getOrCreateTag();
        tag.put(TAG_SITE, site.createLink());
        tag.putString(TAG_SITE_NAME, site.getSiteName().getString());
        tag.remove(TAG_FLYING);
        player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.bound_tablet", site.getSiteName()), true);
    }

    public static void bindFlying(ItemStack stack, UUID drone) {
        stack.getOrCreateTag().putUUID(TAG_FLYING, drone);
    }

    @Override
    public ActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (world.isClientSide) {
            return ActionResult.success(stack);
        }
        ServerPlayerEntity sp = (ServerPlayerEntity) player;
        StrikeDroneEntity drone = find(stack, sp.getServer());
        if (drone == null) {
            sp.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.no_strike_drone"), true);
            return ActionResult.fail(stack);
        }
        if (drone.level != sp.level) {
            sp.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.other_dim"), true);
            return ActionResult.fail(stack);
        }
        if (SessionManager.start(sp, drone, DroneKind.STRIKE, false)) {
            sp.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.connected", "V"), true);
        }
        return ActionResult.consume(stack);
    }

    @Nullable
    private static StrikeDroneEntity find(ItemStack stack, @Nullable MinecraftServer server) {
        CompoundNBT tag = stack.getTag();
        if (tag == null || server == null) {
            return null;
        }
        if (tag.hasUUID(TAG_FLYING)) {
            StrikeDroneEntity s = alive(Launches.findEntity(server, tag.getUUID(TAG_FLYING)));
            if (s != null) {
                return s;
            }
        }
        if (tag.contains(TAG_SITE, Constants.NBT.TAG_COMPOUND)) {
            LaunchSite site = Launches.resolve(server, tag.getCompound(TAG_SITE));
            if (site != null && site.getLastLaunched() != null) {
                return alive(Launches.findEntity(server, site.getLastLaunched()));
            }
        }
        return null;
    }

    @Nullable
    private static StrikeDroneEntity alive(@Nullable Entity e) {
        return e instanceof StrikeDroneEntity && e.isAlive() ? (StrikeDroneEntity) e : null;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable World world, List<ITextComponent> tooltip, ITooltipFlag flag) {
        CompoundNBT tag = stack.getTag();
        if (tag != null && tag.contains(TAG_SITE)) {
            tooltip.add(new TranslationTextComponent("tooltip.fpvdrones.bound", tag.getString(TAG_SITE_NAME)).withStyle(TextFormatting.GREEN));
        } else if (tag != null && tag.hasUUID(TAG_FLYING)) {
            tooltip.add(new TranslationTextComponent("tooltip.fpvdrones.bound", "UAV").withStyle(TextFormatting.GREEN));
        } else {
            tooltip.add(new TranslationTextComponent("tooltip.fpvdrones.unbound").withStyle(TextFormatting.RED));
        }
        tooltip.add(new TranslationTextComponent("tooltip.fpvdrones.tablet").withStyle(TextFormatting.GRAY));
    }
}
