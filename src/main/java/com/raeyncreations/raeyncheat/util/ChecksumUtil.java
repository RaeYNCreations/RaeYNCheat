package com.raeyncreations.raeyncheat.util;

import com.raeyncreations.raeyncheat.RaeYNCheat;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.CRC32;

public class ChecksumUtil {

    /**
     * Calculate CRC32, SHA-256, and MD5 checksums for a single file.
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
     * Calculate checksums for all JAR files in a directory.
     *
     * BUG FIX: File-level errors were previously logged at DEBUG level, meaning a corrupt
     * or locked JAR would be silently skipped. Upgraded to WARN so server admins can
     * detect partial checksum lists that would cause false verification failures.
     */
    public static List<FileChecksum> calculateDirectoryChecksums(Path directory) throws Exception {
        if (directory == null) {
            throw new IllegalArgumentException("Directory path cannot be null");
        }
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Path is not a directory: " + directory);
        }

        File dir = directory.toFile();
        File[] jarFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".jar"));
        if (jarFiles == null) {
            throw new IOException("Unable to list files in directory: " + directory);
        }
        if (jarFiles.length == 0) {
            RaeYNCheat.LOGGER.warn("No JAR files found in directory: {}", directory);
            return new ArrayList<>();
        }

        // Sort for consistent ordering.
        Arrays.sort(jarFiles, Comparator.comparing(File::getName));

        List<FileChecksum> checksums = new ArrayList<>();
        for (File jarFile : jarFiles) {
            try {
                FileChecksum checksum = calculateFileChecksum(jarFile.toPath());
                if (checksum != null) {
                    checksums.add(checksum);
                }
            } catch (Exception e) {
                // WARN (not DEBUG) so admins notice partial checksum lists.
                RaeYNCheat.LOGGER.warn("Skipping JAR file due to checksum error: {} - {}",
                        jarFile.getName(), e.getMessage());
            }
        }

        return checksums;
    }

    /**
     * Calculate an aggregate SHA-256 checksum of all provided file checksums.
     */
    public static String calculateAggregateChecksum(List<FileChecksum> checksums) throws Exception {
        if (checksums == null || checksums.isEmpty()) {
            throw new IllegalArgumentException("Checksum list cannot be null or empty");
        }

        StringBuilder content = new StringBuilder();
        for (FileChecksum checksum : checksums) {
            if (checksum != null) {
                content.append(checksum.toString()).append("\n");
            }
        }

        if (content.length() == 0) {
            throw new IllegalStateException("No valid checksums found to aggregate");
        }

        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] hash = sha256.digest(content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return bytesToHex(hash);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    // ---------------------------------------------------------------------------
    // Data class
    // ---------------------------------------------------------------------------

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
            try {
                return new FileChecksum(parts[0], Long.parseLong(parts[1]), parts[2], parts[3]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid CRC32 value in checksum line: " + line, e);
            }
        }
    }
}
