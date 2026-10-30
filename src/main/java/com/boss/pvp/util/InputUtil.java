package com.boss.pvp.util;

import com.boss.pvp.util.pvp.PvpUtil;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public final class InputUtil {
    private InputUtil() {}

    public static int mouseButton(int bind) {
        if (bind > -1000) return -1;
        int button = -1000 - bind;
        return button > GLFW.GLFW_MOUSE_BUTTON_LEFT && button <= GLFW.GLFW_MOUSE_BUTTON_LAST ? button : -1;
    }

    public static boolean canRunKeybinds(Minecraft mc) {
        // ponytail: no GUI shortcuts; add widget-focus filtering before supporting menu binds.
        return mc != null && mc.player != null && mc.level != null && mc.gui.screen() == null
            && !PvpUtil.hostEnabled("hide");
    }

    public static boolean physicallyDown(Minecraft mc, net.minecraft.client.KeyMapping mapping) {
        if (mc == null || mc.getWindow() == null || mapping == null) return false;
        var key = com.mojang.blaze3d.platform.InputConstants.getKey(mapping.saveString());
        int value = key.getValue();
        long window = mc.getWindow().handle();
        if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.MOUSE) {
            return value >= 0 && value <= GLFW.GLFW_MOUSE_BUTTON_LAST
                && GLFW.glfwGetMouseButton(window, value) == GLFW.GLFW_PRESS;
        }
        if (key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.SCANCODE) {
            for (int symbol = GLFW.GLFW_KEY_SPACE; symbol <= GLFW.GLFW_KEY_LAST; symbol++) {
                if (GLFW.glfwGetKeyScancode(symbol) == value) return GLFW.glfwGetKey(window, symbol) == GLFW.GLFW_PRESS;
            }
            return false;
        }
        return value >= GLFW.GLFW_KEY_SPACE && value <= GLFW.GLFW_KEY_LAST
            && GLFW.glfwGetKey(window, value) == GLFW.GLFW_PRESS;
    }

    public static boolean isBindPressed(Minecraft mc, int bind) {
        if (!canRunKeybinds(mc) || mc.getWindow() == null) return false;
        int button = mouseButton(bind);
        if (button >= 0) return GLFW.glfwGetMouseButton(mc.getWindow().handle(), button) == GLFW.GLFW_PRESS;
        return bind >= GLFW.GLFW_KEY_SPACE && bind <= GLFW.GLFW_KEY_LAST
            && GLFW.glfwGetKey(mc.getWindow().handle(), bind) == GLFW.GLFW_PRESS;
    }
}
