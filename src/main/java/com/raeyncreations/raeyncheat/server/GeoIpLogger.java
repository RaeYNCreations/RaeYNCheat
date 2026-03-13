package com.raeyncreations.raeyncheat.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.raeyncreations.raeyncheat.RaeYNCheat;
import com.raeyncreations.raeyncheat.util.PasskeyLogger;

import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Asynchronous IP geolocation lookup for player connection logging.
 *
 * Uses ip-api.com (free tier, HTTPS, no API key required, 45 req/min limit).
 * All lookups run on a background thread pool — they NEVER block the server tick
 * or the connection handler. Results are cached in-memory for 24 hours so repeat
 * connections from the same IP don't consume quota.
 *
 * FALLBACK CHAIN:
 *   1. In-memory cache (instant)
 *   2. ip-api.com HTTP lookup (async, background thread)
 *   3. RFC classification only (private/loopback/reserved — instant, no network needed)
 *
 * PRIVACY NOTE:
 *   IP addresses are logged for security purposes on a private server. This is
 *   standard practice and disclosed to players via server rules. The geolocation
 *   data is stored only in cheat.log and never transmitted elsewhere.
 *
 * RATE LIMITING:
 *   ip-api.com free tier allows 45 req/min. We enforce this with a token bucket
 *   on the lookup side, and the 24-hour cache means regular players only consume
 *   one lookup ever (or once per server restart + 24h).
 */
public class GeoIpLogger {

    // ---------------------------------------------------------------------------
    // GeoIp result record
    // ---------------------------------------------------------------------------

    public static class GeoResult {
        public final String ip;
        public final String country;
        public final String countryCode;
        public final String region;
        public final String regionName;
        public final String city;
        public final String zip;
        public final double lat;
        public final double lon;
        public final String timezone;
        public final String isp;
        public final String org;
        public final String as;          // ASN string e.g. "AS15169 Google LLC"
        public final boolean isProxy;
        public final boolean isHosting;
        public final String source;      // "cache" | "api" | "rfc" | "loopback" | "error"

        GeoResult(String ip, String country, String countryCode, String region, String regionName,
                  String city, String zip, double lat, double lon, String timezone,
                  String isp, String org, String as, boolean isProxy, boolean isHosting, String source) {
            this.ip          = ip;
            this.country     = country;
            this.countryCode = countryCode;
            this.region      = region;
            this.regionName  = regionName;
            this.city        = city;
            this.zip         = zip;
            this.lat         = lat;
            this.lon         = lon;
            this.timezone    = timezone;
            this.isp         = isp;
            this.org         = org;
            this.as          = as;
            this.isProxy     = isProxy;
            this.isHosting   = isHosting;
            this.source      = source;
        }

        /** Single-line summary for cheat.log */
        public String toLogLine() {
            if ("loopback".equals(source)) return "IP: " + ip + " [LOOPBACK — local connection]";
            if ("rfc".equals(source))      return "IP: " + ip + " [PRIVATE/RESERVED — LAN connection]";
            if ("error".equals(source))    return "IP: " + ip + " [GEO LOOKUP FAILED — raw IP only]";

            StringBuilder sb = new StringBuilder();
            sb.append("IP: ").append(ip);
            if (!city.isEmpty())       sb.append(" | City: ").append(city);
            if (!regionName.isEmpty()) sb.append(", ").append(regionName);
            if (!country.isEmpty())    sb.append(", ").append(country);
            if (!countryCode.isEmpty()) sb.append(" (").append(countryCode).append(")");
            if (lat != 0 || lon != 0)  sb.append(" | Coords: ").append(String.format("%.4f, %.4f", lat, lon));
            if (!timezone.isEmpty())   sb.append(" | TZ: ").append(timezone);
            if (!isp.isEmpty())        sb.append(" | ISP: ").append(isp);
            if (!org.isEmpty() && !org.equals(isp)) sb.append(" | Org: ").append(org);
            if (!as.isEmpty())         sb.append(" | ASN: ").append(as);
            if (isProxy)               sb.append(" | *** PROXY/VPN DETECTED ***");
            if (isHosting)             sb.append(" | *** HOSTING/DATACENTER IP ***");
            sb.append(" | [").append(source).append("]");
            return sb.toString();
        }

        /** Returns true if this IP looks like a datacenter / VPN / proxy — worth flagging */
        public boolean isSuspicious() {
            return isProxy || isHosting;
        }
    }

    // ---------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------

    /** 24-hour in-memory geo cache: ip → GeoResult */
    private static final ConcurrentHashMap<String, CachedResult> cache = new ConcurrentHashMap<>();

    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L; // 24 hours

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** Single-threaded executor for geo lookups — keeps load off main threads */
    private static final ExecutorService lookupExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RaeYNCheat-GeoLookup");
        t.setDaemon(true);
        return t;
    });

    /** Rate limiter for ip-api.com: 45 req/min = ~1 per 1333ms. We use 40/min to be safe. */
    private static final AtomicLong lastApiCallTime = new AtomicLong(0);
    private static final long API_MIN_INTERVAL_MS = 1500; // 40 req/min

    private static final Gson GSON = new Gson();

    private static class CachedResult {
        final GeoResult result;
        final long timestamp;
        CachedResult(GeoResult result) { this.result = result; this.timestamp = System.currentTimeMillis(); }
        boolean expired() { return System.currentTimeMillis() - timestamp > CACHE_TTL_MS; }
    }

    // ---------------------------------------------------------------------------
    // Public API
    // --------------------------------------------------------------------------->

    /**
     * Initiate an async geo lookup for this IP and log results to cheat.log when ready.
     * Non-blocking — returns immediately. The log entry will appear within ~2 seconds.
     *
     * @param ip          Raw IP string from the connection
     * @param username    Player username for the log entry
     * @param uuid        Player UUID for the log entry
     * @param extraDetail Any extra text to include after the geo line (e.g. violation history)
     */
    public static void logConnectionAsync(String ip, String username, String uuid, String extraDetail) {
        if (ip == null || ip.isEmpty()) {
            PasskeyLogger.logGeoEvent(username, uuid, ip,
                    "IP: [UNKNOWN — could not extract address]", false);
            return;
        }

        // Fast-path: loopback
        if (isLoopback(ip)) {
            GeoResult r = loopbackResult(ip);
            PasskeyLogger.logGeoEvent(username, uuid, ip, r.toLogLine(), false);
            return;
        }

        // Fast-path: private/RFC1918
        if (isPrivate(ip)) {
            GeoResult r = rfcResult(ip);
            PasskeyLogger.logGeoEvent(username, uuid, ip, r.toLogLine(), false);
            return;
        }

        // Check cache first (no network needed)
        CachedResult cached = cache.get(ip);
        if (cached != null && !cached.expired()) {
            PasskeyLogger.logGeoEvent(username, uuid, ip, cached.result.toLogLine(), cached.result.isSuspicious());
            if (extraDetail != null && !extraDetail.isEmpty()) {
                PasskeyLogger.logWarning(username, uuid, "CONNECTION_DETAIL", extraDetail);
            }
            return;
        }

        // Submit async lookup
        lookupExecutor.submit(() -> {
            try {
                GeoResult result = fetchFromApi(ip);
                cache.put(ip, new CachedResult(result));
                PasskeyLogger.logGeoEvent(username, uuid, ip, result.toLogLine(), result.isSuspicious());
                if (extraDetail != null && !extraDetail.isEmpty()) {
                    PasskeyLogger.logWarning(username, uuid, "CONNECTION_DETAIL", extraDetail);
                }
                // Flag datacenter/proxy IPs for admin attention
                if (result.isSuspicious()) {
                    RaeYNCheat.LOGGER.warn("[GeoIP] SUSPICIOUS IP for {} ({}): {} — proxy={}, hosting={}",
                            username, uuid, ip, result.isProxy, result.isHosting);
                }
            } catch (Exception e) {
                RaeYNCheat.LOGGER.debug("[GeoIP] Lookup failed for {}: {}", ip, e.getMessage());
                GeoResult fallback = errorResult(ip);
                PasskeyLogger.logGeoEvent(username, uuid, ip, fallback.toLogLine(), false);
            }
        });
    }

    /**
     * Synchronous lookup with timeout — used when you need the result inline (e.g. bot detection).
     * Returns an error result if the lookup takes longer than 3 seconds or fails.
     */
    public static GeoResult lookupSync(String ip) {
        if (ip == null)       return errorResult("null");
        if (isLoopback(ip))   return loopbackResult(ip);
        if (isPrivate(ip))    return rfcResult(ip);

        CachedResult cached = cache.get(ip);
        if (cached != null && !cached.expired()) return cached.result;

        try {
            Future<GeoResult> future = lookupExecutor.submit(() -> fetchFromApi(ip));
            GeoResult result = future.get(3, TimeUnit.SECONDS);
            cache.put(ip, new CachedResult(result));
            return result;
        } catch (Exception e) {
            return errorResult(ip);
        }
    }

    public static void shutdown() {
        lookupExecutor.shutdown();
    }

    public static void clearCache() {
        cache.clear();
    }

    /** Remove entries older than CACHE_TTL_MS from the geo cache. */
    public static void cleanupCache() {
        cache.entrySet().removeIf(e -> e.getValue().expired());
    }

    /** Non-blocking cache-only lookup — returns null if not cached. Used by BotDetector. */
    public static GeoResult lookupCacheOnly(String ip) {
        CachedResult cached = cache.get(ip);
        return (cached != null && !cached.expired()) ? cached.result : null;
    }

    /** Delegate to ConnectionRateLimiter to avoid duplicating the private-range logic. */
    public static boolean isPrivateOrLoopback(String ip) {
        return ConnectionRateLimiter.isPrivateOrLoopback(ip);
    }

    // ---------------------------------------------------------------------------
    // API fetch
    // ---------------------------------------------------------------------------

    private static GeoResult fetchFromApi(String ip) throws Exception {
        // Enforce ip-api.com rate limit (40 req/min). The lookupExecutor is intentionally
        // single-threaded so this sleep only blocks that thread — no server tick impact.
        long now = System.currentTimeMillis();
        long last = lastApiCallTime.get();
        long wait = API_MIN_INTERVAL_MS - (now - last);
        if (wait > 0) Thread.sleep(wait);
        lastApiCallTime.set(System.currentTimeMillis());

        // ip-api.com returns all fields we need in one call, free, no auth required
        // Fields: status,country,countryCode,region,regionName,city,zip,lat,lon,
        //         timezone,isp,org,as,proxy,hosting,query
        // Use HTTPS so MITM cannot suppress isProxy/isHosting flags in the response.
        String url = "https://ip-api.com/json/" + ip
                + "?fields=status,country,countryCode,region,regionName,city,zip,"
                + "lat,lon,timezone,isp,org,as,proxy,hosting,query";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("ip-api.com returned HTTP " + response.statusCode());
        }

        JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
        String status = json.has("status") ? json.get("status").getAsString() : "fail";

        if (!"success".equals(status)) {
            throw new RuntimeException("ip-api.com status: " + status);
        }

        return new GeoResult(
                getString(json, "query",       ip),
                getString(json, "country",     "Unknown"),
                getString(json, "countryCode", "??"),
                getString(json, "region",      ""),
                getString(json, "regionName",  ""),
                getString(json, "city",        ""),
                getString(json, "zip",         ""),
                getDouble(json, "lat"),
                getDouble(json, "lon"),
                getString(json, "timezone",    ""),
                getString(json, "isp",         ""),
                getString(json, "org",         ""),
                getString(json, "as",          ""),
                getBool(json,   "proxy"),
                getBool(json,   "hosting"),
                "api"
        );
    }

    // ---------------------------------------------------------------------------
    // RFC classification fast-paths
    // ---------------------------------------------------------------------------

    private static boolean isLoopback(String ip) {
        return ip.startsWith("127.") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1");
    }

    private static boolean isPrivate(String ip) {
        // RFC 1918: 10.x, 172.16-31.x, 192.168.x
        // RFC 4193: fd/fc IPv6 ULA
        // RFC 3927: 169.254.x (link-local)
        if (ip.startsWith("10."))       return true;
        if (ip.startsWith("192.168."))  return true;
        if (ip.startsWith("169.254."))  return true;
        if (ip.toLowerCase().startsWith("fc") || ip.toLowerCase().startsWith("fd")) return true;
        if (ip.startsWith("172.")) {
            try {
                int second = Integer.parseInt(ip.split("\\.")[1]);
                return second >= 16 && second <= 31;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private static GeoResult loopbackResult(String ip) {
        return new GeoResult(ip, "", "", "", "", "", "", 0, 0, "", "", "", "", false, false, "loopback");
    }

    private static GeoResult rfcResult(String ip) {
        return new GeoResult(ip, "", "", "", "", "", "", 0, 0, "", "", "", "", false, false, "rfc");
    }

    private static GeoResult errorResult(String ip) {
        return new GeoResult(ip, "", "", "", "", "", "", 0, 0, "", "", "", "", false, false, "error");
    }

    // ---------------------------------------------------------------------------
    // JSON helpers
    // ---------------------------------------------------------------------------

    private static String getString(JsonObject json, String key, String def) {
        try { return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : def; }
        catch (Exception e) { return def; }
    }

    private static double getDouble(JsonObject json, String key) {
        try { return json.has(key) ? json.get(key).getAsDouble() : 0.0; }
        catch (Exception e) { return 0.0; }
    }

    private static boolean getBool(JsonObject json, String key) {
        try { return json.has(key) && json.get(key).getAsBoolean(); }
        catch (Exception e) { return false; }
    }
}
