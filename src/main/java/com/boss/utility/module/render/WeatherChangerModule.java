package com.boss.utility.module.render;

import com.boss.utility.BossUtilityAddon;

import autismclient.modules.Module;
import autismclient.api.module.ChoiceSetting;

import net.minecraft.client.Minecraft;

/**
 * WeatherChanger — override the client-side weather. Studied and rewritten from Meteor Client's
 * WeatherChanger (GPL-3.0). Pins the local rain/thunder gradient to the chosen weather each tick,
 * overriding whatever the server sends; restores the real values on disable.
 */
public final class WeatherChangerModule extends Module {

    private float prevRain = 0, prevThunder = 0;

    public WeatherChangerModule() {
        super(BossUtilityAddon.ID + ":weather-changer", "WeatherChanger", "Sets a custom client-side weather.");
        add(new ChoiceSetting("weather", "Weather", "Clear", "Clear", "Rain", "Thunder"));
    }

    @Override
    public void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) { prevRain = mc.level.getRainLevel(1f); prevThunder = mc.level.getThunderLevel(1f); }
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) { mc.level.setRainLevel(prevRain); mc.level.setThunderLevel(prevThunder); }
    }

    public void tick(Minecraft mc) {
        if (mc.level == null) return;
        switch (choice("weather")) {
            case "Rain" -> { mc.level.setRainLevel(1f); mc.level.setThunderLevel(0f); }
            case "Thunder" -> { mc.level.setRainLevel(1f); mc.level.setThunderLevel(1f); }
            default -> { mc.level.setRainLevel(0f); mc.level.setThunderLevel(0f); }
        }
    }
}
