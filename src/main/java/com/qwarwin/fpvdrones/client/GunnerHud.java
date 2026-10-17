package com.qwarwin.fpvdrones.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.qwarwin.fpvdrones.entity.AbstractDroneEntity;
import com.qwarwin.fpvdrones.entity.AirDefenseGunEntity;
import com.qwarwin.fpvdrones.entity.KornetEntity;
import com.qwarwin.fpvdrones.entity.MachineEntity;
import com.qwarwin.fpvdrones.entity.MissileEntity;
import com.qwarwin.fpvdrones.entity.MountedWeaponEntity;
import com.qwarwin.fpvdrones.entity.ShellEntity;
import com.qwarwin.fpvdrones.entity.StrikeDroneEntity;
import com.qwarwin.fpvdrones.entity.TerminatorEntity;
import com.qwarwin.fpvdrones.item.JavelinItem;
import com.qwarwin.fpvdrones.util.Targeting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Locale;

/** Gunner sights for the gun mount, the BMPT and the Kornet, and the Javelin seeker view. */
@OnlyIn(Dist.CLIENT)
public final class GunnerHud {
    private static final int GREEN = 0xFF6CFF8A;
    private static final int GREEN_DIM = 0x996CFF8A;
    private static final int AMBER = 0xFFFFB030;
    private static final int RED = 0xFFFF4040;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int SHADE = 0x88000000;

    // scroll zoom while sitting at a sight
    public static float zoom = 1F;

    public static void render(MatrixStack ms, MountedWeaponEntity gun, float pt) {
        Minecraft mc = Minecraft.getInstance();
        FontRenderer font = mc.font;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        int cx = w / 2, cy = h / 2;
        boolean blink = (mc.player.tickCount / 8) % 2 == 0;

        // where the barrel points right now; the turret lags behind the view
        Vector3d muzzleDir = Vector3d.directionFromRotation(gun.getGunPitch(pt), gun.getTurretYaw(pt));
        int[] gp = Projection.toScreen(gun.sightWorld().add(muzzleDir.scale(300)), w, h);
        if (gp != null) {
            circle(ms, gp[0], gp[1], 5, gun.getAmmo() > 0 ? GREEN : RED);
        }

        if (gun instanceof AirDefenseGunEntity) {
            airDefense(ms, mc, font, (AirDefenseGunEntity) gun, w, h, blink);
        } else if (gun instanceof TerminatorEntity) {
            terminator(ms, mc, font, (TerminatorEntity) gun, w, h, blink, pt);
        } else if (gun instanceof KornetEntity) {
            kornet(ms, mc, font, (KornetEntity) gun, w, h, blink);
        }

        // range to whatever is under the crosshair
        double range = rangefinder(mc, gun);
        String r = range < 0 ? "Д ----" : String.format(Locale.ROOT, "Д %04d", Math.round(range));
        font.drawShadow(ms, r, cx + 26, cy + 4, GREEN);
        String z = String.format(Locale.ROOT, "%.1fx", zoom);
        font.drawShadow(ms, z, cx + 26, cy - 12, GREEN_DIM);

        // hull integrity
        float hp = gun.getHealth() / gun.getMaxHealth();
        int bw = 80, bx = 8, by = h - 14;
        fill(ms, bx - 1, by - 1, bx + bw + 1, by + 6, SHADE);
        fill(ms, bx, by, bx + Math.round(bw * hp), by + 5, hp < 0.3F ? RED : (hp < 0.6F ? AMBER : GREEN));
        font.drawShadow(ms, "КОРПУС", bx, by - 10, GREEN_DIM);
    }

    private static void airDefense(MatrixStack ms, Minecraft mc, FontRenderer font, AirDefenseGunEntity gun, int w, int h, boolean blink) {
        int cx = w / 2, cy = h / 2;
        // ring sight with lead ticks
        circle(ms, cx, cy, 22, GREEN_DIM);
        fill(ms, cx - 34, cy, cx - 24, cy + 1, GREEN);
        fill(ms, cx + 25, cy, cx + 35, cy + 1, GREEN);
        fill(ms, cx, cy - 34, cx + 1, cy - 24, GREEN);
        fill(ms, cx, cy + 25, cx + 1, cy + 35, GREEN);
        fill(ms, cx - 1, cy - 1, cx + 2, cy + 2, GREEN);

        // every powered drone in the area gets a box, the one nearest the crosshair a lead point
        Vector3d eye = gun.sightWorld();
        Entity closest = null;
        double closestScreen = Double.MAX_VALUE;
        for (Entity e : mc.level.getEntities(gun, new AxisAlignedBB(eye, eye).inflate(220), Targeting::isAirTarget)) {
            if (e instanceof AbstractDroneEntity && !((AbstractDroneEntity) e).isPowered()) {
                continue;
            }
            int[] p = Projection.toScreen(e.getBoundingBox().getCenter(), w, h);
            if (p == null) {
                continue;
            }
            box(ms, p[0], p[1], 7, RED);
            String d = Math.round(e.distanceTo(gun)) + "м";
            font.drawShadow(ms, d, p[0] - font.width(d) / 2, p[1] + 10, RED);
            double sd = (p[0] - cx) * (p[0] - cx) + (p[1] - cy) * (p[1] - cy);
            if (sd < closestScreen) {
                closestScreen = sd;
                closest = e;
            }
        }
        if (closest != null) {
            Vector3d lead = Targeting.lead(eye, closest, ShellEntity.SPEED, ShellEntity.GRAVITY);
            int[] lp = Projection.toScreen(lead, w, h);
            if (lp != null) {
                fill(ms, lp[0] - 4, lp[1], lp[0] + 5, lp[1] + 1, AMBER);
                fill(ms, lp[0], lp[1] - 4, lp[0] + 1, lp[1] + 5, AMBER);
                font.drawShadow(ms, "УПР", lp[0] + 6, lp[1] - 9, AMBER);
            }
        }

        panel(ms, font, 8, 8, new String[]{
                "30-мм ЗБМ",
                String.format(Locale.ROOT, "ОФЗ-ДВ  %03d/%d", gun.getAmmo(), gun.maxAmmo()),
                gun.isAuto() ? "РЕЖИМ: АВТО" : "РЕЖИМ: РУЧН",
                "[ЛКМ] огонь  [" + key(KeyBinds.ACTION) + "] авто  [колесо] зум"
        }, gun.getAmmo() == 0 && blink ? RED : GREEN);
    }

    private static void terminator(MatrixStack ms, Minecraft mc, FontRenderer font, TerminatorEntity t, int w, int h, boolean blink,
                                   float pt) {
        int cx = w / 2, cy = h / 2;
        // chevron + stadia
        for (int i = 0; i < 9; i++) {
            fill(ms, cx - i, cy + i, cx - i + 1, cy + i + 1, GREEN);
            fill(ms, cx + i, cy + i, cx + i + 1, cy + i + 1, GREEN);
        }
        fill(ms, cx - 60, cy, cx - 14, cy + 1, GREEN);
        fill(ms, cx + 15, cy, cx + 61, cy + 1, GREEN);
        for (int i = 1; i <= 4; i++) {
            fill(ms, cx - 14 - i * 10, cy - 3, cx - 13 - i * 10, cy + 4, GREEN_DIM);
            fill(ms, cx + 14 + i * 10, cy - 3, cx + 15 + i * 10, cy + 4, GREEN_DIM);
        }

        // hull vs turret: little tank icon bottom centre
        float rel = MathHelper.wrapDegrees(t.getTurretYaw(pt) - t.yRot);
        int ix = cx, iy = h - 30;
        fill(ms, ix - 5, iy - 8, ix + 6, iy + 9, 0x6644FF66);
        double a = Math.toRadians(-rel);
        for (int i = 0; i < 12; i++) {
            int px = ix + (int) Math.round(Math.sin(a) * i);
            int py = iy - (int) Math.round(Math.cos(a) * i);
            fill(ms, px, py, px + 1, py + 1, WHITE);
        }

        boolean guiding = false;
        for (Entity e : mc.level.getEntities(t, t.getBoundingBox().inflate(900), e -> e instanceof MissileEntity)) {
            guiding |= ((MissileEntity) e).isGuided() && ((MissileEntity) e).getMissileType().isBeamRiding();
        }
        boolean driver = !t.getPassengers().isEmpty() && t.getPassengers().get(0) == mc.player;
        panel(ms, font, 8, 8, new String[]{
                "БМПТ «Терминатор»",
                String.format(Locale.ROOT, "2А42 х2  %03d/%d", t.getAmmo(), t.maxAmmo()),
                String.format(Locale.ROOT, "ПТУР «Атака»  %d/%d", t.getAmmo2(), t.maxAmmo2()),
                String.format(Locale.ROOT, "СКОРОСТЬ %d км/ч", Math.round(Math.abs(t.getSpeed()) * 20 * 3.6)),
                "[ЛКМ] пушки  [ПКМ] ПТУР  [колесо] зум" + (driver ? "  [WASD] ход" : "")
        }, GREEN);
        if (guiding) {
            String g = "НАВЕДЕНИЕ — ДЕРЖИ ЦЕЛЬ";
            font.drawShadow(ms, g, cx - font.width(g) / 2, cy + 30, blink ? AMBER : GREEN);
        }
    }

    private static void kornet(MatrixStack ms, Minecraft mc, FontRenderer font, KornetEntity k, int w, int h, boolean blink) {
        int cx = w / 2, cy = h / 2;
        // tinted sight with the classic broken cross
        fill(ms, 0, 0, w, h, 0x1430FF70);
        fill(ms, cx - 80, cy, cx - 6, cy + 1, GREEN);
        fill(ms, cx + 7, cy, cx + 81, cy + 1, GREEN);
        fill(ms, cx, cy + 7, cx + 1, cy + 60, GREEN);
        fill(ms, cx, cy - 40, cx + 1, cy - 6, GREEN_DIM);
        for (int i = 1; i <= 3; i++) {
            fill(ms, cx - 3, cy + 6 + i * 12, cx + 4, cy + 7 + i * 12, GREEN);
        }
        boolean guiding = false;
        for (Entity e : mc.level.getEntities(k, k.getBoundingBox().inflate(900), e -> e instanceof MissileEntity)) {
            guiding |= ((MissileEntity) e).isGuided() && ((MissileEntity) e).getMissileType().isBeamRiding();
        }
        String state = guiding ? "НАВЕДЕНИЕ" : k.getAmmo() > 0 ? "ГОТОВ" : "ПЕРЕЗАРЯДКА";
        int col = guiding ? (blink ? AMBER : GREEN) : k.getAmmo() > 0 ? GREEN : AMBER;
        font.drawShadow(ms, state, cx - font.width(state) / 2, cy - 54, col);
        panel(ms, font, 8, 8, new String[]{
                "ПТРК «Корнет»",
                "[ЛКМ] пуск, держи прицел на цели",
                "[колесо] зум"
        }, GREEN);
    }

    /** CLU view while the Javelin is raised. */
    public static void javelin(MatrixStack ms, PlayerEntity player) {
        Minecraft mc = Minecraft.getInstance();
        FontRenderer font = mc.font;
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        int cx = w / 2, cy = h / 2;
        int r = Math.min(w, h) / 2 - 10;
        // dark surround, greenish seeker picture
        fill(ms, 0, 0, w, cy - r, 0xEE050805);
        fill(ms, 0, cy + r, w, h, 0xEE050805);
        fill(ms, 0, cy - r, cx - r, cy + r, 0xEE050805);
        fill(ms, cx + r, cy - r, w, cy + r, 0xEE050805);
        fill(ms, cx - r, cy - r, cx + r, cy + r, 0x2230FF60);
        fill(ms, cx - r, cy, cx - r + 30, cy + 1, GREEN);
        fill(ms, cx + r - 30, cy, cx + r, cy + 1, GREEN);
        fill(ms, cx, cy - r, cx + 1, cy - r + 30, GREEN);
        fill(ms, cx, cy + r - 30, cx + 1, cy + r, GREEN);

        JavelinItem.Lock lock = JavelinItem.lockOf(player);
        Entity t = lock.targetId < 0 ? null : mc.level.getEntity(lock.targetId);
        boolean locked = lock.locked();
        if (t != null) {
            int[] p = Projection.toScreen(t.getBoundingBox().getCenter(), w, h);
            if (p != null) {
                int s = locked ? 12 : 22 - Math.min(10, lock.ticks / 4);
                int col = locked ? GREEN : AMBER;
                if (locked || (player.tickCount / 3) % 2 == 0) {
                    box(ms, p[0], p[1], s, col);
                }
                String n = t instanceof MachineEntity || t instanceof StrikeDroneEntity ? "ЦЕЛЬ: ТЕХНИКА" : "ЦЕЛЬ";
                font.drawShadow(ms, n, p[0] - font.width(n) / 2, p[1] + s + 4, col);
            }
        }
        String state = locked ? "ЗАХВАТ — ОТПУСТИ ПКМ" : t != null ? "ЗАХВАТ " + Math.min(99, lock.ticks * 100 / JavelinItem.LOCK_TICKS) + "%" : "ПОИСК";
        font.drawShadow(ms, state, cx - font.width(state) / 2, cy + r - 24, locked ? GREEN : AMBER);
        String mode = "ВЕРХ  4x";
        font.drawShadow(ms, mode, cx - r + 8, cy - r + 8, GREEN_DIM);
    }

    private static double rangefinder(Minecraft mc, MountedWeaponEntity gun) {
        Vector3d from = mc.gameRenderer.getMainCamera().getPosition();
        Vector3d dir = mc.player.getViewVector(1F);
        Vector3d to = from.add(dir.scale(1500));
        RayTraceResult r = mc.level.clip(new RayTraceContext(from, to, RayTraceContext.BlockMode.COLLIDER,
                RayTraceContext.FluidMode.NONE, mc.player));
        return r.getType() == RayTraceResult.Type.MISS ? -1 : r.getLocation().distanceTo(from);
    }

    private static void panel(MatrixStack ms, FontRenderer font, int x, int y, String[] lines, int color) {
        int wMax = 0;
        for (String s : lines) {
            wMax = Math.max(wMax, font.width(s));
        }
        fill(ms, x - 4, y - 4, x + wMax + 4, y + lines.length * 11, SHADE);
        for (int i = 0; i < lines.length; i++) {
            font.drawShadow(ms, lines[i], x, y + i * 11, i == 0 ? WHITE : color);
        }
    }

    private static void circle(MatrixStack ms, int x, int y, int r, int color) {
        int n = Math.max(16, r * 4);
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n;
            int px = x + (int) Math.round(Math.cos(a) * r);
            int py = y + (int) Math.round(Math.sin(a) * r);
            fill(ms, px, py, px + 1, py + 1, color);
        }
    }

    private static void box(MatrixStack ms, int x, int y, int s, int color) {
        fill(ms, x - s, y - s, x + s + 1, y - s + 1, color);
        fill(ms, x - s, y + s, x + s + 1, y + s + 1, color);
        fill(ms, x - s, y - s, x - s + 1, y + s + 1, color);
        fill(ms, x + s, y - s, x + s + 1, y + s + 1, color);
    }

    private static void fill(MatrixStack ms, int x1, int y1, int x2, int y2, int color) {
        AbstractGui.fill(ms, x1, y1, x2, y2, color);
    }

    private static String key(KeyBinding kb) {
        return kb.getTranslatedKeyMessage().getString().toUpperCase(Locale.ROOT);
    }

    private GunnerHud() {
    }
}
