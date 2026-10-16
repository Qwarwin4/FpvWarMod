package com.qwarwin.fpvdrones.block;

import com.qwarwin.fpvdrones.entity.StrikeType;
import com.qwarwin.fpvdrones.item.LaunchRemoteItem;
import com.qwarwin.fpvdrones.item.StrikeDroneItem;
import com.qwarwin.fpvdrones.item.TabletItem;
import com.qwarwin.fpvdrones.launch.Launches;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.InventoryHelper;
import net.minecraft.item.BlockItemUseContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateContainer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.shapes.ISelectionContext;
import net.minecraft.util.math.shapes.VoxelShape;
import net.minecraft.util.math.shapes.VoxelShapes;
import net.minecraft.world.IBlockReader;
import net.minecraft.world.World;

import javax.annotation.Nullable;

public class LauncherBlock extends HorizontalBlock {
    private static final VoxelShape SHAPE = VoxelShapes.or(
            Block.box(0, 0, 0, 16, 3, 16),
            Block.box(5, 3, 1, 11, 12, 15));

    public LauncherBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateContainer.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockItemUseContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection());
    }

    @Override
    public VoxelShape getShape(BlockState state, IBlockReader world, BlockPos pos, ISelectionContext ctx) {
        return SHAPE;
    }

    @Override
    public boolean hasTileEntity(BlockState state) {
        return true;
    }

    @Nullable
    @Override
    public TileEntity createTileEntity(BlockState state, IBlockReader world) {
        return new LauncherTileEntity();
    }

    @Override
    public ActionResultType use(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockRayTraceResult hit) {
        if (hand != Hand.MAIN_HAND) {
            return ActionResultType.PASS;
        }
        if (world.isClientSide) {
            return ActionResultType.SUCCESS;
        }
        TileEntity te = world.getBlockEntity(pos);
        if (!(te instanceof LauncherTileEntity)) {
            return ActionResultType.PASS;
        }
        LauncherTileEntity launcher = (LauncherTileEntity) te;
        ItemStack stack = player.getItemInHand(hand);

        if (stack.getItem() instanceof LaunchRemoteItem) {
            LaunchRemoteItem.toggleLink(stack, player, launcher);
        } else if (stack.getItem() instanceof TabletItem) {
            TabletItem.bindSite(stack, player, launcher);
        } else if (stack.getItem() instanceof StrikeDroneItem) {
            Launches.load(stack, player, launcher);
        } else if (stack.isEmpty() && player.isShiftKeyDown()) {
            Launches.unload(player, launcher);
        } else if (player instanceof ServerPlayerEntity) {
            Launches.openMenu((ServerPlayerEntity) player, launcher);
        }
        return ActionResultType.CONSUME;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onRemove(BlockState state, World world, BlockPos pos, BlockState newState, boolean isMoving) {
        if (state.getBlock() != newState.getBlock()) {
            TileEntity te = world.getBlockEntity(pos);
            if (te instanceof LauncherTileEntity) {
                StrikeType t = ((LauncherTileEntity) te).getLoaded(0);
                if (t != null) {
                    InventoryHelper.dropItemStack(world, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, new ItemStack(t.item()));
                }
            }
        }
        super.onRemove(state, world, pos, newState, isMoving);
    }
}
