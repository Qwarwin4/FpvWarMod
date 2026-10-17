package com.qwarwin.fpvdrones.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Slows the mouse down while looking through a zoomed sight, so 8x doesn't feel like aiming with a rocket.
 * Vanilla turns by sensitivity^3, hence the cube root. The real value is put back whenever a screen opens,
 * so it can never end up saved in options.txt.
 */
@OnlyIn(Dist.CLIENT)
public final class Sensitivity {
    private static double saved = Double.NaN;

    public static void update(Minecraft mc, float zoom) {
        if (zoom <= 1.01F || mc.screen != null || mc.player == null) {
            restore(mc);
            return;
        }
        if (Double.isNaN(saved)) {
            saved = mc.options.sensitivity;
        }
        double f = (saved * 0.6 + 0.2) / Math.cbrt(zoom);
        mc.options.sensitivity = Math.max(-0.33, (f - 0.2) / 0.6);
    }

    public static void restore(Minecraft mc) {
        if (!Double.isNaN(saved)) {
            mc.options.sensitivity = saved;
            saved = Double.NaN;
        }
    }

    private Sensitivity() {
    }
}
