package com.qwarwin.fpvdrones.inventory;

import com.qwarwin.fpvdrones.block.AssemblyBenchBlock;
import com.qwarwin.fpvdrones.crafting.AssemblyRecipe;
import com.qwarwin.fpvdrones.item.SolderingIronItem;
import com.qwarwin.fpvdrones.registry.ModContainers;
import com.qwarwin.fpvdrones.registry.ModRecipes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.CraftResultInventory;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.SSetSlotPacket;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * 3x3 grid, a slot for the soldering iron and the result.
 * Slot order: 0 result, 1-9 grid, 10 iron, 11-46 player inventory.
 */
public class AssemblyContainer extends Container {
    public static final int RESULT = 0;
    public static final int IRON = 10;
    private static final int INV_START = 11;
    private static final int INV_END = 47;

    private final CraftingInventory craftSlots = new CraftingInventory(this, 3, 3);
    private final CraftResultInventory resultSlots = new CraftResultInventory();
    private final Inventory ironSlot = new Inventory(1) {
        @Override
        public void setChanged() {
            super.setChanged();
            slotsChanged(this);
        }
    };
    private final PlayerEntity player;
    @Nullable
    private final BlockPos pos;

    // pos is null on the client
    public AssemblyContainer(int id, PlayerInventory inv, @Nullable BlockPos pos) {
        super(ModContainers.ASSEMBLY.get(), id);
        this.player = inv.player;
        this.pos = pos;

        addSlot(new ResultSlot(124, 35));
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                addSlot(new Slot(craftSlots, c + r * 3, 30 + c * 18, 17 + r * 18));
            }
        }
        addSlot(new Slot(ironSlot, 0, 94, 58) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof SolderingIronItem;
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                addSlot(new Slot(inv, c + r * 9 + 9, 8 + c * 18, 84 + r * 18));
            }
        }
        for (int c = 0; c < 9; c++) {
            addSlot(new Slot(inv, c, 8 + c * 18, 142));
        }
    }

    public CraftingInventory getCraftSlots() {
        return craftSlots;
    }

    public boolean hasIron() {
        return ironSlot.getItem(0).getItem() instanceof SolderingIronItem;
    }

    public Optional<AssemblyRecipe> findRecipe(World world) {
        return world.getRecipeManager().getRecipeFor(ModRecipes.ASSEMBLY, craftSlots, world);
    }

    @Override
    public void slotsChanged(IInventory inv) {
        World world = player.level;
        if (world.isClientSide || !(player instanceof ServerPlayerEntity)) {
            return;
        }
        ItemStack out = ItemStack.EMPTY;
        Optional<AssemblyRecipe> recipe = findRecipe(world);
        if (recipe.isPresent() && (!recipe.get().needsSoldering() || hasIron())) {
            out = recipe.get().assemble(craftSlots);
        }
        resultSlots.setItem(0, out);
        ((ServerPlayerEntity) player).connection.send(new SSetSlotPacket(containerId, RESULT, out));
    }

    @Override
    public boolean stillValid(PlayerEntity player) {
        if (pos == null) {
            return true;
        }
        return player.level.getBlockState(pos).getBlock() instanceof AssemblyBenchBlock
                && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void removed(PlayerEntity player) {
        super.removed(player);
        if (!player.level.isClientSide) {
            clearContainer(player, player.level, craftSlots);
            clearContainer(player, player.level, ironSlot);
        }
    }

    @Override
    public ItemStack quickMoveStack(PlayerEntity player, int index) {
        ItemStack copy = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return copy;
        }
        ItemStack stack = slot.getItem();
        copy = stack.copy();
        if (index == RESULT) {
            stack.getItem().onCraftedBy(stack, player.level, player);
            if (!moveItemStackTo(stack, INV_START, INV_END, true)) {
                return ItemStack.EMPTY;
            }
            slot.onQuickCraft(stack, copy);
        } else if (index >= INV_START) {
            if (stack.getItem() instanceof SolderingIronItem && !slots.get(IRON).hasItem()) {
                if (!moveItemStackTo(stack, IRON, IRON + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, 1, 10, false)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, INV_START, INV_END, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == copy.getCount()) {
            return ItemStack.EMPTY;
        }
        ItemStack taken = slot.onTake(player, stack);
        if (index == RESULT) {
            player.drop(taken, false);
        }
        return copy;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return slot.container != resultSlots && super.canTakeItemForPickAll(stack, slot);
    }

    private class ResultSlot extends Slot {
        ResultSlot(int x, int y) {
            super(resultSlots, 0, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public ItemStack onTake(PlayerEntity player, ItemStack stack) {
            stack.onCraftedBy(player.level, player, stack.getCount());
            Optional<AssemblyRecipe> recipe = findRecipe(player.level);
            NonNullList<ItemStack> leftovers = recipe.isPresent()
                    ? recipe.get().getRemainingItems(craftSlots)
                    : NonNullList.withSize(craftSlots.getContainerSize(), ItemStack.EMPTY);
            boolean soldered = recipe.isPresent() && recipe.get().needsSoldering();

            for (int i = 0; i < craftSlots.getContainerSize(); i++) {
                if (!craftSlots.getItem(i).isEmpty()) {
                    craftSlots.removeItem(i, 1);
                }
                ItemStack left = leftovers.get(i);
                if (left.isEmpty()) {
                    continue;
                }
                if (craftSlots.getItem(i).isEmpty()) {
                    craftSlots.setItem(i, left);
                } else if (!player.inventory.add(left)) {
                    player.drop(left, false);
                }
            }
            if (soldered && !player.level.isClientSide) {
                ItemStack iron = ironSlot.getItem(0);
                if (iron.hurt(1, player.getRandom(), player instanceof ServerPlayerEntity ? (ServerPlayerEntity) player : null)) {
                    iron.shrink(1);
                }
                ironSlot.setChanged();
            }
            slotsChanged(craftSlots);
            return stack;
        }
    }
}
