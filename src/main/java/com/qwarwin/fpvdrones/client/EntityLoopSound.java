package com.qwarwin.fpvdrones.client;

import com.qwarwin.fpvdrones.entity.AbstractDroneEntity;
import com.qwarwin.fpvdrones.entity.LaunchTruckEntity;
import com.qwarwin.fpvdrones.entity.StrikeDroneEntity;
import net.minecraft.client.audio.TickableSound;
import net.minecraft.entity.Entity;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class EntityLoopSound extends TickableSound {
    private final Entity entity;
    private final float baseVolume;

    public EntityLoopSound(Entity entity, SoundEvent event, float baseVolume) {
        super(event, SoundCategory.NEUTRAL);
        this.entity = entity;
        this.baseVolume = baseVolume;
        this.looping = true;
        this.delay = 0;
        this.volume = 0.01F;
        this.x = entity.getX();
        this.y = entity.getY();
        this.z = entity.getZ();
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        if (!entity.isAlive()) {
            stop();
            return;
        }
        x = entity.getX();
        y = entity.getY();
        z = entity.getZ();
        float speed = (float) entity.getDeltaMovement().length();
        if (entity instanceof AbstractDroneEntity) {
            AbstractDroneEntity d = (AbstractDroneEntity) entity;
            float target = d.isPowered() ? baseVolume * (d.isLocallyControlled() ? 0.35F : 1F) : 0F;
            volume += (target - volume) * 0.2F;
            pitch = MathHelper.clamp(0.85F + speed * 0.4F, 0.7F, 1.7F);
        } else if (entity instanceof StrikeDroneEntity) {
            volume = baseVolume;
            pitch = ((StrikeDroneEntity) entity).getPhase() == StrikeDroneEntity.DIVE ? 1.18F : 1.0F;
        } else if (entity instanceof LaunchTruckEntity) {
            LaunchTruckEntity t = (LaunchTruckEntity) entity;
            float moving = (float) Math.min(1.0, Math.abs(t.getSpeed()) * 3.0 + (t.isVehicle() ? 0.35 : 0.0));
            volume += (baseVolume * moving - volume) * 0.1F;
            pitch = 0.75F + moving * 0.45F;
        } else {
            volume = baseVolume;
        }
    }
}
