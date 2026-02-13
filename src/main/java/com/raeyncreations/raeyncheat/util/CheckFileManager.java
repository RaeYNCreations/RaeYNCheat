package com.raeyncreations.raeyncheat.util;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class CheckFileManager {
    
    private final Path configDir;
    private final Path modsDir;
    
    public CheckFileManager(Path configDir, Path modsDir) {
        this.configDir = configDir;
        this.modsDir = modsDir;
    }
    
    /**
     * Validate that a client's passkey matches the server's expected passkey
     */
    public boolean validatePasskey(String clientPasskey, String playerUUID, String playerUsername) {
        String expectedPasskey = EncryptionUtil.generatePasskey(playerUUID);
        boolean isValid = expectedPasskey.equals(clientPasskey);
        
        if (isValid) {
            PasskeyLogger.logValidationSuccess(playerUsername, playerUUID, clientPasskey, expectedPasskey);
        } else {
            String reason = "Passkey mismatch - Client passkey does not match server-generated passkey";
            PasskeyLogger.logValidationFailure(playerUsername, playerUUID, clientPasskey, expectedPasskey, reason);
        }
        
        return isValid;
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
        // Ensure config directory exists
        Files.createDirectories(configDir);
        
        // Calculate checksums for all JAR files
        List<ChecksumUtil.FileChecksum> checksums = ChecksumUtil.calculateDirectoryChecksums(modsDir);
        
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
        // Ensure config directory exists
        Files.createDirectories(configDir);
        
        // Calculate checksums for all JAR files in mods_client
        List<ChecksumUtil.FileChecksum> checksums = ChecksumUtil.calculateDirectoryChecksums(modsDir);
        
        if (checksums == null || checksums.isEmpty()) {
            throw new IllegalStateException("No JAR files found in mods directory: " + modsDir);
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
     * Generate server CheckSum file from CheckSum_init for a specific player
     * Process: Read CheckSum_init -> Encrypt with player's key -> Save to CheckSum
     */
    public void generateServerCheckFile(String playerUUID, String playerUsername) throws Exception {
        // Read CheckSum_init
        Path checkSumInitFile = configDir.resolve("CheckSum_init");
        if (!Files.exists(checkSumInitFile)) {
            throw new FileNotFoundException("CheckSum_init file not found. Server must generate it first.");
        }
        
        String obfuscated = Files.readString(checkSumInitFile);
        
        if (obfuscated == null || obfuscated.trim().isEmpty()) {
            throw new IllegalStateException("CheckSum_init file is empty or invalid");
        }
        
        // Generate two-part passkey for this player
        String passkey = EncryptionUtil.generatePasskey(playerUUID);
        
        if (passkey == null || passkey.isEmpty()) {
            throw new IllegalStateException("Failed to generate passkey for player: " + playerUsername);
        }
        
        // Log passkey generation
        PasskeyLogger.logGeneration(playerUsername, playerUUID, passkey);
        
        // Encrypt the obfuscated data
        String encrypted = EncryptionUtil.encrypt(obfuscated, passkey);
        
        if (encrypted == null || encrypted.isEmpty()) {
            throw new IllegalStateException("Failed to encrypt checksum data");
        }
        
        // Write to CheckSum file
        Path checkSumFile = configDir.resolve("CheckSum");
        Files.writeString(checkSumFile, encrypted);
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
     * Compare two check files
     */
    public boolean compareCheckSums(String checkSum1, String checkSum2) {
        return checkSum1 != null && checkSum1.equals(checkSum2);
    }
}
