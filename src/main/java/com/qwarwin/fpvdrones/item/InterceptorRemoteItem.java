package com.qwarwin.fpvdrones.item;

import com.qwarwin.fpvdrones.Config;
import com.qwarwin.fpvdrones.entity.InterceptorEntity;
import com.qwarwin.fpvdrones.util.Targeting;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The interceptor operator's remote: spot a drone, put the sight on it, RMB. The nearest ready interceptor of
 * your side takes off after it. Shift+RMB tells how many are ready.
 */
public class InterceptorRemoteItem extends Item {
    private static final double SIGHT_RANGE = 700.0;
    private static final double CONE = 4.0;

    public InterceptorRemoteItem(Properties props) {
        super(props);
    }

    @Override
    public ActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (world.isClientSide) {
            return ActionResult.success(stack);
        }
        player.getCooldowns().addCooldown(this, 10);
        double reach = Config.INTERCEPTOR_REACH.get();
        if (player.isShiftKeyDown()) {
            long ready = InterceptorEntity.own(world, player.getUUID(), player.position(), reach).stream()
                    .filter(InterceptorEntity::isReady).count();
            player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.intercept_ready", ready)
                    .withStyle(TextFormatting.AQUA), true);
            return ActionResult.consume(stack);
        }
        Entity t = spotted(world, player);
        if (t == null) {
            player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.intercept_no_target").withStyle(TextFormatting.GRAY), true);
            return ActionResult.consume(stack);
        }
        if (Targeting.isFriendly(world, player.getUUID(), Targeting.ownerOf(t))) {
            player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.intercept_friendly").withStyle(TextFormatting.YELLOW), true);
            return ActionResult.consume(stack);
        }
        InterceptorEntity i = InterceptorEntity.nearestReady(world, player.getUUID(), t.position(), reach);
        if (i == null || !i.dispatch(t)) {
            player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.intercept_none").withStyle(TextFormatting.RED), true);
            return ActionResult.consume(stack);
        }
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.STONE_BUTTON_CLICK_ON, SoundCategory.PLAYERS, 1F, 1.5F);
        player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.intercept_sent",
                (int) i.position().distanceTo(t.position())).withStyle(TextFormatting.GREEN), true);
        return ActionResult.consume(stack);
    }

    /** What flies closest to the line of sight, in view, within a few degrees. */
    @Nullable
    private static Entity spotted(World world, PlayerEntity player) {
        Vector3d eye = player.getEyePosition(1F);
        Vector3d look = player.getViewVector(1F);
        AxisAlignedBB box = new AxisAlignedBB(eye, eye.add(look.scale(SIGHT_RANGE))).inflate(40);
        Entity best = null;
        double bestAng = CONE;
        for (Entity e : world.getEntities(player, box, e -> e.isAlive() && Targeting.isAirborne(e) && !(e instanceof InterceptorEntity))) {
            Vector3d to = e.getBoundingBox().getCenter().subtract(eye);
            double d = to.length();
            if (d > SIGHT_RANGE || d < 2) {
                continue;
            }
            // a small drone far off needs a steadier aim than a big one close by
            double ang = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, to.normalize().dot(look)))));
            double allow = Math.max(1.5, Math.min(CONE, Math.toDegrees(Math.atan2(e.getBbWidth() + 3, d))));
            if (ang < allow && ang < bestAng && Targeting.canSee(world, eye, e)) {
                bestAng = ang;
                best = e;
            }
        }
        return best;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable World world, List<ITextComponent> lines, ITooltipFlag flag) {
        lines.add(new TranslationTextComponent("tooltip.fpvdrones.interceptor_remote").withStyle(TextFormatting.GRAY));
    }
}
