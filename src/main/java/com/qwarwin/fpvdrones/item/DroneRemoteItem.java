package com.qwarwin.fpvdrones.item;

import com.qwarwin.fpvdrones.entity.AbstractDroneEntity;
import com.qwarwin.fpvdrones.session.SessionManager;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/** Radio for the FPV / Mavic. Shift+RMB on a drone binds it, the goggles just show whatever it's bound to. */
public class DroneRemoteItem extends Item {
    private static final String TAG_DRONE = "Drone";
    private static final String TAG_NAME = "DroneName";

    public DroneRemoteItem(Properties props) {
        super(props);
    }

    public static void bind(ItemStack stack, AbstractDroneEntity drone) {
        CompoundNBT tag = stack.getOrCreateTag();
        tag.putUUID(TAG_DRONE, drone.getUUID());
        tag.putString(TAG_NAME, drone.shortName());
    }

    @Nullable
    public static UUID getBound(ItemStack stack) {
        CompoundNBT tag = stack.getTag();
        return stack.getItem() instanceof DroneRemoteItem && tag != null && tag.hasUUID(TAG_DRONE) ? tag.getUUID(TAG_DRONE) : null;
    }

    /** Held remote wins, otherwise the first bound one in the inventory. */
    @Nullable
    public static UUID findBound(PlayerEntity player) {
        UUID id = getBound(player.getMainHandItem());
        if (id == null) {
            id = getBound(player.getOffhandItem());
        }
        if (id == null) {
            for (ItemStack s : player.inventory.items) {
                id = getBound(s);
                if (id != null) {
                    break;
                }
            }
        }
        return id;
    }

    public static boolean hasRemoteFor(PlayerEntity player, UUID drone) {
        if (drone.equals(getBound(player.getOffhandItem()))) {
            return true;
        }
        for (ItemStack s : player.inventory.items) {
            if (drone.equals(getBound(s))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public ActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (world.isClientSide) {
            return ActionResult.success(stack);
        }
        if (player.getItemBySlot(EquipmentSlotType.HEAD).getItem() instanceof FpvGogglesItem) {
            SessionManager.connectGoggles((ServerPlayerEntity) player);
        } else {
            player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.put_goggles"), true);
        }
        return ActionResult.consume(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable World world, List<ITextComponent> tooltip, ITooltipFlag flag) {
        CompoundNBT tag = stack.getTag();
        if (tag != null && tag.hasUUID(TAG_DRONE)) {
            tooltip.add(new TranslationTextComponent("tooltip.fpvdrones.bound", tag.getString(TAG_NAME)).withStyle(TextFormatting.GREEN));
        } else {
            tooltip.add(new TranslationTextComponent("tooltip.fpvdrones.unbound").withStyle(TextFormatting.RED));
        }
        tooltip.add(new TranslationTextComponent("tooltip.fpvdrones.drone_remote").withStyle(TextFormatting.GRAY));
    }
}
