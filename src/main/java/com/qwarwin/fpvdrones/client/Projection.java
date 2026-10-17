package com.qwarwin.fpvdrones.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;

/** World point to GUI coordinates for HUD markers. */
@OnlyIn(Dist.CLIENT)
public final class Projection {
    // vertical FOV actually used this frame, set from the FOV event
    public static double fov = 70.0;

    @Nullable
    public static int[] toScreen(Vector3d world, int w, int h) {
        ActiveRenderInfo cam = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vector3d d = world.subtract(cam.getPosition());
        Vector3d fwd = Vector3d.directionFromRotation(cam.getXRot(), cam.getYRot());
        Vector3d left = Vector3d.directionFromRotation(0F, cam.getYRot() - 90F);
        Vector3d up = fwd.cross(left);
        double z = d.dot(fwd);
        if (z < 0.05) {
            return null;
        }
        double tanV = Math.tan(Math.toRadians(fov / 2.0));
        double tanH = tanV * w / (double) h;
        double x = w / 2.0 - d.dot(left) / z / tanH * (w / 2.0);
        double y = h / 2.0 - d.dot(up) / z / tanV * (h / 2.0);
        return new int[]{(int) Math.round(x), (int) Math.round(y)};
    }

    private Projection() {
    }
}
