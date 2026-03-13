package com.raeyncreations.raeyncheat.client;

import com.raeyncreations.raeyncheat.RaeYNCheat;
import com.raeyncreations.raeyncheat.network.SyncPacket;
import com.raeyncreations.raeyncheat.util.CheckFileManager;
import com.raeyncreations.raeyncheat.util.EncryptionUtil;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Mod(value = RaeYNCheat.MOD_ID, dist = Dist.CLIENT)
public class RaeYNCheatClient {

    private static final AtomicReference<CheckFileManager> checkFileManager = new AtomicReference<>();

    // ── Revalidation state ────────────────────────────────────────────────────
    private static final AtomicLong    lastSyncTime          = new AtomicLong(0);
    private static final AtomicBoolean connected             = new AtomicBoolean(false);
    /** Set when a RevalidatePacket arrives; cleared once we respond. */
    private static final AtomicBoolean revalidationRequested = new AtomicBoolean(false);
    /**
     * FIX #10 (nonce echo): Stores the nonce from the most recent RevalidatePacket so it
     * can be echoed back in the SyncPacket. Empty string = login sync (no nonce issued yet).
     */
    private static final AtomicReference<String> pendingNonce = new AtomicReference<>("");

    /** Client tick counter — only read/written from the client main thread. Not volatile by design. */
    private static int tickCounter = 0;
    private static final int TICK_CHECK_INTERVAL = 20;

    public RaeYNCheatClient(IEventBus modEventBus) {
        modEventBus.addListener(this::clientSetup);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedOut);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
    }

    // ---------------------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------------------

    private void clientSetup(final FMLClientSetupEvent event) {
        RaeYNCheat.LOGGER.info("RaeYNCheat client initialized.");
        try {
            Path configDir = FMLPaths.CONFIGDIR.get().resolve("RaeYNCheat");
            Path modsDir   = FMLPaths.GAMEDIR.get().resolve("mods");

            if (!Files.exists(modsDir)) {
                RaeYNCheat.LOGGER.warn("mods/ directory not found at {}. Client verification DISABLED.", modsDir);
                return;
            }

            checkFileManager.set(new CheckFileManager(configDir, modsDir));
            RaeYNCheat.LOGGER.info("RaeYNCheat CheckFileManager ready.");

        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("RaeYNCheat client initialization error", e);
            checkFileManager.set(null);
        }
    }

    // ---------------------------------------------------------------------------
    // Connection events
    // ---------------------------------------------------------------------------

    private void onPlayerLoggedIn(final ClientPlayerNetworkEvent.LoggingIn event) {
        connected.set(true);
        lastSyncTime.set(0);
        pendingNonce.set(""); // Login sync has no nonce — server accepts empty on first contact.
        if (checkFileManager.get() != null) {
            performFullSync("LOGIN", "");
        }
    }

    private void onPlayerLoggedOut(final ClientPlayerNetworkEvent.LoggingOut event) {
        connected.set(false);
        lastSyncTime.set(0);
        pendingNonce.set("");
        revalidationRequested.set(false);
    }

    // ---------------------------------------------------------------------------
    // Client tick
    // ---------------------------------------------------------------------------

    private void onClientTick(final ClientTickEvent.Pre event) {
        if (!connected.get() || checkFileManager.get() == null) return;

        // FIX #10: Drain the pending nonce before using it so concurrent triggers don't race.
        if (revalidationRequested.compareAndSet(true, false)) {
            String nonce = pendingNonce.getAndSet("");
            RaeYNCheat.LOGGER.info("Processing server-requested revalidation (nonce: {}).",
                    nonce.isEmpty() ? "none" : "present");
            performFullSync("SERVER_REQUEST", nonce);
            return;
        }

        if (++tickCounter < TICK_CHECK_INTERVAL) return;
        tickCounter = 0;

        int intervalSeconds = 300;
        long now     = System.currentTimeMillis();
        long elapsed = (now - lastSyncTime.get()) / 1000L;

        if (lastSyncTime.get() > 0 && elapsed >= intervalSeconds) {
            RaeYNCheat.LOGGER.info("Periodic revalidation triggered ({}s elapsed).", elapsed);
            // Periodic self-driven sync has no nonce — server only enforces nonce on
            // RevalidatePacket responses, not on client-initiated syncs.
            performFullSync("PERIODIC", "");
        }
    }

    // ---------------------------------------------------------------------------
    // Called from RevalidatePacket.handle() — static, called from network thread context
    // ---------------------------------------------------------------------------

    /**
     * FIX #10: Accepts the nonce from the RevalidatePacket and stores it for the next sync.
     */
    public static void triggerRevalidation(String nonce) {
        pendingNonce.set(nonce != null ? nonce : "");
        revalidationRequested.set(true);
    }

    // ---------------------------------------------------------------------------
    // Core sync logic
    // ---------------------------------------------------------------------------

    private static void performFullSync(String reason, String nonce) {
        CheckFileManager manager = checkFileManager.get();
        if (manager == null) return;

        try {
            String playerUUID     = getPlayerUUID();
            String playerUsername = getPlayerUsername();

            // ── 1. Generate checksum in memory (no disk round-trip) ──────────
            RaeYNCheat.LOGGER.info("[{}] Generating client checksum for {} ...", reason, playerUsername);
            String clientChecksum = manager.generateClientChecksumInMemory(playerUUID, playerUsername);
            if (clientChecksum == null || clientChecksum.isEmpty()) {
                RaeYNCheat.LOGGER.error("[{}] Client checksum generation returned empty result.", reason);
                return;
            }

            // ── 2. Derive passkey ─────────────────────────────────────────────
            String clientPasskey = EncryptionUtil.generatePasskey(playerUUID).trim();
            if (clientPasskey.isEmpty()) {
                RaeYNCheat.LOGGER.error("[{}] Generated passkey is empty.", reason);
                return;
            }

            // ── 3. Run environment scan and encrypt the report ────────────────
            RaeYNCheat.LOGGER.info("[{}] Running environment scan...", reason);
            String rawReport       = EnvironmentScanner.generateReport();
            String encryptedReport = EncryptionUtil.encrypt(rawReport, clientPasskey);
            if (encryptedReport == null || encryptedReport.isEmpty()) {
                RaeYNCheat.LOGGER.error("[{}] Environment report encryption failed.", reason);
                return;
            }

            // ── 4. Send SyncPacket with nonce echo ────────────────────────────
            RaeYNCheat.LOGGER.info("[{}] Sending SyncPacket (nonce: {})...", reason,
                    nonce.isEmpty() ? "none" : "present");
            PacketDistributor.sendToServer(
                    new SyncPacket(clientPasskey, clientChecksum, encryptedReport, nonce));
            lastSyncTime.set(System.currentTimeMillis());
            RaeYNCheat.LOGGER.info("[{}] SyncPacket sent.", reason);

        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("[{}] Error during full sync", reason, e);
        }
    }

    // ---------------------------------------------------------------------------
    // Identity helpers
    // ---------------------------------------------------------------------------

    private static String getPlayerUUID() {
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.getUser() != null) {
                var id = mc.getUser().getProfileId();
                if (id != null) return id.toString();
            }
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Could not get player UUID", e);
        }
        throw new IllegalStateException("Authentication failed - unable to verify client identity");
    }

    private static String getPlayerUsername() {
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc != null && mc.getUser() != null) {
                var name = mc.getUser().getName();
                if (name != null) return name;
            }
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Could not get player username", e);
        }
        return "Unknown";
    }

    public static CheckFileManager getCheckFileManager() {
        return checkFileManager.get();
    }
}
