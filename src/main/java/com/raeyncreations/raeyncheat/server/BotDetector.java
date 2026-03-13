package com.raeyncreations.raeyncheat.server;

import com.raeyncreations.raeyncheat.RaeYNCheat;
import com.raeyncreations.raeyncheat.config.RaeYNCheatConfig;
import com.raeyncreations.raeyncheat.util.PasskeyLogger;

import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Bot and automated-attack detection for RaeYNCheat.
 *
 * Bots and automated scanners have behavioral fingerprints that humans don't:
 *   - They connect at mechanically precise intervals (±50ms variance instead of human ±2000ms)
 *   - They cycle through predictable username patterns (bot1, bot2, bot3 / abc123, abc124)
 *   - Multiple fake accounts originate from the same IP in rapid succession
 *   - They fail the SyncPacket challenge (no mod installed) every single time
 *   - They reconnect immediately after every kick with no human delay
 *
 * This class tracks all of these signals and combines them into a bot-confidence
 * score. Above a configurable threshold, the player is treated as a bot:
 *
 *   SHADOW-BAN mode: The connection is accepted (no error message) but:
 *     - Their SyncPacket is silently dropped (no validation, no violation)
 *     - They receive no kick message — they just appear to hang
 *     - This wastes the bot's time and prevents it from detecting our defenses
 *     - After SHADOW_BAN_MAX_CONNECTIONS silent accepts, escalate to real block
 *
 *   HARD BLOCK mode: IP is immediately handed to ConnectionRateLimiter for Tier 2+.
 *
 * Username pattern detection:
 *   - Sequential numeric suffixes (bot1, bot2 from same IP)
 *   - Pure random-hex usernames (common in UUID-based bot toolkits)
 *   - Usernames matching known bot toolkit patterns
 *   - Extremely short usernames (many bots use 1-3 char names)
 *
 * All detections are logged to cheat.log for admin review.
 */
public class BotDetector {

    // ---------------------------------------------------------------------------
    // Connection timing record — per IP
    // ---------------------------------------------------------------------------

    private static class TimingRecord {
        final List<Long> connectionTimestamps = Collections.synchronizedList(new ArrayList<>());
        final List<String> usernamesSeen      = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger shadowBanCount    = new AtomicInteger(0);
        final AtomicInteger totalAttempts     = new AtomicInteger(0);
        final AtomicLong firstSeen            = new AtomicLong(System.currentTimeMillis());
        final AtomicLong lastSeen             = new AtomicLong(System.currentTimeMillis());
        volatile boolean hardFlagged          = false;
    }

    // ---------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------

    private static final ConcurrentHashMap<String, TimingRecord> ipRecords = new ConcurrentHashMap<>();

    /** UUIDs that are in shadow-ban mode — their SyncPackets are silently dropped */
    private static final ConcurrentHashMap<String, Long> shadowBanned = new ConcurrentHashMap<>();

    private static final long SHADOW_BAN_DURATION_MS      = 10L * 60 * 1000; // 10 minutes
    private static final int  SHADOW_BAN_MAX_CONNECTIONS  = 5;  // After this, escalate to hard block
    private static final int  TIMING_WINDOW_SIZE          = 10; // Track last N connection times
    private static final long RECORD_TTL_MS               = 30L * 60 * 1000; // 30 min stale cleanup

    // ---------------------------------------------------------------------------
    // Username bot-pattern regexes
    // ---------------------------------------------------------------------------

    /** Pure hex strings — common in UUID-derived bot usernames */
    private static final Pattern HEX_NAME   = Pattern.compile("^[0-9a-fA-F]{8,16}$");
    /** Sequential: base + digits where base ≤ 6 chars */
    private static final Pattern SEQ_NAME   = Pattern.compile("^[a-zA-Z]{1,6}\\d{1,4}$");
    /** All digits — clearly not a real player name */
    private static final Pattern DIGIT_NAME = Pattern.compile("^\\d+$");
    /** Known bot toolkit prefixes/suffixes */
    private static final List<String> BOT_KEYWORDS = Arrays.asList(
            "bot", "scan", "probe", "test", "fake", "hack", "cheat", "exploit", "null", "void"
    );

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Evaluate whether a connecting player looks like a bot.
     * Called in PlayerConnectionHandler before any further processing.
     *
     * @return BotVerdict describing what action to take
     */
    public static BotVerdict evaluate(SocketAddress address, String username, String uuidStr) {
        RaeYNCheatConfig config = RaeYNCheat.getConfig();
        if (config == null || !config.enableBotDetection) return BotVerdict.ALLOW;

        String ip = ConnectionRateLimiter.extractIpPublic(address);
        if (ip == null) return BotVerdict.ALLOW;

        // Skip private/loopback IPs (LAN connections, local testing)
        if (GeoIpLogger.isPrivateOrLoopback(ip)) return BotVerdict.ALLOW;

        TimingRecord record = ipRecords.computeIfAbsent(ip, k -> new TimingRecord());
        record.lastSeen.set(System.currentTimeMillis());
        record.totalAttempts.incrementAndGet();
        record.connectionTimestamps.add(System.currentTimeMillis());
        synchronized (record.usernamesSeen) {
            if (!record.usernamesSeen.contains(username)) {
                record.usernamesSeen.add(username);
                // Cap at 50 entries — prevents unbounded growth from username-cycling bots.
                // At 50 unique names we've already triggered the cycling signal repeatedly.
                while (record.usernamesSeen.size() > 50) record.usernamesSeen.remove(0);
            }
        }

        // Trim timing window to last N entries
        synchronized (record.connectionTimestamps) {
            while (record.connectionTimestamps.size() > TIMING_WINDOW_SIZE)
                record.connectionTimestamps.remove(0);
        }

        int score = 0;
        List<String> reasons = new ArrayList<>();

        // ── Signal 1: Mechanical timing (low variance = bot) ──────────────────
        double timingVariance = calculateTimingVariance(record.connectionTimestamps);
        if (timingVariance >= 0 && timingVariance < config.botTimingVarianceThresholdMs
                && record.connectionTimestamps.size() >= 4) {
            score += 40;
            reasons.add("mechanical timing (variance=" + String.format("%.0f", timingVariance) + "ms)");
        }

        // ── Signal 2: Account cycling from same IP ─────────────────────────────
        int uniqueNames = countUniqueNamesRecently(record);
        if (uniqueNames >= config.botAccountCycleThreshold) {
            score += 35;
            reasons.add("account cycling (" + uniqueNames + " unique names from this IP)");
        }

        // ── Signal 3: Username pattern analysis ───────────────────────────────
        int nameScore = analyzeUsername(username);
        if (nameScore > 0) {
            score += nameScore;
            reasons.add("suspicious username pattern");
        }

        // ── Signal 4: Reconnect velocity (connections per minute) ─────────────
        double connectionsPerMinute = calculateConnectionRate(record.connectionTimestamps);
        if (connectionsPerMinute >= config.botConnectionsPerMinuteThreshold) {
            score += 30;
            reasons.add("high reconnect velocity (" + String.format("%.1f", connectionsPerMinute) + "/min)");
        }

        // ── Signal 5: Already flagged hard ────────────────────────────────────
        if (record.hardFlagged) {
            score += 50;
            reasons.add("previously hard-flagged");
        }

        // ── Signal 6: GeoIP hosting/datacenter IP ────────────────────────────
        // Non-blocking cache check only — don't block the connection for a geo lookup
        GeoIpLogger.GeoResult geo = GeoIpLogger.lookupCacheOnly(ip);
        if (geo != null && geo.isSuspicious()) {
            score += 20;
            reasons.add("datacenter/proxy IP (ASN: " + geo.as + ")");
        }

        // ── Evaluate score ────────────────────────────────────────────────────
        if (score >= config.botHardBlockScore) {
            record.hardFlagged = true;
            String reasonStr = String.join(", ", reasons);
            RaeYNCheat.LOGGER.warn("[BotDetector] HARD BLOCK: {} ({}) from {} — score={}, reasons: {}",
                    username, uuidStr, ip, score, reasonStr);
            PasskeyLogger.logWarning(username, uuidStr, "BOT_HARD_BLOCK",
                    "IP: " + ip + " | Score: " + score + " | " + reasonStr);
            return BotVerdict.HARD_BLOCK;
        }

        if (score >= config.botShadowBanScore) {
            int shadowCount = record.shadowBanCount.incrementAndGet();
            String reasonStr = String.join(", ", reasons);
            RaeYNCheat.LOGGER.warn("[BotDetector] SHADOW BAN: {} ({}) from {} — score={}, shadow#={}, reasons: {}",
                    username, uuidStr, ip, score, shadowCount, reasonStr);
            PasskeyLogger.logWarning(username, uuidStr, "BOT_SHADOW_BAN",
                    "IP: " + ip + " | Score: " + score + " | Shadow#: " + shadowCount + " | " + reasonStr);

            shadowBanned.put(uuidStr, System.currentTimeMillis() + SHADOW_BAN_DURATION_MS);

            // After too many shadow accepts, escalate
            if (shadowCount >= SHADOW_BAN_MAX_CONNECTIONS) {
                record.hardFlagged = true;
                return BotVerdict.HARD_BLOCK;
            }
            return BotVerdict.SHADOW_BAN;
        }

        if (score >= config.botWarnScore) {
            RaeYNCheat.LOGGER.info("[BotDetector] SUSPICIOUS (score={}): {} ({}) from {} — {}",
                    score, username, uuidStr, ip, String.join(", ", reasons));
            PasskeyLogger.logWarning(username, uuidStr, "BOT_SUSPICIOUS",
                    "IP: " + ip + " | Score: " + score + " | " + String.join(", ", reasons));
        }

        return BotVerdict.ALLOW;
    }

    /**
     * Check if a UUID is currently shadow-banned (SyncPacket should be silently dropped).
     */
    public static boolean isShadowBanned(String uuidStr) {
        Long expiry = shadowBanned.get(uuidStr);
        if (expiry == null) return false;
        if (System.currentTimeMillis() > expiry) {
            shadowBanned.remove(uuidStr);
            return false;
        }
        return true;
    }

    /**
     * Mark a UUID/IP as confirmed-clean after successful SyncPacket validation.
     * Resets the timing record so legitimate players don't accumulate false scores.
     */
    public static void markClean(SocketAddress address, String uuidStr) {
        shadowBanned.remove(uuidStr);
        String ip = ConnectionRateLimiter.extractIpPublic(address);
        if (ip == null) return;
        TimingRecord record = ipRecords.get(ip);
        if (record != null) {
            record.hardFlagged = false;
            record.shadowBanCount.set(0);
            // Don't clear timing history — we want it for sustained analysis
        }
    }

    public static void clearIp(String ip) {
        ipRecords.remove(ip);
    }

    public static void clearUuid(String uuidStr) {
        shadowBanned.remove(uuidStr);
    }

    public static void cleanup() {
        long cutoff = System.currentTimeMillis() - RECORD_TTL_MS;
        ipRecords.entrySet().removeIf(e -> e.getValue().lastSeen.get() < cutoff);
        long now = System.currentTimeMillis();
        shadowBanned.entrySet().removeIf(e -> e.getValue() < now);
    }

    public static String getStatusReport() {
        long now = System.currentTimeMillis();
        int activeShadow = (int) shadowBanned.entrySet().stream().filter(e -> e.getValue() > now).count();
        int hardFlagged  = (int) ipRecords.values().stream().filter(r -> r.hardFlagged).count();
        int activeIps    = (int) ipRecords.values().stream()
                .filter(r -> now - r.lastSeen.get() < 60_000).count();
        return String.format("BotDetector: %d tracked IPs (1min), %d shadow-banned, %d hard-flagged",
                activeIps, activeShadow, hardFlagged);
    }

    // ---------------------------------------------------------------------------
    // Analysis helpers
    // ---------------------------------------------------------------------------

    /**
     * Calculate variance (in ms) of inter-connection intervals.
     * Returns -1 if not enough data. Near-zero variance = mechanical bot.
     * Humans typically have >500ms variance; bots often < 50ms.
     */
    private static double calculateTimingVariance(List<Long> timestamps) {
        synchronized (timestamps) {
            if (timestamps.size() < 3) return -1;
            List<Long> intervals = new ArrayList<>();
            for (int i = 1; i < timestamps.size(); i++) {
                intervals.add(timestamps.get(i) - timestamps.get(i - 1));
            }
            double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
            double variance = intervals.stream()
                    .mapToDouble(v -> Math.pow(v - mean, 2))
                    .average().orElse(0);
            return Math.sqrt(variance); // Return std deviation in ms
        }
    }

    /**
     * Count distinct usernames seen from this IP during its tracking lifetime.
     * Names are stored without timestamps, so this counts all unique names in the window
     * (capped at 50, cleared when the record expires after RECORD_TTL_MS inactivity).
     */
    private static int countUniqueNamesRecently(TimingRecord record) {
        synchronized (record.usernamesSeen) {
            return new HashSet<>(record.usernamesSeen).size();
        }
    }

    /**
     * Connections per minute over the tracked window.
     */
    private static double calculateConnectionRate(List<Long> timestamps) {
        synchronized (timestamps) {
            if (timestamps.size() < 2) return 0;
            long window = timestamps.get(timestamps.size() - 1) - timestamps.get(0);
            if (window <= 0) return 0;
            return (timestamps.size() - 1.0) / (window / 60_000.0);
        }
    }

    /**
     * Score a username for bot-like characteristics.
     * Returns 0-30 based on how suspicious the name looks.
     */
    private static int analyzeUsername(String username) {
        if (username == null || username.isEmpty()) return 15;
        String lower = username.toLowerCase();

        int score = 0;

        // Pure hex string
        if (HEX_NAME.matcher(username).matches()) score += 25;
        // Pure digits
        if (DIGIT_NAME.matcher(username).matches()) score += 20;
        // Very short (1-2 chars)
        if (username.length() <= 2) score += 15;
        // Sequential pattern (abc123 style)
        if (SEQ_NAME.matcher(username).matches()) score += 10;
        // Known bot keywords
        for (String kw : BOT_KEYWORDS) {
            if (lower.contains(kw)) { score += 20; break; }
        }
        // Starts/ends with underscore (common bot toolkit naming)
        if (username.startsWith("_") || username.endsWith("_")) score += 10;

        return Math.min(score, 30); // Cap at 30 — name alone shouldn't cause a block
    }

    // ---------------------------------------------------------------------------
    // Verdict enum
    // ---------------------------------------------------------------------------

    public enum BotVerdict {
        ALLOW,        // Looks like a real player — proceed normally
        SHADOW_BAN,   // Looks suspicious — accept silently but drop SyncPacket
        HARD_BLOCK    // Confirmed bot behavior — hand to ConnectionRateLimiter
    }
}
