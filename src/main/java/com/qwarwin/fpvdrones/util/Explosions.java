package com.qwarwin.fpvdrones.util;

import com.qwarwin.fpvdrones.Config;
import com.qwarwin.fpvdrones.item.PayloadType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.DamageSource;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.AxisAlignedBB;
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
                jet(level, attacker, pos, dir, 7.0, 45F);
                fragments(level, attacker, pos, 5.0, 8F);
                sw.sendParticles(ParticleTypes.FLAME, pos.x, pos.y, pos.z, 40, dir.x * 0.6, dir.y * 0.6, dir.z * 0.6, 0.3);
                break;
            case OG7:
                fragments(level, attacker, pos, 11.0, 18F);
                break;
            case CANISTER:
                fragments(level, attacker, pos, 7.0, 8F);
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
        ServerWorld sw = (ServerWorld) level;
        sw.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y, pos.z, 4, 2.0, 1.0, 2.0, 0);
        sw.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, pos.x, pos.y + 1, pos.z, 40, 2.5, 1.0, 2.5, 0.03);
        sw.sendParticles(ParticleTypes.LAVA, pos.x, pos.y + 0.5, pos.z, 25, 1.5, 0.5, 1.5, 0.1);
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

    private static void jet(World level, @Nullable Entity attacker, Vector3d pos, Vector3d dir, double length, float damage) {
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
                e.hurt(ModDamageSources.cumulative(attacker), e instanceof LivingEntity ? damage * mul : damage);
            }
        }
    }

    /** 30 mm HE-frag. With the proximity fuse it bursts next to drones instead of needing a direct hit. */
    public static void shell(World level, Entity shell, @Nullable UUID attackerId, Vector3d pos, boolean airburst) {
        if (level.isClientSide) {
            return;
        }
        LivingEntity attacker = player(level, attackerId);
        ServerWorld sw = (ServerWorld) level;
        double radius = airburst ? 4.0 : 2.5;
        for (Entity e : level.getEntities(shell, new AxisAlignedBB(pos, pos).inflate(radius), Targeting::isShootable)) {
            double d = e.getBoundingBox().getCenter().distanceTo(pos);
            if (d > radius) {
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

    /** Tandem HEAT: the hit itself, the jet behind it, and a modest blast. */
    public static void atgm(World level, @Nullable UUID attackerId, Vector3d pos, Vector3d dir, @Nullable Entity direct,
                            float power, float directDamage) {
        if (level.isClientSide) {
            return;
        }
        LivingEntity attacker = player(level, attackerId);
        if (direct != null && direct.isAlive()) {
            direct.invulnerableTime = 0;
            direct.hurt(ModDamageSources.cumulative(attacker), directDamage);
        }
        jet(level, attacker, pos.subtract(dir.normalize().scale(0.5)), dir, 6.0, directDamage * 0.4F);
        explode(level, attacker, pos, power, false);
        fragments(level, attacker, pos, power * 1.5, 10F);
        ServerWorld sw = (ServerWorld) level;
        Vector3d d = dir.normalize();
        sw.sendParticles(ParticleTypes.FLAME, pos.x, pos.y, pos.z, 30, d.x * 0.5, d.y * 0.5, d.z * 0.5, 0.25);
        sw.sendParticles(ParticleTypes.LARGE_SMOKE, pos.x, pos.y, pos.z, 20, 0.6, 0.6, 0.6, 0.03);
    }

    private Explosions() {
    }
}
