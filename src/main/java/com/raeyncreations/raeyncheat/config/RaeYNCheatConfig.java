package com.raeyncreations.raeyncheat.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.raeyncreations.raeyncheat.RaeYNCheat;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class RaeYNCheatConfig {

    // ---------------------------------------------------------------------------
    // Checksum punishment
    // ---------------------------------------------------------------------------

    public boolean enablePunishmentSystem = true;
    public List<Integer> punishmentSteps  = new CopyOnWriteArrayList<>(createDefaultPunishmentSteps());

    // ---------------------------------------------------------------------------
    // Passkey punishment
    // ---------------------------------------------------------------------------

    public boolean enablePasskeyPunishmentSystem = true;
    public List<Integer> passkeyPunishmentSteps  = new CopyOnWriteArrayList<>(createDefaultPasskeyPunishmentSteps());

    // ---------------------------------------------------------------------------
    // Environment punishment
    // ---------------------------------------------------------------------------

    public List<Integer> envPunishmentSteps = new CopyOnWriteArrayList<>(createDefaultEnvPunishmentSteps());

    // ---------------------------------------------------------------------------
    // Negotiation punishment
    // ---------------------------------------------------------------------------

    public List<Integer> negotiationPunishmentSteps =
            new CopyOnWriteArrayList<>(createDefaultNegotiationPunishmentSteps());

    // ---------------------------------------------------------------------------
    // Periodic re-validation
    // ---------------------------------------------------------------------------

    public int periodicRevalidationSeconds = 300;

    // ---------------------------------------------------------------------------
    // Environment check policy
    // ---------------------------------------------------------------------------

    public boolean enforceJvmArgCheck      = true;
    public boolean enforceExtraJarCheck    = true;
    public boolean enforceGhostModCheck    = true;
    public boolean enforceClassLoaderCheck = false;

    // ---------------------------------------------------------------------------
    // FIX #11: JVM arg whitelist in config
    //
    // Admins can add launcher-specific JVM args here to suppress false positives
    // without requiring a code rebuild or new JAR release.
    // These are checked as prefix-matches, same as the hardcoded whitelist in
    // EnvironmentScanner. Entries here are additive — they extend, not replace,
    // the hardcoded list.
    // ---------------------------------------------------------------------------

    public List<String> extraJvmArgWhitelist = new CopyOnWriteArrayList<>();

    // ---------------------------------------------------------------------------
    // DDoS / rate-limiting — FIX (DDoS)
    // ---------------------------------------------------------------------------

    /** Master switch. Set false to disable all rate-limiting (not recommended). */
    public boolean enableDdosProtection = true;

    /**
     * IPs in this list are always admitted even when the circuit breaker is open.
     * Add your own IP / admin IPs here so you can never accidentally lock yourself out.
     */
    public List<String> ddosWhitelistedIps = new CopyOnWriteArrayList<>();

    // ── Per-IP token bucket ───────────────────────────────────────────────────

    /** Max connection attempts from one IP before rate-limiting kicks in (burst budget). */
    public int ddosIpBurstTokens = 8;
    /** Tokens per second restored to an IP's bucket. 0.5 = one new attempt every 2 seconds. */
    public double ddosIpRefillRatePerSecond = 0.5;

    // ── Sliding window (dual-algorithm, runs alongside token bucket) ──────────

    /** Length of the sliding window in milliseconds. */
    public long ddosSlidingWindowMs = 10_000; // 10 seconds
    /** Max connections allowed within the sliding window before blocking. */
    public int ddosSlidingWindowMaxConnections = 12;

    // ── Per-/24 subnet ────────────────────────────────────────────────────────

    /** Burst budget for an entire /24 subnet (same first 3 octets). */
    public int ddosSubnetBurstTokens = 20;
    /** Refill rate for subnet bucket (tokens/sec). */
    public double ddosSubnetRefillRatePerSecond = 1.0;
    /** Max subnet connections in the sliding window. */
    public int ddosSubnetWindowMax = 30;

    // ── Per-UUID SyncPacket ───────────────────────────────────────────────────

    /** Max SyncPackets a single UUID may send in a burst (login + revalidation bursts). */
    public int ddosSyncBurstTokens = 3;
    /** SyncPacket token refill rate (tokens/sec). */
    public double ddosSyncRefillRatePerSecond = 0.2;

    // ── Escalation tiers ─────────────────────────────────────────────────────

    /** Blocks before escalating to Tier 2 (timed hard-block). */
    public int ddosTier2Threshold = 15;
    /** Initial Tier 2 hard-block duration in seconds. */
    public int ddosTier2BlockSeconds = 300;
    /** Exponential backoff multiplier per escalation. 2.0 = doubles each time. */
    public double ddosTier2BackoffMultiplier = 2.0;
    /** How many blocks between each backoff escalation level. */
    public int ddosTier2EscalationStep = 5;
    /** Maximum Tier 2 hard-block duration in milliseconds (caps exponential growth). */
    public long ddosTier2MaxBlockMs = 3_600_000L; // 1 hour

    /** Total blocks before applying a permanent Minecraft IP ban (Tier 3). */
    public int ddosTier3Threshold = 100;

    // ── Global circuit breaker ────────────────────────────────────────────────

    /** Server-wide connections per second that triggers the circuit breaker. */
    public int globalFloodThresholdPerSecond = 50;
    /** How long (ms) the circuit breaker stays open before auto-resetting. */
    public long globalCircuitBreakerCooldownMs = 30_000; // 30 seconds

    // ── Bot detection ─────────────────────────────────────────────────────────

    /** Master switch for bot detection. */
    public boolean enableBotDetection = true;

    /** Bot score at which a warning is logged but connection is allowed. */
    public int botWarnScore = 20;
    /** Bot score at which shadow-ban mode activates (connection accepted, SyncPacket dropped). */
    public int botShadowBanScore = 45;
    /** Bot score at which the connection is hard-blocked (handed to rate limiter). */
    public int botHardBlockScore = 70;

    /**
     * Connection timing variance threshold in milliseconds.
     * Standard deviation below this value = mechanically regular = bot.
     * Humans typically vary by >500ms; bots often <50ms.
     */
    public double botTimingVarianceThresholdMs = 100.0;

    /** How many different account names from one IP within the tracking window = bot flag. */
    public int botAccountCycleThreshold = 3;

    /** Connections per minute from one IP that triggers the velocity signal. */
    public double botConnectionsPerMinuteThreshold = 8.0;

    // ── Geo IP logging ────────────────────────────────────────────────────────

    /** Enable async geolocation lookup on player login. Requires internet access. */
    public boolean enableGeoIpLogging = true;

    // ── Player authentication (password + 2FA) ────────────────────────────────

    /**
     * Server name shown in authenticator apps and setup messages.
     * Example: "MyMinecraftServer" → shows as "MyMinecraftServer:PlayerName" in the app.
     */
    public String authServerLabel = "RaeYNServer";

    /**
     * Master key used to encrypt TOTP secrets in the database.
     * Change this to a random string before first launch. Treat like a password.
     * WARNING: changing this after players have enrolled in 2FA will invalidate
     * all existing TOTP secrets — users will need to re-enroll.
     */
    public String authDbEncryptionKey = "ChangeThisToARandomStringBeforeLaunch";

    // ── SQLite / MySQL database backend ──────────────────────────────────────

    /**
     * Set true to use a remote MySQL database instead of the local SQLite file.
     * Requires the MySQL JDBC driver to be available on the classpath.
     * Add to build.gradle: jarJar('com.mysql:mysql-connector-j:8.2.0')
     */
    public boolean mysqlEnabled = false;

    /** MySQL host (used when mysqlEnabled = true). */
    public String mysqlHost = "localhost";

    /** MySQL port (default: 3306). */
    public int mysqlPort = 3306;

    /** MySQL database name. */
    public String mysqlDatabase = "raeyncheat";

    /** MySQL username. */
    public String mysqlUsername = "raeyncheat";

    /** MySQL password. */
    public String mysqlPassword = "changeme";

    /** Require SSL for MySQL connection (recommended for remote servers). */
    public boolean mysqlUseSsl = false;

    // ---------------------------------------------------------------------------
    // Violation persistence
    // ---------------------------------------------------------------------------

    public int violationExpiryDays = 30;

    public Map<String, ViolationRecord> checksumViolationRecords     = new ConcurrentHashMap<>();
    public Map<String, ViolationRecord> passkeyViolationRecords      = new ConcurrentHashMap<>();
    public Map<String, ViolationRecord> envViolationRecords          = new ConcurrentHashMap<>();
    public Map<String, ViolationRecord> negotiationViolationRecords  = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------------------
    // FIX #9: Dirty-flag for debounced config saves
    // ---------------------------------------------------------------------------

    private transient volatile boolean dirty = false;
    private transient volatile long    lastSaveAttempt = 0;
    private static final long SAVE_DEBOUNCE_MS = 5_000; // 5 seconds

    public void markDirty() { this.dirty = true; }

    /**
     * Save if dirty and at least SAVE_DEBOUNCE_MS have elapsed since the last save.
     * Called from RaeYNCheat's server tick rather than on every violation.
     */
    public void saveIfDirty(Path configPath) {
        if (!dirty) return;
        long now = System.currentTimeMillis();
        if (now - lastSaveAttempt < SAVE_DEBOUNCE_MS) return;
        lastSaveAttempt = now;
        // Clear dirty AFTER save, not before — if save() throws, markDirty() can be called
        // again and the next tick will retry. Clearing before save would lose the record.
        save(configPath);
        dirty = false;
    }

    // ---------------------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------------------

    private static final int  INVALID_STEP_INDEX = -999;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ---------------------------------------------------------------------------
    // Load / Save
    // ---------------------------------------------------------------------------

    public static RaeYNCheatConfig load(Path configPath) {
        try {
            if (Files.exists(configPath)) {
                try (Reader reader = new FileReader(configPath.toFile())) {
                    RaeYNCheatConfig config = GSON.fromJson(reader, RaeYNCheatConfig.class);
                    if (config != null) {
                        config.postLoad();
                        config.purgeExpiredViolations();
                        return config;
                    }
                }
            }
        } catch (Exception e) {
            RaeYNCheat.LOGGER.warn("Failed to load config from {}, using defaults: {}", configPath, e.getMessage());
        }
        RaeYNCheatConfig defaults = new RaeYNCheatConfig();
        defaults.save(configPath);
        return defaults;
    }

    public synchronized void save(Path configPath) {
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = new FileWriter(configPath.toFile())) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            RaeYNCheat.LOGGER.error("Failed to save config to {}: {}", configPath, e.getMessage());
        }
    }

    private void postLoad() {
        if (!(punishmentSteps instanceof CopyOnWriteArrayList))
            punishmentSteps = new CopyOnWriteArrayList<>(
                    punishmentSteps != null ? punishmentSteps : createDefaultPunishmentSteps());
        if (!(passkeyPunishmentSteps instanceof CopyOnWriteArrayList))
            passkeyPunishmentSteps = new CopyOnWriteArrayList<>(
                    passkeyPunishmentSteps != null ? passkeyPunishmentSteps : createDefaultPasskeyPunishmentSteps());
        if (!(envPunishmentSteps instanceof CopyOnWriteArrayList))
            envPunishmentSteps = new CopyOnWriteArrayList<>(
                    envPunishmentSteps != null ? envPunishmentSteps : createDefaultEnvPunishmentSteps());
        if (!(negotiationPunishmentSteps instanceof CopyOnWriteArrayList))
            negotiationPunishmentSteps = new CopyOnWriteArrayList<>(
                    negotiationPunishmentSteps != null ? negotiationPunishmentSteps : createDefaultNegotiationPunishmentSteps());
        if (!(extraJvmArgWhitelist instanceof CopyOnWriteArrayList))
            extraJvmArgWhitelist = new CopyOnWriteArrayList<>(
                    extraJvmArgWhitelist != null ? extraJvmArgWhitelist : new ArrayList<>());
        if (!(ddosWhitelistedIps instanceof CopyOnWriteArrayList))
            ddosWhitelistedIps = new CopyOnWriteArrayList<>(
                    ddosWhitelistedIps != null ? ddosWhitelistedIps : new ArrayList<>());

        if (checksumViolationRecords    == null) checksumViolationRecords    = new ConcurrentHashMap<>();
        if (passkeyViolationRecords     == null) passkeyViolationRecords     = new ConcurrentHashMap<>();
        if (envViolationRecords         == null) envViolationRecords         = new ConcurrentHashMap<>();
        if (negotiationViolationRecords == null) negotiationViolationRecords = new ConcurrentHashMap<>();

        if (periodicRevalidationSeconds > 0 && periodicRevalidationSeconds < 60)
            periodicRevalidationSeconds = 60;

        // Clamp DDoS values to sane minimums.
        if (ddosIpBurstTokens          < 1)   ddosIpBurstTokens           = 1;
        if (ddosSyncBurstTokens        < 1)   ddosSyncBurstTokens         = 1;
        if (ddosSubnetBurstTokens      < 1)   ddosSubnetBurstTokens       = 1;
        if (ddosTier2Threshold         < 5)   ddosTier2Threshold          = 5;
        if (ddosTier2BlockSeconds      < 30)  ddosTier2BlockSeconds        = 30;
        if (ddosTier2EscalationStep    < 1)   ddosTier2EscalationStep     = 1;
        if (ddosTier2MaxBlockMs        < 60_000) ddosTier2MaxBlockMs      = 60_000;
        if (ddosTier2BackoffMultiplier < 1.0) ddosTier2BackoffMultiplier  = 1.0;
        if (ddosTier3Threshold         < 20)  ddosTier3Threshold          = 20;
        if (ddosSlidingWindowMs        < 1000) ddosSlidingWindowMs        = 1000;
        if (ddosSlidingWindowMaxConnections < 2) ddosSlidingWindowMaxConnections = 2;
        if (ddosSubnetWindowMax        < 2)   ddosSubnetWindowMax         = 2;
        if (globalFloodThresholdPerSecond < 5) globalFloodThresholdPerSecond = 5;
        if (globalCircuitBreakerCooldownMs < 5000) globalCircuitBreakerCooldownMs = 5000;

        // Bot detection clamps
        if (botWarnScore        < 0)   botWarnScore       = 0;
        if (botShadowBanScore   < 10)  botShadowBanScore  = 10;
        if (botHardBlockScore   < 20)  botHardBlockScore  = 20;
        if (botTimingVarianceThresholdMs < 10) botTimingVarianceThresholdMs = 10;
        if (botAccountCycleThreshold < 2) botAccountCycleThreshold = 2;
    }

    // ---------------------------------------------------------------------------
    // Violation records
    // ---------------------------------------------------------------------------

    public boolean purgeExpiredViolations() {
        if (violationExpiryDays <= 0) return false;
        long cutoff = System.currentTimeMillis() - (long) violationExpiryDays * 86400_000L;
        boolean purged = false;
        purged |= checksumViolationRecords.entrySet().removeIf(    e -> e.getValue().lastViolationTimestamp < cutoff);
        purged |= passkeyViolationRecords.entrySet().removeIf(     e -> e.getValue().lastViolationTimestamp < cutoff);
        purged |= envViolationRecords.entrySet().removeIf(         e -> e.getValue().lastViolationTimestamp < cutoff);
        purged |= negotiationViolationRecords.entrySet().removeIf( e -> e.getValue().lastViolationTimestamp < cutoff);
        if (purged) RaeYNCheat.LOGGER.info("Purged expired violation records.");
        return purged;
    }

    public int recordChecksumViolation(UUID uuid)     { return recordIn(checksumViolationRecords,     uuid); }
    public int recordPasskeyViolation(UUID uuid)      { return recordIn(passkeyViolationRecords,      uuid); }
    public int recordEnvViolation(UUID uuid)          { return recordIn(envViolationRecords,           uuid); }
    public int recordNegotiationViolation(UUID uuid)  { return recordIn(negotiationViolationRecords,  uuid); }

    private int recordIn(Map<String, ViolationRecord> map, UUID uuid) {
        ViolationRecord rec = map.computeIfAbsent(uuid.toString(), k -> new ViolationRecord());
        // Both ++ and the return read the same field on the server main thread — consistent.
        int newCount = ++rec.count;
        rec.lastViolationTimestamp = System.currentTimeMillis();
        return newCount;
    }

    public int getChecksumViolationCount(UUID uuid)     { return countFrom(checksumViolationRecords,     uuid); }
    public int getPasskeyViolationCount(UUID uuid)      { return countFrom(passkeyViolationRecords,      uuid); }
    public int getEnvViolationCount(UUID uuid)          { return countFrom(envViolationRecords,           uuid); }
    public int getNegotiationViolationCount(UUID uuid)  { return countFrom(negotiationViolationRecords,  uuid); }

    private int countFrom(Map<String, ViolationRecord> map, UUID uuid) {
        ViolationRecord r = map.get(uuid.toString());
        return r != null ? r.count : 0;
    }

    public void clearViolations(UUID uuid) {
        String key = uuid.toString();
        checksumViolationRecords.remove(key);
        passkeyViolationRecords.remove(key);
        envViolationRecords.remove(key);
        negotiationViolationRecords.remove(key);
    }

    // ---------------------------------------------------------------------------
    // Punishment step helpers
    // ---------------------------------------------------------------------------

    public int getPunishmentDuration(int violations)            { return duration(punishmentSteps,            violations); }
    public int getPasskeyPunishmentDuration(int violations)     { return duration(passkeyPunishmentSteps,     violations); }
    public int getEnvPunishmentDuration(int violations)         { return duration(envPunishmentSteps,         violations); }
    public int getNegotiationPunishmentDuration(int violations) { return duration(negotiationPunishmentSteps, violations); }

    private int duration(List<Integer> steps, int violations) {
        if (steps == null || steps.isEmpty()) return 0;
        int idx = Math.min(violations - 1, steps.size() - 1);
        return idx >= 0 ? steps.get(idx) : 0;
    }

    public boolean setChecksumPunishmentStep(int i, int d)     { return setStep(punishmentSteps,            i, d); }
    public boolean setPasskeyPunishmentStep(int i, int d)      { return setStep(passkeyPunishmentSteps,     i, d); }
    public boolean setEnvPunishmentStep(int i, int d)          { return setStep(envPunishmentSteps,         i, d); }
    public boolean setNegotiationPunishmentStep(int i, int d)  { return setStep(negotiationPunishmentSteps, i, d); }

    private boolean setStep(List<Integer> steps, int index, int duration) {
        if (index < 0 || index >= 30) return false;
        while (steps.size() <= index) steps.add(0);
        steps.set(index, duration);
        return true;
    }

    public String getChecksumPunishmentStepsString()     { return format(punishmentSteps); }
    public String getPasskeyPunishmentStepsString()      { return format(passkeyPunishmentSteps); }
    public String getEnvPunishmentStepsString()          { return format(envPunishmentSteps); }
    public String getNegotiationPunishmentStepsString()  { return format(negotiationPunishmentSteps); }

    private String format(List<Integer> steps) {
        if (steps == null || steps.isEmpty()) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) sb.append(", ");
            int d = steps.get(i);
            sb.append("[").append(i).append("]=").append(d == -1 ? "PERM" : d + "s");
        }
        return sb.toString();
    }

    public int getChecksumPunishmentStep(int i)     { return getStep(punishmentSteps,            i); }
    public int getPasskeyPunishmentStep(int i)      { return getStep(passkeyPunishmentSteps,     i); }
    public int getEnvPunishmentStep(int i)          { return getStep(envPunishmentSteps,         i); }
    public int getNegotiationPunishmentStep(int i)  { return getStep(negotiationPunishmentSteps, i); }

    private int getStep(List<Integer> steps, int index) {
        if (steps == null || index < 0 || index >= steps.size()) return INVALID_STEP_INDEX;
        return steps.get(index);
    }

    public static boolean isInvalidStepIndex(int value) { return value == INVALID_STEP_INDEX; }

    // ---------------------------------------------------------------------------
    // Default punishment ladders
    // ---------------------------------------------------------------------------

    private static List<Integer> createDefaultPunishmentSteps()    { return Arrays.asList(0, 3600, 86400, -1); }
    private static List<Integer> createDefaultPasskeyPunishmentSteps() { return Arrays.asList(0, 3600, 86400, -1); }
    private static List<Integer> createDefaultEnvPunishmentSteps()     { return Arrays.asList(0, 0, 3600, -1); }
    private static List<Integer> createDefaultNegotiationPunishmentSteps() { return Arrays.asList(0, 0, 86400, -1); }

    // ---------------------------------------------------------------------------
    // ViolationRecord inner class
    // ---------------------------------------------------------------------------

    public static class ViolationRecord {
        public int  count;                    // accessed only from server thread — no sync needed
        public long lastViolationTimestamp;   // epoch ms of last violation
        public ViolationRecord() {}
    }
}
