package com.qwarwin.fpvdrones.util;

import com.qwarwin.fpvdrones.Config;
import com.qwarwin.fpvdrones.item.PayloadType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.util.DamageSource;
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
        if (level.isClientSide || type == PayloadType.NONE) {
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
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
            Vector3d c = e.getBoundingBox().getCenter();
            double t = c.subtract(pos).dot(d);
            if (t < -0.5 || t > length) {
                continue;
            }
            if (pos.add(d.scale(Math.max(0, t))).distanceTo(c) < 1.3) {
                e.invulnerableTime = 0;
                e.hurt(ModDamageSources.cumulative(attacker), damage * mul);
            }
        }
    }

    private Explosions() {
    }
}
