package com.raeyncreations.raeyncheat.util;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class CheckFileManager {
    
    private final Path configDir;
    private final Path modsDir;
    
    public CheckFileManager(Path configDir, Path modsDir) {
        if (configDir == null) {
            throw new IllegalArgumentException("Config directory cannot be null");
        }
        if (modsDir == null) {
            throw new IllegalArgumentException("Mods directory cannot be null");
        }
        
        // Validate that paths point to directories (or can be created)
        if (Files.exists(configDir) && !Files.isDirectory(configDir)) {
            throw new IllegalArgumentException("Config path exists but is not a directory: " + configDir);
        }
        if (Files.exists(modsDir) && !Files.isDirectory(modsDir)) {
            throw new IllegalArgumentException("Mods path exists but is not a directory: " + modsDir);
        }
        
        this.configDir = configDir;
        this.modsDir = modsDir;
    }
    
    /**
     * Validate that a client's passkey matches the server's expected passkey
     * Uses constant-time comparison to prevent timing attacks
     */
    public boolean validatePasskey(String clientPasskey, String playerUUID, String playerUsername) {
        String expectedPasskey = EncryptionUtil.generatePasskey(playerUUID);
        
        // Use constant-time comparison to prevent timing attacks
        boolean isValid = constantTimeEquals(clientPasskey, expectedPasskey);
        
        if (isValid) {
            PasskeyLogger.logValidationSuccess(playerUsername, playerUUID, clientPasskey, expectedPasskey);
        } else {
            String reason = "Passkey mismatch - Client passkey does not match server-generated passkey";
            PasskeyLogger.logValidationFailure(playerUsername, playerUUID, clientPasskey, expectedPasskey, reason);
        }
        
        return isValid;
    }
    
    /**
     * Constant-time string comparison to prevent timing attacks
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        
        // Convert to byte arrays for constant-time comparison
        byte[] aBytes = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        
        // If lengths differ, still do comparison to maintain constant time
        // but result will be false
        if (aBytes.length != bBytes.length) {
            return false;
        }
        
        // Use MessageDigest.isEqual for constant-time comparison
        return java.security.MessageDigest.isEqual(aBytes, bBytes);
    }
    
    /**
     * Get the list of checksums for the current mods directory
     */
    public List<ChecksumUtil.FileChecksum> getCurrentChecksums() throws Exception {
        return ChecksumUtil.calculateDirectoryChecksums(modsDir);
    }
    
    /**
     * Generate CheckSum file for client
     * Process: Calculate checksums -> Aggregate -> Obfuscate -> Encrypt -> Save
     */
    public void generateClientCheckFile(String playerUUID, String playerUsername) throws Exception {
        // Validate modsDir exists (null check already done in constructor)
        if (!java.nio.file.Files.exists(modsDir)) {
            throw new IllegalStateException("Mods directory does not exist: " + modsDir);
        }
        
        // Ensure config directory exists
        Files.createDirectories(configDir);
        
        // Calculate checksums for all JAR files
        List<ChecksumUtil.FileChecksum> checksums = ChecksumUtil.calculateDirectoryChecksums(modsDir);
        
        // Validate we have checksums before proceeding
        if (checksums == null || checksums.isEmpty()) {
            throw new IllegalStateException("No JAR files found in mods directory: " + modsDir);
        }
        
        // Calculate aggregate checksum
        String aggregateChecksum = ChecksumUtil.calculateAggregateChecksum(checksums);
        
        // Generate two-part passkey
        String passkey = EncryptionUtil.generatePasskey(playerUUID);
        
        // Log passkey generation
        PasskeyLogger.logGeneration(playerUsername, playerUUID, passkey);
        
        // Obfuscate and encrypt
        String encrypted = EncryptionUtil.obfuscateAndEncrypt(aggregateChecksum, passkey);
        
        // Write to CheckSum file
        Path checkSumFile = configDir.resolve("CheckSum");
        Files.writeString(checkSumFile, encrypted);
    }
    
    /**
     * Generate CheckSum_init file for server (obfuscated only, ready for encryption)
     * Process: Calculate checksums -> Aggregate -> Obfuscate -> Save
     */
    public void generateServerInitCheckFile() throws Exception {
        // Validate modsDir exists (null check already done in constructor)
        if (!java.nio.file.Files.exists(modsDir)) {
            throw new FileNotFoundException("Mods directory does not exist: " + modsDir + ". Please create the mods_client directory and add expected client mods.");
        }
        
        // Ensure config directory exists
        Files.createDirectories(configDir);
        
        // Calculate checksums for all JAR files in mods_client
        List<ChecksumUtil.FileChecksum> checksums = ChecksumUtil.calculateDirectoryChecksums(modsDir);
        
        if (checksums == null || checksums.isEmpty()) {
            throw new IllegalStateException("No JAR files found in mods directory: " + modsDir + ". Please add expected client mod JARs to mods_client directory.");
        }
        
        // Calculate aggregate checksum
        String aggregateChecksum = ChecksumUtil.calculateAggregateChecksum(checksums);
        
        if (aggregateChecksum == null || aggregateChecksum.isEmpty()) {
            throw new IllegalStateException("Failed to calculate aggregate checksum");
        }
        
        // Obfuscate (but don't encrypt yet)
        String obfuscated = EncryptionUtil.obfuscate(aggregateChecksum);
        
        if (obfuscated == null || obfuscated.isEmpty()) {
            throw new IllegalStateException("Failed to obfuscate checksum");
        }
        
        // Write to CheckSum_init file
        Path checkSumInitFile = configDir.resolve("CheckSum_init");
        Files.writeString(checkSumInitFile, obfuscated);
    }
    
    /**
     * Generate server CheckSum file from CheckSum_init for a specific player using a validated passkey
     * Process: Read CheckSum_init -> Encrypt with validated player's key -> Save to CheckSum
     */
    public void generateServerCheckFile(String playerUUID, String playerUsername, String validatedPasskey) throws Exception {
        // Validate inputs
        if (validatedPasskey == null || validatedPasskey.trim().isEmpty()) {
            throw new IllegalArgumentException("Validated passkey cannot be null or empty");
        }
        
        // Read CheckSum_init
        Path checkSumInitFile = configDir.resolve("CheckSum_init");
        if (!Files.exists(checkSumInitFile)) {
            throw new FileNotFoundException("CheckSum_init file not found. Server must generate it first.");
        }
        
        String obfuscated = Files.readString(checkSumInitFile);
        
        if (obfuscated == null || obfuscated.trim().isEmpty()) {
            throw new IllegalStateException("CheckSum_init file is empty or invalid");
        }
        
        // Log the obfuscated checksum being encrypted
        PasskeyLogger.logEncryptionEvent(playerUsername, playerUUID, validatedPasskey, true,
            "ENCRYPT", "Encrypting CheckSum_init (length: " + obfuscated.length() + ") with validated passkey");
        
        // Log passkey generation (using the validated passkey)
        PasskeyLogger.logGeneration(playerUsername, playerUUID, validatedPasskey);
        
        // Encrypt the obfuscated data using the validated passkey
        String encrypted = EncryptionUtil.encrypt(obfuscated, validatedPasskey);
        
        if (encrypted == null || encrypted.isEmpty()) {
            throw new IllegalStateException("Failed to encrypt checksum data");
        }
        
        // Log successful encryption
        PasskeyLogger.logEncryptionEvent(playerUsername, playerUUID, validatedPasskey, true,
            "ENCRYPT_COMPLETE", "Successfully encrypted CheckSum (length: " + encrypted.length() + ")");
        
        // Write to CheckSum file
        Path checkSumFile = configDir.resolve("CheckSum");
        Files.writeString(checkSumFile, encrypted);
    }
    
    /**
     * Read encrypted CheckSum file without decryption (for comparison)
     * 
     * @return The encrypted checksum content as a string
     * @throws FileNotFoundException if the CheckSum file does not exist
     * @throws IOException if an I/O error occurs reading the file
     */
    public String readEncryptedCheckSum() throws IOException {
        Path checkSumFile = configDir.resolve("CheckSum");
        if (!Files.exists(checkSumFile)) {
            throw new FileNotFoundException("CheckSum file not found");
        }
        
        return Files.readString(checkSumFile);
    }
    
    /**
     * Read and decrypt CheckSum file
     */
    public String readCheckSum(String playerUUID, String playerUsername) throws Exception {
        Path checkSumFile = configDir.resolve("CheckSum");
        if (!Files.exists(checkSumFile)) {
            throw new FileNotFoundException("CheckSum file not found");
        }
        
        String encrypted = Files.readString(checkSumFile);
        String passkey = EncryptionUtil.generatePasskey(playerUUID);
        
        try {
            String decrypted = EncryptionUtil.decryptAndDeobfuscate(encrypted, passkey);
            PasskeyLogger.logEncryptionEvent(playerUsername, playerUUID, passkey, true, 
                "DECRYPT", "Successfully decrypted CheckSum file");
            return decrypted;
        } catch (Exception e) {
            PasskeyLogger.logEncryptionEvent(playerUsername, playerUUID, passkey, false, 
                "DECRYPT", "Failed to decrypt CheckSum file: " + e.getMessage());
            throw e;
        }
    }
    
    /**
     * Compare two encrypted checksums by decrypting them first
     * Uses constant-time comparison to prevent timing attacks
     * 
     * @param encryptedCheckSum1 First encrypted checksum
     * @param encryptedCheckSum2 Second encrypted checksum
     * @param passkey The passkey to decrypt both checksums
     * @param playerUUID Player UUID for error reporting
     * @param playerUsername Player username for error reporting
     * @return true if the decrypted checksums match, false otherwise
     * @throws IllegalArgumentException if either checksum or passkey is null
     */
    public boolean compareCheckSums(String encryptedCheckSum1, String encryptedCheckSum2, 
                                     String passkey, String playerUUID, String playerUsername) {
        if (encryptedCheckSum1 == null || encryptedCheckSum2 == null) {
            throw new IllegalArgumentException("Checksums cannot be null for comparison");
        }
        if (passkey == null || passkey.trim().isEmpty()) {
            throw new IllegalArgumentException("Passkey cannot be null or empty for comparison");
        }
        
        try {
            // Decrypt both checksums
            String decrypted1 = EncryptionUtil.decryptAndDeobfuscate(encryptedCheckSum1, passkey);
            String decrypted2 = EncryptionUtil.decryptAndDeobfuscate(encryptedCheckSum2, passkey);
            
            // Use constant-time comparison to prevent timing attacks
            boolean match = constantTimeEquals(decrypted1, decrypted2);
            
            if (match) {
                PasskeyLogger.logEncryptionEvent(playerUsername, playerUUID, passkey, true,
                    "DECRYPT_COMPARE", "Successfully decrypted and compared checksums - MATCH");
            } else {
                PasskeyLogger.logEncryptionEvent(playerUsername, playerUUID, passkey, false,
                    "DECRYPT_COMPARE", "Successfully decrypted and compared checksums - MISMATCH");
            }
            
            return match;
        } catch (Exception e) {
            PasskeyLogger.logEncryptionEvent(playerUsername, playerUUID, passkey, false,
                "DECRYPT_COMPARE", "Failed to decrypt checksums for comparison: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Compare two check files (legacy method - direct comparison)
     * WARNING: This should not be used for encrypted checksums as each encryption
     * generates a random IV, making direct comparison impossible.
     * 
     * @deprecated Use {@link #compareCheckSums(String, String, String, String, String)} for encrypted checksums
     * @throws IllegalArgumentException if either checksum is null
     */
    @Deprecated
    public boolean compareCheckSumsLegacy(String checkSum1, String checkSum2) {
        if (checkSum1 == null || checkSum2 == null) {
            throw new IllegalArgumentException("Checksums cannot be null for comparison");
        }
        return checkSum1.equals(checkSum2);
    }
}
