package com.qwarwin.fpvdrones.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;

@OnlyIn(Dist.CLIENT)
public final class KeyBinds {
    private static final String CAT = "key.categories.fpvdrones";

    public static final KeyBinding TOGGLE_VIEW = new KeyBinding("key.fpvdrones.toggle_view", GLFW.GLFW_KEY_V, CAT);
    public static final KeyBinding ACTION = new KeyBinding("key.fpvdrones.action", GLFW.GLFW_KEY_G, CAT);
    public static final KeyBinding RETURN_HOME = new KeyBinding("key.fpvdrones.return_home", GLFW.GLFW_KEY_H, CAT);
    public static final KeyBinding ZOOM_IN = new KeyBinding("key.fpvdrones.zoom_in", GLFW.GLFW_KEY_EQUAL, CAT);
    public static final KeyBinding ZOOM_OUT = new KeyBinding("key.fpvdrones.zoom_out", GLFW.GLFW_KEY_MINUS, CAT);
    private static final KeyBinding[] ALL = {TOGGLE_VIEW, ACTION, RETURN_HOME, ZOOM_IN, ZOOM_OUT};

    /*
     * When two mods bind the same key (Simple Voice Chat has V and G, for one), Forge hands the press to only
     * one of the bindings. So presses are also counted straight from the keyboard event.
     */
    private static final Map<KeyBinding, Integer> RAW = new HashMap<>();

    public static void register() {
        for (KeyBinding k : ALL) {
            ClientRegistry.registerKeyBinding(k);
        }
    }

    static void onRawKey(int key, int scanCode, int action) {
        if (action != GLFW.GLFW_PRESS || Minecraft.getInstance().screen != null) {
            return;
        }
        for (KeyBinding k : ALL) {
            if (k.matches(key, scanCode)) {
                RAW.merge(k, 1, Integer::sum);
            }
        }
    }

    /** Presses since the last call, from whichever source saw more of them. */
    public static int presses(KeyBinding k) {
        int clicks = 0;
        while (k.consumeClick()) {
            clicks++;
        }
        Integer raw = RAW.remove(k);
        return Math.max(clicks, raw == null ? 0 : raw);
    }

    /** Presses nobody asked for this tick don't carry over into the next mode. */
    public static void clearRaw() {
        RAW.clear();
    }

    public static boolean pressed(KeyBinding k) {
        return presses(k) > 0;
    }

    private KeyBinds() {
    }
}
