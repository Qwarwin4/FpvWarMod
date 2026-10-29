package com.qwarwin.fpvdrones.item;

import com.qwarwin.fpvdrones.entity.MachineEntity;
import com.qwarwin.fpvdrones.entity.MountedWeaponEntity;
import com.qwarwin.fpvdrones.entity.RadioMachineEntity;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUseContext;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Supplier;

/** Puts a truck / gun mount / Kornet down where you click, facing the way you look. */
public class MachineItem extends Item {
    private final Supplier<? extends EntityType<? extends MachineEntity>> type;
    private final String tooltip;

    public MachineItem(Supplier<? extends EntityType<? extends MachineEntity>> type, String tooltip, Properties props) {
        super(props);
        this.type = type;
        this.tooltip = tooltip;
    }

    @Override
    public ActionResultType useOn(ItemUseContext ctx) {
        World world = ctx.getLevel();
        if (world.isClientSide) {
            return ActionResultType.SUCCESS;
        }
        MachineEntity machine = type.get().create(world);
        if (machine == null) {
            return ActionResultType.FAIL;
        }
        Vector3d hit = ctx.getClickLocation();
        PlayerEntity player = ctx.getPlayer();
        float yaw = player != null ? player.yRot : 0F;
        machine.moveTo(hit.x, hit.y + 0.05, hit.z, yaw, 0F);
        if (!world.noCollision(machine)) {
            return ActionResultType.FAIL;
        }
        if (machine instanceof MountedWeaponEntity) {
            MountedWeaponEntity w = (MountedWeaponEntity) machine;
            w.setOwner(player != null ? player.getUUID() : null);
            w.resetAim(yaw);
            if (player != null && player.abilities.instabuild) {
                w.fillAmmo();
            }
        }
        if (machine instanceof RadioMachineEntity) {
            ((RadioMachineEntity) machine).setOwner(player != null ? player.getUUID() : null);
        }
        world.addFreshEntity(machine);
        if (player == null || !player.abilities.instabuild) {
            ctx.getItemInHand().shrink(1);
        }
        return ActionResultType.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable World world, List<ITextComponent> lines, ITooltipFlag flag) {
        lines.add(new TranslationTextComponent(tooltip).withStyle(TextFormatting.GRAY));
    }
}
