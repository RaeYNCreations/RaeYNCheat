package com.raeyncreations.raeyncheat.util;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public class CheckFileManager {

    private final Path configDir;
    private final Path modsDir;

    // Accept yesterday's passkey for this many seconds after midnight (FIX #3).
    private static final int MIDNIGHT_GRACE_SECONDS = 30;

    public CheckFileManager(Path configDir, Path modsDir) {
        if (configDir == null) throw new IllegalArgumentException("Config directory cannot be null");
        if (modsDir == null)   throw new IllegalArgumentException("Mods directory cannot be null");
        if (Files.exists(configDir) && !Files.isDirectory(configDir))
            throw new IllegalArgumentException("Config path exists but is not a directory: " + configDir);
        if (Files.exists(modsDir) && !Files.isDirectory(modsDir))
            throw new IllegalArgumentException("Mods path exists but is not a directory: " + modsDir);
        this.configDir = configDir;
        this.modsDir   = modsDir;
    }

    // ---------------------------------------------------------------------------
    // Passkey validation
    // ---------------------------------------------------------------------------

    /**
     * Validate a client's passkey.
     *
     * FIX #3 (midnight grace window): If it is within MIDNIGHT_GRACE_SECONDS of midnight,
     * also try yesterday's passkey to avoid false violations when a client sends its
     * SyncPacket at 23:59:59 but the server validates at 00:00:01.
     *
     * FIX #8: Passkey values are not passed to PasskeyLogger — only status and reason.
     */
    public boolean validatePasskey(String clientPasskey, String playerUUID, String playerUsername) {
        // Always try today's passkey first.
        if (constantTimeEquals(clientPasskey, EncryptionUtil.generatePasskey(playerUUID))) {
            PasskeyLogger.logValidationSuccess(playerUsername, playerUUID);
            return true;
        }

        // FIX #3: During the grace window, also accept yesterday's passkey.
        LocalTime now = LocalTime.now();
        if (now.getHour() == 0 && now.getMinute() == 0 && now.getSecond() < MIDNIGHT_GRACE_SECONDS) {
            LocalDate yesterday = LocalDate.now().minusDays(1);
            if (constantTimeEquals(clientPasskey, EncryptionUtil.generatePasskey(playerUUID, yesterday))) {
                PasskeyLogger.logValidationSuccess(playerUsername, playerUUID,
                        "Passkey accepted via midnight grace window (yesterday's key)");
                return true;
            }
        }

        PasskeyLogger.logValidationFailure(playerUsername, playerUUID,
                "Passkey mismatch - client passkey does not match server-generated passkey");
        return false;
    }

    /** Constant-time comparison to prevent timing attacks. */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return a == b;
        byte[] ab = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (ab.length != bb.length) return false;
        return java.security.MessageDigest.isEqual(ab, bb);
    }

    // ---------------------------------------------------------------------------
    // Check file generation
    // ---------------------------------------------------------------------------

    public List<ChecksumUtil.FileChecksum> getCurrentChecksums() throws Exception {
        return ChecksumUtil.calculateDirectoryChecksums(modsDir);
    }

    /**
     * Generate an encrypted client checksum IN MEMORY and return it.
     * Process: Calculate checksums → Aggregate → Obfuscate → Encrypt → Return
     *
     * Preferred over generateClientCheckFile() — avoids the disk write/read round-trip
     * and eliminates any file-locking issues on Windows during concurrent server logins.
     */
    public String generateClientChecksumInMemory(String playerUUID, String playerUsername) throws Exception {
        if (!Files.exists(modsDir))
            throw new IllegalStateException("Mods directory does not exist: " + modsDir);

        List<ChecksumUtil.FileChecksum> checksums = ChecksumUtil.calculateDirectoryChecksums(modsDir);
        if (checksums == null || checksums.isEmpty())
            throw new IllegalStateException("No JAR files found in mods directory: " + modsDir);

        String aggregateChecksum = ChecksumUtil.calculateAggregateChecksum(checksums);
        String passkey = EncryptionUtil.generatePasskey(playerUUID);
        PasskeyLogger.logGeneration(playerUsername, playerUUID);

        return EncryptionUtil.obfuscateAndEncrypt(aggregateChecksum, passkey);
    }

    /**
     * Generate an encrypted CheckSum for the client and write it to disk.
     * Process: Calculate checksums → Aggregate → Obfuscate → Encrypt → Save
     *
     * @deprecated Use {@link #generateClientChecksumInMemory} — avoids disk I/O and file locks.
     */
    @Deprecated
    public void generateClientCheckFile(String playerUUID, String playerUsername) throws Exception {
        String encrypted = generateClientChecksumInMemory(playerUUID, playerUsername);
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("CheckSum"), encrypted);
    }

    /**
     * Generate CheckSum_init for the server (obfuscated, not yet encrypted).
     * Process: Calculate checksums → Aggregate → Obfuscate → Save
     */
    public void generateServerInitCheckFile() throws Exception {
        if (!Files.exists(modsDir))
            throw new FileNotFoundException("Mods directory does not exist: " + modsDir);
        Files.createDirectories(configDir);

        List<ChecksumUtil.FileChecksum> checksums = ChecksumUtil.calculateDirectoryChecksums(modsDir);
        if (checksums == null || checksums.isEmpty())
            throw new IllegalStateException("No JAR files found in mods directory: " + modsDir);

        String aggregateChecksum = ChecksumUtil.calculateAggregateChecksum(checksums);
        if (aggregateChecksum == null || aggregateChecksum.isEmpty())
            throw new IllegalStateException("Failed to calculate aggregate checksum");

        String obfuscated = EncryptionUtil.obfuscate(aggregateChecksum);
        if (obfuscated == null || obfuscated.isEmpty())
            throw new IllegalStateException("Failed to obfuscate checksum");

        Files.writeString(configDir.resolve("CheckSum_init"), obfuscated);
    }

    /**
     * Generate a server-side encrypted checksum IN MEMORY for a specific player.
     *
     * RACE CONDITION FIX: The old approach wrote to a shared "CheckSum" file on disk,
     * so concurrent logins would clobber each other's file. This method returns the
     * encrypted result as a String — no shared file in the hot path.
     *
     * FIX #3 (midnight grace window): If the client used yesterday's passkey (accepted
     * by validatePasskey above), we deobfuscate CheckSum_init with today's key then
     * re-obfuscate with yesterday's key so both sides decrypt to the same raw value.
     */
    public String generateServerChecksumInMemory(String playerUUID, String playerUsername,
                                                   String validatedPasskey) throws Exception {
        if (validatedPasskey == null || validatedPasskey.trim().isEmpty())
            throw new IllegalArgumentException("Validated passkey cannot be null or empty");

        Path checkSumInitFile = configDir.resolve("CheckSum_init");
        if (!Files.exists(checkSumInitFile))
            throw new FileNotFoundException("CheckSum_init file not found. Server must generate it first.");

        String obfuscated = Files.readString(checkSumInitFile).trim();
        if (obfuscated.isEmpty())
            throw new IllegalStateException("CheckSum_init file is empty or invalid");

        // FIX #3: Detect whether the client used today's or yesterday's passkey,
        // and align the obfuscation key accordingly.
        LocalDate today     = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        boolean usedYesterday = constantTimeEquals(validatedPasskey,
                EncryptionUtil.generatePasskey(playerUUID, yesterday));

        String obfuscatedForClient;
        if (usedYesterday) {
            // Deobfuscate with today's key, re-obfuscate with yesterday's so client can decrypt.
            String raw = EncryptionUtil.deobfuscate(obfuscated, today);
            obfuscatedForClient = EncryptionUtil.obfuscate(raw, yesterday);
            PasskeyLogger.logEncryptionEvent(playerUsername, playerUUID, true,
                    "ENCRYPT", "Using yesterday's obfuscation key (midnight grace window)");
        } else {
            obfuscatedForClient = obfuscated;
        }

        PasskeyLogger.logGeneration(playerUsername, playerUUID);

        String encrypted = EncryptionUtil.encrypt(obfuscatedForClient, validatedPasskey);
        if (encrypted == null || encrypted.isEmpty())
            throw new IllegalStateException("Failed to encrypt checksum data");

        PasskeyLogger.logEncryptionEvent(playerUsername, playerUUID, true,
                "ENCRYPT_COMPLETE", "Encrypted CheckSum (length: " + encrypted.length() + ")");

        return encrypted;
    }

    /**
     * @deprecated Use {@link #generateServerChecksumInMemory} to avoid the shared-file race.
     *             Kept only for client-side single-user context.
     */
    @Deprecated
    public String readEncryptedCheckSum() throws IOException {
        Path f = configDir.resolve("CheckSum");
        if (!Files.exists(f)) throw new FileNotFoundException("CheckSum file not found");
        return Files.readString(f).trim();
    }

    /**
     * Compare two encrypted checksums by decrypting and deobfuscating both.
     * Uses constant-time comparison to prevent timing attacks.
     * FIX #8: No passkey values passed to PasskeyLogger.
     */
    public boolean compareCheckSums(String encryptedCheckSum1, String encryptedCheckSum2,
                                    String passkey, String playerUUID, String playerUsername) {
        if (encryptedCheckSum1 == null || encryptedCheckSum2 == null)
            throw new IllegalArgumentException("Checksums cannot be null for comparison");
        if (passkey == null || passkey.trim().isEmpty())
            throw new IllegalArgumentException("Passkey cannot be null or empty for comparison");

        try {
            String d1 = EncryptionUtil.decryptAndDeobfuscate(encryptedCheckSum1, passkey);
            String d2 = EncryptionUtil.decryptAndDeobfuscate(encryptedCheckSum2, passkey);
            boolean match = constantTimeEquals(d1, d2);

            PasskeyLogger.logEncryptionEvent(playerUsername, playerUUID, match,
                    "DECRYPT_COMPARE", "Decrypted and compared checksums - " + (match ? "MATCH" : "MISMATCH"));
            return match;
        } catch (Exception e) {
            PasskeyLogger.logEncryptionEvent(playerUsername, playerUUID, false,
                    "DECRYPT_COMPARE", "Failed to decrypt checksums: " + e.getMessage());
            return false;
        }
    }
}
