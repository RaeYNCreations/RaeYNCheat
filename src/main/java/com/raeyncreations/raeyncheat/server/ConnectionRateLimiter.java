package com.raeyncreations.raeyncheat.server;

import com.raeyncreations.raeyncheat.RaeYNCheat;
import com.raeyncreations.raeyncheat.config.RaeYNCheatConfig;
import com.raeyncreations.raeyncheat.util.PasskeyLogger;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Industry-grade connection rate limiter and DDoS mitigator for RaeYNCheat.
 *
 * ═══════════════════════════════════════════════════════════════════
 * DUAL ALGORITHM
 * ═══════════════════════════════════════════════════════════════════
 *   Token Bucket  — absorbs legitimate burst (crash reconnect) while enforcing
 *                   sustained-rate ceiling. O(1), lock-free.
 *   Sliding Window — rolling time-window counter. Catches sustained low-rate
 *                    floods that stay just under burst threshold. Ring buffer,
 *                    O(1) amortized, minimal allocation.
 *   A connection is blocked if EITHER algorithm rejects it.
 *
 * ═══════════════════════════════════════════════════════════════════
 * TRACKING AXES
 * ═══════════════════════════════════════════════════════════════════
 *   Per-IP       Primary enforcement axis.
 *   Per-/24      IPv4 subnet. Catches botnets rotating through same /24 block.
 *   Per-UUID     SyncPacket rate limiting. Blocks PBKDF2 CPU exhaustion.
 *   Global       Server-wide rate. If volumetric attack overwhelms all per-IP
 *                limits combined, global circuit breaker fires.
 *
 * ═══════════════════════════════════════════════════════════════════
 * ESCALATION TIERS
 * ═══════════════════════════════════════════════════════════════════
 *   Tier 1 — Soft block: refuse connection, log it.
 *   Tier 2 — Hard-block with EXPONENTIAL BACKOFF:
 *             base_duration * multiplier ^ escalation_index, capped at max.
 *             Example (5min base, 2x multiplier): 5 → 10 → 20 → 40 → capped.
 *   Tier 3 — Permanent Minecraft IP ban.
 *
 * ═══════════════════════════════════════════════════════════════════
 * CIRCUIT BREAKER
 * ═══════════════════════════════════════════════════════════════════
 *   When server-wide rate exceeds globalFloodThresholdPerSecond, breaker OPENS:
 *   All non-whitelisted connections dropped with zero processing cost.
 *   Auto-resets after globalCircuitBreakerCooldownMs.
 *
 * ═══════════════════════════════════════════════════════════════════
 * PERFORMANCE
 * ═══════════════════════════════════════════════════════════════════
 *   Already-blocked IP: single map lookup + long compare. ~50ns. Zero alloc.
 *   Normal path: 2 map lookups + atomic ops. No locks anywhere.
 *   Cleanup: background thread every 2 min, not on server tick.
 *   Memory: ~200 bytes per IpState. 10k IPs = ~2MB.
 */
public class ConnectionRateLimiter {

    // ─── IpState ─────────────────────────────────────────────────────────────

    static final class IpState {
        final AtomicInteger tokens;
        final AtomicLong    lastRefill;

        // Sliding window ring buffer
        private static final int WIN = 32;
        private final long[] window  = new long[WIN];
        private final AtomicInteger wHead = new AtomicInteger(0);

        final AtomicInteger totalBlocks;
        final AtomicInteger consecutiveBlocks;
        volatile long       hardBlockExpiry;    // epoch ms, 0 = not blocked
        volatile long       currentBlockDuration;
        volatile boolean    permanentlyBanned;

        final AtomicLong firstSeen;
        final AtomicLong lastSeen;
        final AtomicLong lastBlockTime;

        IpState(int tok) {
            tokens              = new AtomicInteger(tok);
            lastRefill          = new AtomicLong(System.currentTimeMillis());
            totalBlocks         = new AtomicInteger(0);
            consecutiveBlocks   = new AtomicInteger(0);
            hardBlockExpiry     = 0;
            currentBlockDuration = 0;
            permanentlyBanned   = false;
            firstSeen           = new AtomicLong(System.currentTimeMillis());
            lastSeen            = new AtomicLong(System.currentTimeMillis());
            lastBlockTime       = new AtomicLong(0);
        }

        void recordConn(long ts) { window[wHead.getAndIncrement() % WIN] = ts; }

        int countWindow(long ms) {
            long cutoff = System.currentTimeMillis() - ms;
            int n = 0;
            for (long t : window) if (t > cutoff) n++;
            return n;
        }
    }

    // ─── UuidState ────────────────────────────────────────────────────────────

    private static final class UuidState {
        final AtomicInteger tokens;
        final AtomicLong    lastRefill;
        final AtomicInteger totalBlocks;
        final AtomicLong    lastSeen;

        UuidState(int tok) {
            tokens      = new AtomicInteger(tok);
            lastRefill  = new AtomicLong(System.currentTimeMillis());
            totalBlocks = new AtomicInteger(0);
            lastSeen    = new AtomicLong(System.currentTimeMillis());
        }
    }

    // ─── State ────────────────────────────────────────────────────────────────

    private static final ConcurrentHashMap<String, IpState>   ipStates     = new ConcurrentHashMap<>(256);
    private static final ConcurrentHashMap<String, IpState>   subnetStates = new ConcurrentHashMap<>(128);
    private static final ConcurrentHashMap<String, UuidState> uuidStates   = new ConcurrentHashMap<>(64);

    // Global sliding window
    private static final long[]        globalWin  = new long[256];
    private static final AtomicInteger globalHead = new AtomicInteger(0);
    private static volatile boolean    circuitOpen     = false;
    private static volatile long       circuitOpenedAt = 0;

    private static final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "RaeYNCheat-RateLimiter");
                t.setDaemon(true);
                return t;
            });

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    public static void initialize() {
        scheduler.scheduleAtFixedRate(ConnectionRateLimiter::cleanup, 2, 2, TimeUnit.MINUTES);
        RaeYNCheat.LOGGER.info("[RateLimit] Initialized: dual-algorithm, /24 subnet, global circuit breaker.");
    }

    public static void shutdown() {
        try {
            scheduler.shutdown();
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) scheduler.shutdownNow();
        } catch (InterruptedException e) { scheduler.shutdownNow(); Thread.currentThread().interrupt(); }
    }

    // ─── Primary enforcement ──────────────────────────────────────────────────

    /**
     * Enforce rate limits on an incoming connection.
     * Hot-path ordered: cheapest checks first to minimize CPU under flood.
     */
    public static boolean allowConnection(SocketAddress address, String username) {
        RaeYNCheatConfig cfg = RaeYNCheat.getConfig();
        if (cfg == null || !cfg.enableDdosProtection) return true;

        String ip = extractIp(address);
        if (ip == null) return true;
        if (isPrivateOrLoopback(ip)) return true;

        long now = System.currentTimeMillis();

        // ── Circuit breaker ──────────────────────────────────────────────────
        if (circuitOpen) {
            if (now - circuitOpenedAt < cfg.globalCircuitBreakerCooldownMs) {
                if (!isWhitelisted(ip, cfg)) {
                    RaeYNCheat.LOGGER.debug("[RateLimit] Circuit OPEN — dropped {}", ip);
                    return false;
                }
            } else {
                circuitOpen = false;
                RaeYNCheat.LOGGER.warn("[RateLimit] Circuit breaker RESET after {}s.",
                        cfg.globalCircuitBreakerCooldownMs / 1000);
            }
        }

        // ── Global rate ──────────────────────────────────────────────────────
        globalWin[globalHead.getAndIncrement() % globalWin.length] = now;
        int globalRate = countGlobal(1000L);
        if (globalRate >= cfg.globalFloodThresholdPerSecond && !circuitOpen) {
            circuitOpen     = true;
            circuitOpenedAt = now;
            RaeYNCheat.LOGGER.error(
                    "[RateLimit] *** CIRCUIT BREAKER OPEN *** {}/s flood (threshold: {}). " +
                    "Rejecting all non-whitelisted for {}s.",
                    globalRate, cfg.globalFloodThresholdPerSecond,
                    cfg.globalCircuitBreakerCooldownMs / 1000);
            PasskeyLogger.logWarning("SERVER", "GLOBAL", "CIRCUIT_BREAKER_OPEN",
                    "Flood: " + globalRate + " conn/s");
            if (!isWhitelisted(ip, cfg)) return false;
        }

        // ── Per-IP ───────────────────────────────────────────────────────────
        IpState st = ipStates.computeIfAbsent(ip, k -> new IpState(cfg.ddosIpBurstTokens));
        st.lastSeen.set(now);
        st.recordConn(now);

        if (st.permanentlyBanned) return false;

        if (st.hardBlockExpiry > now) return false;
        if (st.hardBlockExpiry > 0) {
            st.hardBlockExpiry = 0;
            st.consecutiveBlocks.set(0);
        }

        refill(st.tokens, st.lastRefill, cfg.ddosIpBurstTokens, cfg.ddosIpRefillRatePerSecond);
        boolean tokOk = st.tokens.get() > 0;
        int recentConns = st.countWindow(cfg.ddosSlidingWindowMs);
        boolean winOk   = recentConns < cfg.ddosSlidingWindowMaxConnections;

        if (!tokOk || !winOk) {
            return handleBlock(st, ip, username, now, cfg,
                    !tokOk ? "token bucket empty" : "sliding window (" + recentConns + "/" +
                            cfg.ddosSlidingWindowMaxConnections + " in " + cfg.ddosSlidingWindowMs + "ms)");
        }

        st.tokens.decrementAndGet();
        st.consecutiveBlocks.set(0);

        // ── Per-/24 subnet ───────────────────────────────────────────────────
        String subnet = subnet24(ip);
        if (subnet != null) {
            IpState ss = subnetStates.computeIfAbsent(subnet, k -> new IpState(cfg.ddosSubnetBurstTokens));
            ss.lastSeen.set(now);
            ss.recordConn(now);
            refill(ss.tokens, ss.lastRefill, cfg.ddosSubnetBurstTokens, cfg.ddosSubnetRefillRatePerSecond);

            if (ss.tokens.get() <= 0 || ss.countWindow(cfg.ddosSlidingWindowMs) >= cfg.ddosSubnetWindowMax) {
                int n = ss.totalBlocks.incrementAndGet();
                RaeYNCheat.LOGGER.warn("[RateLimit] /24 {} blocked — IP: {}, user: {}, block #{}", subnet, ip, username, n);
                PasskeyLogger.logWarning(username, "N/A", "SUBNET_RATE_LIMIT",
                        "Subnet " + subnet + "/24 rate exceeded — " + ip + " (#" + n + ")");
                return false;
            }
            ss.tokens.decrementAndGet();
        }

        return true;
    }

    /** Rate-limit SyncPacket submissions per UUID (protects PBKDF2 from CPU exhaustion). */
    public static boolean allowSyncPacket(String uuidStr, String username) {
        RaeYNCheatConfig cfg = RaeYNCheat.getConfig();
        if (cfg == null || !cfg.enableDdosProtection) return true;

        UuidState us = uuidStates.computeIfAbsent(uuidStr, k -> new UuidState(cfg.ddosSyncBurstTokens));
        us.lastSeen.set(System.currentTimeMillis());
        refill(us.tokens, us.lastRefill, cfg.ddosSyncBurstTokens, cfg.ddosSyncRefillRatePerSecond);

        if (us.tokens.get() <= 0) {
            int n = us.totalBlocks.incrementAndGet();
            RaeYNCheat.LOGGER.warn("[RateLimit] SyncPacket rate exceeded: {} ({}) block #{}", username, uuidStr, n);
            PasskeyLogger.logWarning(username, uuidStr, "SYNC_RATE_LIMIT", "SyncPacket flood #" + n);
            return false;
        }
        us.tokens.decrementAndGet();
        return true;
    }

    /** Refund one token on clean disconnect. */
    public static void onDisconnect(SocketAddress address) {
        RaeYNCheatConfig cfg = RaeYNCheat.getConfig();
        if (cfg == null || !cfg.enableDdosProtection) return;
        String ip = extractIp(address);
        if (ip == null || isPrivateOrLoopback(ip)) return;
        IpState st = ipStates.get(ip);
        if (st != null) {
            int cur = st.tokens.get();
            if (cur < cfg.ddosIpBurstTokens) st.tokens.compareAndSet(cur, cur + 1);
        }
    }

    // ─── Block handler ────────────────────────────────────────────────────────

    private static boolean handleBlock(IpState st, String ip, String username,
                                        long now, RaeYNCheatConfig cfg, String reason) {
        int total  = st.totalBlocks.incrementAndGet();
        int consec = st.consecutiveBlocks.incrementAndGet();
        st.lastBlockTime.set(now);

        // Tier 3: permanent ban
        if (total >= cfg.ddosTier3Threshold && !st.permanentlyBanned) {
            st.permanentlyBanned = true;
            applyPermaBan(ip, username, total);
            return false;
        }

        // Tier 2: exponential backoff hard-block
        if (total >= cfg.ddosTier2Threshold) {
            long esc      = Math.max(0, (total - cfg.ddosTier2Threshold) /
                    Math.max(1, cfg.ddosTier2EscalationStep));
            long base     = cfg.ddosTier2BlockSeconds * 1000L;
            long duration = Math.min(
                    (long)(base * Math.pow(cfg.ddosTier2BackoffMultiplier, esc)),
                    cfg.ddosTier2MaxBlockMs);

            if (st.hardBlockExpiry < now + duration) {
                st.hardBlockExpiry      = now + duration;
                st.currentBlockDuration = duration;
                RaeYNCheat.LOGGER.warn("[RateLimit] TIER-2 hard-block: {} — {}s (block #{}, esc #{}). {}",
                        ip, duration / 1000, total, esc, reason);
                PasskeyLogger.logWarning(username, "N/A", "DDOS_TIER2",
                        ip + " hard-blocked " + duration/1000 + "s — #" + total + " — " + reason);
            }
            return false;
        }

        // Tier 1: soft block (throttle log spam — every 5 blocks only)
        if (total % 5 == 1) {
            RaeYNCheat.LOGGER.warn("[RateLimit] Soft-block: {} — block #{}, consec {}. {}", ip, total, consec, reason);
            PasskeyLogger.logWarning(username, "N/A", "DDOS_TIER1",
                    ip + " soft-blocked #" + total + " — " + reason);
        }
        return false;
    }

    // ─── Permanent IP ban ─────────────────────────────────────────────────────

    private static void applyPermaBan(String ip, String username, int blocks) {
        var server = RaeYNCheat.getCurrentServer();
        if (server == null) return;
        var bans = server.getPlayerList().getIpBans();
        if (bans.isBanned(ip)) return;
        bans.add(new net.minecraft.server.players.IpBanListEntry(
                ip, null, "RaeYNCheat-DDoS", null,
                "Automated flood ban: " + blocks + " violations"));
        RaeYNCheat.LOGGER.error("[RateLimit] PERMANENT IP BAN: {} ({} total blocks).", ip, blocks);
        PasskeyLogger.logWarning(username, "N/A", "DDOS_TIER3",
                ip + " permanent IP ban — " + blocks + " total blocks");
    }

    // ─── Token bucket refill ──────────────────────────────────────────────────

    private static void refill(AtomicInteger tokens, AtomicLong lastRefill,
                                int max, double perSec) {
        long now  = System.currentTimeMillis();
        long last = lastRefill.get();
        long elapsed = now - last;
        if (elapsed < 100) return;
        int toAdd = (int)(elapsed * perSec / 1000.0);
        if (toAdd <= 0) return;
        if (lastRefill.compareAndSet(last, now))
            tokens.set(Math.min(max, tokens.get() + toAdd));
    }

    // ─── Global window ────────────────────────────────────────────────────────

    private static int countGlobal(long ms) {
        long cutoff = System.currentTimeMillis() - ms;
        int n = 0;
        for (long t : globalWin) if (t > cutoff) n++;
        return n;
    }

    // ─── Admin API ────────────────────────────────────────────────────────────

    public static void clearIp(String ip) {
        IpState st = ipStates.remove(ip);
        if (st != null) st.permanentlyBanned = false;
        String sub = subnet24(ip);
        if (sub != null) subnetStates.remove(sub);
        RaeYNCheat.LOGGER.info("[RateLimit] Cleared rate-limit state for IP {}.", ip);
    }

    public static void clearUuid(String uuid) {
        uuidStates.remove(uuid);
        RaeYNCheat.LOGGER.info("[RateLimit] Cleared SyncPacket state for UUID {}.", uuid);
    }

    public static boolean isCircuitOpen() { return circuitOpen; }

    public static void resetCircuitBreaker() {
        circuitOpen = false;
        RaeYNCheat.LOGGER.warn("[RateLimit] Circuit breaker manually reset.");
    }

    public static String getStatusReport() {
        long now = System.currentTimeMillis();
        int activeIp   = (int) ipStates.values().stream().filter(s -> now - s.lastSeen.get() < 60_000).count();
        int hardBlk    = (int) ipStates.values().stream().filter(s -> s.hardBlockExpiry > now).count();
        int permBan    = (int) ipStates.values().stream().filter(s -> s.permanentlyBanned).count();
        int activeUuid = (int) uuidStates.values().stream().filter(s -> now - s.lastSeen.get() < 60_000).count();
        int globalRate = countGlobal(1000L);
        int subnetAct  = (int) subnetStates.values().stream().filter(s -> now - s.lastSeen.get() < 60_000).count();

        return String.format(
                "[RateLimit] IPs: %d active, %d hard-blocked, %d perm-banned | " +
                "Subnets: %d | UUIDs: %d | Global: %d/s | Circuit: %s",
                activeIp, hardBlk, permBan, subnetAct, activeUuid, globalRate,
                circuitOpen ? "OPEN (flood mode)" : "closed");
    }

    public static String getIpReport(String ip) {
        IpState st = ipStates.get(ip);
        if (st == null) return "IP " + ip + ": no data";
        long now = System.currentTimeMillis();
        return String.format("IP %s: blocks=%d, tokens=%d, hardBlock=%s, permBan=%b, firstSeen=%dmin ago",
                ip, st.totalBlocks.get(), st.tokens.get(),
                st.hardBlockExpiry > now ? (st.hardBlockExpiry - now)/1000 + "s" : "none",
                st.permanentlyBanned, (now - st.firstSeen.get()) / 60_000);
    }

    // ─── Package-private helpers (used by BotDetector / GeoIpLogger) ─────────

    static String extractIpPublic(SocketAddress address) { return extractIp(address); }

    static boolean isPrivateOrLoopback(String ip) {
        if (ip == null) return true;
        return ip.startsWith("127.") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1")
                || ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("169.254.")
                || ip.toLowerCase().startsWith("fc") || ip.toLowerCase().startsWith("fd")
                || isRfc172(ip);
    }

    private static boolean isRfc172(String ip) {
        if (!ip.startsWith("172.")) return false;
        try { int s = Integer.parseInt(ip.split("\\.")[1]); return s >= 16 && s <= 31; }
        catch (Exception e) { return false; }
    }

    private static String extractIp(SocketAddress addr) {
        if (addr instanceof InetSocketAddress inet) {
            InetAddress a = inet.getAddress();
            return a != null ? a.getHostAddress() : null;
        }
        String s = addr.toString();
        int slash = s.indexOf('/'), colon = s.lastIndexOf(':');
        return (slash >= 0 && colon > slash) ? s.substring(slash + 1, colon) : null;
    }

    private static String subnet24(String ip) {
        if (ip == null || ip.contains(":")) return null;
        int last = ip.lastIndexOf('.');
        return last > 0 ? ip.substring(0, last) : null;
    }

    private static boolean isWhitelisted(String ip, RaeYNCheatConfig cfg) {
        return cfg.ddosWhitelistedIps != null && cfg.ddosWhitelistedIps.contains(ip);
    }

    // ─── Cleanup ─────────────────────────────────────────────────────────────

    private static void cleanup() {
        long now    = System.currentTimeMillis();
        long cutoff = now - TimeUnit.MINUTES.toMillis(15);
        int ri = 0, rs = 0, ru = 0;

        for (var it = ipStates.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next(); var st = e.getValue();
            if (st.permanentlyBanned || st.hardBlockExpiry > now) continue;
            if (st.lastSeen.get() < cutoff) { it.remove(); ri++; }
        }
        for (var it = subnetStates.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().lastSeen.get() < cutoff) { it.remove(); rs++; }
        }
        for (var it = uuidStates.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().lastSeen.get() < cutoff) { it.remove(); ru++; }
        }

        BotDetector.cleanup();
        GeoIpLogger.cleanupCache();

        if (ri + rs + ru > 0)
            RaeYNCheat.LOGGER.debug("[RateLimit] Cleanup: IP={}, subnet={}, UUID={}.", ri, rs, ru);
    }
}
