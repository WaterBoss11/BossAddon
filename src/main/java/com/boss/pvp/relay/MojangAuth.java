package com.boss.pvp.relay;

import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The client half of Mojang session authentication — the same handshake a vanilla server uses to prove a
 * connecting player really owns their Minecraft account, done here so the relay can trust our identity without
 * us ever sending a password or a spoofable "I am X" claim.
 *
 * <p><b>How it proves identity (and why it can't be faked):</b>
 * <ol>
 *   <li>The relay sends us a random one-time challenge string ({@code serverId}).</li>
 *   <li>We call Mojang's session server {@code /join} with our real, launcher-issued access token, our UUID,
 *       and that challenge. Mojang only accepts this if the access token is genuinely ours — an attacker
 *       cannot mint one for someone else's account.</li>
 *   <li>The relay then asks Mojang {@code /hasJoined?username=&serverId=}. Mojang answers with the verified
 *       profile ONLY if a real account just joined with that exact challenge. The relay trusts Mojang's
 *       returned UUID/username, not ours — so a client cannot claim to be a different player.</li>
 * </ol>
 * The challenge is single-use and short-lived, so a captured hasJoined lookup can't be replayed.
 *
 * <p>This class only performs step 2 (the {@code /join} POST). Returns a boolean; never throws into the caller.
 */
public final class MojangAuth {

    private MojangAuth() {}

    private static final String JOIN_URL = "https://sessionserver.mojang.com/session/minecraft/join";

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    /**
     * Perform the Mojang {@code /join} call for the relay's challenge. {@code uuid} may be dashed or dashless
     * (Mojang wants dashless — we strip). Returns true on HTTP 204 (Mojang accepted), false otherwise.
     */
    public static boolean joinServer(String accessToken, String uuid, String serverId) {
        if (accessToken == null || uuid == null || serverId == null) return false;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("accessToken", accessToken);
            body.addProperty("selectedProfile", uuid.replace("-", ""));
            body.addProperty("serverId", serverId);

            HttpRequest req = HttpRequest.newBuilder(URI.create(JOIN_URL))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

            HttpResponse<Void> res = HTTP.send(req, HttpResponse.BodyHandlers.discarding());
            return res.statusCode() == 204;   // Mojang returns 204 No Content on success
        } catch (Throwable t) {
            return false;
        }
    }
}
