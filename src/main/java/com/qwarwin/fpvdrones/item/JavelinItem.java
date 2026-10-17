package com.qwarwin.fpvdrones.item;

import com.qwarwin.fpvdrones.client.ClientHooks;
import com.qwarwin.fpvdrones.entity.MissileEntity;
import com.qwarwin.fpvdrones.entity.MissileType;
import com.qwarwin.fpvdrones.registry.ModEntities;
import com.qwarwin.fpvdrones.registry.ModItems;
import com.qwarwin.fpvdrones.registry.ModSounds;
import com.qwarwin.fpvdrones.util.Targeting;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.UseAction;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fire-and-forget launcher. Hold RMB to look through the seeker and keep the target in the box until it locks,
 * let go to launch. The missile then flies a top-attack arc on its own, the gunner can take cover.
 */
public class JavelinItem extends Item {
    public static final int LOCK_TICKS = 40;
    public static final double RANGE = 400.0;

    /** Seeker state per player. Client and server each track their own, the server's decides the launch. */
    public static final class Lock {
        public int targetId = -1;
        public int ticks;

        public boolean locked() {
            return targetId >= 0 && ticks >= LOCK_TICKS;
        }
    }

    private static final Map<UUID, Lock> SERVER = new HashMap<>();
    private static final Map<UUID, Lock> CLIENT = new HashMap<>();

    public JavelinItem(Properties props) {
        super(props);
    }

    public static Lock lockOf(PlayerEntity player) {
        return (player.level.isClientSide ? CLIENT : SERVER).computeIfAbsent(player.getUUID(), k -> new Lock());
    }

    @Override
    public UseAction getUseAnimation(ItemStack stack) {
        return UseAction.BOW;
    }

    @Override
    public int getUseDuration(ItemStack stack) {
        return 72000;
    }

    @Override
    public ActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.abilities.instabuild && findMissile(player).isEmpty()) {
            if (!world.isClientSide) {
                player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.no_missiles"), true);
            }
            return ActionResult.fail(stack);
        }
        Lock lock = lockOf(player);
        lock.targetId = -1;
        lock.ticks = 0;
        player.startUsingItem(hand);
        return ActionResult.consume(stack);
    }

    @Override
    public void onUsingTick(ItemStack stack, LivingEntity user, int count) {
        if (!(user instanceof PlayerEntity)) {
            return;
        }
        PlayerEntity player = (PlayerEntity) user;
        Lock lock = lockOf(player);
        Entity seen = seekerTarget(player);
        if (seen != null && seen.getId() == lock.targetId) {
            lock.ticks++;
        } else if (seen != null) {
            lock.targetId = seen.getId();
            lock.ticks = 0;
        } else {
            lock.ticks = Math.max(0, lock.ticks - 3);
            if (lock.ticks == 0) {
                lock.targetId = -1;
            }
        }
        if (player.level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.javelinTone(lock));
        }
    }

    /** Whatever is under the crosshair: a vehicle, a mob, a drone. */
    @Nullable
    public static Entity seekerTarget(PlayerEntity player) {
        World world = player.level;
        Vector3d eye = player.getEyePosition(1F);
        Vector3d end = eye.add(player.getViewVector(1F).scale(RANGE));
        RayTraceResult block = world.clip(new RayTraceContext(eye, end, RayTraceContext.BlockMode.COLLIDER,
                RayTraceContext.FluidMode.NONE, player));
        if (block.getType() != RayTraceResult.Type.MISS) {
            end = block.getLocation();
        }
        Targeting.Hit hit = Targeting.firstEntity(world, player, eye, end, 0.6,
                e -> Targeting.isShootable(e) && e != player.getVehicle());
        return hit == null ? null : hit.entity;
    }

    @Override
    public void releaseUsing(ItemStack stack, World world, LivingEntity user, int timeLeft) {
        if (!(user instanceof PlayerEntity)) {
            return;
        }
        PlayerEntity player = (PlayerEntity) user;
        Lock lock = lockOf(player);
        boolean locked = lock.locked();
        Entity target = locked ? world.getEntity(lock.targetId) : null;
        lock.targetId = -1;
        lock.ticks = 0;
        if (world.isClientSide) {
            return;
        }
        if (target == null || !target.isAlive()) {
            player.displayClientMessage(new TranslationTextComponent("msg.fpvdrones.no_lock").withStyle(TextFormatting.YELLOW), true);
            return;
        }
        if (!player.abilities.instabuild) {
            ItemStack missile = findMissile(player);
            if (missile.isEmpty()) {
                return;
            }
            missile.shrink(1);
        }
        MissileEntity m = ModEntities.MISSILE.get().create(world);
        if (m == null) {
            return;
        }
        Vector3d look = player.getViewVector(1F);
        Vector3d pos = player.getEyePosition(1F).add(look.scale(1.2)).add(0, -0.15, 0);
        // soft launch slightly upward, the arc does the rest
        m.setup(MissileType.JAVELIN, player.getUUID(), player, pos, look.add(0, 0.35, 0));
        m.setTarget(target);
        world.addFreshEntity(m);
        world.playSound(null, pos.x, pos.y, pos.z, ModSounds.ATGM_LAUNCH.get(), SoundCategory.PLAYERS, 4F, 1.3F);
        Vector3d back = player.getEyePosition(1F).subtract(look.scale(1.5));
        ((ServerWorld) world).sendParticles(ParticleTypes.CLOUD, back.x, back.y, back.z, 12, 0.3, 0.3, 0.3, 0.05);
        player.getCooldowns().addCooldown(this, 60);
    }

    private static ItemStack findMissile(PlayerEntity player) {
        for (ItemStack s : player.inventory.items) {
            if (s.getItem() == ModItems.JAVELIN_MISSILE.get()) {
                return s;
            }
        }
        for (ItemStack s : player.inventory.offhand) {
            if (s.getItem() == ModItems.JAVELIN_MISSILE.get()) {
                return s;
            }
        }
        return ItemStack.EMPTY;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable World world, List<ITextComponent> tooltip, ITooltipFlag flag) {
        tooltip.add(new TranslationTextComponent("tooltip.fpvdrones.javelin").withStyle(TextFormatting.GRAY));
    }
}
