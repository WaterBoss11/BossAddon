package com.boss.utility.module.render;

import com.boss.utility.BossUtilityAddon;

import autismclient.modules.Module;
import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;

import net.minecraft.client.Minecraft;

/**
 * Ambience — recolours parts of the environment. Studied and rewritten from Meteor Client's
 * Ambience (GPL-3.0). Meteor's module is purely a colour store read by render mixins; this port keeps
 * that shape — the effects live in {@code com.boss.utility.mixin.*} (BiomeColors, FogRenderer,
 * SkyRenderer).
 *
 * <p>Ported subset (all verified against 26.2's render pipeline): custom grass / foliage / water
 * colour, custom fog colour, and an End-sky-everywhere toggle. Meteor's custom sky colour, cloud
 * colour, lightning colour and lava colour are intentionally NOT exposed here — those hook render
 * targets that were removed/renamed in 26.2 (e.g. no {@code SkyRenderState.skyColor}, no
 * {@code LevelRenderer.extractLevel}) and would be dead toggles, so they are held back.
 */
public final class AmbienceModule extends Module {

    public AmbienceModule() {
        super(BossUtilityAddon.ID + ":ambience", "Ambience",
            "Recolours the environment (grass, foliage, water, fog) and can force an End sky.");

        add(new BoolSetting("endSky", "End sky everywhere", false).group("Sky"));

        add(new BoolSetting("customGrass", "Custom grass colour", false).group("World"));
        add(new ColorSetting("grassColor", "Grass colour", 0xFF5FA733).group("World"));
        add(new BoolSetting("customFoliage", "Custom foliage colour", false).group("World"));
        add(new ColorSetting("foliageColor", "Foliage colour", 0xFF3E8E29).group("World"));
        add(new BoolSetting("customWater", "Custom water colour", false).group("World"));
        add(new ColorSetting("waterColor", "Water colour", 0xFF3F76E4).group("World"));
        add(new BoolSetting("customFog", "Custom fog colour", false).group("World"));
        add(new ColorSetting("fogColor", "Fog colour", 0xFFB0C4DE).group("World"));
    }

    @Override public void onEnable() { reload(); }
    @Override public void onDisable() { reload(); }

    /** Force a chunk re-render so the biome-colour mixins re-apply immediately. */
    private void reload() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.levelRenderer != null && mc.level != null && mc.gameRenderer != null) {
            mc.levelRenderer.invalidateCompiledGeometry(mc.level, mc.options, mc.gameRenderer.mainCamera(), mc.getBlockColors());
        }
    }

    // Read by the render mixins (the raw accessors are protected).
    public boolean cfgBool(String key) { return bool(key); }
    public int cfgColor(String key) { return integer(key); }

    /** Test-only: enable the module with a vivid grass colour for the gametest (ColorSetting takes an ARGB hex string). */
    public void debugSeed() {
        setEnabled(true);
        setValue("customGrass", "true");
        setValue("grassColor", "FFFF0000");
        reload();
    }
}

