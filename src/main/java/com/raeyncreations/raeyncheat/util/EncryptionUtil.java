package com.raeyncreations.raeyncheat.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.time.LocalDate;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Encryption utility for RaeYNCheat mod verification.
 *
 * SECURITY MODEL:
 * - Primary encryption: AES-128/GCM with PBKDF2 key derivation (cryptographically secure).
 * - XOR obfuscation: Secondary layer only. NOT cryptographically secure on its own.
 *
 * TWO-ENVIRONMENT KEY MATERIAL:
 *   Server side: PBKDF2 salt = SHA-256(passkey || KM_A || serverSecret || passkey)
 *   Client side: PBKDF2 salt = SHA-256(passkey || KM_A || passkey)  (no serverSecret)
 *   Call EncryptionUtil.initialize(config.authDbEncryptionKey) once at server startup.
 *   A leaked client JAR cannot reproduce server-side AES keys — it lacks serverSecret.
 *   The passkey derivation (derivePermanentKey) remains symmetric so client/server
 *   generate the same passkey for comparison; the server secret applies only to the
 *   AES envelope that protects the checksum and environment report payloads.
 *
 * KEY DERIVATION:
 *   The permanent key is derived from the current date combined with several hardcoded
 *   constants through a multi-step process designed to be opaque after ProGuard obfuscation.
 *   A cheater who does not decompile the JAR cannot reconstruct the key from the date alone.
 *   After ProGuard, all method names, field names, and string constants are renamed or
 *   inlined into unrecognisable bytecode, significantly raising the bar for reverse engineering.
 *
 *   The derivation intentionally:
 *   - Splits the key material across multiple private methods (each gets a different ProGuard name)
 *   - Mixes in hardcoded byte arrays that appear as opaque constants after obfuscation
 *   - Uses intermediate SHA-256 rounds so no single method reveals the full picture
 *   - Avoids any human-readable string that directly encodes the key
 *
 * FIX #3 (PBKDF2 salt): Previously the salt was derived solely from SHA-256(passkey),
 * meaning it was fully deterministic from the password. An attacker with the passkey
 * could precompute the AES key with no additional entropy. The salt now mixes in
 * KM_A so it requires both the passkey AND the embedded key material to reproduce.
 *
 * FIX #6 (PBKDF2 iterations): Updated from 10,000 to 310,000 — aligned with
 * OWASP 2023 recommended minimum for PBKDF2-HMAC-SHA256.
 *
 * MIDNIGHT ROLLOVER:
 *   generatePasskey() and obfuscate() accept an explicit LocalDate so callers can pass
 *   yesterday's date during the midnight grace window. See CheckFileManager.validatePasskey().
 *
 * Thread Safety: All public methods are thread-safe.
 */
public class EncryptionUtil {

    private static final String ALGORITHM     = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int GCM_IV_LENGTH    = 12;
    private static final int GCM_TAG_LENGTH   = 128;
    private static final int KEY_LENGTH       = 128;

    // FIX #6: Updated from 10,000 to 310,000 (OWASP 2023 recommendation for PBKDF2-HMAC-SHA256).
    private static final int PBKDF2_ITERATIONS = 310_000;

    private static final SecureRandom secureRandom = new SecureRandom();

    /**
     * Server-side secret injected at startup from config (authDbEncryptionKey).
     * Mixed into the PBKDF2 salt in createKey() so AES envelope encryption requires
     * BOTH the in-JAR KM_A AND this secret to reproduce.
     *
     * This field is NEVER set on the client — clients initialize from config.json which
     * doesn't include authDbEncryptionKey. A leaked client JAR therefore cannot reproduce
     * the server-side AES keys used to encrypt the checksum and environment report.
     *
     * Passkey derivation (derivePermanentKey) does NOT use this secret — it must remain
     * symmetric so client and server generate identical passkeys for comparison.
     */
    private static volatile byte[] serverSecretBytes = null;

    /**
     * Called once at server startup with the operator's secret from config.json.
     * Clears the key cache so subsequent operations use the new material.
     *
     * @param secret  authDbEncryptionKey value from RaeYNCheatConfig; must be non-empty.
     */
    public static void initialize(String secret) {
        if (secret == null || secret.isBlank()) {
            RaeYNCheat.LOGGER.warn("[EncryptionUtil] Server secret is blank — AES keys will not be hardened. "
                    + "Set authDbEncryptionKey in config.json.");
            serverSecretBytes = null;
            return;
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            serverSecretBytes = sha.digest(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            clearKeyCache(); // invalidate any keys derived before the secret was set
            RaeYNCheat.LOGGER.info("[EncryptionUtil] Server secret initialized — AES keys hardened.");
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("[EncryptionUtil] Failed to initialize server secret", e);
        }
    }

    /**
     * Key derivation cache: passkey → derived AES SecretKey.
     * createKey() runs 310,000 PBKDF2 iterations — far too expensive to repeat per-packet.
     * The cache is bounded naturally: one entry per unique passkey in flight (one per player
     * session per day). Keys rotate daily (passkey is date-derived) so old entries age out.
     * Max realistic size: concurrent_players × 2 (today + yesterday grace window).
     */
    private static final ConcurrentHashMap<String, SecretKey> KEY_CACHE = new ConcurrentHashMap<>();

    /** Clear the key cache. Call on server shutdown or when key material is rotated. */
    public static void clearKeyCache() {
        KEY_CACHE.clear();
    }

    // ---------------------------------------------------------------------------
    // Hardcoded key material — after ProGuard these become opaque byte arrays with
    // renamed field identifiers. KM_A contains the primary salt material; its actual
    // byte values must be kept private (not committed to public repos). The bytes
    // here are placeholders — replace with your own unique random 30-byte sequence
    // before distributing, generated e.g. via SecureRandom and hardcoded as literals.
    //
    // NOTE: Even after ProGuard, a determined attacker with a hex editor can locate
    // these byte arrays in the bytecode. The security model depends on distributing
    // the obfuscated JAR only to trusted players (private Discord), not on these
    // values being cryptographically secret. They are one of several interlocking
    // checks, not a standalone secret.
    // ---------------------------------------------------------------------------

    private static final byte[] KM_A = {
        // Replace with your own 30 unique random bytes before distribution.
        (byte)0x52, (byte)0x61, (byte)0x65, (byte)0x59, (byte)0x4E,
        (byte)0x43, (byte)0x68, (byte)0x65, (byte)0x61, (byte)0x74,
        (byte)0x2D, (byte)0x76, (byte)0x65, (byte)0x72, (byte)0x69,
        (byte)0x66, (byte)0x79, (byte)0x2D, (byte)0x73, (byte)0x61,
        (byte)0x6C, (byte)0x74, (byte)0x2D, (byte)0x61, (byte)0x6C,
        (byte)0x70, (byte)0x68, (byte)0x61, (byte)0x00, (byte)0x01
    };

    private static final byte[] KM_B = {
        (byte)0xDE, (byte)0xAD, (byte)0xC0, (byte)0xDE, (byte)0xBE,
        (byte)0xEF, (byte)0xFA, (byte)0xCE, (byte)0xCA, (byte)0xFE,
        (byte)0xBA, (byte)0xBE, (byte)0xF0, (byte)0x0D, (byte)0xD0,
        (byte)0x0D, (byte)0x1A, (byte)0x2B, (byte)0x3C, (byte)0x4D,
        (byte)0x5E, (byte)0x6F, (byte)0x7A, (byte)0x8B, (byte)0x9C,
        (byte)0xAD, (byte)0xBE, (byte)0xCF, (byte)0xD0, (byte)0xE1
    };

    private static final int[] KM_C = {
        0x52, 0x59, 0x43, 0x52, 0x45, 0x41, 0x54, 0x49, 0x4F, 0x4E,
        0x53, 0x5F, 0x4D, 0x4F, 0x44, 0x5F, 0x56, 0x45, 0x52, 0x49
    };

    // ---------------------------------------------------------------------------
    // Key derivation
    // ---------------------------------------------------------------------------

    private static byte[] extractDateBytes(LocalDate date) {
        int y = date.getYear();
        int m = date.getMonthValue();
        int d = date.getDayOfMonth();
        return new byte[] {
            (byte)((y >> 8) & 0xFF),
            (byte)(y & 0xFF),
            (byte)(((m << 4) | (d & 0x0F)) & 0xFF),
            (byte)(((d << 4) | (m & 0x0F)) & 0xFF),
            (byte)((y ^ (m * 31)) & 0xFF),
            (byte)((y ^ (d * 17)) & 0xFF),
            (byte)(((y + m + d) * 7) & 0xFF),
            (byte)(((y * m * d) ^ 0xAB) & 0xFF)
        };
    }

    private static byte[] firstRound(byte[] dateBytes) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(KM_A);
        sha.update(dateBytes);
        sha.update(KM_A);
        return sha.digest();
    }

    private static byte[] secondRound(byte[] firstRound) throws Exception {
        byte[] mixed = new byte[firstRound.length];
        for (int i = 0; i < firstRound.length; i++) {
            mixed[i] = (byte) (firstRound[i] ^ KM_B[i % KM_B.length]);
        }
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(mixed);
        sha.update(KM_B);
        return sha.digest();
    }

    private static String finalMix(byte[] secondRound) {
        byte[] result = new byte[secondRound.length];
        for (int i = 0; i < secondRound.length; i++) {
            result[i] = (byte) (secondRound[i] ^ (KM_C[i % KM_C.length] & 0xFF));
        }
        return Base64.getEncoder().encodeToString(result);
    }

    private static String derivePermanentKey(LocalDate date) {
        try {
            return finalMix(secondRound(firstRound(extractDateBytes(date))));
        } catch (Exception e) {
            throw new RuntimeException("CRITICAL: Key derivation failed", e);
        }
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    public static String generatePasskey(String playerUUID) {
        return generatePasskey(playerUUID, LocalDate.now());
    }

    public static String generatePasskey(String playerUUID, LocalDate date) {
        try {
            String permanentKey = derivePermanentKey(date);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] uuidHash = sha.digest(playerUUID.getBytes(StandardCharsets.UTF_8));
            String compactHash = Base64.getEncoder().encodeToString(uuidHash).substring(0, 32);
            return permanentKey + ":" + compactHash;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("CRITICAL: SHA-256 not available", e);
        }
    }

    public static String encrypt(String data, String passkey) throws Exception {
        SecretKey key = createKey(passkey);
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));

        ByteBuffer buf = ByteBuffer.allocate(iv.length + encrypted.length);
        buf.put(iv);
        buf.put(encrypted);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    public static String decrypt(String encryptedData, String passkey) throws Exception {
        SecretKey key = createKey(passkey);
        byte[] decoded = Base64.getDecoder().decode(encryptedData);
        ByteBuffer buf = ByteBuffer.wrap(decoded);

        byte[] iv = new byte[GCM_IV_LENGTH];
        buf.get(iv);
        byte[] encrypted = new byte[buf.remaining()];
        buf.get(encrypted);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    public static String obfuscate(String data) {
        return obfuscate(data, LocalDate.now());
    }

    public static String obfuscate(String data, LocalDate date) {
        if (data == null) throw new IllegalArgumentException("Data to obfuscate cannot be null");
        String permanentKey = derivePermanentKey(date);
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        byte[] pattern = permanentKey.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            out[i] = (byte) (bytes[i] ^ pattern[i % pattern.length]);
        }
        return Base64.getEncoder().encodeToString(out);
    }

    public static String deobfuscate(String obfuscatedData) {
        return deobfuscate(obfuscatedData, LocalDate.now());
    }

    public static String deobfuscate(String obfuscatedData, LocalDate date) {
        if (obfuscatedData == null) throw new IllegalArgumentException("Data to deobfuscate cannot be null");
        String permanentKey = derivePermanentKey(date);
        byte[] obfuscated = Base64.getDecoder().decode(obfuscatedData);
        byte[] pattern = permanentKey.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[obfuscated.length];
        for (int i = 0; i < obfuscated.length; i++) {
            out[i] = (byte) (obfuscated[i] ^ pattern[i % pattern.length]);
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    public static String obfuscateAndEncrypt(String data, String passkey) throws Exception {
        return encrypt(obfuscate(data), passkey);
    }

    public static String decryptAndDeobfuscate(String data, String passkey) throws Exception {
        return deobfuscate(decrypt(data, passkey));
    }

    public static String decryptAndDeobfuscate(String data, String passkey, LocalDate date) throws Exception {
        return deobfuscate(decrypt(data, passkey), date);
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    /**
     * FIX #3: Salt now mixes passkey-derived bytes with KM_A so that reproducing the
     * AES key requires BOTH the passkey AND the embedded KM_A constant.
     * Previously: salt = SHA-256(passkey)  — fully deterministic from the passkey alone.
     * Now:        salt = SHA-256(passkey || KM_A || passkey)  — requires KM_A too.
     */
    /**
     * Derive an AES-128 key from a passkey via PBKDF2-HMAC-SHA256 (310,000 iterations).
     * Results are cached per-passkey — derivation is expensive and the key is deterministic.
     *
     * Salt composition:
     *   SHA-256(passkey || KM_A || serverSecretBytes || passkey)
     *
     * FIX #3: KM_A is mixed in so reproducing the key requires the JAR constant.
     * FIX v12: serverSecretBytes (from authDbEncryptionKey in config.json, server-only)
     *   is also mixed in when available. A leaked client JAR cannot reproduce server-side
     *   AES keys because it has no access to the server config.
     */
    private static SecretKey createKey(String passkey) throws Exception {
        // Build cache key: if server secret is set, include a marker so client/server caches
        // don't accidentally share entries (relevant in integrated-server test scenarios).
        byte[] ss = serverSecretBytes;
        String cacheKey = (ss != null ? "S:" : "C:") + passkey;

        SecretKey cached = KEY_CACHE.get(cacheKey);
        if (cached != null) return cached;

        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(passkey.getBytes(StandardCharsets.UTF_8));
        sha.update(KM_A);                // in-JAR constant (FIX #3)
        if (ss != null) sha.update(ss); // server-only secret — not present on client
        sha.update(passkey.getBytes(StandardCharsets.UTF_8));
        byte[] saltBytes = sha.digest();
        byte[] salt = new byte[16];
        System.arraycopy(saltBytes, 0, salt, 0, 16);

        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(passkey.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH);
        SecretKey derived = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), KEY_ALGORITHM);

        KEY_CACHE.putIfAbsent(cacheKey, derived);
        return derived;
    }
}
