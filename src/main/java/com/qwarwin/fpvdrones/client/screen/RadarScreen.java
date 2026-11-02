package com.qwarwin.fpvdrones.client.screen;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.qwarwin.fpvdrones.entity.InterceptorEntity;
import com.qwarwin.fpvdrones.network.Network;
import com.qwarwin.fpvdrones.network.RadarCommandPacket;
import com.qwarwin.fpvdrones.network.RadarPicturePacket;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The radar console's scope: north up, the console in the middle, contacts with their course, our interceptors
 * and who they're after. Pick a contact on the scope or in the list, "Intercept" sends the nearest ready one.
 */
@OnlyIn(Dist.CLIENT)
public class RadarScreen extends Screen {
    private static final int[] RANGES = {250, 500, 1000, 2000, 4000};
    private static final int BG = 0xF0101812, GRID = 0xFF2E5A36, SWEEP = 0xFF4FD06A, HOSTILE = 0xFFFF5040,
            FRIEND = 0xFF50E070, OWN = 0xFF4AA8FF, SELECT = 0xFFFFE040, TEXT = 0xFFD8E8D8, DIM = 0xFF7C907E;
    private static final int ROWS = 12;

    private RadarPicturePacket pic;
    private int range = 3;
    private int selected = -1;
    private int ticks;
    private Button intercept;

    private int cx, cy, r, listX, listY;

    public RadarScreen(RadarPicturePacket pic) {
        super(new TranslationTextComponent("screen.fpvdrones.radar"));
        this.pic = pic;
    }

    public int console() {
        return pic.console;
    }

    public void update(RadarPicturePacket m) {
        pic = m;
        if (selected >= 0 && pic.blips.stream().noneMatch(b -> b.id == selected)) {
            selected = -1;
        }
        refreshButtons();
    }

    @Override
    protected void init() {
        r = Math.max(60, Math.min(height / 2 - 24, (int) (width * 0.3)));
        cx = 12 + r;
        cy = height / 2;
        listX = cx + r + 16;
        listY = 30;
        int bx = listX, by = height - 30;
        intercept = addButton(new Button(bx, by, 110, 20, new TranslationTextComponent("screen.fpvdrones.radar.intercept"),
                b -> send(RadarCommandPacket.INTERCEPT, selected)));
        addButton(new Button(bx + 114, by, 20, 20, new StringTextComponent("-"), b -> range = Math.min(RANGES.length - 1, range + 1)));
        addButton(new Button(bx + 136, by, 20, 20, new StringTextComponent("+"), b -> range = Math.max(0, range - 1)));
        addButton(new Button(bx + 160, by, 60, 20, new TranslationTextComponent("gui.done"), b -> onClose()));
        refreshButtons();
    }

    private void refreshButtons() {
        if (intercept == null) {
            return;
        }
        RadarPicturePacket.Blip b = blip(selected);
        intercept.active = b != null && !b.friendly && readyCount() > 0;
    }

    private void send(int action, int target) {
        Network.CHANNEL.sendToServer(new RadarCommandPacket(pic.console, action, target));
    }

    @Override
    public void tick() {
        if (++ticks % 10 == 0) {
            send(RadarCommandPacket.REFRESH, -1);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private RadarPicturePacket.Blip blip(int id) {
        for (RadarPicturePacket.Blip b : pic.blips) {
            if (b.id == id) {
                return b;
            }
        }
        return null;
    }

    private int readyCount() {
        int n = 0;
        for (RadarPicturePacket.Own o : pic.own) {
            if (o.state == InterceptorEntity.PARKED || o.state == InterceptorEntity.LOITER) {
                n++;
            }
        }
        return n;
    }

    private double scale() {
        return r / (double) RANGES[range];
    }

    private int sx(float x) {
        return cx + (int) Math.round((x - pic.cx) * scale());
    }

    private int sy(float z) {
        return cy + (int) Math.round((z - pic.cz) * scale());
    }

    private boolean inside(int x, int y) {
        int dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy <= r * r;
    }

    private List<RadarPicturePacket.Blip> sorted() {
        List<RadarPicturePacket.Blip> l = new ArrayList<>(pic.blips);
        l.sort(Comparator.<RadarPicturePacket.Blip>comparingInt(b -> b.friendly ? 1 : 0)
                .thenComparingDouble(b -> dist(b.x, b.z)));
        return l;
    }

    private double dist(float x, float z) {
        return Math.sqrt((x - pic.cx) * (x - pic.cx) + (z - pic.cz) * (z - pic.cz));
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(MatrixStack ms, int mouseX, int mouseY, float partial) {
        fill(ms, 0, 0, width, height, 0xC0000000);
        // scope face
        for (int y = -r; y <= r; y++) {
            int w = (int) Math.sqrt(r * r - y * y);
            fill(ms, cx - w, cy + y, cx + w, cy + y + 1, BG);
        }
        for (int k = 1; k <= 4; k++) {
            ring(ms, r * k / 4, GRID);
        }
        fill(ms, cx - r, cy, cx + r, cy + 1, GRID);
        fill(ms, cx, cy - r, cx + 1, cy + r, GRID);
        drawCenteredString(ms, font, "N", cx, cy - r - 10, DIM);
        // the beam going round
        double a = ((ticks + partial) * 6.0) % 360.0 * Math.PI / 180.0;
        for (int i = 4; i < r; i += 2) {
            int x = cx + (int) (Math.sin(a) * i), y = cy - (int) (Math.cos(a) * i);
            fill(ms, x, y, x + 1, y + 1, SWEEP);
        }
        // our interceptors and where they're going
        for (RadarPicturePacket.Own o : pic.own) {
            int x = sx(o.x), y = sy(o.z);
            if (!inside(x, y)) {
                continue;
            }
            RadarPicturePacket.Blip t = o.target >= 0 ? blip(o.target) : null;
            if (t != null) {
                dashed(ms, x, y, sx(t.x), sy(t.z), OWN);
            }
            fill(ms, x - 2, y - 2, x + 2, y + 2, o.state == InterceptorEntity.PARKED ? 0xFF2A6090 : OWN);
        }
        // contacts with a course line: where it'll be in ten seconds
        for (RadarPicturePacket.Blip b : pic.blips) {
            int x = sx(b.x), y = sy(b.z);
            if (!inside(x, y)) {
                continue;
            }
            int col = b.friendly ? FRIEND : HOSTILE;
            dashed(ms, x, y, sx(b.x + b.vx * 200), sy(b.z + b.vz * 200), col);
            fill(ms, x - 2, y - 2, x + 3, y + 3, col);
            if (b.id == selected) {
                box(ms, x - 5, y - 5, x + 6, y + 6, SELECT);
            }
            font.draw(ms, I18n.get("radar.fpvdrones.short." + b.kind), x + 5, y - 9, col);
        }
        fill(ms, cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFFFFFF);

        // the list
        int x = listX, y = 10;
        font.draw(ms, title, x, y, TEXT);
        y += 12;
        font.draw(ms, I18n.get("screen.fpvdrones.radar.status", pic.radars, readyCount(), pic.own.size(),
                RANGES[range]), x, y, pic.radars > 0 ? DIM : HOSTILE);
        y += 14;
        listY = y;
        List<RadarPicturePacket.Blip> l = sorted();
        if (pic.radars == 0) {
            font.draw(ms, I18n.get("screen.fpvdrones.radar.no_radar"), x, y, HOSTILE);
        } else if (l.isEmpty()) {
            font.draw(ms, I18n.get("screen.fpvdrones.radar.clear"), x, y, DIM);
        }
        for (int i = 0; i < Math.min(ROWS, l.size()); i++) {
            RadarPicturePacket.Blip b = l.get(i);
            int ry = y + i * 11;
            if (b.id == selected) {
                fill(ms, x - 2, ry - 1, width - 6, ry + 10, 0x60FFE040);
            }
            double speed = Math.sqrt(b.vx * b.vx + b.vz * b.vz) * 72.0;
            String row = I18n.get("screen.fpvdrones.radar.row", I18n.get("radar.fpvdrones.kind." + b.kind),
                    (int) dist(b.x, b.z), (int) (b.y - pic.cy), (int) speed);
            font.draw(ms, row, x, ry, b.friendly ? FRIEND : HOSTILE);
        }
        RadarPicturePacket.Blip s = blip(selected);
        if (s != null) {
            int sy = height - 54;
            font.draw(ms, I18n.get(s.friendly ? "screen.fpvdrones.radar.friendly" : "screen.fpvdrones.radar.hostile"),
                    x, sy, s.friendly ? FRIEND : HOSTILE);
        }
        super.render(ms, mouseX, mouseY, partial);
    }

    private void ring(MatrixStack ms, int rad, int col) {
        int n = Math.max(48, rad * 3);
        for (int i = 0; i < n; i++) {
            double a = i * 2 * Math.PI / n;
            int x = cx + (int) Math.round(Math.cos(a) * rad), y = cy + (int) Math.round(Math.sin(a) * rad);
            fill(ms, x, y, x + 1, y + 1, col);
        }
    }

    private void dashed(MatrixStack ms, int x0, int y0, int x1, int y1, int col) {
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        for (int i = 0; i <= steps; i += 3) {
            int x = x0 + (x1 - x0) * i / Math.max(1, steps), y = y0 + (y1 - y0) * i / Math.max(1, steps);
            if (inside(x, y)) {
                fill(ms, x, y, x + 1, y + 1, col);
            }
        }
    }

    private static void box(MatrixStack ms, int x0, int y0, int x1, int y1, int col) {
        AbstractGui.fill(ms, x0, y0, x1, y0 + 1, col);
        AbstractGui.fill(ms, x0, y1 - 1, x1, y1, col);
        AbstractGui.fill(ms, x0, y0, x0 + 1, y1, col);
        AbstractGui.fill(ms, x1 - 1, y0, x1, y1, col);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        if (inside((int) mx, (int) my)) {
            int best = -1;
            double bestD = 10 * 10;
            for (RadarPicturePacket.Blip b : pic.blips) {
                double dx = sx(b.x) - mx, dy = sy(b.z) - my;
                if (dx * dx + dy * dy < bestD) {
                    bestD = dx * dx + dy * dy;
                    best = b.id;
                }
            }
            selected = best;
            refreshButtons();
            return true;
        }
        if (mx >= listX && my >= listY) {
            int row = (int) ((my - listY) / 11);
            List<RadarPicturePacket.Blip> l = sorted();
            if (row >= 0 && row < Math.min(ROWS, l.size())) {
                selected = l.get(row).id;
                refreshButtons();
                return true;
            }
        }
        return false;
    }
}
