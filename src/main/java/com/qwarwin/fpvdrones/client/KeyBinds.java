package com.qwarwin.fpvdrones.client;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.glfw.GLFW;

@OnlyIn(Dist.CLIENT)
public final class KeyBinds {
    private static final String CAT = "key.categories.fpvdrones";

    public static final KeyBinding TOGGLE_VIEW = new KeyBinding("key.fpvdrones.toggle_view", GLFW.GLFW_KEY_V, CAT);
    public static final KeyBinding ACTION = new KeyBinding("key.fpvdrones.action", GLFW.GLFW_KEY_G, CAT);
    public static final KeyBinding ZOOM_IN = new KeyBinding("key.fpvdrones.zoom_in", GLFW.GLFW_KEY_EQUAL, CAT);
    public static final KeyBinding ZOOM_OUT = new KeyBinding("key.fpvdrones.zoom_out", GLFW.GLFW_KEY_MINUS, CAT);

    public static void register() {
        ClientRegistry.registerKeyBinding(TOGGLE_VIEW);
        ClientRegistry.registerKeyBinding(ACTION);
        ClientRegistry.registerKeyBinding(ZOOM_IN);
        ClientRegistry.registerKeyBinding(ZOOM_OUT);
    }

    private KeyBinds() {
    }
}
