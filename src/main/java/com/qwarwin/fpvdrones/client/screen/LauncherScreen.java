package com.qwarwin.fpvdrones.client.screen;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.qwarwin.fpvdrones.client.ClientSession;
import com.qwarwin.fpvdrones.entity.StrikeType;
import com.qwarwin.fpvdrones.launch.LaunchConfig;
import com.qwarwin.fpvdrones.network.LauncherUpdatePacket;
import com.qwarwin.fpvdrones.network.Network;
import com.qwarwin.fpvdrones.network.OpenLauncherPacket;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class LauncherScreen extends Screen {
    private static final int W = 236;
    private static final int H = 206;

    private final OpenLauncherPacket data;
    private TextFieldWidget fx, fy, fz, ffreq;
    private int left, top;

    public LauncherScreen(OpenLauncherPacket data) {
        super(new TranslationTextComponent(data.site.entity ? "screen.fpvdrones.truck" : "screen.fpvdrones.launcher"));
        this.data = data;
    }

    private boolean isTruck() {
        return data.slots.length > 1;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        LaunchConfig c = data.config;

        fx = coord(left + 14, top + 46, c.hasTarget() ? String.valueOf(c.getX()) : "");
        fy = coord(left + 84, top + 46, c.hasTarget() && !c.isAutoY() ? String.valueOf(c.getY()) : "");
        fz = coord(left + 154, top + 46, c.hasTarget() ? String.valueOf(c.getZ()) : "");

        ffreq = new TextFieldWidget(font, left + 14, top + 96, 90, 18, new StringTextComponent("freq"));
        ffreq.setMaxLength(7);
        ffreq.setFilter(s -> s.matches("\\d{0,5}(\\.\\d?)?"));
        ffreq.setValue(LaunchConfig.formatFreq(c.getFrequency()));
        addButton(ffreq);

        addButton(new Button(left + 114, top + 95, 108, 20, new TranslationTextComponent("screen.fpvdrones.mavic_point"), b -> {
            BlockPos aim = ClientSession.lastAim;
            if (aim != null) {
                fx.setValue(String.valueOf(aim.getX()));
                fy.setValue(String.valueOf(aim.getY()));
                fz.setValue(String.valueOf(aim.getZ()));
            }
        }));

        int y = top + 152;
        addButton(new Button(left + 14, y, 100, 20, new TranslationTextComponent("screen.fpvdrones.save"),
                b -> send(LauncherUpdatePacket.SAVE)));
        addButton(new Button(left + 122, y, 100, 20, new TranslationTextComponent("screen.fpvdrones.unload"),
                b -> send(LauncherUpdatePacket.UNLOAD)));
        if (isTruck()) {
            addButton(new Button(left + 14, y + 24, 100, 20,
                    new TranslationTextComponent("screen.fpvdrones.launch").withStyle(TextFormatting.RED),
                    b -> send(LauncherUpdatePacket.LAUNCH)));
            addButton(new Button(left + 122, y + 24, 100, 20,
                    new TranslationTextComponent("screen.fpvdrones.salvo").withStyle(TextFormatting.RED, TextFormatting.BOLD),
                    b -> send(LauncherUpdatePacket.SALVO)));
        } else {
            addButton(new Button(left + 14, y + 24, 208, 20,
                    new TranslationTextComponent("screen.fpvdrones.launch").withStyle(TextFormatting.RED, TextFormatting.BOLD),
                    b -> send(LauncherUpdatePacket.LAUNCH)));
        }
        setInitialFocus(fx);
    }

    private TextFieldWidget coord(int x, int y, String value) {
        TextFieldWidget f = new TextFieldWidget(font, x, y, 64, 18, new StringTextComponent("coord"));
        f.setMaxLength(9);
        f.setFilter(s -> s.matches("-?\\d{0,8}"));
        f.setValue(value);
        addButton(f);
        return f;
    }

    private static Integer parse(String s) {
        try {
            return s == null || s.isEmpty() || s.equals("-") ? null : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int parseFreq() {
        try {
            return (int) Math.round(Double.parseDouble(ffreq.getValue()) * 10.0);
        } catch (NumberFormatException e) {
            return data.config.getFrequency();
        }
    }

    private void send(int action) {
        Integer x = parse(fx.getValue()), y = parse(fy.getValue()), z = parse(fz.getValue());
        LaunchConfig c = new LaunchConfig();
        c.set(x != null && z != null, x == null ? 0 : x, y == null ? 0 : y, z == null ? 0 : z, y == null, parseFreq());
        Network.CHANNEL.sendToServer(new LauncherUpdatePacket(data.site, c, action));
        if (action != LauncherUpdatePacket.SAVE) {
            onClose();
        }
    }

    @Override
    public void tick() {
        fx.tick();
        fy.tick();
        fz.tick();
        ffreq.tick();
    }

    @Override
    public void render(MatrixStack ms, int mouseX, int mouseY, float partial) {
        renderBackground(ms);
        AbstractGui.fill(ms, left - 1, top - 1, left + W + 1, top + H + 1, 0xFF5A6340);
        AbstractGui.fill(ms, left, top, left + W, top + H, 0xF0161A12);
        AbstractGui.fill(ms, left, top, left + W, top + 18, 0xFF2B3220);
        for (int i = 0; i < W; i += 8) {
            AbstractGui.fill(ms, left + i, top + H - 4, left + i + 4, top + H, 0xFFE3B40F);
        }
        font.drawShadow(ms, title, left + 8, top + 5, 0xFFE8E8D0);
        BlockPos o = data.origin;
        String coords = o.getX() + " " + o.getY() + " " + o.getZ();
        font.draw(ms, coords, left + W - 8 - font.width(coords), top + 5, 0xFF8FA088);

        font.draw(ms, new TranslationTextComponent("screen.fpvdrones.target"), left + 14, top + 24, 0xFFCFD8B8);
        font.draw(ms, "X", left + 14, top + 36, 0xFF8FA088);
        font.draw(ms, "Y", left + 84, top + 36, 0xFF8FA088);
        font.draw(ms, "Z", left + 154, top + 36, 0xFF8FA088);
        font.draw(ms, new TranslationTextComponent("screen.fpvdrones.y_hint"), left + 14, top + 68, 0xFF6F7A60);
        font.draw(ms, new TranslationTextComponent("screen.fpvdrones.freq"), left + 14, top + 84, 0xFFCFD8B8);

        for (int i = 0; i < data.slots.length; i++) {
            int sx = left + 14 + i * 22, sy = top + 124;
            AbstractGui.fill(ms, sx - 1, sy - 1, sx + 19, sy + 19, 0xFF5A6340);
            AbstractGui.fill(ms, sx, sy, sx + 18, sy + 18, 0xFF0E110B);
            StrikeType t = data.slots[i] < 0 ? null : StrikeType.byIdOrNull(data.slots[i]);
            if (t != null) {
                itemRenderer.renderAndDecorateItem(new ItemStack(t.item()), sx + 1, sy + 1);
            }
        }
        Integer x = parse(fx.getValue()), z = parse(fz.getValue());
        if (x != null && z != null) {
            double dx = x + 0.5 - (o.getX() + 0.5), dz = z + 0.5 - (o.getZ() + 0.5);
            String dist = new TranslationTextComponent("screen.fpvdrones.distance", Math.round(Math.sqrt(dx * dx + dz * dz))).getString();
            font.draw(ms, dist, left + W - 14 - font.width(dist), top + 129, 0xFFCFD8B8);
        }
        super.render(ms, mouseX, mouseY, partial);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
