package com.boss.pvp.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/** Test-mod-only, fail-closed guard. This class must not reference Minecraft classes at prelaunch. */
public final class BossQaIsolation implements PreLaunchEntrypoint {
    static final String DISABLED_ENDPOINT = "REPLACE_ME_BOSS_QA_DISABLED";
    private static Path profile;

    @Override
    public void onPreLaunch() {
        try {
            verifyProfile();
            System.out.println("[BOSS-QA] isolated prelaunch guard accepted; relay/report endpoints disabled");
        } catch (Exception exception) {
            throw new IllegalStateException("Boss QA refused an unsafe or incomplete isolated launch", exception);
        }
    }

    private static void verifyProfile() throws IOException {
        require(Boolean.getBoolean("boss.qa.isolated"), "boss.qa.isolated=true is required");
        require(System.getProperty("fabric.client.gametest") != null, "Fabric client gametest must be active");
        require("boss-pvp-test".equals(System.getProperty("fabric.client.gametest.modid")), "Scope the native runner to boss-pvp-test");
        require(Runtime.version().feature() == 25, "This QA contract requires JDK 25");

        Path root = Path.of(requiredProperty("boss.qa.root")).toRealPath();
        require(root.getFileName().toString().equals("boss-qa")
                && root.getParent().getFileName().toString().equals("run")
                && root.getParent().getParent().getFileName().toString().equals("BossAddon"),
                "QA root must be BossAddon/run/boss-qa");
        Path actual = FabricLoader.getInstance().getGameDir().toRealPath();
        Path expected = Path.of(requiredProperty("boss.qa.profile")).toRealPath();
        require(actual.equals(expected) && actual.getParent().equals(root.resolve("profiles")), "Game directory is not an isolated QA profile");
        require(!Files.isSymbolicLink(actual) && !Files.isSymbolicLink(actual.resolve("config")), "QA profile/config must not be symbolic links");
        require(FabricLoader.getInstance().getConfigDir().toRealPath().equals(actual.resolve("config")), "Config directory escaped the QA profile");
        require(Files.readString(actual.resolve(".boss-qa-profile")).trim().equals("boss-qa-v1"), "QA profile sentinel missing");
        profile = actual;

        for (String key : List.of("BOSS_PVP_FLAGS_WEBHOOK", "BOSS_PVP_FLAGS_DUAL_WEBHOOK", "BOSS_UTILITY_FLAGS_WEBHOOK")) {
            require(DISABLED_ENDPOINT.equals(System.getenv(key)), "A reporting environment override was not neutralized: " + key);
        }
        require("false".equals(System.getenv("BOSS_PVP_RELAY_ENABLED")), "BossChat must be disabled before addon initialization");
        require(DISABLED_ENDPOINT.equals(System.getenv("BOSS_PVP_RELAY_URL")), "BossChat endpoint must be neutralized");
        for (String key : List.of("BOSS_PVP_RELAY_INVITE", "BOSS_PVP_RELAY_PASSWORD")) {
            String value = System.getenv(key);
            require(value == null || value.isBlank(), "A relay credential was inherited: " + key);
        }
        for (String key : List.of("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")) {
            require(System.getenv(key) == null, "Uncontrolled Java options were inherited: " + key);
        }
        require(Path.of(System.getProperty("user.home")).toRealPath().startsWith(actual), "Java home-directory writes must stay in the profile");
        require(Path.of(System.getProperty("java.io.tmpdir")).toRealPath().startsWith(actual), "Java temporary files must stay in the profile");

        JsonObject hostConfig = JsonParser.parseString(Files.readString(actual.resolve("config/autism/config.json"))).getAsJsonObject();
        // A real native host preference, verified in BOTH supplied jars. No framework check is patched out.
        require(hostConfig.has("customMainMenu") && !hostConfig.get("customMainMenu").getAsBoolean(), "Use the host's native vanilla-menu preference for this test profile");
        JsonObject modules = hostConfig.getAsJsonObject("modules");
        require(modules != null, "QA host module configuration missing");
        for (String id : List.of("bossaddon:pvp:flagreport", "bossaddon:utility:flagreport")) {
            JsonObject state = modules.getAsJsonObject(id);
            require(state != null && !state.get("enabled").getAsBoolean()
                    && "false".equals(state.getAsJsonObject("settings").get("report").getAsString()), "QA reporting toggle is not off: " + id);
        }
        for (var entry : modules.entrySet()) {
            require(!entry.getValue().getAsJsonObject().get("enabled").getAsBoolean(), "QA config must not pre-enable modules: " + entry.getKey());
        }
        Properties addonConfig = new Properties();
        try (var reader = Files.newBufferedReader(actual.resolve("config/boss-pvp.properties"))) {
            addonConfig.load(reader);
        }
        require("false".equals(addonConfig.getProperty("relay.enabled")), "BossChat profile opt-out missing");
        int expectedApi = Integer.parseInt(requiredProperty("boss.qa.expectedApi"));
        String expectedVersion = switch (expectedApi) {
            case 3 -> "5.0-26.2-dev";
            case 4 -> "5.1-26.2";
            default -> throw new IllegalStateException("Unsupported QA host API");
        };
        String actualVersion = FabricLoader.getInstance().getModContainer("autism").orElseThrow()
                .getMetadata().getVersion().getFriendlyString();
        require(expectedVersion.equals(actualVersion), "Wrong AUTISM jar selected for the requested API");
    }

    static Path profile() {
        if (profile == null) throw new IllegalStateException("QA isolation guard did not run");
        return profile;
    }

    static String requiredProperty(String key) {
        String value = System.getProperty(key);
        require(value != null && !value.isBlank(), "Required launch property missing: " + key);
        return value;
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
