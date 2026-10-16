package com.qwarwin.fpvdrones.client;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import com.qwarwin.fpvdrones.entity.AbstractDroneEntity;
import com.qwarwin.fpvdrones.entity.FpvDroneEntity;
import com.qwarwin.fpvdrones.entity.MavicEntity;
import com.qwarwin.fpvdrones.entity.LaunchTruckEntity;
import com.qwarwin.fpvdrones.entity.StrikeDroneEntity;
import com.qwarwin.fpvdrones.entity.StrikeType;
import com.qwarwin.fpvdrones.item.PayloadType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.gen.Heightmap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Locale;
import java.util.Random;

/**
 * Overlays: analog OSD for the FPV (noise grows as the link gets worse), DJI-ish screen for the Mavic,
 * tablet frame with telemetry for strike drones.
 */
@OnlyIn(Dist.CLIENT)
public final class HudRenderer {
    private static final Random RNG = new Random();
    private static final int WHITE = 0xFFFFFFFF;
    private static final int RED = 0xFFFF4040;
    private static final int YELLOW = 0xFFFFD040;
    private static final int GREEN = 0xFF50FF80;
    private static final int GREY = 0xFFB0B0B0;
    private static final String[] COMPASS = {"С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ"};

    public static void render(MatrixStack ms, float pt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        if (!ClientSession.active) {
            noSignal(ms, mc, w, h, new TranslationTextComponent(ClientSession.lostReason).getString());
            return;
        }
        Entity e = mc.level.getEntity(ClientSession.entityId);
        if (e == null) {
            noSignal(ms, mc, w, h, "...");
            return;
        }
        if (e instanceof FpvDroneEntity) {
            fpv(ms, mc, (FpvDroneEntity) e, w, h);
        } else if (e instanceof MavicEntity) {
            mavic(ms, mc, (MavicEntity) e, w, h);
        } else if (e instanceof StrikeDroneEntity) {
            strike(ms, mc, (StrikeDroneEntity) e, w, h, pt);
        }
    }

    private static void fpv(MatrixStack ms, Minecraft mc, FpvDroneEntity e, int w, int h) {
        FontRenderer font = mc.font;
        float sig = MathHelper.clamp(ClientSession.signal, 0F, 1F);
        boolean blink = (ClientSession.ticks / 6) % 2 == 0;
        int cx = w / 2, cy = h / 2;

        float noiseAmt = 0.03F + (float) Math.pow(1F - sig, 2) * 0.95F;
        if (e.getBattery() <= 0F) {
            noiseAmt = Math.max(noiseAmt, 0.5F);
        }
        noise(ms, w, h, noiseAmt);
        scanlines(ms, w, h);
        vignette(ms, w, h, 14, 0x16);

        fill(ms, cx - 8, cy, cx - 3, cy + 1, WHITE);
        fill(ms, cx + 4, cy, cx + 9, cy + 1, WHITE);
        fill(ms, cx, cy - 1, cx + 1, cy + 2, WHITE);

        float roll = e.tiltRoll;
        for (int i = -4; i <= 4; i++) {
            if (Math.abs(i) < 2) {
                continue;
            }
            int x = cx + i * 9;
            int y = cy + Math.round((float) Math.tan(Math.toRadians(-roll)) * i * 9) + 20;
            fill(ms, x, y, x + 5, y + 1, 0xAAFFFFFF);
        }

        float bat = e.getBattery();
        float volts = 19.8F + 5.4F * bat;
        int batCol = bat < 0.2F ? (blink ? RED : WHITE) : WHITE;
        osd(ms, font, String.format(Locale.ROOT, "%.1fV", volts), 10, 10, batCol);
        osd(ms, font, String.format(Locale.ROOT, "%d mAh", Math.round((1F - bat) * 1300F)), 10, 22, WHITE);

        int sec = ClientSession.ticks / 20;
        String timer = String.format(Locale.ROOT, "%02d:%02d", sec / 60, sec % 60);
        osd(ms, font, timer, w - 10 - font.width(timer), 10, WHITE);

        String lq = "LQ " + Math.round(sig * 100) + "  RSSI -" + Math.round(40 + (1 - sig) * 60) + "dB";
        osd(ms, font, lq, cx - font.width(lq) / 2, 10, sig < 0.3F ? (blink ? RED : YELLOW) : WHITE);

        Vector3d d = e.getDeltaMovement();
        int kmh = Math.round((float) d.length() * 20F * 3.6F);
        osd(ms, font, kmh + " KM/H", 10, cy - 4, WHITE);
        int ground = mc.level.getHeight(Heightmap.Type.MOTION_BLOCKING, MathHelper.floor(e.getX()), MathHelper.floor(e.getZ()));
        String alt = "ALT " + Math.max(0, Math.round((float) e.getY() - ground)) + "M";
        osd(ms, font, alt, w - 10 - font.width(alt), cy - 4, WHITE);

        float yaw = MathHelper.wrapDegrees(e.yRot + 180F);
        int heading = Math.round(yaw < 0 ? yaw + 360F : yaw) % 360;
        String hd = COMPASS[Math.round(heading / 45F) % 8] + " " + String.format(Locale.ROOT, "%03d", heading);
        osd(ms, font, hd, cx - font.width(hd) / 2, h - 34, WHITE);
        double dist = Math.sqrt(sq(e.getX() - ClientSession.homeX) + sq(e.getZ() - ClientSession.homeZ));
        String home = "H " + Math.round(dist) + "M";
        osd(ms, font, home, cx - font.width(home) / 2, h - 22, WHITE);

        PayloadType p = e.getPayload();
        if (p != PayloadType.NONE) {
            drawWarhead(ms, cx, h, p);
            String name = new TranslationTextComponent(p.translationKey()).getString().toUpperCase(Locale.ROOT);
            osd(ms, font, name, 10, h - 34, WHITE);
            osd(ms, font, "ARMED", 10, h - 22, blink ? RED : 0xFFAA2020);
        } else {
            osd(ms, font, "DISARMED", 10, h - 22, GREY);
        }

        String hint = (p != PayloadType.NONE ? "[" + key(KeyBinds.ACTION) + "] ПОДРЫВ  " : "") + "[" + key(KeyBinds.TOGGLE_VIEW) + "] ВЫХОД";
        osd(ms, font, hint, w - 10 - font.width(hint), h - 22, GREY);

        if (bat < 0.15F && blink) {
            centered(ms, font, "LOW BATTERY", cx, cy + 34, RED);
        }
        if (sig < 0.25F && !blink) {
            centered(ms, font, "СЛАБЫЙ СИГНАЛ", cx, cy - 40, YELLOW);
        }
    }

    // warhead nose sticking into the bottom of the frame, like on real FPV footage
    private static void drawWarhead(MatrixStack ms, int cx, int h, PayloadType p) {
        int body = p == PayloadType.OG7 ? 0xFF3E4724 : (p == PayloadType.CANISTER ? 0xFFBDB9AE : 0xFF262A20);
        int hi = p == PayloadType.CANISTER ? 0xFFDAD6CB : 0xFF4A5040;
        if (p == PayloadType.CANISTER) {
            fill(ms, cx - 70, h - 14, cx + 70, h, body);
            fill(ms, cx - 70, h - 14, cx + 70, h - 12, hi);
            fill(ms, cx - 70, h - 8, cx + 70, h - 5, 0xFF8C8F91);
            return;
        }
        fill(ms, cx - 22, h - 20, cx + 22, h, body);
        fill(ms, cx - 16, h - 32, cx + 16, h - 20, body);
        fill(ms, cx - 9, h - 42, cx + 9, h - 32, body);
        fill(ms, cx - 3, h - 50, cx + 3, h - 42, 0xFFB8963A);
        fill(ms, cx - 22, h - 20, cx - 16, h, hi);
        fill(ms, cx - 16, h - 32, cx - 11, h - 20, hi);
        fill(ms, cx - 9, h - 42, cx - 6, h - 32, hi);
    }

    private static void mavic(MatrixStack ms, Minecraft mc, MavicEntity e, int w, int h) {
        FontRenderer font = mc.font;
        float sig = MathHelper.clamp(ClientSession.signal, 0F, 1F);
        int cx = w / 2, cy = h / 2;
        boolean blink = (ClientSession.ticks / 8) % 2 == 0;

        if (sig < 0.45F) {
            // digital link breaks into blocks instead of snow
            blocks(ms, w, h, (0.45F - sig) * 2F);
        }

        fill(ms, w / 3, 18, w / 3 + 1, h - 30, 0x22FFFFFF);
        fill(ms, 2 * w / 3, 18, 2 * w / 3 + 1, h - 30, 0x22FFFFFF);
        fill(ms, 0, h / 3, w, h / 3 + 1, 0x22FFFFFF);
        fill(ms, 0, 2 * h / 3, w, 2 * h / 3 + 1, 0x22FFFFFF);

        int fs = 14;
        corner(ms, cx - fs, cy - fs, 1, 1);
        corner(ms, cx + fs, cy - fs, -1, 1);
        corner(ms, cx - fs, cy + fs, 1, -1);
        corner(ms, cx + fs, cy + fs, -1, -1);
        fill(ms, cx, cy, cx + 1, cy + 1, WHITE);

        fill(ms, 0, 0, w, 16, 0x99000000);
        String status = e.isPowered() ? "В ПОЛЁТЕ" : "НА ЗЕМЛЕ";
        font.drawShadow(ms, "M3  •  " + status, 6, 4, WHITE);
        String gnss = "GNSS 22   " + bars(sig);
        font.drawShadow(ms, gnss, cx - font.width(gnss) / 2, 4, sig < 0.3F ? YELLOW : WHITE);
        int pct = Math.round(e.getBattery() * 100F);
        String bat = pct + "%";
        int bx = w - 8 - font.width(bat);
        font.drawShadow(ms, bat, bx, 4, pct < 20 ? (blink ? RED : WHITE) : WHITE);
        int barW = 22;
        fill(ms, bx - barW - 6, 5, bx - 6, 11, 0xFF505050);
        fill(ms, bx - barW - 5, 6, bx - barW - 5 + Math.round((barW - 2) * e.getBattery()), 10,
                pct < 20 ? RED : (pct < 40 ? YELLOW : GREEN));

        String zoom = String.format(Locale.ROOT, "%.1fx", ClientSession.zoom);
        font.drawShadow(ms, zoom, w - 10 - font.width(zoom), cy - 30, WHITE);
        int zb = cy - 18, zt = cy + 30;
        fill(ms, w - 14, zb, w - 12, zt, 0x66FFFFFF);
        int zp = zt - Math.round((zt - zb) * (float) (Math.log(ClientSession.zoom) / Math.log(28)));
        fill(ms, w - 17, zp - 1, w - 9, zp + 1, WHITE);

        float gp = MathHelper.clamp(mc.player.xRot, -30F, 90F);
        int gt = cy - 30, gb = cy + 30;
        fill(ms, 12, gt, 14, gb, 0x66FFFFFF);
        int gy = gt + Math.round((gb - gt) * (gp + 30F) / 120F);
        fill(ms, 9, gy - 1, 17, gy + 1, WHITE);
        font.drawShadow(ms, Math.round(-gp) + "°", 20, gy - 4, WHITE);

        fill(ms, 0, h - 28, w, h, 0x99000000);
        Vector3d d = e.getDeltaMovement();
        double hs = Math.sqrt(d.x * d.x + d.z * d.z) * 20.0;
        double vs = d.y * 20.0;
        double dist = Math.sqrt(sq(e.getX() - ClientSession.homeX) + sq(e.getZ() - ClientSession.homeZ));
        double hAlt = e.getY() - ClientSession.homeY;
        font.drawShadow(ms, String.format(Locale.ROOT, "H %.1fм   D %dм", hAlt, Math.round(dist)), 6, h - 24, WHITE);
        font.drawShadow(ms, String.format(Locale.ROOT, "HS %.1fм/с   VS %.1fм/с", hs, vs), 6, h - 13, WHITE);

        BlockPos aim = ClientSession.aim;
        String aimS = aim == null ? "ЦЕЛЬ: —" : "ЦЕЛЬ: " + aim.getX() + " " + aim.getY() + " " + aim.getZ();
        font.drawShadow(ms, aimS, cx - font.width(aimS) / 2, h - 24, YELLOW);
        String hint = "[" + key(KeyBinds.ACTION) + "] СБРОС  [колесо] ЗУМ  [" + key(KeyBinds.TOGGLE_VIEW) + "] ВЫХОД";
        font.drawShadow(ms, hint, cx - font.width(hint) / 2, h - 13, GREY);

        int sx = w - 8;
        for (int i = MavicEntity.SLOTS - 1; i >= 0; i--) {
            PayloadType t = e.getSlot(i);
            String n = t == PayloadType.NONE ? "ПУСТО" : new TranslationTextComponent(t.translationKey()).getString();
            int tw = font.width(n) + 8;
            fill(ms, sx - tw, h - 26, sx, h - 14, t == PayloadType.NONE ? 0x66404040 : 0xCC8A1C1C);
            font.drawShadow(ms, n, sx - tw + 4, h - 24, WHITE);
            sx -= tw + 4;
        }
    }

    private static void strike(MatrixStack ms, Minecraft mc, StrikeDroneEntity e, int w, int h, float pt) {
        FontRenderer font = mc.font;
        int cx = w / 2, cy = h / 2;
        boolean blink = (ClientSession.ticks / 10) % 2 == 0;

        fill(ms, 0, 0, w, h, 0x1630FF70);
        noise(ms, w, h, 0.05F);
        scanlines(ms, w, h);

        fill(ms, cx - 20, cy, cx - 6, cy + 1, GREEN);
        fill(ms, cx + 7, cy, cx + 21, cy + 1, GREEN);
        fill(ms, cx, cy - 14, cx + 1, cy - 5, GREEN);
        fill(ms, cx, cy + 6, cx + 1, cy + 15, GREEN);

        Vector3d target = new Vector3d(ClientSession.targetX + 0.5, e.getPhase() == StrikeDroneEntity.DIVE ? ClientSession.targetY : e.getY(),
                ClientSession.targetZ + 0.5);
        targetMarker(ms, mc, font, target, w, h);

        int b = 12;
        int frame = 0xFF1C201B, bevel = 0xFF3B4237;
        fill(ms, 0, 0, w, b, frame);
        fill(ms, 0, h - b - 10, w, h, frame);
        fill(ms, 0, 0, b, h, frame);
        fill(ms, w - b, 0, w, h, frame);
        fill(ms, b, b, w - b, b + 1, bevel);
        fill(ms, b, h - b - 11, w - b, h - b - 10, bevel);
        fill(ms, b, b, b + 1, h - b - 10, bevel);
        fill(ms, w - b - 1, b, w - b, h - b - 10, bevel);
        for (int[] s : new int[][]{{4, 4}, {w - 8, 4}, {4, h - 8}, {w - 8, h - 8}}) {
            fill(ms, s[0], s[1], s[0] + 4, s[1] + 4, 0xFF4E564A);
            fill(ms, s[0] + 1, s[1] + 2, s[0] + 3, s[1] + 3, 0xFF2A2F28);
        }
        fill(ms, cx - 2, 4, cx + 2, 8, 0xFF0D0F0D);
        String label = new TranslationTextComponent(e.getStrikeType().translationKey()).getString().toUpperCase(Locale.ROOT)
                + "  •  КАНАЛ " + Math.abs(e.getId() % 900 + 100);
        font.draw(ms, label, b + 4, h - b - 8, 0xFF8FA088);
        String rec = blink ? "● REC" : "  REC";
        font.draw(ms, rec, w - b - 4 - font.width(rec), h - b - 8, RED);

        Vector3d d = e.getDeltaMovement();
        double speed = d.length() * 20.0;
        double hd = Math.sqrt(sq(ClientSession.targetX + 0.5 - e.getX()) + sq(ClientSession.targetZ + 0.5 - e.getZ()));
        int eta = speed > 0.5 ? (int) Math.round(hd / speed) : 0;
        String phase;
        switch (e.getPhase()) {
            case StrikeDroneEntity.BOOST:
                phase = "СТАРТ / УСКОРИТЕЛЬ";
                break;
            case StrikeDroneEntity.DIVE:
                phase = "ПИКИРОВАНИЕ";
                break;
            default:
                phase = "МАРШ";
                break;
        }
        int px = b + 8, py = b + 8;
        fill(ms, px - 4, py - 4, px + 150, py + 64, 0x88000000);
        font.drawShadow(ms, phase, px, py, e.getPhase() == StrikeDroneEntity.DIVE ? (blink ? RED : YELLOW) : GREEN);
        font.drawShadow(ms, String.format(Locale.ROOT, "ВЫС  %d м", Math.round(e.getY())), px, py + 12, GREEN);
        font.drawShadow(ms, String.format(Locale.ROOT, "СКОР %d км/ч", Math.round(speed * 3.6)), px, py + 22, GREEN);
        font.drawShadow(ms, String.format(Locale.ROOT, "ДО ЦЕЛИ %d м", Math.round(hd)), px, py + 32, GREEN);
        font.drawShadow(ms, String.format(Locale.ROOT, "ETA  %02d:%02d", eta / 60, eta % 60), px, py + 42, GREEN);
        font.drawShadow(ms, String.format(Locale.ROOT, "ЦЕЛЬ %d %d %d", (int) ClientSession.targetX,
                (int) ClientSession.targetY, (int) ClientSession.targetZ), px, py + 52, 0xFFB0FFB0);
        String hint = "[" + key(KeyBinds.TOGGLE_VIEW) + "] ВЫХОД";
        font.drawShadow(ms, hint, w - b - 8 - font.width(hint), b + 6, GREY);
    }

    private static void targetMarker(MatrixStack ms, Minecraft mc, FontRenderer font, Vector3d target, int w, int h) {
        ActiveRenderInfo cam = mc.gameRenderer.getMainCamera();
        Vector3d cp = cam.getPosition();
        Vector3d d = target.subtract(cp);
        double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
        float tyaw = (float) (MathHelper.atan2(d.z, d.x) * (180.0 / Math.PI)) - 90F;
        float tpitch = (float) (-MathHelper.atan2(d.y, horiz) * (180.0 / Math.PI));
        float dyaw = MathHelper.wrapDegrees(tyaw - cam.getYRot());
        float dpitch = tpitch - cam.getXRot();
        double tanV = Math.tan(Math.toRadians(mc.options.fov / 2.0));
        double aspect = w / (double) h;
        int cx = w / 2, cy = h / 2;
        int x, y;
        boolean onScreen = Math.abs(dyaw) < 80 && Math.abs(dpitch) < 80;
        if (onScreen) {
            x = cx + (int) Math.round(Math.tan(Math.toRadians(dyaw)) / (tanV * aspect) * (w / 2.0));
            y = cy + (int) Math.round(Math.tan(Math.toRadians(dpitch)) / tanV * (h / 2.0));
            onScreen = x > 20 && x < w - 20 && y > 20 && y < h - 30;
        } else {
            x = cx + (dyaw > 0 ? w : -w);
            y = cy;
        }
        if (onScreen) {
            int s = 6;
            for (int i = 0; i <= s; i++) {
                fill(ms, x - s + i, y - i, x - s + i + 1, y - i + 1, RED);
                fill(ms, x + s - i, y - i, x + s - i + 1, y - i + 1, RED);
                fill(ms, x - s + i, y + i, x - s + i + 1, y + i + 1, RED);
                fill(ms, x + s - i, y + i, x + s - i + 1, y + i + 1, RED);
            }
            font.drawShadow(ms, "ЦЕЛЬ", x - font.width("ЦЕЛЬ") / 2, y + 9, RED);
        } else {
            int ax = MathHelper.clamp(x, 24, w - 34);
            int ay = MathHelper.clamp(y, 24, h - 40);
            String arrow = dyaw > 0 ? "ЦЕЛЬ ▶" : "◀ ЦЕЛЬ";
            font.drawShadow(ms, arrow, ax, ay, RED);
        }
    }

    public static void renderTruck(MatrixStack ms, LaunchTruckEntity truck) {
        Minecraft mc = Minecraft.getInstance();
        FontRenderer font = mc.font;
        int h = mc.getWindow().getGuiScaledHeight();
        int x = 8, y = h - 62;
        fill(ms, x - 4, y - 4, x + 128, y + 26, 0x88000000);
        font.drawShadow(ms, new TranslationTextComponent("entity.fpvdrones.launch_truck").getString(), x, y, 0xFFCFD8B8);
        for (int i = 0; i < LaunchTruckEntity.CAPACITY; i++) {
            StrikeType t = truck.getLoaded(i);
            int sx = x + i * 14;
            fill(ms, sx, y + 12, sx + 11, y + 21, t == null ? 0xFF3A3A3A : (t.isJet() ? 0xFFE08A2A : 0xFF8FA088));
        }
        String hint = truck.isFiring() ? "ЗАЛП..." : "[" + key(KeyBinds.ACTION) + "] меню";
        font.drawShadow(ms, hint, x + 74, y + 13, truck.isFiring() ? RED : GREY);
    }

    private static void noSignal(MatrixStack ms, Minecraft mc, int w, int h, String reason) {
        FontRenderer font = mc.font;
        fill(ms, 0, 0, w, h, 0xFF101010);
        noise(ms, w, h, 1.0F);
        scanlines(ms, w, h);
        int cx = w / 2, cy = h / 2;
        fill(ms, cx - 80, cy - 18, cx + 80, cy + 22, 0xDD000000);
        ms.pushPose();
        ms.translate(cx, cy - 12, 0);
        ms.scale(1.6F, 1.6F, 1F);
        String t = "НЕТ СИГНАЛА";
        font.drawShadow(ms, t, -font.width(t) / 2F, 0, WHITE);
        ms.popPose();
        if (reason != null && !reason.isEmpty()) {
            centered(ms, font, reason, cx, cy + 8, RED);
        }
    }

    private static void fill(MatrixStack ms, int x1, int y1, int x2, int y2, int color) {
        AbstractGui.fill(ms, x1, y1, x2, y2, color);
    }

    private static void osd(MatrixStack ms, FontRenderer font, String s, int x, int y, int color) {
        font.draw(ms, s, x - 1, y, 0xFF000000);
        font.draw(ms, s, x + 1, y, 0xFF000000);
        font.draw(ms, s, x, y - 1, 0xFF000000);
        font.draw(ms, s, x, y + 1, 0xFF000000);
        font.draw(ms, s, x, y, color);
    }

    private static void centered(MatrixStack ms, FontRenderer font, String s, int cx, int y, int color) {
        osd(ms, font, s, cx - font.width(s) / 2, y, color);
    }

    private static void noise(MatrixStack ms, int w, int h, float amount) {
        int n = (int) (amount * 1100);
        for (int i = 0; i < n; i++) {
            int x = RNG.nextInt(Math.max(1, w));
            int y = RNG.nextInt(Math.max(1, h));
            int s = 1 + RNG.nextInt(2);
            int g = 110 + RNG.nextInt(146);
            int a = MathHelper.clamp((int) (60 + 195 * amount), 0, 255);
            fill(ms, x, y, x + s * 2, y + s, (a << 24) | (g << 16) | (g << 8) | g);
        }
        int lines = (int) (amount * 10);
        for (int i = 0; i < lines; i++) {
            int y = RNG.nextInt(Math.max(1, h));
            int a = MathHelper.clamp((int) (40 + amount * 140), 0, 255);
            fill(ms, 0, y, w, y + 1 + RNG.nextInt(2), (a << 24) | 0xDDDDDD);
        }
    }

    private static void blocks(MatrixStack ms, int w, int h, float amount) {
        int n = (int) (amount * 60);
        for (int i = 0; i < n; i++) {
            int x = RNG.nextInt(Math.max(1, w / 16)) * 16;
            int y = RNG.nextInt(Math.max(1, h / 16)) * 16;
            int g = 40 + RNG.nextInt(60);
            fill(ms, x, y, x + 16, y + 16, 0xDD000000 | (g << 16) | ((g + 20) << 8) | g);
        }
    }

    private static void scanlines(MatrixStack ms, int w, int h) {
        for (int y = 0; y < h; y += 3) {
            fill(ms, 0, y, w, y + 1, 0x14000000);
        }
    }

    private static void vignette(MatrixStack ms, int w, int h, int steps, int alphaStep) {
        for (int i = 0; i < steps; i++) {
            int c = (Math.min(255, alphaStep * (steps - i)) / 2) << 24;
            int o = i * 3;
            fill(ms, o, o, w - o, o + 3, c);
            fill(ms, o, h - o - 3, w - o, h - o, c);
            fill(ms, o, o + 3, o + 3, h - o - 3, c);
            fill(ms, w - o - 3, o + 3, w - o, h - o - 3, c);
        }
    }

    private static void corner(MatrixStack ms, int x, int y, int dx, int dy) {
        int len = 6;
        fill(ms, Math.min(x, x + dx * len), y, Math.max(x, x + dx * len) + 1, y + 1, WHITE);
        fill(ms, x, Math.min(y, y + dy * len), x + 1, Math.max(y, y + dy * len) + 1, WHITE);
    }

    private static String bars(float sig) {
        int n = Math.round(sig * 5);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append(i < n ? '|' : '.');
        }
        return sb.toString();
    }

    private static String key(net.minecraft.client.settings.KeyBinding kb) {
        return kb.getTranslatedKeyMessage().getString().toUpperCase(Locale.ROOT);
    }

    private static double sq(double v) {
        return v * v;
    }

    private HudRenderer() {
    }
}
