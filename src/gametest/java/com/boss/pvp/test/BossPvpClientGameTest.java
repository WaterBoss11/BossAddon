package com.boss.pvp.test;

import com.boss.pvp.BossPvpAddon;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * In-game (FabricClientGameTest) checks for Boss's PVP. Run with: {@code gradlew runClientGameTest}.
 *
 * STATUS — the harness is fully set up and DOES launch: the client boots with the AUTISM client + this addon
 * loaded, all 31 modules register, a singleplayer world is created and entered, and the assertions below run
 * and pass. HOWEVER, {@code runClientGameTest} cannot currently exit green, because the framework's mandatory
 * end-of-test state check requires the client to be on a vanilla {@code net.minecraft.client.gui.screens.
 * TitleScreen}, and AUTISM replaces it with its own {@code autismclient.gui.screen.AutismTitleScreen} (which
 * does NOT extend TitleScreen) and re-establishes it continuously — neither {@code context.setScreen(TitleScreen)}
 * nor a direct field write survives to the check. There is no framework property to disable that check. This is
 * a structural AUTISM-vs-FabricClientGameTest incompatibility (a custom client owning the main menu vs. a harness
 * owning the vanilla menu flow), not a fixable test bug. See TESTING.md. The per-module tests are therefore not
 * added: they would run their assertions but be masked by the same title-screen post-check.
 */
@SuppressWarnings("UnstableApiUsage")
public final class BossPvpClientGameTest implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        // testModulesLoad — the addon's onInitialize ran and populated the static module fields (verified
        // in-game: this passes; only the framework's title-screen post-check below fails).
        context.runOnClient(client -> {
            if (BossPvpAddon.killAura == null || BossPvpAddon.autoCrystal == null
                    || BossPvpAddon.autoArmor == null || BossPvpAddon.surround == null
                    || BossPvpAddon.reach == null || BossPvpAddon.autoTotem == null) {
                throw new AssertionError("Boss PVP modules did not register during onInitialize");
            }
        });

        // Verify the world flow: create + enter a singleplayer world, wait a few ticks, then close.
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksDownload();
            context.waitTicks(20);
        }
    }
}
