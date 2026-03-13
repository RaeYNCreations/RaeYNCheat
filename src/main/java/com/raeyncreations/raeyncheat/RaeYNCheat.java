package com.raeyncreations.raeyncheat;

import com.raeyncreations.raeyncheat.config.RaeYNCheatConfig;
import com.raeyncreations.raeyncheat.network.NetworkHandler;
import com.raeyncreations.raeyncheat.network.RevalidatePacket;
import com.raeyncreations.raeyncheat.server.ConnectionRateLimiter;
import com.raeyncreations.raeyncheat.server.GeoIpLogger;
import com.raeyncreations.raeyncheat.server.PlayerConnectionHandler;
import com.raeyncreations.raeyncheat.server.RaeYNCommand;
import com.raeyncreations.raeyncheat.server.ValidationHandler;
import com.raeyncreations.raeyncheat.auth.AuthChatListener;
import com.raeyncreations.raeyncheat.auth.AuthDatabase;
import com.raeyncreations.raeyncheat.auth.AuthManager;
import com.raeyncreations.raeyncheat.auth.AuthMovementListener;
import com.raeyncreations.raeyncheat.util.CheckFileManager;
import com.raeyncreations.raeyncheat.util.EncryptionUtil;
import com.raeyncreations.raeyncheat.util.PasskeyLogger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Mod(RaeYNCheat.MOD_ID)
public class RaeYNCheat {

    public static final String MOD_ID = "raeyncheat";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile CheckFileManager checkFileManager;
    private static volatile com.raeyncreations.raeyncheat.auth.AuthDatabase authDatabase;
    private static volatile RaeYNCheatConfig config;
    private static volatile Path configFilePath;
    private static final Object CHECK_FILE_MANAGER_LOCK = new Object();

    // ── Midnight auto-refresh ─────────────────────────────────────────────────
    private static volatile LocalDate  lastRefreshDate           = null;
    private static volatile boolean    midnightRefreshEnabled    = true;
    private static final AtomicBoolean midnightRefreshInProgress = new AtomicBoolean(false);
    private static final Object        MIDNIGHT_REFRESH_LOCK     = new Object();

    private static final int MIDNIGHT_HOUR            = 0;
    private static final int MIDNIGHT_MINUTE          = 0;
    private static final int MIDNIGHT_WINDOW_SECONDS  = 10;
    private static final int REFRESH_FLAG_RESET_DELAY = 15;

    // ── Periodic revalidation ─────────────────────────────────────────────────
    private static int  revalidationTickCounter    = 0;
    private static long revalidationElapsedSeconds = 0;

    // ── FIX #1: Login timeout tick counter ────────────────────────────────────
    /** Check for timed-out pending validations every N ticks (20 = 1 second). */
    private static int timeoutTickCounter = 0;
    private static final int TIMEOUT_CHECK_INTERVAL_TICKS = 20;

    // ── FIX #9: Debounced config save tick counter ─────────────────────────────
    private static int saveTickCounter = 0;
    private static final int SAVE_CHECK_INTERVAL_TICKS = 100; // check every 5 seconds

    // ── Server reference ──────────────────────────────────────────────────────
    private static final AtomicReference<MinecraftServer> currentServer = new AtomicReference<>(null);

    // ── Shared scheduler ──────────────────────────────────────────────────────
    private static final ScheduledExecutorService scheduledExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "RaeYNCheat-Scheduler");
                t.setDaemon(true);
                return t;
            });

    public RaeYNCheat(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener((RegisterPayloadHandlersEvent event) ->
                NetworkHandler.register(event.registrar(MOD_ID)));

        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);

        NeoForge.EVENT_BUS.addListener(PlayerConnectionHandler::onPlayerNegotiation);
        NeoForge.EVENT_BUS.addListener(PlayerConnectionHandler::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(PlayerConnectionHandler::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(AuthChatListener::onServerChat);
        NeoForge.EVENT_BUS.addListener(AuthMovementListener::onPlayerTick);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("RaeYNCheat mod initialized.");
    }

    // ---------------------------------------------------------------------------
    // Server lifecycle
    // ---------------------------------------------------------------------------

    private void onServerStarted(final ServerStartedEvent event) {
        currentServer.set(event.getServer());
        LOGGER.info("RaeYNCheat server started.");
        try {
            Path configDir     = FMLPaths.CONFIGDIR.get().resolve("RaeYNCheat");
            Path modsClientDir = FMLPaths.GAMEDIR.get().resolve("mods_client");
            Path logsDir       = FMLPaths.GAMEDIR.get().resolve("logs");
            configFilePath     = configDir.resolve("config.json");

            PasskeyLogger.initialize(logsDir);
            PasskeyLogger.logSessionSeparator("Server Started");

            config = RaeYNCheatConfig.load(configFilePath);

            // Harden AES envelope encryption with server-side secret (never ships in client JAR).
            // Must be called before any encrypt/decrypt operations — i.e. before auth DB opens.
            EncryptionUtil.initialize(config.authDbEncryptionKey);

            // Initialize DDoS rate limiter.
            ConnectionRateLimiter.initialize();

            // Initialize player authentication database (independent of mod verification).
            try {
                AuthDatabase authDb = AuthDatabase.open(configDir, config);
                authDatabase = authDb;
                AuthManager.initialize(authDb, config.authServerLabel);
                // Purge violation records older than violationExpiryDays
                authDb.purgeExpiredViolations(config.violationExpiryDays);
            } catch (Exception e) {
                LOGGER.error("[Auth] Failed to initialize auth database — auth system DISABLED.", e);
            }

            if (!java.nio.file.Files.exists(modsClientDir)) {
                LOGGER.warn("mods_client/ not found at {}. Mod verification DISABLED.", modsClientDir);
                return;
            }

            synchronized (CHECK_FILE_MANAGER_LOCK) {
                checkFileManager = new CheckFileManager(configDir, modsClientDir);
                try {
                    LOGGER.info("Generating server CheckSum_init...");
                    checkFileManager.generateServerInitCheckFile();
                    lastRefreshDate = LocalDate.now();
                    LOGGER.info("Server CheckSum_init generated.");
                } catch (IllegalStateException | FileNotFoundException e) {
                    LOGGER.error("CheckSum_init failed: {}", e.getMessage());
                    LOGGER.warn("Mod verification DISABLED.");
                    checkFileManager = null;
                } catch (Exception e) {
                    LOGGER.error("Unexpected error generating CheckSum_init", e);
                    LOGGER.warn("Mod verification DISABLED.");
                    checkFileManager = null;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Critical error during RaeYNCheat startup", e);
            LOGGER.warn("Mod verification DISABLED.");
            checkFileManager = null;
        }
    }

    private void onServerStopping(final ServerStoppingEvent event) {
        LOGGER.info("RaeYNCheat server stopping.");
        currentServer.set(null);

        // Force-save any pending dirty config changes before shutdown.
        if (config != null && configFilePath != null) {
            config.save(configFilePath);
        }

        authDatabase = null;
        ConnectionRateLimiter.shutdown();
        GeoIpLogger.shutdown();
        AuthManager.shutdown();
        EncryptionUtil.clearKeyCache();  // Purge cached AES keys from memory on shutdown.

        try {
            PasskeyLogger.logSessionSeparator("Server Stopping");
            PasskeyLogger.shutdown();
        } catch (Exception e) {
            LOGGER.debug("PasskeyLogger error during shutdown", e);
        }
        try {
            scheduledExecutor.shutdown();
            if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS))
                scheduledExecutor.shutdownNow();
        } catch (InterruptedException e) {
            scheduledExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------------------
    // Server tick
    // ---------------------------------------------------------------------------

    private void onServerTick(final ServerTickEvent.Pre event) {
        tickMidnightRefresh();
        tickPeriodicRevalidation();
        tickLoginTimeoutCheck();   // FIX #1
        tickDebouncedConfigSave(); // FIX #9
        AuthManager.onServerTick();
    }

    private void tickMidnightRefresh() {
        if (!midnightRefreshEnabled || checkFileManager == null || scheduledExecutor.isShutdown()) return;

        LocalDate today = LocalDate.now();
        LocalTime now   = LocalTime.now();

        if ((lastRefreshDate == null || !lastRefreshDate.equals(today))
                && now.getHour()   == MIDNIGHT_HOUR
                && now.getMinute() == MIDNIGHT_MINUTE
                && now.getSecond() < MIDNIGHT_WINDOW_SECONDS
                && midnightRefreshInProgress.compareAndSet(false, true)) {

            synchronized (MIDNIGHT_REFRESH_LOCK) {
                if (lastRefreshDate != null && lastRefreshDate.equals(today)) {
                    midnightRefreshInProgress.set(false);
                    return;
                }
                try {
                    LOGGER.info("Auto-refreshing CheckSum_init at midnight...");
                    checkFileManager.generateServerInitCheckFile();
                    lastRefreshDate = today;
                    LOGGER.info("CheckSum_init auto-refreshed.");
                } catch (Exception e) {
                    LOGGER.error("Error auto-refreshing CheckSum_init at midnight", e);
                } finally {
                    try {
                        scheduledExecutor.schedule(
                                () -> midnightRefreshInProgress.set(false),
                                REFRESH_FLAG_RESET_DELAY, TimeUnit.SECONDS);
                    } catch (java.util.concurrent.RejectedExecutionException e) {
                        midnightRefreshInProgress.set(false);
                    }
                }
            }
        }
    }

    private void tickPeriodicRevalidation() {
        RaeYNCheatConfig localConfig = config;
        if (localConfig == null) return;
        int intervalSeconds = localConfig.periodicRevalidationSeconds;
        if (intervalSeconds <= 0) return;

        if (++revalidationTickCounter < 20) return;
        revalidationTickCounter = 0;
        revalidationElapsedSeconds++;

        if (revalidationElapsedSeconds < intervalSeconds) return;
        revalidationElapsedSeconds = 0;

        MinecraftServer server = currentServer.get();
        if (server == null) return;

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;

        LOGGER.info("Sending periodic revalidation to {} player(s).", players.size());
        for (ServerPlayer player : players) {
            sendRevalidationRequest(player);
        }
    }

    /**
     * FIX #1: Check every second for players who logged in but never sent a SyncPacket.
     * Any player still in the pending map after SYNC_TIMEOUT_SECONDS is kicked.
     */
    private void tickLoginTimeoutCheck() {
        if (++timeoutTickCounter < TIMEOUT_CHECK_INTERVAL_TICKS) return;
        timeoutTickCounter = 0;

        List<String> timedOut = ValidationHandler.getTimedOutPlayers();
        if (timedOut.isEmpty()) return;

        MinecraftServer server = currentServer.get();
        if (server == null) return;

        for (String uuidStr : timedOut) {
            ValidationHandler.clearPending(uuidStr);
            try {
                UUID uuid = UUID.fromString(uuidStr);
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (player != null) {
                    LOGGER.warn("Player {} ({}) timed out — no SyncPacket received within {}s. Kicking.",
                            player.getName().getString(), uuidStr, ValidationHandler.SYNC_TIMEOUT_SECONDS);
                    player.connection.disconnect(
                            net.minecraft.network.chat.Component.literal(
                                    "Mod verification timed out — ensure RaeYNCheat is installed correctly."));
                }
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Invalid UUID in pending validation map: {}", uuidStr);
            }
        }
    }

    /**
     * FIX #9: Check every 5 seconds whether the config needs saving.
     * Replaces the previous approach of saving on every single violation record,
     * which could cause I/O contention under rapid reconnect floods.
     */
    private void tickDebouncedConfigSave() {
        if (++saveTickCounter < SAVE_CHECK_INTERVAL_TICKS) return;
        saveTickCounter = 0;
        RaeYNCheatConfig c = config;
        if (c != null && configFilePath != null) c.saveIfDirty(configFilePath);
    }

    // ---------------------------------------------------------------------------
    // Revalidation — FIX #10: Send nonce with each RevalidatePacket
    // ---------------------------------------------------------------------------

    public static void sendRevalidationRequest(ServerPlayer player) {
        try {
            // Generate a fresh nonce and register it before sending the packet.
            RevalidatePacket packet = RevalidatePacket.withFreshNonce();
            ValidationHandler.registerNonce(player.getUUID().toString(), packet.nonce());
            PacketDistributor.sendToPlayer(player, packet);
            LOGGER.info("Sent revalidation request (with nonce) to {}.", player.getName().getString());
        } catch (Exception e) {
            LOGGER.error("Failed to send RevalidatePacket to {}", player.getName().getString(), e);
        }
    }

    // ---------------------------------------------------------------------------
    // Violation tracking — FIX #9: markDirty instead of immediate save
    // ---------------------------------------------------------------------------

    /**
     * Record a violation in the auth database (crash-safe, no debounce needed).
     * Falls back to the config map if the DB is unavailable so violations are never silently lost.
     */
    private static int recordViolationInternal(UUID uuid, String type,
            java.util.function.Function<UUID, Integer> configFallback,
            java.util.function.Consumer<UUID> configMark) {
        com.raeyncreations.raeyncheat.auth.AuthDatabase db = authDatabase;
        if (db != null) {
            try {
                return db.recordViolation(uuid.toString(), type);
            } catch (Exception e) {
                LOGGER.error("[Violation] DB write failed for {} {} — falling back to config map", uuid, type, e);
            }
        }
        // Fallback: config map + debounced save
        RaeYNCheatConfig c = config;
        if (c == null) { LOGGER.warn("Config not loaded — {} violation for {} not recorded.", type, uuid); return 0; }
        int n = configFallback.apply(uuid);
        configMark.accept(uuid);
        return n;
    }

    public static void recordChecksumViolation(UUID uuid) {
        int n = recordViolationInternal(uuid, "checksum",
                u -> { RaeYNCheatConfig c = config; return c != null ? c.recordChecksumViolation(u) : 0; },
                u -> { RaeYNCheatConfig c = config; if (c != null) c.markDirty(); });
        RaeYNCheatConfig c = config;
        if (c != null) LOGGER.warn("Player {} checksum violations: {}. Punishment: {}", uuid, n, fmt(c.getPunishmentDuration(n)));
    }

    public static void recordPasskeyViolation(UUID uuid) {
        int n = recordViolationInternal(uuid, "passkey",
                u -> { RaeYNCheatConfig c = config; return c != null ? c.recordPasskeyViolation(u) : 0; },
                u -> { RaeYNCheatConfig c = config; if (c != null) c.markDirty(); });
        RaeYNCheatConfig c = config;
        if (c != null) LOGGER.warn("Player {} passkey violations: {}. Punishment: {}", uuid, n, fmt(c.getPasskeyPunishmentDuration(n)));
    }

    public static void recordEnvViolation(UUID uuid) {
        int n = recordViolationInternal(uuid, "env",
                u -> { RaeYNCheatConfig c = config; return c != null ? c.recordEnvViolation(u) : 0; },
                u -> { RaeYNCheatConfig c = config; if (c != null) c.markDirty(); });
        RaeYNCheatConfig c = config;
        if (c != null) LOGGER.warn("Player {} env violations: {}. Punishment: {}", uuid, n, fmt(c.getEnvPunishmentDuration(n)));
    }

    public static void recordNegotiationViolation(UUID uuid) {
        int n = recordViolationInternal(uuid, "negotiation",
                u -> { RaeYNCheatConfig c = config; return c != null ? c.recordNegotiationViolation(u) : 0; },
                u -> { RaeYNCheatConfig c = config; if (c != null) c.markDirty(); });
        RaeYNCheatConfig c = config;
        if (c != null) LOGGER.warn("Player {} negotiation violations: {}. Punishment: {}", uuid, n, fmt(c.getNegotiationPunishmentDuration(n)));
    }

    // ---------------------------------------------------------------------------
    // Violation count accessors
    // ---------------------------------------------------------------------------

    private static int getViolationCountInternal(UUID uuid, String type,
            java.util.function.Function<UUID, Integer> configFallback) {
        com.raeyncreations.raeyncheat.auth.AuthDatabase db = authDatabase;
        if (db != null) {
            try { return db.getViolationCount(uuid.toString(), type); }
            catch (Exception e) { LOGGER.warn("[Violation] DB read failed for {} {} — using config map", uuid, type); }
        }
        return configFallback.apply(uuid);
    }

    public static int getChecksumViolationCount(UUID uuid)    { return getViolationCountInternal(uuid, "checksum",    u -> { RaeYNCheatConfig c = config; return c != null ? c.getChecksumViolationCount(u)    : 0; }); }
    public static int getPasskeyViolationCount(UUID uuid)     { return getViolationCountInternal(uuid, "passkey",     u -> { RaeYNCheatConfig c = config; return c != null ? c.getPasskeyViolationCount(u)     : 0; }); }
    public static int getEnvViolationCount(UUID uuid)         { return getViolationCountInternal(uuid, "env",         u -> { RaeYNCheatConfig c = config; return c != null ? c.getEnvViolationCount(u)         : 0; }); }
    public static int getNegotiationViolationCount(UUID uuid) { return getViolationCountInternal(uuid, "negotiation", u -> { RaeYNCheatConfig c = config; return c != null ? c.getNegotiationViolationCount(u) : 0; }); }

    // ---------------------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------------------

    public static RaeYNCheatConfig getConfig()           { return config; }
    public static CheckFileManager getCheckFileManager() { synchronized (CHECK_FILE_MANAGER_LOCK) { return checkFileManager; } }
    public static MinecraftServer  getCurrentServer()    { return currentServer.get(); }

    public static com.raeyncreations.raeyncheat.auth.AuthDatabase getAuthDatabase() { return authDatabase; }

    /** Force-save immediately. Use sparingly (admin commands, shutdown). */
    public static void saveConfig() {
        if (config != null && configFilePath != null) config.save(configFilePath);
    }

    private void onRegisterCommands(final RegisterCommandsEvent event) {
        RaeYNCommand.register(event.getDispatcher(), event.getBuildContext(), event.getCommandSelection());
    }

    private static String fmt(int d) {
        return d == -1 ? "PERMANENT" : (d == 0 ? "KICK" : d + "s");
    }
}
