package com.raeyncreations.raeyncheat.auth;

import com.raeyncreations.raeyncheat.RaeYNCheat;
import com.raeyncreations.raeyncheat.util.PasskeyLogger;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central manager for RaeYNCheat player authentication.
 *
 * FLOW ON JOIN (player HAS password or 2FA):
 *   1. onPlayerJoin() called from PlayerConnectionHandler after login.
 *   2. Player's GameType saved, set to SPECTATOR. Abilities zeroed.
 *   3. AuthMovementListener.lockPosition() called — position freeze begins.
 *   4. PendingAuth entry created with the appropriate stage.
 *   5. Auth prompt sent to chat.
 *   6. Server tick checks for AUTH_TIMEOUT_SECONDS expiry each second.
 *
 * FLOW ON SUCCESS:
 *   1. submitAuth() validates the input.
 *   2. completeAuth() restores GameType and abilities.
 *   3. AuthMovementListener.unlockPosition() releases the freeze.
 *   4. PendingAuth removed from map.
 *   5. Setup prompt shown if player is missing password or 2FA.
 *
 * FLOW ON FAILURE:
 *   1. Failed attempt logged to DB (persistent across sessions).
 *   2. After MAX_FAILED_ATTEMPTS, account locks for LOCKOUT_SECONDS.
 *   3. Player kicked; told how long the lockout lasts.
 *
 * SETUP PROMPT LOGIC:
 *   - hasPassword=false, hasTotp=false → show BOTH prompts
 *   - hasPassword=true,  hasTotp=false → show only 2FA prompt
 *   - hasPassword=true,  hasTotp=true  → show nothing (fully protected)
 */
public class AuthManager {

    // ─── State ────────────────────────────────────────────────────────────────

    private static AuthDatabase db;

    /** UUID string → pending auth state for players not yet authenticated this session. */
    private static final ConcurrentHashMap<String, PendingAuth> pending = new ConcurrentHashMap<>();

    /** Server tick counter — only ever read/written from the server main thread. Not volatile by design. */
    private static int tickCounter    = 0;
    private static final int TICK_INTERVAL = 20; // check every 20 ticks = 1 second

    /** The name shown in TOTP provisioning URIs and setup instructions. */
    public static String serverLabel = "RaeYNServer"; // updated from config at init

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    public static void initialize(AuthDatabase database, String serverName) {
        db          = database;
        serverLabel = (serverName != null && !serverName.isEmpty()) ? serverName : "RaeYNServer";
        // Give TotpUtil access to the DB for persistent TOTP replay protection.
        TotpUtil.setDatabase(database);
        // Purge any expired TOTP replay entries from previous sessions.
        if (database != null) database.purgeExpiredTotpCodes();
        RaeYNCheat.LOGGER.info("[Auth] AuthManager initialized (server label: {}).", serverLabel);
    }

    public static void shutdown() {
        if (db != null) { db.close(); db = null; }
        pending.clear();
    }

    public static boolean isInitialized() { return db != null; }

    // ─── Player join ──────────────────────────────────────────────────────────

    /**
     * Called from PlayerConnectionHandler.onPlayerLoggedIn() after all other
     * connection checks pass. Determines required auth and enters lockdown if needed.
     */
    public static void onPlayerJoin(ServerPlayer player) {
        if (db == null) return;

        String uuid     = player.getUUID().toString();
        String username = player.getName().getString();

        try {
            db.upsertUsername(uuid, username);
            AuthDatabase.AuthRecord rec = db.getRecord(uuid);

            if (rec == null) { sendSetupPrompt(player, false, false); return; }

            // Lockout check — kick before creating pending state
            if (rec.isLocked()) {
                player.connection.disconnect(Component.literal(
                        "§cYour account is locked for " + rec.lockSecondsRemaining()
                        + "s due to too many failed login attempts.\nContact an admin to unlock."));
                return;
            }

            if (!rec.hasPassword && !rec.hasTotp) {
                // No auth configured — player joins freely but gets setup prompt
                sendSetupPrompt(player, false, false);
                return;
            }

            // ── Auth required — freeze the player ────────────────────────────
            GameType original = player.gameMode.getGameModeForPlayer();
            enterLockdown(player, original);

            PendingAuth.Stage stage = rec.hasTotp
                    ? PendingAuth.Stage.AWAIT_TOTP
                    : PendingAuth.Stage.AWAIT_PASSWORD;

            PendingAuth pa = new PendingAuth(uuid, username, stage, original);
            pending.put(uuid, pa);

            sendAuthPrompt(player, stage);

            PasskeyLogger.logWarning(username, uuid, "AUTH_REQUIRED",
                    "Player joined — awaiting " + stage);

        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("[Auth] onPlayerJoin error for {}", username, e);
        }
    }

    /** Called from PlayerConnectionHandler.onPlayerLoggedOut(). Cleans up all state. */
    public static void onPlayerLeave(String uuid) {
        pending.remove(uuid);
        AuthMovementListener.unlockPosition(uuid);
    }

    // ─── Chat interception ────────────────────────────────────────────────────

    /**
     * Called from AuthChatListener (ServerChatEvent). Returns true if the event
     * was consumed — caller should cancel the event.
     */
    public static boolean interceptChat(ServerPlayer player, String message) {
        String uuid = player.getUUID().toString();
        if (!isLocked(uuid)) return false;
        player.sendSystemMessage(msg(
                "§c[Auth] You must authenticate first. Use §e/login <code>§c."));
        return true;
    }

    /** True if this player is in auth lockdown and must not be allowed to act. */
    public static boolean isLocked(String uuid) {
        PendingAuth pa = pending.get(uuid);
        return pa != null && pa.stage != PendingAuth.Stage.COMPLETE;
    }

    // ─── Authentication submission — /login <input> ─────────────────────────

    public static void submitAuth(ServerPlayer player, String input) {
        if (db == null) { player.sendSystemMessage(msg("§c[Auth] Auth system offline.")); return; }

        String uuid = player.getUUID().toString();
        PendingAuth pa = pending.get(uuid);

        if (pa == null) {
            player.sendSystemMessage(msg("§a[Auth] You are already authenticated."));
            return;
        }

        try {
            AuthDatabase.AuthRecord rec = db.getRecord(uuid);
            if (rec == null) { player.sendSystemMessage(msg("§c[Auth] Record not found. Contact an admin.")); return; }

            if (rec.isLocked()) {
                player.sendSystemMessage(msg("§c[Auth] Account locked for "
                        + rec.lockSecondsRemaining() + "s. Contact an admin."));
                return;
            }

            boolean success = false;

            switch (pa.stage) {
                case AWAIT_PASSWORD -> success = PasswordUtil.verifyPassword(input, rec.passwordHash);
                case AWAIT_TOTP -> {
                    String secret = db.getDecryptedTotpSecret(uuid);
                    success = secret != null && TotpUtil.verifyCode(secret, input, uuid);
                }
                case AWAIT_TOTP_SETUP -> {
                    String pending_secret = db.getDecryptedPendingTotpSecret(uuid);
                    if (pending_secret != null && TotpUtil.verifyCode(pending_secret, input, "SETUP:" + uuid)) {
                        db.confirmTotp(uuid);
                        db.recordSuccessfulLogin(uuid);
                        PasskeyLogger.logWarning(pa.username, uuid, "TOTP_CONFIRMED",
                                "Player confirmed TOTP setup with valid code");
                        completeAuth(player, pa, db.getRecord(uuid));
                        player.sendSystemMessage(totpSetupCompleteMsg());
                        return;
                    }
                    // Fall through to failure path
                }
                default -> {}
            }

            if (success) {
                db.recordSuccessfulLogin(uuid);
                PasskeyLogger.logWarning(pa.username, uuid, "AUTH_SUCCESS",
                        "Authenticated via " + pa.stage);
                // Re-fetch record for fresh has_password/has_totp flags for setup prompt
                AuthDatabase.AuthRecord freshRec = db.getRecord(uuid);
                completeAuth(player, pa, freshRec != null ? freshRec : rec);
            } else {
                int fails     = db.recordFailedAttempt(uuid);
                int remaining = Math.max(0, AuthDatabase.MAX_FAILED_ATTEMPTS - fails);
                pa.sessionFailures++;

                PasskeyLogger.logWarning(pa.username, uuid, "AUTH_FAILURE",
                        "Failed " + pa.stage + " attempt (DB total: " + fails + ")");

                if (remaining == 0) {
                    pending.remove(uuid);
                    AuthMovementListener.unlockPosition(uuid);
                    player.connection.disconnect(Component.literal(
                            "§cToo many failed login attempts.\n"
                            + "Account locked for " + AuthDatabase.LOCKOUT_SECONDS
                            + "s. Contact an admin."));
                } else {
                    player.sendSystemMessage(msg("§c[Auth] Incorrect. "
                            + remaining + " attempt(s) remaining before lockout."));
                }
            }

        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("[Auth] submitAuth error for {}", player.getName().getString(), e);
            player.sendSystemMessage(msg("§c[Auth] Internal error. Contact an admin."));
        }
    }

    // ─── Password management ──────────────────────────────────────────────────

    /** /password set <password> */
    public static void setPassword(ServerPlayer player, String password) {
        if (db == null) return;
        String uuid = player.getUUID().toString();
        try {
            AuthDatabase.AuthRecord rec = db.getRecord(uuid);
            if (rec != null && rec.hasPassword) {
                player.sendSystemMessage(msg(
                        "§c[Auth] You already have a password. Use §e/password change§c."));
                return;
            }
            PasswordUtil.validatePolicy(password);
            db.upsertUsername(uuid, player.getName().getString());
            db.setPassword(uuid, PasswordUtil.hashPassword(password));
            player.sendSystemMessage(msg(
                    "§a[Auth] Password set. Your account is now password protected.\n"
                    + "§7Enable 2FA with §e/2fa setup§7 for stronger security."));
            PasskeyLogger.logWarning(player.getName().getString(), uuid, "PASSWORD_SET",
                    "Player set account password");
        } catch (IllegalArgumentException e) {
            player.sendSystemMessage(msg("§c[Auth] " + e.getMessage()));
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("[Auth] setPassword error for {}", player.getName().getString(), e);
            player.sendSystemMessage(msg("§c[Auth] Internal error."));
        }
    }

    /** /password change <old> <new> <confirm> */
    public static void changePassword(ServerPlayer player, String oldPass, String newPass, String confirm) {
        if (db == null) return;
        String uuid = player.getUUID().toString();
        try {
            if (isLocked(uuid)) {
                player.sendSystemMessage(msg(
                        "§c[Auth] You must complete login before changing your password."));
                return;
            }
            AuthDatabase.AuthRecord rec = db.getRecord(uuid);
            if (rec == null || !rec.hasPassword) {
                player.sendSystemMessage(msg("§c[Auth] No password set. Use §e/password set§c first."));
                return;
            }
            if (!PasswordUtil.verifyPassword(oldPass, rec.passwordHash)) {
                db.recordFailedAttempt(uuid);
                player.sendSystemMessage(msg("§c[Auth] Old password is incorrect."));
                PasskeyLogger.logWarning(player.getName().getString(), uuid,
                        "PASSWORD_CHANGE_FAIL", "Old password mismatch");
                return;
            }
            if (!newPass.equals(confirm)) {
                player.sendSystemMessage(msg("§c[Auth] New passwords do not match."));
                return;
            }
            PasswordUtil.validatePolicy(newPass);
            db.setPassword(uuid, PasswordUtil.hashPassword(newPass));
            player.sendSystemMessage(msg("§a[Auth] Password changed successfully."));
            PasskeyLogger.logWarning(player.getName().getString(), uuid,
                    "PASSWORD_CHANGED", "Player changed account password");
        } catch (IllegalArgumentException e) {
            player.sendSystemMessage(msg("§c[Auth] " + e.getMessage()));
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("[Auth] changePassword error for {}", player.getName().getString(), e);
            player.sendSystemMessage(msg("§c[Auth] Internal error."));
        }
    }

    // ─── TOTP setup ───────────────────────────────────────────────────────────

    /** /2fa setup — begin TOTP enrollment flow */
    public static void beginTotpSetup(ServerPlayer player) {
        if (db == null) return;
        String uuid = player.getUUID().toString();
        try {
            // Security: reject if the player has not yet authenticated this session.
            // Without this check, a player in AWAIT_PASSWORD lockdown could call /2fa setup
            // to overwrite their pending stage to AWAIT_TOTP_SETUP and bypass password auth.
            if (isLocked(uuid)) {
                player.sendSystemMessage(msg(
                        "§c[Auth] You must complete login before managing 2FA settings."));
                return;
            }

            AuthDatabase.AuthRecord rec = db.getRecord(uuid);
            if (rec == null || !rec.hasPassword) {
                player.sendSystemMessage(msg(
                        "§c[Auth] Set a password first before enabling 2FA.\n"
                        + "§eUse: /password set <password>"));
                return;
            }
            if (rec.hasTotp) {
                player.sendSystemMessage(msg(
                        "§c[Auth] 2FA is already enabled. Disable with §e/2fa disable <password>§c first."));
                return;
            }

            String secret = TotpUtil.generateSecret();
            db.setPendingTotp(uuid, secret);

            // Enter setup-confirmation lockdown (player already authed but needs to confirm)
            String    username = player.getName().getString();
            PendingAuth existing = pending.get(uuid);
            if (existing != null) {
                existing.stage                = PendingAuth.Stage.AWAIT_TOTP_SETUP;
                existing.pendingTotpPlainSecret = secret;
            } else {
                GameType gt = player.gameMode.getGameModeForPlayer();
                PendingAuth pa = new PendingAuth(uuid, username, PendingAuth.Stage.AWAIT_TOTP_SETUP, gt);
                pa.pendingTotpPlainSecret = secret;
                pending.put(uuid, pa);
                enterLockdown(player, gt);
            }

            sendTotpSetupInstructions(player, username, secret);
            PasskeyLogger.logWarning(username, uuid, "TOTP_SETUP_STARTED",
                    "Player initiated TOTP setup — awaiting confirmation code");

        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("[Auth] beginTotpSetup error for {}", player.getName().getString(), e);
            player.sendSystemMessage(msg("§c[Auth] Internal error during 2FA setup."));
        }
    }

    /** /2fa disable <password> */
    public static void disableTotp(ServerPlayer player, String passwordConfirm) {
        if (db == null) return;
        String uuid = player.getUUID().toString();
        try {
            if (isLocked(uuid)) {
                player.sendSystemMessage(msg(
                        "§c[Auth] You must complete login before managing 2FA settings."));
                return;
            }
            AuthDatabase.AuthRecord rec = db.getRecord(uuid);
            if (rec == null || !rec.hasTotp) {
                player.sendSystemMessage(msg("§c[Auth] 2FA is not currently enabled."));
                return;
            }
            if (!PasswordUtil.verifyPassword(passwordConfirm, rec.passwordHash)) {
                player.sendSystemMessage(msg("§c[Auth] Incorrect password. 2FA not disabled."));
                PasskeyLogger.logWarning(player.getName().getString(), uuid,
                        "TOTP_DISABLE_FAIL", "Wrong password during 2FA disable");
                return;
            }
            db.clearTotp(uuid);
            player.sendSystemMessage(msg("§a[Auth] 2FA disabled. Account is now password-only."));
            PasskeyLogger.logWarning(player.getName().getString(), uuid, "TOTP_DISABLED", "Player disabled 2FA");
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("[Auth] disableTotp error for {}", player.getName().getString(), e);
            player.sendSystemMessage(msg("§c[Auth] Internal error."));
        }
    }

    // ─── Admin actions — OP level 2 required (enforced in command registration) ──

    /** /raeyn auth reset <player> — wipes all auth, forces re-setup on next login */
    public static void adminReset(String targetUuid, String targetName, String adminName) {
        if (db == null) throw new RuntimeException("Auth offline");
        try {
            pending.remove(targetUuid);
            AuthMovementListener.unlockPosition(targetUuid);
            db.adminResetPassword(targetUuid);
            PasskeyLogger.logWarning(targetName, targetUuid, "ADMIN_AUTH_RESET",
                    "Admin " + adminName + " wiped all auth for player");
            RaeYNCheat.LOGGER.warn("[Auth] Admin {} reset all auth for {} ({})",
                    adminName, targetName, targetUuid);
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("[Auth] adminReset error for {}", targetName, e);
            throw new RuntimeException(e);
        }
    }

    /** /raeyn auth unlock <player> — clears lockout without wiping password/2FA */
    public static void adminUnlock(String targetUuid, String targetName, String adminName) {
        if (db == null) throw new RuntimeException("Auth offline");
        try {
            db.unlockAccount(targetUuid);
            PasskeyLogger.logWarning(targetName, targetUuid, "ADMIN_UNLOCK",
                    "Admin " + adminName + " unlocked account");
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("[Auth] adminUnlock error for {}", targetName, e);
            throw new RuntimeException(e);
        }
    }

    /** /raeyn auth status <player> — show auth record summary */
    public static String getAuthStatus(String uuid) {
        if (db == null) return "Auth system offline";
        try {
            AuthDatabase.AuthRecord rec = db.getRecord(uuid);
            if (rec == null) return "No auth record for this player";
            PendingAuth pa = pending.get(uuid);
            return String.format(
                    "Password: %s | 2FA: %s | Locked: %s (fails: %d) | LastLogin: %s | PendingStage: %s",
                    rec.hasPassword ? "§aYES§r" : "§cNO§r",
                    rec.hasTotp    ? "§aYES§r" : "§cNO§r",
                    rec.isLocked() ? "§c" + rec.lockSecondsRemaining() + "s§r" : "§ano§r",
                    rec.failedAttempts,
                    rec.lastLogin != null ? "<" + ((System.currentTimeMillis()/1000 - rec.lastLogin)/60) + "min ago>" : "never",
                    pa != null ? pa.stage : "authenticated");
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ─── Server tick — timeout enforcement ───────────────────────────────────

    public static void onServerTick() {
        if (++tickCounter < TICK_INTERVAL) return;
        tickCounter = 0;
        if (pending.isEmpty()) return;

        var server = RaeYNCheat.getCurrentServer();
        if (server == null) return;

        for (var it = pending.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            PendingAuth pa = entry.getValue();
            if (pa.stage == PendingAuth.Stage.COMPLETE) { it.remove(); continue; }

            if (pa.isTimedOut()) {
                it.remove();
                AuthMovementListener.unlockPosition(pa.uuid);
                try {
                    ServerPlayer p = server.getPlayerList().getPlayer(UUID.fromString(pa.uuid));
                    if (p != null) {
                        p.connection.disconnect(Component.literal(
                                "§cLogin timed out. You did not authenticate within "
                                + PendingAuth.AUTH_TIMEOUT_SECONDS + "s."));
                    }
                    PasskeyLogger.logWarning(pa.username, pa.uuid, "AUTH_TIMEOUT",
                            "Kicked — no auth within " + PendingAuth.AUTH_TIMEOUT_SECONDS + "s");
                } catch (Exception e) {
                    RaeYNCheat.LOGGER.debug("[Auth] Timeout error for {}", pa.uuid, e);
                }
            }
        }
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private static void enterLockdown(ServerPlayer player, GameType original) {
        player.setGameMode(GameType.SPECTATOR);
        player.getAbilities().mayfly       = false;
        player.getAbilities().flying       = false;
        player.getAbilities().invulnerable = true;
        player.onUpdateAbilities();
        AuthMovementListener.lockPosition(player);
    }

    private static void completeAuth(ServerPlayer player, PendingAuth pa,
                                      AuthDatabase.AuthRecord rec) {
        pa.stage = PendingAuth.Stage.COMPLETE;
        pending.remove(pa.uuid);
        AuthMovementListener.unlockPosition(pa.uuid);

        // Restore original game mode and abilities
        player.setGameMode(pa.originalGameType);
        boolean creative  = pa.originalGameType == GameType.CREATIVE;
        boolean spectator = pa.originalGameType == GameType.SPECTATOR;
        player.getAbilities().mayfly       = creative || spectator;
        player.getAbilities().invulnerable = creative || spectator;
        player.getAbilities().flying       = false; // don't resume mid-air flight
        player.onUpdateAbilities();

        player.sendSystemMessage(msg("§a§l✔ Authentication successful! Welcome, "
                + player.getName().getString() + "."));

        // Selective setup prompts — only show what's missing
        if (rec != null) {
            if (!rec.hasPassword) sendSetupPrompt(player, false, false);
            else if (!rec.hasTotp) sendSetupPrompt(player, true, false);
            // both set → no prompt needed
        }
    }

    // ─── Messaging ────────────────────────────────────────────────────────────

    private static void sendAuthPrompt(ServerPlayer player, PendingAuth.Stage stage) {
        player.sendSystemMessage(msg(""));
        player.sendSystemMessage(msg("§6§l=== RaeYNCheat Account Security ==="));
        player.sendSystemMessage(msg("§cYou are frozen in spectator mode until you authenticate."));
        player.sendSystemMessage(msg("§cMovement, chat, and interaction are disabled."));
        player.sendSystemMessage(msg(""));
        if (stage == PendingAuth.Stage.AWAIT_TOTP) {
            player.sendSystemMessage(msg("§e2FA required — open your authenticator app and enter:"));
            player.sendSystemMessage(msg("§a  /login <6-digit code>"));
        } else {
            player.sendSystemMessage(msg("§ePassword required:"));
            player.sendSystemMessage(msg("§a  /login <your password>"));
        }
        player.sendSystemMessage(msg(""));
        PendingAuth pa = pending.get(player.getUUID().toString());
        long secs = pa != null ? pa.secondsRemaining() : PendingAuth.AUTH_TIMEOUT_SECONDS;
        player.sendSystemMessage(msg("§7You have §e" + secs + "s§7 to authenticate or you will be kicked."));
        player.sendSystemMessage(msg(""));
    }

    private static void sendSetupPrompt(ServerPlayer player, boolean hasPassword, boolean hasTotp) {
        player.sendSystemMessage(msg(""));
        player.sendSystemMessage(msg("§6§l=== RaeYNCheat Account Security ==="));
        player.sendSystemMessage(msg("§7This server supports optional account protection to prevent"));
        player.sendSystemMessage(msg("§7account theft and unauthorized access."));
        player.sendSystemMessage(msg(""));

        if (!hasPassword) {
            player.sendSystemMessage(msg("§e● Password Protection §8(not set)"));
            player.sendSystemMessage(msg("§7  Locks your account on every login. Set one with:"));
            player.sendSystemMessage(msg("§a    /password set <password>"));
            player.sendSystemMessage(msg("§7  To change it later: §a/password change <old> <new> <new>"));
            player.sendSystemMessage(msg("§8  (Tip: quote passwords with spaces, e.g. \"my pass\")"));
            player.sendSystemMessage(msg("§7  Policy: §f" + PasswordUtil.policyDescription()));
            player.sendSystemMessage(msg(""));
        }

        if (hasPassword && !hasTotp) {
            player.sendSystemMessage(msg("§e● Two-Factor Authentication (2FA) §8(not set)"));
            player.sendSystemMessage(msg("§7  Adds a rotating code from your authenticator app"));
            player.sendSystemMessage(msg("§7  (Microsoft Authenticator, Google Authenticator, Authy…)"));
            player.sendSystemMessage(msg("§7  Requires a password first. Enable with:"));
            player.sendSystemMessage(msg("§a    /2fa setup"));
            player.sendSystemMessage(msg(""));
        }

        player.sendSystemMessage(msg("§8Both are optional. Protect your account to prevent theft."));
        player.sendSystemMessage(msg(""));
    }

    private static void sendTotpSetupInstructions(ServerPlayer player, String username, String secret) {
        String uri       = TotpUtil.buildProvisioningUri(username, secret, serverLabel);
        String formatted = TotpUtil.formatForDisplay(secret);

        player.sendSystemMessage(msg(""));
        player.sendSystemMessage(msg("§6§l=== Two-Factor Authentication Setup ==="));
        player.sendSystemMessage(msg("§7Open your authenticator app and add a new account manually:"));
        player.sendSystemMessage(msg(""));
        player.sendSystemMessage(msg("§7  Account name: §e" + serverLabel + " / " + username));
        player.sendSystemMessage(msg("§7  Secret key:   §a§l" + formatted));
        player.sendSystemMessage(msg("§7  Type: §fTime-based (TOTP)"));
        player.sendSystemMessage(msg(""));
        player.sendSystemMessage(msg("§7Then confirm setup by entering the 6-digit code:"));
        player.sendSystemMessage(msg("§a  /login <6-digit code from your app>"));
        player.sendSystemMessage(msg(""));
        player.sendSystemMessage(msg("§8For QR-code apps, use this URI:"));
        player.sendSystemMessage(msg("§8" + uri));
        player.sendSystemMessage(msg(""));
        player.sendSystemMessage(msg("§c⚠ Save your secret key somewhere safe — you need it"));
        player.sendSystemMessage(msg("§c  to recover access if you lose your authenticator app."));
        player.sendSystemMessage(msg(""));
    }

    private static MutableComponent totpSetupCompleteMsg() {
        return msg("§a§l✔ 2FA enabled!\n"
                + "§7Your account now requires your authenticator code on every login.\n"
                + "§7To disable: §e/2fa disable <your password>");
    }

    private static MutableComponent msg(String text) {
        return Component.literal(text).withStyle(Style.EMPTY);
    }
}
