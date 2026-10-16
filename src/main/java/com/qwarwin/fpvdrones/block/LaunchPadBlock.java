package com.qwarwin.fpvdrones.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.shapes.ISelectionContext;
import net.minecraft.util.math.shapes.VoxelShape;
import net.minecraft.util.math.shapes.VoxelShapes;
import net.minecraft.world.IBlockReader;

/**
 * Printed stand for the FPV / Mavic. Only the base collides, the drone itself is held
 * on the forks by {@code AbstractDroneEntity#padSupport()} so the payload can hang between the legs.
 */
public class LaunchPadBlock extends Block {
    public static final double FORK_TOP = 14.6 / 16.0;

    private static final VoxelShape COLLISION = Block.box(2, 0, 2, 14, 2.6, 14);
    private static final VoxelShape OUTLINE = VoxelShapes.or(COLLISION,
            Block.box(2.4, 2.6, 2.4, 4.4, 16, 4.4),
            Block.box(11.6, 2.6, 2.4, 13.6, 16, 4.4),
            Block.box(2.4, 2.6, 11.6, 4.4, 16, 13.6),
            Block.box(11.6, 2.6, 11.6, 13.6, 16, 13.6));

    public LaunchPadBlock(Properties props) {
        super(props);
    }

    @Override
    public VoxelShape getShape(BlockState state, IBlockReader world, BlockPos pos, ISelectionContext ctx) {
        return OUTLINE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, IBlockReader world, BlockPos pos, ISelectionContext ctx) {
        return COLLISION;
    }
}
