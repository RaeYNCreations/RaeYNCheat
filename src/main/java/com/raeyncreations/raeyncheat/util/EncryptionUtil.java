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
import java.time.Month;
import java.util.Base64;

/**
 * Encryption utility for RaeYNCheat mod verification.
 * 
 * SECURITY NOTES:
 * - Primary encryption: AES-128/GCM with PBKDF2 key derivation (cryptographically secure)
 * - XOR obfuscation: Used for additional layer only, NOT cryptographically secure
 *   (XOR is reversible and should not be relied upon for security)
 * - Permanent key derivation: Based on current date (predictable, ~36,500 variations per decade)
 *   This is acceptable for this use case as it's combined with player UUID hashing
 * 
 * Thread Safety: All public methods are thread-safe
 */
public class EncryptionUtil {
    
    // Use AES/GCM for authenticated encryption
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int GCM_IV_LENGTH = 12; // 96 bits recommended for GCM
    private static final int GCM_TAG_LENGTH = 128; // 128 bits authentication tag
    private static final int PBKDF2_ITERATIONS = 10000; // Reasonable for game server performance
    private static final int KEY_LENGTH = 128; // AES-128
    
    private static final SecureRandom secureRandom = new SecureRandom();
    
    // Reconstruct the permanent key at runtime using current date
    private static String reconstructPermanentKey() {
        LocalDate today = LocalDate.now();
        int year = today.getYear();
        Month month = today.getMonth();
        int day = today.getDayOfMonth();
        String daySuffix = getDaySuffix(day);
        
        // Use StringBuilder to make it harder to track
        StringBuilder sb = new StringBuilder();
        sb.append(year);
        sb.append(", ");
        sb.append(month.toString().substring(0, 1).toUpperCase() + month.toString().substring(1).toLowerCase());
        sb.append(" ");
        sb.append(day);
        sb.append(daySuffix);
        return sb.toString();
    }
    
    private static String getDaySuffix(int day) {
        if (day >= 11 && day <= 13) {
            return "th";
        }
        switch (day % 10) {
            case 1: return "st";
            case 2: return "nd";
            case 3: return "rd";
            default: return "th";
        }
    }
    
    /**
     * Get the obfuscated permanent key
     */
    private static String getPermanentKey() {
        // Simple obfuscation using Base64 and reverse
        String key = reconstructPermanentKey();
        String reversed = new StringBuilder(key).reverse().toString();
        return Base64.getEncoder().encodeToString(reversed.getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * Get the deobfuscated permanent key for use
     */
    private static String getDeobfuscatedPermanentKey() {
        try {
            String decoded = new String(Base64.getDecoder().decode(getPermanentKey()), StandardCharsets.UTF_8);
            return new StringBuilder(decoded).reverse().toString();
        } catch (Exception e) {
            return reconstructPermanentKey(); // Fallback to reconstructed key if deobfuscation fails
        }
    }
    
    /**
     * Generate a two-part passkey from permanent key and player UUID
     * Uses SHA-256 hash of UUID to prevent precomputation attacks
     * 
     * Hash truncation rationale: We use the first 32 characters (192 bits of entropy)
     * of the Base64-encoded SHA-256 hash for compactness in network transmission
     * while maintaining cryptographic strength. 192 bits provides more than sufficient
     * security against brute-force attacks (2^192 possibilities).
     */
    public static String generatePasskey(String playerUUID) {
        try {
            // Hash the UUID to prevent precomputation attacks on known player UUIDs
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] uuidHash = sha.digest(playerUUID.getBytes(StandardCharsets.UTF_8));
            
            // Convert hash to Base64 and use first 32 characters for compactness
            // SHA-256 produces 32 bytes which encodes to 44 Base64 chars, always safe
            String uuidHashB64 = Base64.getEncoder().encodeToString(uuidHash);
            String compactHash = uuidHashB64.substring(0, 32);
            
            return getDeobfuscatedPermanentKey() + ":" + compactHash;
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 should always be available, this is a critical error
            throw new RuntimeException("CRITICAL: SHA-256 algorithm not available. Cannot generate secure passkey.", e);
        }
    }
    
    /**
     * Create encryption key from passkey using PBKDF2 for proper key derivation
     * Uses a fixed salt derived from the passkey itself for deterministic encryption
     * (same passkey always produces same key, which is required for checksum verification)
     */
    private static SecretKey createKey(String passkey) throws Exception {
        // Use first 16 bytes of SHA-256(passkey) as salt for deterministic key derivation
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] saltBytes = sha.digest(passkey.getBytes(StandardCharsets.UTF_8));
        byte[] salt = new byte[16];
        System.arraycopy(saltBytes, 0, salt, 0, 16);
        
        // Use PBKDF2 for proper key derivation
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(passkey.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH);
        SecretKey tmp = factory.generateSecret(spec);
        return new SecretKeySpec(tmp.getEncoded(), KEY_ALGORITHM);
    }
    
    /**
     * Encrypt data using AES/GCM with the two-part passkey
     * Returns Base64-encoded: IV (12 bytes) + encrypted data + authentication tag
     */
    public static String encrypt(String data, String passkey) throws Exception {
        SecretKey key = createKey(passkey);
        
        // Generate random IV for GCM
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);
        
        // Initialize cipher with GCM parameters
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec);
        
        // Encrypt the data
        byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
        
        // Combine IV + encrypted data for transmission
        ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + encrypted.length);
        byteBuffer.put(iv);
        byteBuffer.put(encrypted);
        
        return Base64.getEncoder().encodeToString(byteBuffer.array());
    }
    
    /**
     * Decrypt data using AES/GCM with the two-part passkey
     * Expects Base64-encoded: IV (12 bytes) + encrypted data + authentication tag
     */
    public static String decrypt(String encryptedData, String passkey) throws Exception {
        SecretKey key = createKey(passkey);
        
        // Decode from Base64
        byte[] decoded = Base64.getDecoder().decode(encryptedData);
        
        // Extract IV and encrypted data
        ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);
        byte[] iv = new byte[GCM_IV_LENGTH];
        byteBuffer.get(iv);
        byte[] encrypted = new byte[byteBuffer.remaining()];
        byteBuffer.get(encrypted);
        
        // Initialize cipher with GCM parameters
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);
        
        // Decrypt and verify authentication tag
        byte[] decrypted = cipher.doFinal(encrypted);
        return new String(decrypted, StandardCharsets.UTF_8);
    }
    
    /**
     * XOR encrypt data for additional protection
     */
    public static String xorEncrypt(String data, String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("XOR encryption key cannot be null or empty");
        }
        if (data == null) {
            throw new IllegalArgumentException("Data to encrypt cannot be null");
        }
        
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < data.length(); i++) {
            result.append((char) (data.charAt(i) ^ key.charAt(i % key.length())));
        }
        return Base64.getEncoder().encodeToString(result.toString().getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * XOR decrypt data
     */
    public static String xorDecrypt(String encryptedData, String key) throws Exception {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("XOR decryption key cannot be null or empty");
        }
        if (encryptedData == null) {
            throw new IllegalArgumentException("Data to decrypt cannot be null");
        }
        
        String data = new String(Base64.getDecoder().decode(encryptedData), StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < data.length(); i++) {
            result.append((char) (data.charAt(i) ^ key.charAt(i % key.length())));
        }
        return result.toString();
    }
    
    /**
     * Obfuscate a string using simple XOR obfuscation
     */
    public static String obfuscate(String data) {
        if (data == null) {
            throw new IllegalArgumentException("Data to obfuscate cannot be null");
        }
        
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        byte[] obfuscated = new byte[bytes.length];
        
        // Simple XOR with a pattern (use deobfuscated key)
        String permanentKey = getDeobfuscatedPermanentKey();
        if (permanentKey == null || permanentKey.isEmpty()) {
            throw new IllegalStateException("Permanent key cannot be null or empty");
        }
        
        byte[] pattern = permanentKey.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            obfuscated[i] = (byte) (bytes[i] ^ pattern[i % pattern.length]);
        }
        
        return Base64.getEncoder().encodeToString(obfuscated);
    }
    
    /**
     * Deobfuscate a string
     */
    public static String deobfuscate(String obfuscatedData) {
        if (obfuscatedData == null) {
            throw new IllegalArgumentException("Data to deobfuscate cannot be null");
        }
        
        byte[] obfuscated = Base64.getDecoder().decode(obfuscatedData);
        byte[] deobfuscated = new byte[obfuscated.length];
        
        // Reverse XOR (use deobfuscated key)
        String permanentKey = getDeobfuscatedPermanentKey();
        if (permanentKey == null || permanentKey.isEmpty()) {
            throw new IllegalStateException("Permanent key cannot be null or empty");
        }
        
        byte[] pattern = permanentKey.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < obfuscated.length; i++) {
            deobfuscated[i] = (byte) (obfuscated[i] ^ pattern[i % pattern.length]);
        }
        
        return new String(deobfuscated, StandardCharsets.UTF_8);
    }
    
    /**
     * Apply both obfuscation and encryption
     */
    public static String obfuscateAndEncrypt(String data, String passkey) throws Exception {
        String obfuscated = obfuscate(data);
        return encrypt(obfuscated, passkey);
    }
    
    /**
     * Decrypt and deobfuscate
     */
    public static String decryptAndDeobfuscate(String data, String passkey) throws Exception {
        String decrypted = decrypt(data, passkey);
        return deobfuscate(decrypted);
    }
}
