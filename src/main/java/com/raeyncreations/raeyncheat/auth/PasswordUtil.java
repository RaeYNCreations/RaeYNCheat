package com.raeyncreations.raeyncheat.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * Secure password hashing for RaeYNCheat account protection.
 *
 * Algorithm: PBKDF2-HMAC-SHA256
 *   - 310,000 iterations (OWASP 2023 minimum for PBKDF2-HMAC-SHA256)
 *   - 32-byte (256-bit) cryptographic salt, unique per password
 *   - 32-byte (256-bit) derived key output
 *   - Total stored string: ~120 chars including algorithm tag, iterations, salt, hash
 *
 * Storage format (similar to PHC string format):
 *   $pbkdf2-sha256$i=310000$<base64_salt>$<base64_hash>
 *
 * This format is self-describing — the iteration count and algorithm are embedded
 * so future upgrades (increasing iterations) are backward-compatible: old hashes
 * continue to verify against the iteration count they were created with.
 *
 * Password policy enforced at registration:
 *   - Minimum 8 characters
 *   - At least one letter and one digit (basic complexity)
 *   - Maximum 128 characters (prevents DoS via enormous PBKDF2 input)
 *
 * Thread safety: All methods are stateless and thread-safe.
 */
public class PasswordUtil {

    private static final String ALGORITHM     = "PBKDF2WithHmacSHA256";
    private static final int    ITERATIONS    = 310_000;
    private static final int    SALT_BYTES    = 32;
    private static final int    KEY_BITS      = 256;
    private static final String FORMAT_PREFIX = "$pbkdf2-sha256$";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // Password policy
    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 128;

    // ---------------------------------------------------------------------------
    // Hashing
    // ---------------------------------------------------------------------------

    /**
     * Hash a password for storage.
     *
     * @param password Plain-text password from the player.
     * @return Encoded hash string for storage in the database.
     * @throws IllegalArgumentException if the password fails policy checks.
     */
    public static String hashPassword(String password) {
        validatePolicy(password);

        byte[] salt = new byte[SALT_BYTES];
        SECURE_RANDOM.nextBytes(salt);

        byte[] hash = pbkdf2(password.toCharArray(), salt, ITERATIONS, KEY_BITS / 8);

        return FORMAT_PREFIX
                + "i=" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    /**
     * Verify a plain-text password against a stored hash.
     * Uses constant-time comparison to prevent timing attacks.
     *
     * @param password    Plain-text password to check.
     * @param storedHash  Hash string previously returned by hashPassword().
     * @return true if the password matches.
     */
    public static boolean verifyPassword(String password, String storedHash) {
        if (password == null || storedHash == null) return false;
        if (!storedHash.startsWith(FORMAT_PREFIX)) return false;

        try {
            String body = storedHash.substring(FORMAT_PREFIX.length());
            String[] parts = body.split("\\$");
            if (parts.length != 3) return false;

            int iterations = Integer.parseInt(parts[0].substring(2)); // strip "i="
            byte[] salt    = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);

            byte[] computed = pbkdf2(password.toCharArray(), salt, iterations, expected.length);

            // Constant-time comparison — never short-circuit on mismatch
            return MessageDigest.isEqual(expected, computed);

        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------------------
    // Policy validation
    // ---------------------------------------------------------------------------

    /**
     * Validate a new password against the policy.
     * @throws IllegalArgumentException with a player-friendly message on failure.
     */
    public static void validatePolicy(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must be at least " + MIN_LENGTH + " characters long.");
        }
        if (password.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must be at most " + MAX_LENGTH + " characters long.");
        }
        boolean hasLetter = false, hasDigit = false;
        for (char c : password.toCharArray()) {
            if (Character.isLetter(c)) hasLetter = true;
            if (Character.isDigit(c)) hasDigit  = true;
        }
        if (!hasLetter || !hasDigit) {
            throw new IllegalArgumentException(
                    "Password must contain at least one letter and one number.");
        }
    }

    /**
     * Returns a user-facing description of the password policy.
     */
    public static String policyDescription() {
        return MIN_LENGTH + "-" + MAX_LENGTH + " characters, must include at least one letter and one number";
    }

    // ---------------------------------------------------------------------------
    // PBKDF2 helper
    // ---------------------------------------------------------------------------

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyBytes) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyBytes * 8);
            SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGORITHM);
            byte[] result = skf.generateSecret(spec).getEncoded();
            spec.clearPassword();
            return result;
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("PBKDF2 unavailable — JVM error", e);
        }
    }
}
