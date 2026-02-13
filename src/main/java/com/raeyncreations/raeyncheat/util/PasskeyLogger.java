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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Logger specifically for passkey-related events
 * Logs all passkey generation, validation attempts, successes, and failures
 * Uses async logging to prevent I/O bottlenecks on validation path
 */
public class PasskeyLogger {
    
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static volatile Path logFile;
    private static final BlockingQueue<String> logQueue = new LinkedBlockingQueue<>(1000);
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static volatile Thread loggerThread;
    private static final long MAX_LOG_SIZE = 10 * 1024 * 1024; // 10 MB
    
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
                writeHeaderSync();
            }
            
            // Start async logger thread
            startLoggerThread();
            
            RaeYNCheat.LOGGER.info("PasskeyLogger initialized. Logging to: " + logFile.toAbsolutePath());
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Failed to initialize PasskeyLogger", e);
        }
    }
    
    /**
     * Start the async logger thread
     */
    private static void startLoggerThread() {
        if (running.compareAndSet(false, true)) {
            loggerThread = new Thread(() -> {
                PrintWriter writer = null;
                try {
                    writer = new PrintWriter(new BufferedWriter(new FileWriter(logFile.toFile(), true)));
                    
                    while (running.get() || !logQueue.isEmpty()) {
                        String message = logQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (message != null) {
                            writer.print(message);
                            writer.flush();
                            
                            // Check for log rotation
                            if (Files.size(logFile) > MAX_LOG_SIZE) {
                                rotateLog(writer);
                                writer = new PrintWriter(new BufferedWriter(new FileWriter(logFile.toFile(), true)));
                            }
                        }
                    }
                } catch (Exception e) {
                    RaeYNCheat.LOGGER.error("Error in PasskeyLogger thread", e);
                } finally {
                    if (writer != null) {
                        writer.close();
                    }
                }
            }, "PasskeyLogger-Async");
            loggerThread.setDaemon(true);
            loggerThread.start();
        }
    }
    
    /**
     * Rotate log file when it exceeds max size
     */
    private static void rotateLog(PrintWriter currentWriter) {
        try {
            currentWriter.close();
            Path archived = logFile.getParent().resolve("cheat.log." + System.currentTimeMillis());
            Files.move(logFile, archived);
            RaeYNCheat.LOGGER.info("Rotated log file to: " + archived);
        } catch (IOException e) {
            RaeYNCheat.LOGGER.error("Failed to rotate log file", e);
        }
    }
    
    /**
     * Shutdown the logger (call on server stop)
     */
    public static void shutdown() {
        running.set(false);
        if (loggerThread != null) {
            try {
                loggerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Write header synchronously (used only during initialization)
     */
    private static void writeHeaderSync() {
        try {
            try (PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(logFile.toFile(), true)))) {
                writer.println("================================================================================");
                writer.println("RaeYNCheat Passkey Event Log");
                writer.println("Log Started: " + LocalDateTime.now().format(TIMESTAMP_FORMAT));
                writer.println("================================================================================");
                writer.println();
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
     * Log a passkey-related warning
     */
    public static void logWarning(String playerUsername, String playerUUID, String passkey, 
                                 String warningType, String warningMessage) {
        String details = String.format("Warning Type: %s | Message: %s", warningType, warningMessage);
        logEvent("WARNING", playerUsername, playerUUID, passkey, true, null, details);
    }
    
    /**
     * Core logging method - async version
     */
    private static void logEvent(String eventType, String playerUsername, String playerUUID, 
                                 String passkey, boolean success, String failureReason, String details) {
        if (logFile == null || !running.get()) {
            RaeYNCheat.LOGGER.warn("PasskeyLogger not initialized or not running. Skipping log entry.");
            return;
        }
        
        try {
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
            String status = success ? "SUCCESS" : "FAILURE";
            
            StringBuilder message = new StringBuilder();
            message.append("--------------------------------------------------------------------------------\n");
            message.append("[").append(timestamp).append("] ").append(eventType).append(" - ").append(status).append("\n");
            message.append("Player: ").append(playerUsername != null ? playerUsername : "Unknown")
                   .append(" (UUID: ").append(playerUUID != null ? playerUUID : "Unknown").append(")\n");
            message.append("Passkey: ").append(maskPasskey(passkey)).append("\n");
            
            if (!success && failureReason != null) {
                message.append("Failure Reason: ").append(failureReason).append("\n");
            }
            
            if (details != null && !details.isEmpty()) {
                message.append("Details: ").append(details).append("\n");
            }
            
            message.append("\n");
            
            // Queue the message for async writing
            if (!logQueue.offer(message.toString())) {
                RaeYNCheat.LOGGER.warn("PasskeyLogger queue full, dropping log entry");
            }
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Failed to queue passkey log entry", e);
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
        if (logFile == null || !running.get()) {
            return;
        }
        
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("\n");
            sb.append("================================================================================\n");
            sb.append(message).append("\n");
            sb.append("Timestamp: ").append(LocalDateTime.now().format(TIMESTAMP_FORMAT)).append("\n");
            sb.append("================================================================================\n");
            sb.append("\n");
            
            if (!logQueue.offer(sb.toString())) {
                RaeYNCheat.LOGGER.warn("PasskeyLogger queue full, dropping session separator");
            }
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Failed to log session separator", e);
        }
    }
}
