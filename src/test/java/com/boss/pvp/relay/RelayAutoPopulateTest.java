package com.boss.pvp.relay;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fresh-install behavior of the pilot auto-populate: on first launch (no relay keys yet) the baked defaults are
 * written; already-set keys are never clobbered; and the public build (empty baked defaults) writes nothing.
 * Uses an in-memory config store injected into {@link RelayConfig#autoPopulate} so the exact write logic runs
 * without a live client (the real path just swaps in {@code BossPvpAddon.getConfigString/setConfigString}).
 */
class RelayAutoPopulateTest {

    private final Map<String, String> store = new HashMap<>();
    private final Function<String, String> get = store::get;
    private final BiConsumer<String, String> set = store::put;

    @Test
    void freshInstallWritesBothRelayKeys() {
        // No config present (fresh install) → both keys seeded from the pilot defaults.
        RelayConfig.autoPopulate(get, set, "wss://bossrelay.onrender.com", "pilot-c662f9c905");
        assertEquals("wss://bossrelay.onrender.com", store.get("relay.url"));
        assertEquals("pilot-c662f9c905", store.get("relay.invite"));
    }

    @Test
    void doesNotClobberAKeyTheUserAlreadySet() {
        store.put("relay.url", "ws://127.0.0.1:8080");   // user already pointed it somewhere
        RelayConfig.autoPopulate(get, set, "wss://bossrelay.onrender.com", "pilot-c662f9c905");
        assertEquals("ws://127.0.0.1:8080", store.get("relay.url"), "existing value must be preserved");
        assertEquals("pilot-c662f9c905", store.get("relay.invite"), "the missing key is still seeded");
    }

    @Test
    void publicBuildEmptyDefaultsWritesNothing() {
        RelayConfig.autoPopulate(get, set, "", "");   // public build: nothing baked
        assertTrue(store.isEmpty(), "empty baked defaults must be a no-op");
        assertNull(store.get("relay.url"));
        assertNull(store.get("relay.invite"));
    }

    @Test
    void publicBuildDefaultConstantsAreEmpty() {
        // Guards the checked-in source: the PUBLIC build must ship with no baked pilot values.
        assertTrue(RelayConfig.DEFAULT_URL.isBlank(), "public DEFAULT_URL must be empty");
        assertTrue(RelayConfig.DEFAULT_INVITE.isBlank(), "public DEFAULT_INVITE must be empty");
    }
}
