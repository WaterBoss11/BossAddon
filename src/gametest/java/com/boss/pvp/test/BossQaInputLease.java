package com.boss.pvp.test;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

/** Scoped native input for subsequent movement scenarios; release only keys this fixture acquired. */
@SuppressWarnings("UnstableApiUsage")
final class BossQaInputLease implements AutoCloseable {
    private final ClientGameTestContext context;
    private final Set<KeyMapping> keys = new LinkedHashSet<>();
    private final Set<Integer> mouse = new LinkedHashSet<>();
    private boolean closed;

    BossQaInputLease(ClientGameTestContext context) {
        this.context = context;
    }

    void hold(Function<Options, KeyMapping> selector) {
        BossQaIsolation.require(!closed, "Input lease is closed");
        KeyMapping key = context.computeOnClient(client -> selector.apply(client.options));
        if (keys.add(key)) context.getInput().holdKey(key);
    }

    void holdMouse(int button) {
        BossQaIsolation.require(!closed && button >= 0 && button <= 2, "Invalid/closed fixture mouse lease");
        if (mouse.add(button)) context.getInput().holdMouse(button);
    }

    @Override
    public void close() {
        if (closed) return;
        Throwable first = null;
        try {
            for (KeyMapping key : keys) {
                try {
                    context.getInput().releaseKey(key);
                } catch (RuntimeException | Error failure) {
                    if (first == null) first = failure; else first.addSuppressed(failure);
                }
            }
            for (int button : mouse) {
                try {
                    context.getInput().releaseMouse(button);
                } catch (RuntimeException | Error failure) {
                    if (first == null) first = failure; else first.addSuppressed(failure);
                }
            }
        } finally {
            keys.clear();
            mouse.clear();
            closed = true;
        }
        if (first instanceof RuntimeException failure) throw failure;
        if (first instanceof Error failure) throw failure;
    }
}
