package com.raeyncreations.raeyncheat.util;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.CRC32;

public class ChecksumUtil {
    
    /**
     * Calculate CRC32, SHA-256 hash, and MD5 checksum for a file
     */
    public static FileChecksum calculateFileChecksum(Path filePath) throws Exception {
        File file = filePath.toFile();
        if (!file.exists() || !file.isFile()) {
            throw new FileNotFoundException("File not found: " + filePath);
        }
        
        CRC32 crc32 = new CRC32();
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        
        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis)) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            
            while ((bytesRead = bis.read(buffer)) != -1) {
                crc32.update(buffer, 0, bytesRead);
                sha256.update(buffer, 0, bytesRead);
                md5.update(buffer, 0, bytesRead);
            }
        }
        
        return new FileChecksum(
            file.getName(),
            crc32.getValue(),
            bytesToHex(sha256.digest()),
            bytesToHex(md5.digest())
        );
    }
    
    /**
     * Calculate checksums for all JAR files in a directory
     */
    public static List<FileChecksum> calculateDirectoryChecksums(Path directory) throws Exception {
        List<FileChecksum> checksums = new ArrayList<>();
        
        File dir = directory.toFile();
        if (!dir.exists() || !dir.isDirectory()) {
            throw new FileNotFoundException("Directory not found: " + directory);
        }
        
        File[] jarFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
        if (jarFiles == null) {
            return checksums;
        }
        
        // Sort files by name for consistent ordering
        Arrays.sort(jarFiles, Comparator.comparing(File::getName));
        
        for (File jarFile : jarFiles) {
            try {
                checksums.add(calculateFileChecksum(jarFile.toPath()));
            } catch (Exception e) {
                System.err.println("Error calculating checksum for " + jarFile.getName() + ": " + e.getMessage());
            }
        }
        
        return checksums;
    }
    
    /**
     * Create a temporary file with checksum data and calculate its checksum
     */
    public static String calculateAggregateChecksum(List<FileChecksum> checksums) throws Exception {
        // Create temp file with all checksums
        StringBuilder content = new StringBuilder();
        for (FileChecksum checksum : checksums) {
            content.append(checksum.toString()).append("\n");
        }
        
        // Calculate SHA-256 hash of the aggregated content
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] hash = sha256.digest(content.toString().getBytes());
        
        return bytesToHex(hash);
    }
    
    /**
     * Convert byte array to hex string
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
    
    /**
     * Data class to hold file checksum information
     */
    public static class FileChecksum {
        public final String fileName;
        public final long crc32;
        public final String sha256;
        public final String md5;
        
        public FileChecksum(String fileName, long crc32, String sha256, String md5) {
            this.fileName = fileName;
            this.crc32 = crc32;
            this.sha256 = sha256;
            this.md5 = md5;
        }
        
        @Override
        public String toString() {
            return fileName + "|" + crc32 + "|" + sha256 + "|" + md5;
        }
        
        public static FileChecksum fromString(String line) {
            String[] parts = line.split("\\|");
            if (parts.length != 4) {
                throw new IllegalArgumentException("Invalid checksum line: " + line);
            }
            return new FileChecksum(parts[0], Long.parseLong(parts[1]), parts[2], parts[3]);
        }
    }
}
