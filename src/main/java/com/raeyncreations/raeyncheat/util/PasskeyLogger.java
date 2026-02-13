package com.raeyncreations.raeyncheat.util;

import com.raeyncreations.raeyncheat.RaeYNCheat;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Logger specifically for passkey-related events
 * Logs all passkey generation, validation attempts, successes, and failures
 */
public class PasskeyLogger {
    
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static Path logFile;
    private static final Object LOCK = new Object();
    
    /**
     * Initialize the passkey logger with the logs directory
     */
    public static void initialize(Path logsDir) {
        try {
            // Create logs directory if it doesn't exist
            Files.createDirectories(logsDir);
            
            // Set up log file path
            logFile = logsDir.resolve("cheat.log");
            
            // Create file if it doesn't exist and write header
            if (!Files.exists(logFile)) {
                writeHeader();
            }
            
            RaeYNCheat.LOGGER.info("PasskeyLogger initialized. Logging to: " + logFile.toAbsolutePath());
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Failed to initialize PasskeyLogger", e);
        }
    }
    
    /**
     * Write header to new log file
     */
    private static void writeHeader() {
        try {
            synchronized (LOCK) {
                try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(logFile.toFile(), true)))) {
                    writer.println("================================================================================");
                    writer.println("RaeYNCheat Passkey Event Log");
                    writer.println("Log Started: " + LocalDateTime.now().format(TIMESTAMP_FORMAT));
                    writer.println("================================================================================");
                    writer.println();
                }
            }
        } catch (IOException e) {
            RaeYNCheat.LOGGER.error("Failed to write log header", e);
        }
    }
    
    /**
     * Log a passkey generation event
     */
    public static void logGeneration(String playerUsername, String playerUUID, String passkey) {
        logEvent("GENERATION", playerUsername, playerUUID, passkey, true, null, 
            "Passkey generated for player");
    }
    
    /**
     * Log a successful passkey validation
     */
    public static void logValidationSuccess(String playerUsername, String playerUUID, String clientPasskey, String serverPasskey) {
        logEvent("VALIDATION", playerUsername, playerUUID, clientPasskey, true, null,
            "Passkey validation successful. Client and server passkeys match.");
    }
    
    /**
     * Log a failed passkey validation
     */
    public static void logValidationFailure(String playerUsername, String playerUUID, String clientPasskey, 
                                           String expectedPasskey, String reason) {
        String details = String.format("Passkey validation failed. Expected: %s | Received: %s | Reason: %s",
            maskPasskey(expectedPasskey), maskPasskey(clientPasskey), reason);
        logEvent("VALIDATION", playerUsername, playerUUID, clientPasskey, false, reason, details);
    }
    
    /**
     * Log a manual passkey violation triggered by admin command
     */
    public static void logManualViolation(String playerUsername, String playerUUID, String adminUsername, 
                                         int violationCount, String punishmentType) {
        String details = String.format("Manual passkey violation triggered by admin '%s'. Total violations: %d. Punishment: %s",
            adminUsername, violationCount, punishmentType);
        logEvent("MANUAL_VIOLATION", playerUsername, playerUUID, "N/A", false, 
            "Admin triggered", details);
    }
    
    /**
     * Log a passkey encryption/decryption event
     */
    public static void logEncryptionEvent(String playerUsername, String playerUUID, String passkey, 
                                         boolean success, String operation, String details) {
        logEvent("ENCRYPTION_" + operation.toUpperCase(), playerUsername, playerUUID, passkey, 
            success, success ? null : "Encryption/Decryption failed", details);
    }
    
    /**
     * Log a passkey-related error
     */
    public static void logError(String playerUsername, String playerUUID, String passkey, 
                               String errorType, String errorMessage, Exception e) {
        String details = String.format("Error Type: %s | Message: %s | Exception: %s",
            errorType, errorMessage, e != null ? e.getMessage() : "None");
        logEvent("ERROR", playerUsername, playerUUID, passkey, false, errorType, details);
    }
    
    /**
     * Core logging method
     */
    private static void logEvent(String eventType, String playerUsername, String playerUUID, 
                                 String passkey, boolean success, String failureReason, String details) {
        if (logFile == null) {
            RaeYNCheat.LOGGER.warn("PasskeyLogger not initialized. Skipping log entry.");
            return;
        }
        
        try {
            synchronized (LOCK) {
                try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(logFile.toFile(), true)))) {
                    String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
                    String status = success ? "SUCCESS" : "FAILURE";
                    
                    writer.println("--------------------------------------------------------------------------------");
                    writer.println("[" + timestamp + "] " + eventType + " - " + status);
                    writer.println("Player: " + (playerUsername != null ? playerUsername : "Unknown") + 
                                 " (UUID: " + (playerUUID != null ? playerUUID : "Unknown") + ")");
                    writer.println("Passkey: " + maskPasskey(passkey));
                    
                    if (!success && failureReason != null) {
                        writer.println("Failure Reason: " + failureReason);
                    }
                    
                    if (details != null && !details.isEmpty()) {
                        writer.println("Details: " + details);
                    }
                    
                    writer.println();
                }
            }
        } catch (IOException e) {
            RaeYNCheat.LOGGER.error("Failed to write to passkey log", e);
        }
    }
    
    /**
     * Mask sensitive parts of the passkey for logging
     * Shows first and last few characters, masks the middle
     */
    private static String maskPasskey(String passkey) {
        if (passkey == null || passkey.isEmpty()) {
            return "NULL";
        }
        
        if (passkey.length() <= 3) {
            // For very short passkeys, show only first char + asterisks
            return passkey.substring(0, 1) + "****";
        }
        
        if (passkey.length() <= 10) {
            // For short passkeys, show first 2 and last 1
            return passkey.substring(0, 2) + "****" + passkey.substring(passkey.length() - 1);
        }
        
        // For longer passkeys, show more context (first 5 and last 5)
        int prefixLen = 5;
        int suffixLen = 5;
        String prefix = passkey.substring(0, prefixLen);
        String suffix = passkey.substring(passkey.length() - suffixLen);
        return prefix + "***[" + (passkey.length() - prefixLen - suffixLen) + " chars]***" + suffix;
    }
    
    /**
     * Log session separator
     */
    public static void logSessionSeparator(String message) {
        if (logFile == null) {
            return;
        }
        
        try {
            synchronized (LOCK) {
                try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(logFile.toFile(), true)))) {
                    writer.println();
                    writer.println("================================================================================");
                    writer.println(message);
                    writer.println("Timestamp: " + LocalDateTime.now().format(TIMESTAMP_FORMAT));
                    writer.println("================================================================================");
                    writer.println();
                }
            }
        } catch (IOException e) {
            RaeYNCheat.LOGGER.error("Failed to write session separator", e);
        }
    }
}
