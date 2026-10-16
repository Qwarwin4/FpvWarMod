package com.qwarwin.fpvdrones.item;

import com.qwarwin.fpvdrones.launch.LaunchConfig;
import com.qwarwin.fpvdrones.launch.LaunchSite;
import com.qwarwin.fpvdrones.launch.Launches;
import com.qwarwin.fpvdrones.registry.ModSounds;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nullable;
import java.util.List;

/** Any number of launchers and trucks, as long as they all sit on one frequency. */
public class LaunchRemoteItem extends Item {
    private static final String TAG_FREQ = "Freq";
    private static final String TAG_LINKS = "Links";

    public LaunchRemoteItem(Properties props) {
        super(props);
    }

    public static void toggleLink(ItemStack stack, PlayerEntity player, LaunchSite site) {
        CompoundNBT tag = stack.getOrCreateTag();
        ListNBT links = tag.getList(TAG_LINKS, Constants.NBT.TAG_COMPOUND);
        CompoundNBT link = site.createLink();

        for (int i = 0; i < links.size(); i++) {
            if (Launches.sameLink(links.getCompound(i), link)) {
                links.remove(i);
                if (links.isEmpty()) {
                    tag.remove(TAG_FREQ);
                }
                tag.put(TAG_LINKS, links);
                player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.remote_unbound", links.size()), true);
                return;
            }
        }

        int freq = site.getLaunchConfig().getFrequency();
        if (tag.contains(TAG_FREQ) && !links.isEmpty() && tag.getInt(TAG_FREQ) != freq) {
            player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.remote_wrong_freq",
                    LaunchConfig.formatFreq(tag.getInt(TAG_FREQ)), LaunchConfig.formatFreq(freq))
                    .withStyle(TextFormatting.RED), true);
            return;
        }
        links.add(link);
        tag.put(TAG_LINKS, links);
        tag.putInt(TAG_FREQ, freq);
        player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.remote_bound", links.size(),
                LaunchConfig.formatFreq(freq)), true);
        Vector3d p = site.getLaunchPos(0);
        site.getSiteLevel().playSound(null, p.x, p.y, p.z, ModSounds.BEEP.get(), SoundCategory.BLOCKS, 0.7F, 1.0F);
    }

    @Override
    public ActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (world.isClientSide) {
            return ActionResult.success(stack);
        }
        CompoundNBT tag = stack.getTag();
        ListNBT links = tag == null ? new ListNBT() : tag.getList(TAG_LINKS, Constants.NBT.TAG_COMPOUND);
        if (links.isEmpty()) {
            player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.remote_empty"), true);
            return ActionResult.fail(stack);
        }
        int freq = tag.getInt(TAG_FREQ);
        if (player.isShiftKeyDown()) {
            player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.remote_status",
                    LaunchConfig.formatFreq(freq), links.size()), true);
            return ActionResult.success(stack);
        }
        int launched = 0, skipped = 0;
        for (int i = 0; i < links.size(); i++) {
            LaunchSite site = Launches.resolve(world.getServer(), links.getCompound(i));
            if (site == null || site.getLaunchConfig().getFrequency() != freq) {
                skipped++;
                continue;
            }
            int count = site.countLoaded();
            if (site.fireAll(player.getUUID()) == Launches.Result.OK) {
                launched += site.getCapacity() > 1 ? count : 1;
            } else {
                skipped++;
            }
        }
        player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.remote_result", launched, skipped)
                .withStyle(launched > 0 ? TextFormatting.GREEN : TextFormatting.YELLOW), true);
        world.playSound(null, player.getX(), player.getY(), player.getZ(), ModSounds.BEEP.get(), SoundCategory.PLAYERS, 0.8F, 0.8F);
        player.getCooldowns().addCooldown(this, 20);
        return ActionResult.consume(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable World world, List<ITextComponent> tooltip, ITooltipFlag flag) {
        CompoundNBT tag = stack.getTag();
        if (tag != null && tag.contains(TAG_FREQ)) {
            int n = tag.getList(TAG_LINKS, Constants.NBT.TAG_COMPOUND).size();
            tooltip.add(new TranslationTextComponent("tooltip.fpvdrones.remote_freq",
                    LaunchConfig.formatFreq(tag.getInt(TAG_FREQ)), n).withStyle(TextFormatting.GREEN));
        } else {
            tooltip.add(new TranslationTextComponent("tooltip.fpvdrones.unbound").withStyle(TextFormatting.RED));
        }
        tooltip.add(new TranslationTextComponent("tooltip.fpvdrones.remote").withStyle(TextFormatting.GRAY));
    }
}
