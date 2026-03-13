package com.raeyncreations.raeyncheat.server;

import com.raeyncreations.raeyncheat.RaeYNCheat;
import com.raeyncreations.raeyncheat.config.RaeYNCheatConfig;
import com.raeyncreations.raeyncheat.util.CheckFileManager;
import com.raeyncreations.raeyncheat.util.EncryptionUtil;
import com.raeyncreations.raeyncheat.util.PasskeyLogger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.UserBanListEntry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side validation of all fields in SyncPacket:
 *   1. Passkey      — proves the client is running our mod and knows the derived key.
 *   2. Checksum     — proves the client's mods/ folder matches mods_client/ on the server.
 *   3. Environment  — proves the client's JVM environment is clean.
 *   4. Nonce        — proves this SyncPacket is a fresh response, not a replay.
 *
 * FIX #1 (login timeout): Tracks when each player logged in. If no valid SyncPacket
 * arrives within SYNC_TIMEOUT_SECONDS, the player is kicked on the next server tick.
 * This closes the silence bypass where a player could connect and never send a SyncPacket.
 *
 * FIX #2 (nonce): Pending nonces are stored here per UUID. When a RevalidatePacket is
 * sent, its nonce is registered. The next SyncPacket from that player MUST echo it.
 * Login syncs (empty nonce) are accepted only before any nonce has been registered.
 *
 * FIX #2 (rate limiting): Per-UUID SyncPacket cooldown enforced in SyncPacket.handle()
 * via ConnectionRateLimiter before this class is even reached.
 */
public class ValidationHandler {

    // ---------------------------------------------------------------------------
    // Login timeout tracking — FIX #1
    // ---------------------------------------------------------------------------

    /** UUID → epoch millis of login time. Entry removed after first successful validation. */
    private static final ConcurrentHashMap<String, Long> pendingValidation = new ConcurrentHashMap<>();

    /**
     * How long a player may stay connected without sending a valid SyncPacket.
     * After this many seconds, the player is kicked by the timeout checker in RaeYNCheat.
     */
    public static final int SYNC_TIMEOUT_SECONDS = 15;

    /**
     * Called by PlayerConnectionHandler.onPlayerLoggedIn to start the timeout clock.
     */
    public static void onPlayerLoggedIn(String uuidStr) {
        pendingValidation.put(uuidStr, System.currentTimeMillis());
    }

    /**
     * Called by RaeYNCheat's server tick to enforce the login timeout.
     * Returns a list of UUIDs whose timeout has expired and should be kicked.
     */
    public static List<String> getTimedOutPlayers() {
        long cutoff = System.currentTimeMillis() - SYNC_TIMEOUT_SECONDS * 1000L;
        List<String> timedOut = new ArrayList<>();
        for (Map.Entry<String, Long> entry : pendingValidation.entrySet()) {
            if (entry.getValue() < cutoff) timedOut.add(entry.getKey());
        }
        return timedOut;
    }

    /**
     * Remove a player from the pending-validation map (called after success or disconnect).
     */
    public static void clearPending(String uuidStr) {
        pendingValidation.remove(uuidStr);
    }

    // ---------------------------------------------------------------------------
    // Nonce management — FIX #2/#10
    // ---------------------------------------------------------------------------

    /** UUID → nonce that must be echoed in the next SyncPacket from that player. */
    private static final ConcurrentHashMap<String, String> pendingNonces = new ConcurrentHashMap<>();

    /** How long (seconds) a nonce remains valid. After this, the server re-issues one via revalidation. */
    private static final int NONCE_EXPIRY_SECONDS = 60;

    /** UUID → epoch millis when the nonce was issued (for expiry). */
    private static final ConcurrentHashMap<String, Long> nonceTimestamps = new ConcurrentHashMap<>();

    /**
     * Register a nonce issued to a player via RevalidatePacket.
     * Called from RaeYNCheat when a RevalidatePacket is sent.
     */
    public static void registerNonce(String uuidStr, String nonce) {
        pendingNonces.put(uuidStr, nonce);
        nonceTimestamps.put(uuidStr, System.currentTimeMillis());
    }

    /** Remove nonce state for a player (on disconnect or after consumption). */
    public static void clearNonce(String uuidStr) {
        pendingNonces.remove(uuidStr);
        nonceTimestamps.remove(uuidStr);
    }

    /**
     * Validate the nonce echoed in a SyncPacket.
     *
     * Rules:
     *   - If no nonce is registered for this player (first login), empty nonce is accepted.
     *   - If a nonce is registered, the packet's nonce must match exactly.
     *   - An expired nonce (> NONCE_EXPIRY_SECONDS old) is rejected — player must wait for
     *     the next periodic revalidation to get a fresh one.
     *
     * @return true if the nonce is valid and should be consumed, false if it is a replay/mismatch.
     */
    private static boolean validateAndConsumeNonce(String uuidStr, String clientNonce) {
        String expected = pendingNonces.get(uuidStr);

        // No nonce registered — only allow if the client also sent nothing (login sync).
        if (expected == null) {
            return clientNonce == null || clientNonce.isEmpty();
        }

        // Nonce registered but client sent nothing — reject.
        if (clientNonce == null || clientNonce.isEmpty()) return false;

        // Check expiry.
        Long issuedAt = nonceTimestamps.get(uuidStr);
        if (issuedAt != null &&
                System.currentTimeMillis() - issuedAt > NONCE_EXPIRY_SECONDS * 1000L) {
            clearNonce(uuidStr);
            return false; // Expired — need a fresh revalidation cycle.
        }

        // Constant-time comparison to prevent timing oracle.
        boolean match = java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                clientNonce.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        if (match) clearNonce(uuidStr); // Consume — nonces are single-use.
        return match;
    }

    // ---------------------------------------------------------------------------
    // Validation entry point
    // ---------------------------------------------------------------------------

    public static void validatePlayer(ServerPlayer player, String clientPasskey,
                                      String clientChecksum, String clientEnvReport,
                                      String clientNonce) {
        String playerUUID     = player.getUUID().toString();
        String playerUsername = player.getName().getString();

        RaeYNCheat.LOGGER.info("Validating player {} ({})", playerUsername, playerUUID);

        // ── Phase 0: Nonce check ──────────────────────────────────────────────
        if (!validateAndConsumeNonce(playerUUID, clientNonce)) {
            RaeYNCheat.LOGGER.warn("Nonce FAILED for {} ({}) — possible replay attack.", playerUsername, playerUUID);
            PasskeyLogger.logValidationFailure(playerUsername, playerUUID,
                    "Nonce mismatch or replay detected — SyncPacket rejected");
            player.connection.disconnect(Component.literal("Validation failed: invalid or replayed packet"));
            return;
        }

        CheckFileManager cfm = RaeYNCheat.getCheckFileManager();
        if (cfm == null) {
            RaeYNCheat.LOGGER.warn("CheckFileManager not initialized — skipping validation for {}", playerUsername);
            PasskeyLogger.logWarning(playerUsername, playerUUID,
                    "VALIDATION_SKIPPED", "CheckFileManager not initialized - mod verification disabled");
            // Still remove from pending so the timeout doesn't kick them.
            clearPending(playerUUID);
            return;
        }

        // ── Phase 1: Passkey ──────────────────────────────────────────────────
        try {
            if (!cfm.validatePasskey(clientPasskey, playerUUID, playerUsername)) {
                RaeYNCheat.LOGGER.warn("Passkey FAILED for {} ({})", playerUsername, playerUUID);
                handlePasskeyViolation(player);
                return;
            }
            RaeYNCheat.LOGGER.info("Passkey PASSED for {} ({})", playerUsername, playerUUID);
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Passkey check threw for {}", playerUsername, e);
            PasskeyLogger.logError(playerUsername, playerUUID, "PASSKEY_VALIDATION_ERROR", e.getMessage(), e);
            handlePasskeyViolation(player);
            return;
        }

        // ── Phase 2: Checksum ─────────────────────────────────────────────────
        try {
            if (clientChecksum.trim().isEmpty()) {
                PasskeyLogger.logError(playerUsername, playerUUID, "CLIENT_CHECKSUM_INVALID", "Empty checksum", null);
                handleChecksumViolation(player);
                return;
            }

            String serverChecksum = cfm.generateServerChecksumInMemory(playerUUID, playerUsername, clientPasskey);

            if (!cfm.compareCheckSums(clientChecksum, serverChecksum, clientPasskey, playerUUID, playerUsername)) {
                RaeYNCheat.LOGGER.warn("Checksum FAILED for {} ({})", playerUsername, playerUUID);
                PasskeyLogger.logValidationFailure(playerUsername, playerUUID,
                        "Checksum mismatch - client mods do not match server expectations");
                handleChecksumViolation(player);
                return;
            }
            RaeYNCheat.LOGGER.info("Checksum PASSED for {} ({})", playerUsername, playerUUID);
            PasskeyLogger.logValidationSuccess(playerUsername, playerUUID, "Checksum validation passed");
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Checksum check threw for {}", playerUsername, e);
            PasskeyLogger.logError(playerUsername, playerUUID, "CHECKSUM_VALIDATION_ERROR", e.getMessage(), e);
            handleChecksumViolation(player);
            return;
        }

        // ── Phase 3: Environment report ───────────────────────────────────────
        try {
            validateEnvironmentReport(player, clientPasskey, clientEnvReport, playerUUID, playerUsername);
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Environment check threw for {}", playerUsername, e);
            PasskeyLogger.logError(playerUsername, playerUUID, "ENV_VALIDATION_ERROR", e.getMessage(), e);
            handleEnvViolation(player, List.of("VALIDATION_EXCEPTION: " + e.getMessage()));
            return;
        }

        // All phases passed — clear the pending-validation timeout entry.
        clearPending(playerUUID);

        // Mark this player as confirmed-clean in the bot detector.
        // This resets their timing record so legitimate players don't accumulate false scores.
        if (player.connection != null) {
            com.raeyncreations.raeyncheat.server.BotDetector.markClean(
                    player.connection.getRemoteAddress(), playerUUID);
        }
    }

    // ---------------------------------------------------------------------------
    // Phase 3 implementation
    // ---------------------------------------------------------------------------

    private static void validateEnvironmentReport(ServerPlayer player, String clientPasskey,
                                                   String encryptedReport,
                                                   String playerUUID, String playerUsername) throws Exception {
        RaeYNCheatConfig config = RaeYNCheat.getConfig();
        if (config == null) return;

        String decryptedReport;
        try {
            decryptedReport = EncryptionUtil.decrypt(encryptedReport, clientPasskey);
        } catch (Exception e) {
            RaeYNCheat.LOGGER.warn("Could not decrypt env report from {} ({}): {}", playerUsername, playerUUID, e.getMessage());
            PasskeyLogger.logWarning(playerUsername, playerUUID,
                    "ENV_DECRYPT_FAILED", "Environment report could not be decrypted — treating as violation");
            handleEnvViolation(player, List.of("ENV_DECRYPT_FAILED"));
            return;
        }

        if (decryptedReport == null || decryptedReport.isBlank()) {
            handleEnvViolation(player, List.of("EMPTY_ENV_REPORT"));
            return;
        }

        String[] lines = decryptedReport.split("\n");
        if (lines.length == 1 && lines[0].trim().equals("CLEAN")) {
            RaeYNCheat.LOGGER.info("Environment CLEAN for {} ({})", playerUsername, playerUUID);
            PasskeyLogger.logValidationSuccess(playerUsername, playerUUID,
                    "Environment check passed - no anomalies detected");
            return;
        }

        List<String> violations = new ArrayList<>();
        int modCount  = -1;
        int diskCount = -1;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.equals("CLEAN")) continue;

            if (trimmed.startsWith("MOD_COUNT:")) {
                try { modCount = Integer.parseInt(trimmed.substring(10)); } catch (NumberFormatException ignored) {}
                continue;
            }
            if (trimmed.startsWith("DISK_JAR_COUNT:")) {
                try { diskCount = Integer.parseInt(trimmed.substring(15)); } catch (NumberFormatException ignored) {}
                continue;
            }

            if (trimmed.startsWith("JVM_FLAG:") && config.enforceJvmArgCheck) {
                // FIX #11: Check admin-configured extra whitelist for UNKNOWN flags.
                if (trimmed.startsWith("JVM_FLAG:UNKNOWN:") && !config.extraJvmArgWhitelist.isEmpty()) {
                    String flagValue = trimmed.substring("JVM_FLAG:UNKNOWN:".length());
                    boolean whitelisted = config.extraJvmArgWhitelist.stream()
                            .anyMatch(flagValue::startsWith);
                    if (whitelisted) {
                        RaeYNCheat.LOGGER.debug("JVM arg whitelisted by config for {}: {}", playerUsername, flagValue);
                        continue;
                    }
                }
                violations.add(trimmed);
            } else if (trimmed.startsWith("EXTRA_JAR:") && config.enforceExtraJarCheck) {
                violations.add(trimmed);
            } else if (trimmed.startsWith("MOD_GHOST:") && config.enforceGhostModCheck) {
                violations.add(trimmed);
            } else if (trimmed.startsWith("CL_ANOMALY:") && config.enforceClassLoaderCheck) {
                violations.add(trimmed);
            } else if (trimmed.startsWith("JVM_SCAN_ERROR:") || trimmed.startsWith("MODLIST_SCAN_ERROR:")) {
                RaeYNCheat.LOGGER.warn("Client-side scan error from {} ({}): {}", playerUsername, playerUUID, trimmed);
                PasskeyLogger.logWarning(playerUsername, playerUUID, "CLIENT_SCAN_ERROR", trimmed);
            } else {
                RaeYNCheat.LOGGER.debug("Unknown env report line from {}: {}", playerUsername, trimmed);
            }
        }

        if (modCount >= 0 && diskCount >= 0) {
            RaeYNCheat.LOGGER.info("Env report for {}: {} runtime mods, {} JARs on disk",
                    playerUsername, modCount, diskCount);
            if (Math.abs(modCount - diskCount) > 3) {
                PasskeyLogger.logWarning(playerUsername, playerUUID, "MOD_COUNT_MISMATCH",
                        "Runtime mod count (" + modCount + ") vs disk JAR count (" + diskCount + ") discrepancy > 3");
            }
        }

        if (violations.isEmpty()) {
            RaeYNCheat.LOGGER.info("Environment PASSED (no enforced violations) for {} ({})", playerUsername, playerUUID);
            PasskeyLogger.logValidationSuccess(playerUsername, playerUUID,
                    "Environment check passed - findings present but none enforced");
        } else {
            RaeYNCheat.LOGGER.warn("Environment FAILED for {} ({}) — {} violation(s): {}",
                    playerUsername, playerUUID, violations.size(), violations);
            PasskeyLogger.logValidationFailure(playerUsername, playerUUID,
                    "Environment violation(s): " + violations);
            handleEnvViolation(player, violations);
        }
    }

    // ---------------------------------------------------------------------------
    // Violation handlers
    // ---------------------------------------------------------------------------

    private static void handlePasskeyViolation(ServerPlayer player) {
        UUID uuid = player.getUUID();
        clearPending(uuid.toString());
        RaeYNCheat.recordPasskeyViolation(uuid);
        RaeYNCheatConfig config = RaeYNCheat.getConfig();
        if (config == null) { player.connection.disconnect(Component.literal("Passkey validation failed")); return; }
        applyPunishment(player,
                config.getPasskeyPunishmentDuration(RaeYNCheat.getPasskeyViolationCount(uuid)),
                RaeYNCheat.getPasskeyViolationCount(uuid),
                "passkey violation",
                "Permanently banned for passkey validation failure",
                "Banned for %d seconds for passkey validation failure (violation %d)",
                "Passkey validation failed");
    }

    private static void handleChecksumViolation(ServerPlayer player) {
        UUID uuid = player.getUUID();
        clearPending(uuid.toString());
        RaeYNCheat.recordChecksumViolation(uuid);
        RaeYNCheatConfig config = RaeYNCheat.getConfig();
        if (config == null) { player.connection.disconnect(Component.literal("Mod verification failed")); return; }
        applyPunishment(player,
                config.getPunishmentDuration(RaeYNCheat.getChecksumViolationCount(uuid)),
                RaeYNCheat.getChecksumViolationCount(uuid),
                "checksum violation",
                "Permanently banned for using unauthorized mods",
                "Banned for %d seconds for unauthorized mods (violation %d)",
                "Mod verification failed - unauthorized mods detected");
    }

    private static void handleEnvViolation(ServerPlayer player, List<String> findings) {
        UUID uuid = player.getUUID();
        clearPending(uuid.toString());
        RaeYNCheat.recordEnvViolation(uuid);
        RaeYNCheatConfig config = RaeYNCheat.getConfig();

        String findingsSummary = String.join(", ", findings.subList(0, Math.min(findings.size(), 3)));
        if (findings.size() > 3) findingsSummary += " (+" + (findings.size() - 3) + " more)";

        PasskeyLogger.logValidationFailure(player.getName().getString(), uuid.toString(),
                "Environment violation: " + findingsSummary);

        if (config == null) { player.connection.disconnect(Component.literal("Environment check failed")); return; }
        applyPunishment(player,
                config.getEnvPunishmentDuration(RaeYNCheat.getEnvViolationCount(uuid)),
                RaeYNCheat.getEnvViolationCount(uuid),
                "environment violation",
                "Permanently banned for environment violation (" + findingsSummary + ")",
                "Banned for %d seconds for environment violation (violation %d): " + findingsSummary,
                "Environment check failed: " + findingsSummary);
    }

    private static void applyPunishment(ServerPlayer player, int duration, int violationCount,
                                         String type, String permMsg, String tempFmt, String kickMsg) {
        String name = player.getName().getString();
        if (duration == -1) {
            player.getServer().getPlayerList().getBans().add(new UserBanListEntry(
                    player.getGameProfile(), null, "RaeYNCheat", null, permMsg));
            player.connection.disconnect(Component.literal(permMsg));
            RaeYNCheat.LOGGER.warn("Permanently banned {} for {} (count: {})", name, type, violationCount);
        } else if (duration > 0) {
            String msg = String.format(tempFmt, duration, violationCount);
            player.getServer().getPlayerList().getBans().add(new UserBanListEntry(
                    player.getGameProfile(),
                    new Date(System.currentTimeMillis() + duration * 1000L),
                    "RaeYNCheat", null, msg));
            player.connection.disconnect(Component.literal(msg));
            RaeYNCheat.LOGGER.warn("Banned {} for {}s ({}, count: {})", name, duration, type, violationCount);
        } else {
            player.connection.disconnect(Component.literal(kickMsg));
            RaeYNCheat.LOGGER.warn("Kicked {} for {} (count: {})", name, type, violationCount);
        }
    }
}

