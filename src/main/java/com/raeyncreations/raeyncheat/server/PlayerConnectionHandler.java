package com.raeyncreations.raeyncheat.server;

import com.raeyncreations.raeyncheat.RaeYNCheat;
import com.raeyncreations.raeyncheat.config.RaeYNCheatConfig;
import com.raeyncreations.raeyncheat.util.PasskeyLogger;
import com.raeyncreations.raeyncheat.auth.AuthManager;
import com.raeyncreations.raeyncheat.auth.AuthMovementListener;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.UserBanListEntry;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.PlayerNegotiationEvent;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side event handler for player connections.
 *
 * On every connection attempt (negotiation or login), this class runs the full
 * defensive stack in order:
 *
 *   1. Reconnect cooldown check        — blocks UUID-level reconnect spam after kicks
 *   2. ConnectionRateLimiter           — token bucket + sliding window + /24 subnet + circuit breaker
 *   3. BotDetector                     — behavioral fingerprint scoring
 *      a. ALLOW      → proceed normally
 *      b. SHADOW_BAN → admit silently; SyncPacket will be dropped
 *      c. HARD_BLOCK → hand back to rate limiter for Tier 2 escalation
 *   4. GeoIpLogger.logConnectionAsync  — async IP geolocation → cheat.log
 *   5. ValidationHandler.onPlayerLoggedIn — start sync timeout clock
 */
public class PlayerConnectionHandler {

    // ─── Reconnect cooldown ───────────────────────────────────────────────────

    private static final ConcurrentHashMap<String, Long> kickCooldowns = new ConcurrentHashMap<>();
    private static final int RECONNECT_COOLDOWN_SECONDS = 30;

    public static void registerKickCooldown(String uuidStr) {
        kickCooldowns.put(uuidStr, System.currentTimeMillis());
    }

    private static boolean isOnCooldown(String uuidStr, String playerName) {
        Long kicked = kickCooldowns.get(uuidStr);
        if (kicked == null) return false;
        long elapsed = (System.currentTimeMillis() - kicked) / 1000L;
        if (elapsed < RECONNECT_COOLDOWN_SECONDS) {
            RaeYNCheat.LOGGER.warn("[PCH] Reconnect-spam blocked: {} ({}) — {}s remaining.",
                    playerName, uuidStr, RECONNECT_COOLDOWN_SECONDS - elapsed);
            return true;
        }
        kickCooldowns.remove(uuidStr);
        return false;
    }

    // ─── Negotiation failure (mod list mismatch — fires before login) ─────────

    public static void onPlayerNegotiation(PlayerNegotiationEvent event) {
        if (!event.isFailed()) return;

        var profile = event.getProfile();
        if (profile == null) {
            RaeYNCheat.LOGGER.warn("[PCH] Negotiation failed for unknown profile.");
            return;
        }

        UUID   playerUUID     = profile.getId();
        String playerUsername = profile.getName() != null ? profile.getName() : "Unknown";
        String uuidStr        = playerUUID != null ? playerUUID.toString() : "Unknown-UUID";
        SocketAddress remoteAddr = event.getConnection().getRemoteAddress();

        // ── 1. Reconnect cooldown ─────────────────────────────────────────────
        if (playerUUID != null && isOnCooldown(uuidStr, playerUsername)) {
            event.setFailureReason(Component.literal(
                    "Please wait " + RECONNECT_COOLDOWN_SECONDS + "s before reconnecting."));
            return;
        }

        // ── 2. Rate limiter ───────────────────────────────────────────────────
        if (!ConnectionRateLimiter.allowConnection(remoteAddr, playerUsername)) {
            event.setFailureReason(Component.literal("Connection rejected — too many attempts."));
            return;
        }

        // ── 3. Bot detection ──────────────────────────────────────────────────
        BotDetector.BotVerdict verdict = BotDetector.evaluate(remoteAddr, playerUsername, uuidStr);
        if (verdict == BotDetector.BotVerdict.HARD_BLOCK) {
            // Hand back to rate limiter so escalation tier logic fires
            ConnectionRateLimiter.allowConnection(remoteAddr, playerUsername);
            event.setFailureReason(Component.literal("Connection rejected."));
            return;
        }
        // SHADOW_BAN: allow through — SyncPacket will be silently dropped later

        RaeYNCheat.LOGGER.warn("[PCH] Negotiation FAILED: {} ({}) — mod list mismatch.", playerUsername, uuidStr);
        PasskeyLogger.logSessionSeparator("Negotiation Failed: " + playerUsername + " (" + uuidStr + ")");

        if (playerUUID == null) {
            event.setFailureReason(Component.literal(
                    "Mod list mismatch. Get the modpack from the Discord server."));
            return;
        }

        RaeYNCheat.recordNegotiationViolation(playerUUID);
        int violations = RaeYNCheat.getNegotiationViolationCount(playerUUID);
        RaeYNCheatConfig config = RaeYNCheat.getConfig();

        if (config == null) {
            event.setFailureReason(Component.literal(buildKickMessage(1)));
            registerKickCooldown(uuidStr);
            return;
        }

        int duration = config.getNegotiationPunishmentDuration(violations);
        PasskeyLogger.logValidationFailure(playerUsername, uuidStr,
                "Negotiation failure #" + violations + " — punishment: " + fmtDuration(duration));

        if (duration == -1) {
            var server = RaeYNCheat.getCurrentServer();
            if (server != null) {
                server.getPlayerList().getBans().add(new UserBanListEntry(
                        profile, null, "RaeYNCheat", null,
                        "Permanently banned: repeated mod list violations"));
            }
            event.setFailureReason(Component.literal(
                    "You have been permanently banned for repeated mod list violations."));

        } else if (duration > 0) {
            var server = RaeYNCheat.getCurrentServer();
            if (server != null) {
                server.getPlayerList().getBans().add(new UserBanListEntry(
                        profile, new Date(System.currentTimeMillis() + duration * 1000L),
                        "RaeYNCheat", null,
                        "Mod list mismatch — banned " + duration + "s (violation #" + violations + ")"));
            }
            event.setFailureReason(Component.literal(
                    "Banned for " + duration + "s: mod list mismatch (violation #" + violations + "). "
                    + "Update your modpack from the Discord server."));

        } else {
            event.setFailureReason(Component.literal(buildKickMessage(violations)));
            registerKickCooldown(uuidStr);
        }
    }

    // ─── Player logged in ─────────────────────────────────────────────────────

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        String uuid     = player.getUUID() != null ? player.getUUID().toString() : "Unknown-UUID";
        String username = player.getName() != null ? player.getName().getString() : "Unknown-Player";
        SocketAddress remoteAddr = player.connection.getRemoteAddress();
        String ip = ConnectionRateLimiter.extractIpPublic(remoteAddr);

        // ── 1. Start sync timeout clock ───────────────────────────────────────
        ValidationHandler.onPlayerLoggedIn(uuid);

        // ── 2. Clear stale kick cooldown (they made it through legitimately) ──
        kickCooldowns.remove(uuid);

        // ── 3. Session separator in cheat.log ─────────────────────────────────
        PasskeyLogger.logSessionSeparator(
                "Player Connected: " + username + " (" + uuid + ")" +
                (ip != null ? " from " + ip : ""));

        RaeYNCheat.LOGGER.info("[PCH] {} ({}) connected from {} — awaiting SyncPacket ({}s timeout).",
                username, uuid, ip != null ? ip : "unknown", ValidationHandler.SYNC_TIMEOUT_SECONDS);

        // ── 4. Async geo-IP log ────────────────────────────────────────────────
        // Runs on a background thread — never blocks the login path.
        // Writes full location detail to cheat.log when lookup completes (~1-2s).
        RaeYNCheatConfig config = RaeYNCheat.getConfig();
        if (ip != null && config != null && config.enableGeoIpLogging) {
            // Build violation history summary for the log entry
            UUID uuidObj = player.getUUID();
            String violationSummary = uuidObj != null
                    ? buildViolationSummary(uuidObj)
                    : "violation history unavailable";
            GeoIpLogger.logConnectionAsync(ip, username, uuid, violationSummary);
        }

        // ── 5. Bot detection on confirmed login ───────────────────────────────
        // Re-evaluate now that we have a full ServerPlayer (UUID is confirmed at this point).
        // Negotiation already ran evaluate(), so this is the second signal — catches cases
        // where negotiation was bypassed or bot scored below block threshold there.
        BotDetector.BotVerdict verdict = BotDetector.evaluate(remoteAddr, username, uuid);
        if (verdict == BotDetector.BotVerdict.HARD_BLOCK) {
            RaeYNCheat.LOGGER.warn("[PCH] BotDetector HARD BLOCK on login for {} ({}) — disconnecting.", username, uuid);
            player.connection.disconnect(Component.literal("Connection rejected."));
            return;
        }
        // SHADOW_BAN: don't disconnect — SyncPacket.handle() will check isShadowBanned() and drop silently.

        // ── 6. Auth lockdown — called last so the player is fully in the world ─
        AuthManager.onPlayerJoin(player);
    }

    // ─── Player logged out ────────────────────────────────────────────────────

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        String uuid     = player.getUUID() != null ? player.getUUID().toString() : "Unknown-UUID";
        String username = player.getName() != null ? player.getName().getString() : "Unknown-Player";
        SocketAddress remoteAddr = player.connection.getRemoteAddress();

        ValidationHandler.clearPending(uuid);
        ValidationHandler.clearNonce(uuid);
        ConnectionRateLimiter.onDisconnect(remoteAddr);
        AuthManager.onPlayerLeave(uuid);  // clears pending auth state and position freeze
        // Don't clear BotDetector state on logout — timing history is valuable across sessions.

        PasskeyLogger.logSessionSeparator("Player Disconnected: " + username + " (" + uuid + ")");
        RaeYNCheat.LOGGER.info("[PCH] {} ({}) disconnected.", username, uuid);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static String buildViolationSummary(UUID uuid) {
        int cs = RaeYNCheat.getChecksumViolationCount(uuid);
        int pk = RaeYNCheat.getPasskeyViolationCount(uuid);
        int ev = RaeYNCheat.getEnvViolationCount(uuid);
        int ng = RaeYNCheat.getNegotiationViolationCount(uuid);
        if (cs + pk + ev + ng == 0) return "no prior violations";
        return "prior violations — checksum:" + cs + " passkey:" + pk + " env:" + ev + " negotiation:" + ng;
    }

    private static String buildKickMessage(int violations) {
        return violations <= 1
                ? "Mod list mismatch — your modpack is out of date or modified.\n"
                  + "Get the latest modpack from the Discord server."
                : "Mod list mismatch again (violation #" + violations + ").\n"
                  + "Update your modpack from Discord. Further violations will result in a ban.";
    }

    private static String fmtDuration(int d) {
        if (d == -1) return "PERMANENT BAN";
        if (d ==  0) return "KICK";
        return d + "s temp ban";
    }
}
