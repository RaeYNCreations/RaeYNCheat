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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Async logger for passkey and checksum validation events.
 *
 * FIX #8: Passkey values are never included in log entries. The original implementation
 * logged masked passkeys in every entry, and logValidationFailure logged BOTH expected and
 * received passkeys. Since the key material lives in the JAR (protected by ProGuard), any
 * partial exposure in a log file unnecessarily reduces the bar for reverse engineering.
 * Event type, player identity, and reason are sufficient for a useful audit trail.
 *
 * Queue overflow tracking: dropped entries are counted and a summary written to the log
 * when the queue drains, rather than silently losing entries.
 */
public class PasskeyLogger {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static volatile Path logFile;
    private static final BlockingQueue<String> logQueue = new LinkedBlockingQueue<>(1000);
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static volatile Thread loggerThread;
    private static final long MAX_LOG_SIZE = 10 * 1024 * 1024; // 10 MB
    private static final AtomicLong droppedCount = new AtomicLong(0);

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    public static void initialize(Path logsDir) {
        try {
            Files.createDirectories(logsDir);
            logFile = logsDir.resolve("cheat.log");
            if (!Files.exists(logFile)) writeHeaderSync();
            startLoggerThread();
            RaeYNCheat.LOGGER.info("PasskeyLogger initialized. Logging to: {}", logFile.toAbsolutePath());
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Failed to initialize PasskeyLogger", e);
        }
    }

    private static void startLoggerThread() {
        if (running.compareAndSet(false, true)) {
            loggerThread = new Thread(() -> {
                PrintWriter writer = null;
                try {
                    writer = new PrintWriter(new BufferedWriter(new FileWriter(logFile.toFile(), true)));
                    while (running.get() || !logQueue.isEmpty()) {
                        String message = logQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (message != null) {
                            long dropped = droppedCount.getAndSet(0);
                            if (dropped > 0) { writer.print(buildDropSummary(dropped)); writer.flush(); }
                            writer.print(message);
                            writer.flush();
                            try {
                                if (Files.size(logFile) > MAX_LOG_SIZE) {
                                    writer.close(); writer = null;
                                    rotateLog();
                                    writer = new PrintWriter(new BufferedWriter(new FileWriter(logFile.toFile(), true)));
                                }
                            } catch (IOException ioEx) {
                                RaeYNCheat.LOGGER.error("Error checking log file size", ioEx);
                            }
                        }
                    }
                    long remaining = droppedCount.getAndSet(0);
                    if (remaining > 0 && writer != null) { writer.print(buildDropSummary(remaining)); writer.flush(); }
                } catch (Exception e) {
                    RaeYNCheat.LOGGER.error("Error in PasskeyLogger thread", e);
                } finally {
                    if (writer != null) {
                        try { writer.close(); } catch (Exception e) {
                            RaeYNCheat.LOGGER.debug("Error closing PasskeyLogger writer", e);
                        }
                    }
                }
            }, "PasskeyLogger-Async");
            loggerThread.setDaemon(true);
            loggerThread.start();
        }
    }

    public static void shutdown() {
        running.set(false);
        if (loggerThread != null) {
            try {
                long start = System.currentTimeMillis();
                while (!logQueue.isEmpty() && System.currentTimeMillis() - start < 10000)
                    Thread.sleep(100);
                loggerThread.join(5000);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static void rotateLog() {
        try {
            // Use a datetime string so two rotations in the same millisecond don't clobber each other.
            String timestamp = LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
            Files.move(logFile, logFile.getParent().resolve("cheat.log." + timestamp));
        } catch (IOException e) { RaeYNCheat.LOGGER.error("Failed to rotate log file", e); }
    }

    private static void writeHeaderSync() {
        try (PrintWriter w = new PrintWriter(new BufferedWriter(new FileWriter(logFile.toFile(), true)))) {
            w.println("================================================================================");
            w.println("RaeYNCheat Passkey Event Log");
            w.println("Log Started: " + LocalDateTime.now().format(TIMESTAMP_FORMAT));
            w.println("================================================================================");
            w.println();
        } catch (IOException e) { RaeYNCheat.LOGGER.error("Failed to write log header", e); }
    }

    private static String buildDropSummary(long count) {
        return "--------------------------------------------------------------------------------\n"
                + "[" + LocalDateTime.now().format(TIMESTAMP_FORMAT) + "] QUEUE_OVERFLOW - WARNING\n"
                + "Details: " + count + " log entry/entries were dropped due to queue overflow.\n\n";
    }

    // ---------------------------------------------------------------------------
    // Public log methods — no passkey values (FIX #8)
    // ---------------------------------------------------------------------------

    public static void logGeneration(String playerUsername, String playerUUID) {
        logEvent("GENERATION", playerUsername, playerUUID, true, null, "Passkey generated for player");
    }

    public static void logValidationSuccess(String playerUsername, String playerUUID) {
        logEvent("VALIDATION", playerUsername, playerUUID, true, null, "Validation successful");
    }

    public static void logValidationSuccess(String playerUsername, String playerUUID, String details) {
        logEvent("VALIDATION", playerUsername, playerUUID, true, null, details);
    }

    public static void logValidationFailure(String playerUsername, String playerUUID, String reason) {
        logEvent("VALIDATION", playerUsername, playerUUID, false, reason, reason);
    }

    public static void logManualViolation(String playerUsername, String playerUUID,
                                           String adminUsername, int violationCount, String punishmentType) {
        String details = String.format(
                "Manual violation by admin '%s'. Total violations: %d. Punishment: %s",
                adminUsername, violationCount, punishmentType);
        logEvent("MANUAL_VIOLATION", playerUsername, playerUUID, false, "Admin triggered", details);
    }

    public static void logEncryptionEvent(String playerUsername, String playerUUID,
                                           boolean success, String operation, String details) {
        logEvent("ENCRYPTION_" + operation.toUpperCase(), playerUsername, playerUUID,
                success, success ? null : "Encryption/Decryption failed", details);
    }

    public static void logError(String playerUsername, String playerUUID,
                                 String errorType, String errorMessage, Exception e) {
        logEvent("ERROR", playerUsername, playerUUID, false, errorType,
                "Type: " + errorType + " | Message: " + errorMessage
                        + " | Exception: " + (e != null ? e.getMessage() : "None"));
    }

    public static void logWarning(String playerUsername, String playerUUID,
                                   String warningType, String warningMessage) {
        logEvent("WARNING", playerUsername, playerUUID, true, null,
                "Type: " + warningType + " | " + warningMessage);
    }

    /**
     * Log a geolocation event for a player connection.
     * Called by GeoIpLogger after async lookup completes.
     * Writes to cheat.log with full geo detail. If suspicious (proxy/datacenter),
     * marks the entry prominently so admins notice it easily.
     */
    public static void logGeoEvent(String playerUsername, String playerUUID,
                                    String ip, String geoLine, boolean suspicious) {
        if (logFile == null || !running.get()) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("--------------------------------------------------------------------------------\n");
            sb.append("[").append(LocalDateTime.now().format(TIMESTAMP_FORMAT)).append("] ")
              .append("CONNECTION_GEO");
            if (suspicious) sb.append(" *** SUSPICIOUS ***");
            sb.append("\n");
            sb.append("Player: ").append(playerUsername != null ? playerUsername : "Unknown")
              .append(" (UUID: ").append(playerUUID != null ? playerUUID : "Unknown").append(")\n");
            sb.append(geoLine).append("\n");
            if (suspicious)
                sb.append("!!! PROXY/DATACENTER IP DETECTED — review this connection !!!\n");
            sb.append("\n");
            offerOrDrop(sb.toString());
        } catch (Exception e) { RaeYNCheat.LOGGER.error("Failed to queue geo log entry", e); }
    }

    public static void logSessionSeparator(String message) {
        if (logFile == null || !running.get()) return;
        try {
            offerOrDrop("\n================================================================================\n"
                    + message + "\nTimestamp: " + LocalDateTime.now().format(TIMESTAMP_FORMAT) + "\n"
                    + "================================================================================\n\n");
        } catch (Exception e) { RaeYNCheat.LOGGER.error("Failed to log session separator", e); }
    }

    // ---------------------------------------------------------------------------
    // Internal
    // ---------------------------------------------------------------------------

    private static void logEvent(String eventType, String playerUsername, String playerUUID,
                                  boolean success, String failureReason, String details) {
        if (logFile == null || !running.get()) {
            RaeYNCheat.LOGGER.warn("PasskeyLogger not running. Skipping entry for {}", playerUsername);
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("--------------------------------------------------------------------------------\n");
            sb.append("[").append(LocalDateTime.now().format(TIMESTAMP_FORMAT)).append("] ")
              .append(eventType).append(" - ").append(success ? "SUCCESS" : "FAILURE").append("\n");
            sb.append("Player: ").append(playerUsername != null ? playerUsername : "Unknown")
              .append(" (UUID: ").append(playerUUID != null ? playerUUID : "Unknown").append(")\n");
            if (!success && failureReason != null)
                sb.append("Failure Reason: ").append(failureReason).append("\n");
            if (details != null && !details.isEmpty())
                sb.append("Details: ").append(details).append("\n");
            sb.append("\n");
            offerOrDrop(sb.toString());
        } catch (Exception e) { RaeYNCheat.LOGGER.error("Failed to queue log entry", e); }
    }

    private static void offerOrDrop(String message) {
        if (!logQueue.offer(message)) {
            if (droppedCount.incrementAndGet() == 1)
                RaeYNCheat.LOGGER.warn("PasskeyLogger queue full - entries being dropped.");
        }
    }
}
