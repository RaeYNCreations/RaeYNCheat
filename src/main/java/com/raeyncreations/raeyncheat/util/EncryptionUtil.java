package com.raeyncreations.raeyncheat.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.Month;
import java.util.Base64;

public class EncryptionUtil {
    
    // Permanent key in date format: "YYYY, Month Dayth" where date is current date
    private static final String ALGORITHM = "AES";
    
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
     */
    public static String generatePasskey(String playerUUID) {
        return getDeobfuscatedPermanentKey() + ":" + playerUUID;
    }
    
    /**
     * Create encryption key from passkey
     */
    private static SecretKey createKey(String passkey) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] key = sha.digest(passkey.getBytes(StandardCharsets.UTF_8));
        // Use first 16 bytes for AES-128
        byte[] keyBytes = new byte[16];
        System.arraycopy(key, 0, keyBytes, 0, 16);
        return new SecretKeySpec(keyBytes, ALGORITHM);
    }
    
    /**
     * Encrypt data using the two-part passkey
     */
    public static String encrypt(String data, String passkey) throws Exception {
        SecretKey key = createKey(passkey);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(encrypted);
    }
    
    /**
     * Decrypt data using the two-part passkey
     */
    public static String decrypt(String encryptedData, String passkey) throws Exception {
        SecretKey key = createKey(passkey);
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, key);
        byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedData));
        return new String(decrypted, StandardCharsets.UTF_8);
    }
    
    /**
     * XOR encrypt data for additional protection
     */
    public static String xorEncrypt(String data, String key) {
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
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        byte[] obfuscated = new byte[bytes.length];
        
        // Simple XOR with a pattern (use deobfuscated key)
        byte[] pattern = getDeobfuscatedPermanentKey().getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            obfuscated[i] = (byte) (bytes[i] ^ pattern[i % pattern.length]);
        }
        
        return Base64.getEncoder().encodeToString(obfuscated);
    }
    
    /**
     * Deobfuscate a string
     */
    public static String deobfuscate(String obfuscatedData) {
        byte[] obfuscated = Base64.getDecoder().decode(obfuscatedData);
        byte[] deobfuscated = new byte[obfuscated.length];
        
        // Reverse XOR (use deobfuscated key)
        byte[] pattern = getDeobfuscatedPermanentKey().getBytes(StandardCharsets.UTF_8);
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
