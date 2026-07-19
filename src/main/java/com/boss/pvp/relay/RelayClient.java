package com.boss.pvp.relay;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Thin transport wrapper over the JDK's built-in {@link java.net.http.WebSocket} (no third-party dependency,
 * per the design doc). It only moves text frames and reports lifecycle; all protocol logic lives in
 * {@link RelayManager}. Reconnection policy is the manager's job — this class just tells it when the socket
 * opened, delivered a full message, or closed.
 *
 * <p>Inbound text frames can arrive in fragments, so {@code onText} is accumulated until {@code last} before
 * a whole JSON message is handed up. Outbound sends are serialized through a future chain because
 * {@code WebSocket.sendText} must not be called again before the previous send completes.
 */
public final class RelayClient {

    /** Callbacks into the manager. All may be invoked on WebSocket executor threads, not the game thread. */
    public interface Handler {
        void onOpen();
        void onMessage(String json);
        void onClosed(String reason);
    }

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final URI uri;
    private final Handler handler;
    private final StringBuilder buf = new StringBuilder();

    private volatile WebSocket ws;
    private volatile boolean closing = false;

    private final Object sendLock = new Object();
    private CompletableFuture<WebSocket> sendChain = CompletableFuture.completedFuture(null);

    public RelayClient(URI uri, Handler handler) {
        this.uri = uri;
        this.handler = handler;
    }

    /** Open the connection. On failure the handler's {@code onClosed} fires so the manager can back off/retry. */
    public void connect() {
        try {
            HTTP.newWebSocketBuilder()
                .buildAsync(uri, new Listener())
                .whenComplete((socket, err) -> {
                    if (err != null) {
                        handler.onClosed("connect failed: " + err.getMessage());
                    } else {
                        ws = socket;
                    }
                });
        } catch (Throwable t) {
            handler.onClosed("connect error: " + t);
        }
    }

    /** Serialized text send; silently no-ops if not connected. */
    public void send(String text) {
        WebSocket w = ws;
        if (w == null || text == null) return;
        synchronized (sendLock) {
            sendChain = sendChain
                .handle((r, e) -> null)                    // ignore the previous result/error, keep the chain alive
                .thenCompose(ignored -> w.sendText(text, true));
        }
    }

    /** Close intentionally — suppresses the manager's auto-reconnect (set your own flag before calling). */
    public void close() {
        closing = true;
        WebSocket w = ws;
        if (w != null) {
            try {
                w.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (Throwable ignored) {
                // best effort
            }
        }
    }

    private final class Listener implements WebSocket.Listener {
        @Override public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            handler.onOpen();
        }

        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String msg = buf.toString();
                buf.setLength(0);
                try {
                    handler.onMessage(msg);
                } catch (Throwable ignored) {
                    // a bad frame must never kill the receive loop
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            handler.onClosed("closed " + statusCode + (reason == null || reason.isBlank() ? "" : " " + reason));
            return null;
        }

        @Override public void onError(WebSocket webSocket, Throwable error) {
            handler.onClosed("error: " + error.getMessage());
        }
    }
}
