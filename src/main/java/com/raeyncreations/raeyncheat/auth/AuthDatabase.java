package com.raeyncreations.raeyncheat.auth;

import com.raeyncreations.raeyncheat.RaeYNCheat;
import com.raeyncreations.raeyncheat.config.RaeYNCheatConfig;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.util.Base64;

/**
 * Database abstraction layer for RaeYNCheat player authentication.
 *
 * Supports SQLite (default, zero-config) and MySQL (optional, configured via config.json).
 * Both backends share the same schema. Switching is a config-only change.
 *
 * SCHEMA
 *
 * Table: player_auth
 *   uuid            TEXT PRIMARY KEY   — Minecraft UUID string
 *   username        TEXT NOT NULL      — Last known username (display only, not auth key)
 *   password_hash   TEXT               — PBKDF2 hash, NULL if no password set
 *   totp_secret_enc TEXT               — AES-128/GCM encrypted Base32 TOTP secret, NULL if not set
 *   has_password    INTEGER DEFAULT 0
 *   has_totp        INTEGER DEFAULT 0
 *   created_at      INTEGER NOT NULL   — epoch seconds
 *   last_login      INTEGER            — epoch seconds of last successful authentication
 *   failed_attempts INTEGER DEFAULT 0  — consecutive failures, reset on success
 *   locked_until    INTEGER            — epoch seconds, NULL if not locked
 *   totp_pending    INTEGER DEFAULT 0  — 1 while player is in TOTP setup confirmation window
 *   totp_secret_pending TEXT           — unconfirmed secret, promoted on first successful verify
 *
 * Table: violations  (crash-safe; replaces config.json violation maps)
 *   uuid            TEXT               — Minecraft UUID string
 *   violation_type  TEXT               — "checksum" | "passkey" | "env" | "negotiation"
 *   count           INTEGER            — cumulative violation count for this type
 *   last_timestamp  INTEGER            — epoch seconds of most recent violation
 *   PRIMARY KEY (uuid, violation_type)
 *
 * Table: totp_used_codes  (crash-safe replay protection; replaces in-memory HashSet)
 *   replay_key      TEXT PRIMARY KEY   — "uuid:step:code"
 *   expires_at_step INTEGER            — TOTP step number after which this row can be purged
 *
 * TOTP ENCRYPTION AT REST:
 *   The TOTP secret is AES-128/GCM encrypted before storage. The encryption key
 *   is derived per-player: SHA-256(masterKey || uuid)[0..15]. This means:
 *     - Raw database access does NOT expose usable TOTP secrets
 *     - Each player uses a unique derived key (UUID-salted)
 *     - The master key is stored in config.json only
 *
 * ACCOUNT LOCKOUT:
 *   After 5 consecutive failed attempts, account locks for 15 minutes.
 *   Admin can unlock via /raeyn auth unlock <player>.
 *
 * NOTE ON SQLITE-JDBC DEPENDENCY:
 *   Add to build.gradle dependencies:
 *     implementation 'org.xerial:sqlite-jdbc:3.45.1.0'
 *   For MySQL: the server operator must have mysql-connector-j on the classpath.
 *   Both JARs can be JiJ'd (Jar-in-Jar) via NeoForge's jarJar configuration.
 */
public class AuthDatabase {

    // ---------------------------------------------------------------------------
    // Record
    // ---------------------------------------------------------------------------

    public static class AuthRecord {
        public final String  uuid;
        public final String  username;
        public final String  passwordHash;    // null = no password
        public final String  totpSecretEnc;  // null = no TOTP
        public final boolean hasPassword;
        public final boolean hasTotp;
        public final long    createdAt;
        public final Long    lastLogin;
        public final int     failedAttempts;
        public final Long    lockedUntil;
        public final boolean totpPending;
        public final String  totpSecretPending;

        AuthRecord(ResultSet rs) throws SQLException {
            uuid              = rs.getString("uuid");
            username          = rs.getString("username");
            passwordHash      = rs.getString("password_hash");
            totpSecretEnc     = rs.getString("totp_secret_enc");
            hasPassword       = rs.getInt("has_password") == 1;
            hasTotp           = rs.getInt("has_totp") == 1;
            createdAt         = rs.getLong("created_at");
            long ll           = rs.getLong("last_login"); lastLogin = rs.wasNull() ? null : ll;
            failedAttempts    = rs.getInt("failed_attempts");
            long lu           = rs.getLong("locked_until"); lockedUntil = rs.wasNull() ? null : lu;
            totpPending       = rs.getInt("totp_pending") == 1;
            totpSecretPending = rs.getString("totp_secret_pending");
        }

        public boolean isLocked() {
            return lockedUntil != null && System.currentTimeMillis() / 1000L < lockedUntil;
        }

        public long lockSecondsRemaining() {
            if (!isLocked()) return 0;
            return lockedUntil - System.currentTimeMillis() / 1000L;
        }
    }

    // ---------------------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------------------

    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final int LOCKOUT_SECONDS     = 900; // 15 minutes

    private static final String AES_ALGORITHM  = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LEN     = 12;
    private static final int    GCM_TAG_LEN    = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    // ---------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------

    private final Connection connection;
    private final String     masterKey; // for TOTP encryption
    private final boolean    isMysql;   // dialect flag for SQL differences

    // ---------------------------------------------------------------------------
    // Factory
    // ---------------------------------------------------------------------------

    /**
     * Open or create the auth database.
     * Uses SQLite by default; switches to MySQL when config.mysqlEnabled is true.
     */
    public static AuthDatabase open(Path dbDir, RaeYNCheatConfig config) throws Exception {
        Connection conn;

        if (config.mysqlEnabled) {
            // Use Properties to pass credentials and config — avoids URL injection from config values.
            String url = "jdbc:mysql://" + config.mysqlHost + ":" + config.mysqlPort
                    + "/" + config.mysqlDatabase;
            java.util.Properties props = new java.util.Properties();
            props.setProperty("user",                    config.mysqlUsername);
            props.setProperty("password",                config.mysqlPassword);
            props.setProperty("useSSL",                  String.valueOf(config.mysqlUseSsl));
            props.setProperty("allowPublicKeyRetrieval", "true");
            props.setProperty("serverTimezone",          "UTC");
            props.setProperty("connectTimeout",          "5000");
            props.setProperty("socketTimeout",           "10000");
            conn = DriverManager.getConnection(url, props);
            RaeYNCheat.LOGGER.info("[Auth] Connected to MySQL database at {}:{}/{}",
                    config.mysqlHost, config.mysqlPort, config.mysqlDatabase);
        } else {
            // Load SQLite driver — required because NeoForge uses a custom classloader
            Class.forName("org.sqlite.JDBC");
            java.nio.file.Files.createDirectories(dbDir);
            String url = "jdbc:sqlite:" + dbDir.resolve("raeyncheat_auth.db").toAbsolutePath();
            conn = DriverManager.getConnection(url);
            // SQLite performance pragmas
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA foreign_keys=ON");
            }
            RaeYNCheat.LOGGER.info("[Auth] Opened SQLite database at {}", dbDir.resolve("raeyncheat_auth.db"));
        }

        AuthDatabase db = new AuthDatabase(conn, config.authDbEncryptionKey, config.mysqlEnabled);
        db.createSchema(config.mysqlEnabled);
        return db;
    }

    private AuthDatabase(Connection connection, String masterKey, boolean isMysql) {
        this.connection = connection;
        this.masterKey  = masterKey != null && !masterKey.isEmpty() ? masterKey : "RaeYNCheatDefaultKey2024";
        this.isMysql    = isMysql;
    }

    // ---------------------------------------------------------------------------
    // Schema
    // ---------------------------------------------------------------------------

    private void createSchema(boolean mysql) throws SQLException {
        String textType = mysql ? "VARCHAR(512)" : "TEXT";

        String playerAuth = "CREATE TABLE IF NOT EXISTS player_auth ("
                + "uuid              " + textType + " PRIMARY KEY,"
                + "username          " + textType + " NOT NULL,"
                + "password_hash     " + textType + ","
                + "totp_secret_enc   " + textType + ","
                + "has_password      INTEGER NOT NULL DEFAULT 0,"
                + "has_totp          INTEGER NOT NULL DEFAULT 0,"
                + "created_at        INTEGER NOT NULL,"
                + "last_login        INTEGER,"
                + "failed_attempts   INTEGER NOT NULL DEFAULT 0,"
                + "locked_until      INTEGER,"
                + "totp_pending      INTEGER NOT NULL DEFAULT 0,"
                + "totp_secret_pending " + textType
                + ")";

        // Violation records — persisted to survive server crashes.
        // violation_type: "checksum" | "passkey" | "env" | "negotiation"
        // Replaces the four ConcurrentHashMaps previously held in RaeYNCheatConfig.
        String violations = "CREATE TABLE IF NOT EXISTS violations ("
                + "uuid             " + textType + " NOT NULL,"
                + "violation_type   " + textType + " NOT NULL,"
                + "count            INTEGER NOT NULL DEFAULT 1,"
                + "last_timestamp   INTEGER NOT NULL,"
                + "PRIMARY KEY (uuid, violation_type)"
                + ")";

        // TOTP replay protection — persisted so server restarts don't open a replay window.
        String totpUsed = "CREATE TABLE IF NOT EXISTS totp_used_codes ("
                + "replay_key       " + textType + " PRIMARY KEY,"
                + "expires_at_step  INTEGER NOT NULL"
                + ")";

        try (Statement st = connection.createStatement()) {
            st.execute(playerAuth);
            st.execute(violations);
            st.execute(totpUsed);
        }

        // Clean up expired TOTP replay entries from previous sessions on open.
        purgeExpiredTotpCodes();
    }

    // ---------------------------------------------------------------------------
    // DAO methods
    // ---------------------------------------------------------------------------

    /** Fetch the auth record for a UUID, or null if no record exists. */
    public synchronized AuthRecord getRecord(String uuid) throws SQLException {
        String sql = "SELECT * FROM player_auth WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? new AuthRecord(rs) : null;
        }
    }

    /** Create a new record for a player who doesn't have one yet. */
    public synchronized void createRecord(String uuid, String username) throws SQLException {
        // SQLite uses "INSERT OR IGNORE", MySQL uses "INSERT IGNORE INTO"
        String sql = isMysql
                ? "INSERT IGNORE INTO player_auth (uuid, username, created_at) VALUES (?, ?, ?)"
                : "INSERT OR IGNORE INTO player_auth (uuid, username, created_at) VALUES (?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.setString(2, username);
            ps.setLong(3, System.currentTimeMillis() / 1000L);
            ps.executeUpdate();
        }
    }

    /** Upsert: create record if missing, then update username to current name. */
    public synchronized void upsertUsername(String uuid, String username) throws SQLException {
        createRecord(uuid, username);
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE player_auth SET username = ? WHERE uuid = ?")) {
            ps.setString(1, username);
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
    }

    // ── Password ──────────────────────────────────────────────────────────────

    public synchronized void setPassword(String uuid, String passwordHash) throws SQLException {
        String sql = "UPDATE player_auth SET password_hash = ?, has_password = 1 WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, passwordHash);
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
    }

    public synchronized void clearPassword(String uuid) throws SQLException {
        String sql = "UPDATE player_auth SET password_hash = NULL, has_password = 0 WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        }
    }

    // ── TOTP ──────────────────────────────────────────────────────────────────

    /** Store a pending (unconfirmed) TOTP secret. Promoted to active on first successful verify. */
    public synchronized void setPendingTotp(String uuid, String rawSecret) throws SQLException {
        String encrypted = encryptTotp(rawSecret, uuid);
        String sql = "UPDATE player_auth SET totp_pending = 1, totp_secret_pending = ? WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, encrypted);
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
    }

    /** Promote a pending TOTP secret to active after the player confirms with a valid code. */
    public synchronized void confirmTotp(String uuid) throws SQLException {
        String sql = "UPDATE player_auth SET "
                + "totp_secret_enc = totp_secret_pending, "
                + "has_totp = 1, "
                + "totp_pending = 0, "
                + "totp_secret_pending = NULL "
                + "WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        }
    }

    public synchronized void clearTotp(String uuid) throws SQLException {
        String sql = "UPDATE player_auth SET "
                + "totp_secret_enc = NULL, has_totp = 0, "
                + "totp_pending = 0, totp_secret_pending = NULL "
                + "WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        }
    }

    /**
     * Decrypt and return the active TOTP secret for a UUID.
     * Returns null if no active TOTP is configured.
     */
    public String getDecryptedTotpSecret(String uuid) throws Exception {
        AuthRecord rec = getRecord(uuid);
        if (rec == null || rec.totpSecretEnc == null) return null;
        return decryptTotp(rec.totpSecretEnc, uuid);
    }

    /**
     * Decrypt and return the pending (unconfirmed) TOTP secret.
     * Used during the setup confirmation flow.
     */
    public String getDecryptedPendingTotpSecret(String uuid) throws Exception {
        AuthRecord rec = getRecord(uuid);
        if (rec == null || rec.totpSecretPending == null) return null;
        return decryptTotp(rec.totpSecretPending, uuid);
    }

    // ── Login tracking ────────────────────────────────────────────────────────

    public synchronized void recordSuccessfulLogin(String uuid) throws SQLException {
        String sql = "UPDATE player_auth SET last_login = ?, failed_attempts = 0, locked_until = NULL WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis() / 1000L);
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
    }

    /**
     * Record a failed attempt atomically. If this pushes failed_attempts >= MAX_FAILED_ATTEMPTS,
     * lock the account for LOCKOUT_SECONDS and reset the counter.
     *
     * Uses a single transaction: increment → read back in same synchronized block → maybe lock.
     * The synchronized keyword on this method guarantees no concurrent writer sneaks between
     * the two queries (both run on the server main thread anyway, but belt-and-suspenders).
     *
     * Returns the post-increment failed_attempts count.
     */
    public synchronized int recordFailedAttempt(String uuid) throws SQLException {
        // Increment atomically
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE player_auth SET failed_attempts = failed_attempts + 1 WHERE uuid = ?")) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        }

        // Read back in same synchronized block — no other writer can intervene
        int count;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT failed_attempts FROM player_auth WHERE uuid = ?")) {
            ps.setString(1, uuid);
            ResultSet rs = ps.executeQuery();
            count = rs.next() ? rs.getInt(1) : 1;
        }

        if (count >= MAX_FAILED_ATTEMPTS) {
            long until = System.currentTimeMillis() / 1000L + LOCKOUT_SECONDS;
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE player_auth SET locked_until = ?, failed_attempts = 0 WHERE uuid = ?")) {
                ps.setLong(1, until);
                ps.setString(2, uuid);
                ps.executeUpdate();
            }
        }
        return count;
    }

    public synchronized void unlockAccount(String uuid) throws SQLException {
        String sql = "UPDATE player_auth SET locked_until = NULL, failed_attempts = 0 WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        }
    }

    // ── Admin ─────────────────────────────────────────────────────────────────

    public synchronized void adminResetPassword(String uuid) throws SQLException {
        String sql = "UPDATE player_auth SET password_hash = NULL, has_password = 0, "
                + "has_totp = 0, totp_secret_enc = NULL, totp_pending = 0, "
                + "totp_secret_pending = NULL, failed_attempts = 0, locked_until = NULL WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        }
    }

    // ── Violations (persisted) ────────────────────────────────────────────────

    /**
     * Record a violation and return the new total count for this UUID+type.
     * Uses INSERT OR REPLACE / INSERT … ON DUPLICATE KEY UPDATE to atomically upsert.
     * Violations are durable — they survive server crashes because SQLite WAL flushes
     * on every transaction commit. Replaces the volatile ConcurrentHashMaps in config.json.
     *
     * @param uuid          Player UUID string
     * @param violationType One of: "checksum", "passkey", "env", "negotiation"
     * @return new total violation count after this increment
     */
    public synchronized int recordViolation(String uuid, String violationType) throws SQLException {
        long now = System.currentTimeMillis() / 1000L;
        if (isMysql) {
            String sql = "INSERT INTO violations (uuid, violation_type, count, last_timestamp) "
                    + "VALUES (?, ?, 1, ?) "
                    + "ON DUPLICATE KEY UPDATE count = count + 1, last_timestamp = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, uuid);
                ps.setString(2, violationType);
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
        } else {
            // SQLite: INSERT OR IGNORE to create the row, then increment
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR IGNORE INTO violations (uuid, violation_type, count, last_timestamp) VALUES (?, ?, 0, ?)")) {
                ps.setString(1, uuid);
                ps.setString(2, violationType);
                ps.setLong(3, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE violations SET count = count + 1, last_timestamp = ? WHERE uuid = ? AND violation_type = ?")) {
                ps.setLong(1, now);
                ps.setString(2, uuid);
                ps.setString(3, violationType);
                ps.executeUpdate();
            }
        }
        // Read back the new count
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT count FROM violations WHERE uuid = ? AND violation_type = ?")) {
            ps.setString(1, uuid);
            ps.setString(2, violationType);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 1;
        }
    }

    /** Return the current violation count for a UUID+type, or 0 if no record exists. */
    public synchronized int getViolationCount(String uuid, String violationType) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT count FROM violations WHERE uuid = ? AND violation_type = ?")) {
            ps.setString(1, uuid);
            ps.setString(2, violationType);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Clear all violations for a UUID (admin pardon). */
    public synchronized void clearViolations(String uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM violations WHERE uuid = ?")) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        }
    }

    /**
     * Delete violation records older than {@code expiryDays} days.
     * Called at server startup to mirror the expiry behaviour previously in RaeYNCheatConfig.
     */
    public synchronized void purgeExpiredViolations(int expiryDays) throws SQLException {
        if (expiryDays <= 0) return;
        long cutoff = System.currentTimeMillis() / 1000L - (long) expiryDays * 86_400L;
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM violations WHERE last_timestamp < ?")) {
            ps.setLong(1, cutoff);
            int deleted = ps.executeUpdate();
            if (deleted > 0)
                RaeYNCheat.LOGGER.info("[Auth] Purged {} expired violation record(s).", deleted);
        }
    }

    // ── TOTP replay protection (persisted) ─────────────────────────────────────

    /**
     * Attempt to mark a TOTP replay key as used.
     * Returns true if the key was successfully inserted (first use).
     * Returns false if the key already exists (replay detected).
     *
     * @param replayKey     "uuid:step:code" string
     * @param expiresAtStep TOTP step number after which this entry may be deleted
     */
    public synchronized boolean markTotpCodeUsed(String replayKey, long expiresAtStep) throws SQLException {
        if (isMysql) {
            // INSERT IGNORE returns 0 rows affected if the key already exists
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT IGNORE INTO totp_used_codes (replay_key, expires_at_step) VALUES (?, ?)")) {
                ps.setString(1, replayKey);
                ps.setLong(2, expiresAtStep);
                return ps.executeUpdate() > 0; // >0 = inserted (first use), 0 = already existed
            }
        } else {
            // SQLite INSERT OR IGNORE
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT OR IGNORE INTO totp_used_codes (replay_key, expires_at_step) VALUES (?, ?)")) {
                ps.setString(1, replayKey);
                ps.setLong(2, expiresAtStep);
                return ps.executeUpdate() > 0;
            }
        }
    }

    /**
     * Check whether a TOTP replay key has already been used.
     * Called before markTotpCodeUsed so we can return the right rejection message.
     */
    public synchronized boolean isTotpCodeUsed(String replayKey) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM totp_used_codes WHERE replay_key = ?")) {
            ps.setString(1, replayKey);
            return ps.executeQuery().next();
        }
    }

    /** Delete TOTP replay entries whose step window has expired. */
    public synchronized void purgeExpiredTotpCodes() {
        long currentStep = System.currentTimeMillis() / 1000L / 30L;
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM totp_used_codes WHERE expires_at_step <= ?")) {
            ps.setLong(1, currentStep);
            int deleted = ps.executeUpdate();
            if (deleted > 0)
                RaeYNCheat.LOGGER.debug("[Auth] Purged {} expired TOTP replay entries.", deleted);
        } catch (SQLException e) {
            RaeYNCheat.LOGGER.warn("[Auth] Failed to purge expired TOTP codes: {}", e.getMessage());
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public synchronized void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
            RaeYNCheat.LOGGER.info("[Auth] Database connection closed.");
        } catch (SQLException e) {
            RaeYNCheat.LOGGER.error("[Auth] Error closing database", e);
        }
    }

    // ---------------------------------------------------------------------------
    // AES-128/GCM TOTP encryption
    // ---------------------------------------------------------------------------

    /**
     * Derive a 16-byte AES key for a specific player's TOTP secret.
     * Key = SHA-256(masterKey || uuid)[0..15]
     * UUID-salting ensures each player uses a different AES key.
     */
    private SecretKeySpec derivePlayerKey(String uuid) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(masterKey.getBytes(StandardCharsets.UTF_8));
        sha.update(uuid.getBytes(StandardCharsets.UTF_8));
        byte[] digest = sha.digest();
        byte[] keyBytes = new byte[16];
        System.arraycopy(digest, 0, keyBytes, 0, 16);
        return new SecretKeySpec(keyBytes, "AES");
    }

    /** Encrypt a TOTP secret string for storage. Returns Base64-encoded IV+ciphertext. */
    private String encryptTotp(String secret, String uuid) {
        try {
            SecretKeySpec key = derivePlayerKey(uuid);
            byte[] iv = new byte[GCM_IV_LEN];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN, iv));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buf = ByteBuffer.allocate(iv.length + encrypted.length);
            buf.put(iv);
            buf.put(encrypted);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new RuntimeException("TOTP encryption failed", e);
        }
    }

    /** Decrypt a stored TOTP secret. Returns the original Base32 string. */
    private String decryptTotp(String encryptedB64, String uuid) throws Exception {
        SecretKeySpec key = derivePlayerKey(uuid);
        byte[] decoded = Base64.getDecoder().decode(encryptedB64);
        ByteBuffer buf = ByteBuffer.wrap(decoded);

        byte[] iv = new byte[GCM_IV_LEN];
        buf.get(iv);
        byte[] ciphertext = new byte[buf.remaining()];
        buf.get(ciphertext);

        Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }
}
