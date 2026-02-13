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
     * Generate CheckSum file for client
     * Process: Calculate checksums -> Aggregate -> Obfuscate -> Encrypt -> Save
     */
    public void generateClientCheckFile(String playerUUID) throws Exception {
        // Ensure config directory exists
        Files.createDirectories(configDir);
        
        // Calculate checksums for all JAR files
        List<ChecksumUtil.FileChecksum> checksums = ChecksumUtil.calculateDirectoryChecksums(modsDir);
        
        // Calculate aggregate checksum
        String aggregateChecksum = ChecksumUtil.calculateAggregateChecksum(checksums);
        
        // Generate two-part passkey
        String passkey = EncryptionUtil.generatePasskey(playerUUID);
        
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
        
        // Calculate aggregate checksum
        String aggregateChecksum = ChecksumUtil.calculateAggregateChecksum(checksums);
        
        // Obfuscate (but don't encrypt yet)
        String obfuscated = EncryptionUtil.obfuscate(aggregateChecksum);
        
        // Write to CheckSum_init file
        Path checkSumInitFile = configDir.resolve("CheckSum_init");
        Files.writeString(checkSumInitFile, obfuscated);
    }
    
    /**
     * Generate server CheckSum file from CheckSum_init for a specific player
     * Process: Read CheckSum_init -> Encrypt with player's key -> Save to CheckSum
     */
    public void generateServerCheckFile(String playerUUID) throws Exception {
        // Read CheckSum_init
        Path checkSumInitFile = configDir.resolve("CheckSum_init");
        if (!Files.exists(checkSumInitFile)) {
            throw new FileNotFoundException("CheckSum_init file not found. Server must generate it first.");
        }
        
        String obfuscated = Files.readString(checkSumInitFile);
        
        // Generate two-part passkey for this player
        String passkey = EncryptionUtil.generatePasskey(playerUUID);
        
        // Encrypt the obfuscated data
        String encrypted = EncryptionUtil.encrypt(obfuscated, passkey);
        
        // Write to CheckSum file
        Path checkSumFile = configDir.resolve("CheckSum");
        Files.writeString(checkSumFile, encrypted);
    }
    
    /**
     * Read and decrypt CheckSum file
     */
    public String readCheckSum(String playerUUID) throws Exception {
        Path checkSumFile = configDir.resolve("CheckSum");
        if (!Files.exists(checkSumFile)) {
            throw new FileNotFoundException("CheckSum file not found");
        }
        
        String encrypted = Files.readString(checkSumFile);
        String passkey = EncryptionUtil.generatePasskey(playerUUID);
        
        return EncryptionUtil.decryptAndDeobfuscate(encrypted, passkey);
    }
    
    /**
     * Compare two check files
     */
    public boolean compareCheckSums(String checkSum1, String checkSum2) {
        return checkSum1 != null && checkSum1.equals(checkSum2);
    }
}
