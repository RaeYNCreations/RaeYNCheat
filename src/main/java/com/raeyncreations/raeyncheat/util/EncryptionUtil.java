package com.raeyncreations.raeyncheat.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public class EncryptionUtil {
    
    private static final String PERMANENT_KEY = "4pp7354Uc3!";
    private static final String ALGORITHM = "AES";
    
    /**
     * Generate a two-part passkey from permanent key and player UUID
     */
    public static String generatePasskey(String playerUUID) {
        return PERMANENT_KEY + ":" + playerUUID;
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
     * Obfuscate a string using simple XOR obfuscation
     */
    public static String obfuscate(String data) {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        byte[] obfuscated = new byte[bytes.length];
        
        // Simple XOR with a pattern
        byte[] pattern = PERMANENT_KEY.getBytes(StandardCharsets.UTF_8);
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
        
        // Reverse XOR
        byte[] pattern = PERMANENT_KEY.getBytes(StandardCharsets.UTF_8);
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
