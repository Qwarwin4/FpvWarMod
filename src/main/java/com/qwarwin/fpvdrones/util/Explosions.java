package com.qwarwin.fpvdrones.util;

import com.qwarwin.fpvdrones.Config;
import com.qwarwin.fpvdrones.item.PayloadType;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.DamageSource;
import net.minecraft.util.Direction;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;

import javax.annotation.Nullable;
import java.util.UUID;

public final class Explosions {

    public static Explosion.Mode mode() {
        return Config.EXPLOSIONS_BREAK_BLOCKS.get() ? Explosion.Mode.BREAK : Explosion.Mode.NONE;
    }

    @Nullable
    public static ServerPlayerEntity player(World level, @Nullable UUID id) {
        if (id == null || level.getServer() == null) {
            return null;
        }
        return level.getServer().getPlayerList().getPlayer(id);
    }

    public static void payload(World level, @Nullable UUID attackerId, Vector3d pos, PayloadType type, Vector3d dir) {
        if (level.isClientSide || !type.isExplosive()) {
            return;
        }
        LivingEntity attacker = player(level, attackerId);
        explode(level, attacker, pos, type.power(), false);
        ServerWorld sw = (ServerWorld) level;
        switch (type) {
            case PG7VS:
                // a tandem round on top of the engine deck or into the side: the crew compartment or the ammunition
                // goes, one or two of them finish a tank
                jet(level, attacker, pos, dir, 7.0, 45F, 450F);
                fragments(level, attacker, pos, 5.0, 8F);
                sw.sendParticles(ParticleTypes.FLAME, pos.x, pos.y, pos.z, 40, dir.x * 0.6, dir.y * 0.6, dir.z * 0.6, 0.3);
                break;
            case OG7:
                fragments(level, attacker, pos, 11.0, 18F);
                hull(level, attacker, pos, 2.5, 90F);
                break;
            case CANISTER:
                fragments(level, attacker, pos, 7.0, 8F);
                // five litres of explosive on the roof: the hatches, optics, engine deck and whoever is inside
                hull(level, attacker, pos, 3.0, 300F);
                sw.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y, pos.z, 2, 1.0, 0.5, 1.0, 0);
                sw.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y + 1, pos.z, 60, 2.0, 1.5, 2.0, 0.05);
                break;
            case RGD5:
                fragments(level, attacker, pos, 6.5, 9F);
                break;
            case VOG17M:
                fragments(level, attacker, pos, 6.5, 12F);
                break;
            default:
                break;
        }
    }

    public static void strike(World level, @Nullable UUID attackerId, Vector3d pos, float power, boolean fire) {
        if (level.isClientSide) {
            return;
        }
        LivingEntity attacker = player(level, attackerId);
        explode(level, attacker, pos, power, fire);
        fragments(level, attacker, pos, power * 1.8, 16F);
        // 50 kg of explosive on top of a vehicle: the roof caves in whatever it is
        hull(level, attacker, pos, power * 0.35, power * 45F);
        ServerWorld sw = (ServerWorld) level;
        sw.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y, pos.z, 4, 2.0, 1.0, 2.0, 0);
        sw.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.x, pos.y + 1, pos.z, 40, 2.5, 1.0, 2.5, 0.03);
        sw.sendParticles(ParticleTypes.LAVA, pos.x, pos.y + 0.5, pos.z, 25, 1.5, 0.5, 1.5, 0.1);
    }

    /**
     * The interceptor's warhead: a fragmentation charge that bursts next to the target. What it was sent after
     * takes the full cone; anything else in the air close by and people under it get the fragments.
     */
    public static void interceptor(World level, @Nullable UUID attackerId, Vector3d pos, @Nullable Entity hit) {
        if (level.isClientSide) {
            return;
        }
        LivingEntity attacker = player(level, attackerId);
        level.explode(null, ModDamageSources.shrapnel(attacker), null, pos.x, pos.y, pos.z, 1.2F, false, Explosion.Mode.NONE);
        if (hit != null && hit.isAlive()) {
            hit.invulnerableTime = 0;
            hit.hurt(ModDamageSources.shrapnel(attacker), 60F);
        }
        AxisAlignedBB box = new AxisAlignedBB(pos, pos).inflate(5.0);
        for (Entity e : level.getEntities((Entity) null, box, e -> e.isAlive() && e != hit && Targeting.isAirborne(e))) {
            double d = e.getBoundingBox().getCenter().distanceTo(pos);
            if (d < 5.0) {
                e.invulnerableTime = 0;
                e.hurt(ModDamageSources.shrapnel(attacker), (float) (30.0 * (1.0 - d / 5.0)) + 2F);
            }
        }
        fragments(level, attacker, pos, 5.0, 10F);
        ((ServerWorld) level).sendParticles(ParticleTypes.SMOKE, pos.x, pos.y, pos.z, 25, 0.6, 0.6, 0.6, 0.05);
    }

    private static void explode(World level, @Nullable LivingEntity attacker, Vector3d pos, float power, boolean fire) {
        DamageSource src = DamageSource.explosion(attacker);
        level.explode((Entity) null, src, null, pos.x, pos.y, pos.z, power, fire, mode());
    }

    private static void fragments(World level, @Nullable Entity attacker, Vector3d pos, double radius, float maxDamage) {
        float mul = Config.FRAGMENT_DAMAGE.get().floatValue();
        if (mul <= 0F) {
            return;
        }
        AxisAlignedBB box = new AxisAlignedBB(pos, pos).inflate(radius);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
            Vector3d eye = e.getEyePosition(1F);
            double d = eye.distanceTo(pos);
            if (d > radius) {
                continue;
            }
            RayTraceResult r = level.clip(new RayTraceContext(pos, eye, RayTraceContext.BlockMode.COLLIDER,
                    RayTraceContext.FluidMode.NONE, e));
            if (r.getType() != RayTraceResult.Type.MISS) {
                continue;
            }
            e.invulnerableTime = 0;
            e.hurt(ModDamageSources.shrapnel(attacker), ((float) (maxDamage * (1.0 - d / radius)) + 1F) * mul);
        }
    }

    /** Blast against vehicles right next to the burst: what a warhead lying on the hull does to it. */
    private static void hull(World level, @Nullable Entity attacker, Vector3d pos, double radius, float damage) {
        for (Entity e : level.getEntities((Entity) null, new AxisAlignedBB(pos, pos).inflate(radius + 4),
                e -> e.isAlive() && !(e instanceof LivingEntity) && Targeting.isShootable(e) && !Targeting.isAirTarget(e))) {
            double d = boxDistance(e.getBoundingBox(), pos);
            if (d > radius) {
                continue;
            }
            e.invulnerableTime = 0;
            e.hurt(ModDamageSources.cumulative(attacker), damage * (float) (1.0 - 0.7 * d / radius) * zone(e, pos));
        }
    }

    /** From a point to the nearest point of a box (0 inside it). */
    private static double boxDistance(AxisAlignedBB b, Vector3d p) {
        double dx = Math.max(Math.max(b.minX - p.x, 0), p.x - b.maxX);
        double dy = Math.max(Math.max(b.minY - p.y, 0), p.y - b.maxY);
        double dz = Math.max(Math.max(b.minZ - p.z, 0), p.z - b.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * How much of a hit gets through a vehicle's armour depending on where it lands: thinnest on the roof and
     * engine deck, then the stern, the sides, and the front is what armour is built for.
     */
    private static float zone(Entity e, Vector3d from) {
        Vector3d c = e.getBoundingBox().getCenter();
        Vector3d to = c.subtract(from);
        double above = from.y - (e.getY() + e.getBbHeight() * 0.7);
        if (above > 0 && above > Math.sqrt(to.x * to.x + to.z * to.z) * 0.5) {
            return 1F;
        }
        return zoneFacing(e, to);
    }

    /** By the direction the hit travels (the jet, the missile). */
    private static float zoneFacing(Entity e, Vector3d travel) {
        double h = Math.sqrt(travel.x * travel.x + travel.z * travel.z);
        if (h < 1e-6 || -travel.y > h * 1.2) {
            return 1F;
        }
        float yaw = e.yRot * ((float) Math.PI / 180F);
        // the way the vehicle faces; a hit travelling against it lands on the front
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double dot = -(travel.x * fx + travel.z * fz) / h;
        if (dot > 0.7) {
            return 0.45F;
        }
        if (dot < -0.5) {
            return 1F;
        }
        return 0.75F;
    }

    private static void jet(World level, @Nullable Entity attacker, Vector3d pos, Vector3d dir, double length, float damage) {
        jet(level, attacker, pos, dir, length, damage, damage);
    }

    /** The jet: {@code damage} to people and things, {@code armour} to vehicles, depending on where it lands. */
    private static void jet(World level, @Nullable Entity attacker, Vector3d pos, Vector3d dir, double length, float damage,
                            float armour) {
        Vector3d d = dir.normalize();
        Vector3d end = pos.add(d.scale(length));
        AxisAlignedBB box = new AxisAlignedBB(pos, end).inflate(1.0);
        float mul = Config.FRAGMENT_DAMAGE.get().floatValue();
        for (Entity e : level.getEntities((Entity) null, box, e -> e.isAlive() && Targeting.isShootable(e))) {
            Vector3d c = e.getBoundingBox().getCenter();
            double t = c.subtract(pos).dot(d);
            if (t < -0.5 || t > length) {
                continue;
            }
            double reach = 1.0 + Math.max(e.getBbWidth(), e.getBbHeight()) * 0.5;
            if (pos.add(d.scale(Math.max(0, t))).distanceTo(c) < reach) {
                e.invulnerableTime = 0;
                // armour is what the jet is for, so machines take it at full strength
                e.hurt(ModDamageSources.cumulative(attacker), e instanceof LivingEntity ? damage * mul
                        : Targeting.isAirTarget(e) ? damage : armour * zoneFacing(e, d));
            }
        }
    }

    /** 30 mm HE-frag. With the proximity fuse it bursts next to drones instead of needing a direct hit. */
    public static void shell(World level, Entity shell, @Nullable UUID attackerId, Vector3d pos, boolean airburst) {
        shell(level, shell, attackerId, pos, airburst, airburst ? 3.0 : 2.5);
    }

    public static void shell(World level, Entity shell, @Nullable UUID attackerId, Vector3d pos, boolean airburst, double radius) {
        if (level.isClientSide) {
            return;
        }
        LivingEntity attacker = player(level, attackerId);
        ServerWorld sw = (ServerWorld) level;
        for (Entity e : level.getEntities(shell, new AxisAlignedBB(pos, pos).inflate(radius), Targeting::isShootable)) {
            double d = e.getBoundingBox().getCenter().distanceTo(pos);
            if (d > radius) {
                continue;
            }
            // the fragment cone is sparse: the smaller the target and the further away, the likelier it's missed
            double size = Math.max(e.getBbWidth(), e.getBbHeight());
            double pHit = Math.min(1.0, 0.25 + size * 0.35) * (1.0 - 0.6 * d / radius);
            if (level.random.nextDouble() > pHit) {
                continue;
            }
            float dmg = (float) (7.0 * (1.0 - d / radius)) + 2F;
            if (e instanceof LivingEntity) {
                e.invulnerableTime = 0;
                dmg *= Config.FRAGMENT_DAMAGE.get().floatValue();
            }
            e.hurt(ModDamageSources.shrapnel(attacker), dmg);
        }
        if (Config.SHELLS_BREAK_BLOCKS.get() && !airburst) {
            level.explode(shell, ModDamageSources.shell(shell, attacker), null, pos.x, pos.y, pos.z, 1.2F, false, Explosion.Mode.BREAK);
        } else {
            sw.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE, SoundCategory.BLOCKS, 1.6F, 1.6F);
        }
        sw.sendParticles(ParticleTypes.EXPLOSION, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0);
        sw.sendParticles(ParticleTypes.SMOKE, pos.x, pos.y, pos.z, 8, 0.3, 0.3, 0.3, 0.02);
    }

    /**
     * Shaped-charge jet through blocks: clears a narrow tunnel up to {@code length} blocks, slowed by hard blocks,
     * stopped by obsidian-class ones. Returns where it ran out.
     */
    private static Vector3d bore(World level, Vector3d from, Vector3d dir, double length) {
        if (!Config.EXPLOSIONS_BREAK_BLOCKS.get()) {
            return from;
        }
        double left = length;
        Vector3d p = from;
        BlockPos last = null;
        while (left > 0) {
            p = p.add(dir.scale(0.4));
            left -= 0.4;
            BlockPos bp = new BlockPos(p);
            if (bp.equals(last)) {
                continue;
            }
            last = bp;
            BlockState st = level.getBlockState(bp);
            if (st.isAir(level, bp)) {
                continue;
            }
            float res = st.getExplosionResistance(level, bp, null);
            if (res >= 1200F || st.getDestroySpeed(level, bp) < 0) {
                break;
            }
            // hard stuff eats more of the jet
            left -= Math.min(3.0, res / 4.0);
            level.destroyBlock(bp, false);
            for (Direction side : Direction.values()) {
                BlockPos n = bp.relative(side);
                BlockState ns = level.getBlockState(n);
                if (!ns.isAir(level, n) && ns.getExplosionResistance(level, n, null) < 10F
                        && ns.getDestroySpeed(level, n) >= 0 && level.random.nextFloat() < 0.45F) {
                    level.destroyBlock(n, false);
                }
            }
        }
        return p;
    }

    /** Tandem HEAT: the hit itself, the jet through armour and walls, a blast at the surface and one behind it. */
    public static void atgm(World level, @Nullable UUID attackerId, Vector3d pos, Vector3d dir, @Nullable Entity direct,
                            float power, float directDamage, double penetration) {
        if (level.isClientSide) {
            return;
        }
        LivingEntity attacker = player(level, attackerId);
        if (direct != null && direct.isAlive()) {
            direct.invulnerableTime = 0;
            direct.hurt(ModDamageSources.cumulative(attacker), directDamage);
        }
        Vector3d d0 = dir.normalize();
        jet(level, attacker, pos.subtract(d0.scale(0.5)), dir, penetration + 2.0, directDamage * 0.5F);
        // the precursor charge blows the surface, the main jet bores a tunnel and the rest goes off at its end
        explode(level, attacker, pos, power, false);
        Vector3d end = bore(level, pos.subtract(d0.scale(0.3)), d0, penetration);
        if (end.distanceTo(pos) > 1.5) {
            explode(level, attacker, end, power * 0.55F, false);
        }
        fragments(level, attacker, pos, power * 1.6, 14F);
        ServerWorld sw = (ServerWorld) level;
        Vector3d d = dir.normalize();
        sw.sendParticles(ParticleTypes.FLAME, pos.x, pos.y, pos.z, 30, d.x * 0.5, d.y * 0.5, d.z * 0.5, 0.25);
        sw.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y, pos.z, 20, 0.6, 0.6, 0.6, 0.03);
    }

    private Explosions() {
    }
}
