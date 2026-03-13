package com.raeyncreations.raeyncheat.auth;

import net.minecraft.world.level.GameType;

/**
 * Tracks the authentication state of a player who has logged in but not yet
 * completed password/2FA verification.
 *
 * Lifecycle:
 *   1. Created in AuthManager.onPlayerJoin() when a player with auth is detected.
 *   2. Player is immediately placed in SPECTATOR mode and all interaction blocked.
 *   3. Auth prompt is sent to chat.
 *   4. Player submits code via /login <code>.
 *   5. On success: GameType restored, state removed, player notified.
 *   6. On AUTH_TIMEOUT_SECONDS expiry without success: player kicked.
 *
 * Thread safety: Only accessed from the server thread (Minecraft's main thread),
 * so no synchronization needed on individual instances.
 */
public class PendingAuth {

    public enum Stage {
        AWAIT_PASSWORD,    // Player needs to enter /login <password>
        AWAIT_TOTP,        // Player needs to enter /login <6-digit code>
        AWAIT_TOTP_SETUP,  // Player is confirming a newly-generated TOTP secret
        COMPLETE           // Authentication passed — remove from pending map
    }

    // ---------------------------------------------------------------------------
    // Fields
    // ---------------------------------------------------------------------------

    public final String    uuid;
    public final String    username;
    public Stage           stage;

    /** The game type the player was in before we switched them to SPECTATOR. */
    public GameType        originalGameType;

    /** Server-side epoch ms when this pending entry was created — used for timeout. */
    public final long      createdAtMs;

    /**
     * Number of failed attempts in this auth session.
     * Tracked separately from DB failed_attempts (which is persistent across sessions).
     */
    public int             sessionFailures;

    /**
     * Set during TOTP setup: the plain Base32 secret the player should enter in their app.
     * Cleared once setup is confirmed or cancelled.
     */
    public String          pendingTotpPlainSecret;

    // How long before an unauthenticated player is kicked
    public static final int AUTH_TIMEOUT_SECONDS = 90;

    // ---------------------------------------------------------------------------
    // Constructor
    // ---------------------------------------------------------------------------

    public PendingAuth(String uuid, String username, Stage stage, GameType originalGameType) {
        this.uuid             = uuid;
        this.username         = username;
        this.stage            = stage;
        this.originalGameType = originalGameType;
        this.createdAtMs      = System.currentTimeMillis();
        this.sessionFailures  = 0;
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    public boolean isTimedOut() {
        return System.currentTimeMillis() - createdAtMs > AUTH_TIMEOUT_SECONDS * 1000L;
    }

    public long secondsRemaining() {
        long elapsed = (System.currentTimeMillis() - createdAtMs) / 1000L;
        return Math.max(0, AUTH_TIMEOUT_SECONDS - elapsed);
    }

    public boolean requiresPassword() {
        return stage == Stage.AWAIT_PASSWORD;
    }

    public boolean requiresTotp() {
        return stage == Stage.AWAIT_TOTP || stage == Stage.AWAIT_TOTP_SETUP;
    }

    @Override
    public String toString() {
        return "PendingAuth{uuid=" + uuid + ", user=" + username + ", stage=" + stage + "}";
    }
}
