package com.raeyncreations.raeyncheat;

import com.raeyncreations.raeyncheat.config.RaeYNCheatConfig;
import com.raeyncreations.raeyncheat.server.PlayerConnectionHandler;
import com.raeyncreations.raeyncheat.server.RaeYNCommand;
import com.raeyncreations.raeyncheat.util.CheckFileManager;
import com.raeyncreations.raeyncheat.util.PasskeyLogger;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod(RaeYNCheat.MOD_ID)
public class RaeYNCheat {
    
    public static final String MOD_ID = "raeyncheat";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    
    private static CheckFileManager checkFileManager;
    private static RaeYNCheatConfig config;
    private static Path configFilePath;
    private static final Map<UUID, Integer> checksumViolations = new HashMap<>();
    private static final Map<UUID, Integer> passkeyViolations = new HashMap<>();
    
    // Midnight auto-refresh tracking
    private static LocalDate lastRefreshDate = null;
    private static boolean midnightRefreshEnabled = true;
    
    public RaeYNCheat(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        
        // Register server events
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
        
        // Register player connection events for passkey logging
        NeoForge.EVENT_BUS.addListener(PlayerConnectionHandler::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(PlayerConnectionHandler::onPlayerLoggedOut);
    }
    
    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("RaeYNCheat mod initialized");
    }
    
    private void onServerStarted(final ServerStartedEvent event) {
        LOGGER.info("RaeYNCheat server started");
        
        // Get paths
        Path configDir = FMLPaths.CONFIGDIR.get().resolve("RaeYNCheat");
        Path modsClientDir = FMLPaths.GAMEDIR.get().resolve("mods_client");
        Path logsDir = FMLPaths.GAMEDIR.get().resolve("logs");
        configFilePath = configDir.resolve("config.json");
        
        // Initialize passkey logger
        PasskeyLogger.initialize(logsDir);
        PasskeyLogger.logSessionSeparator("Server Started");
        
        // Load config
        config = RaeYNCheatConfig.load(configFilePath);
        
        // Initialize check file manager
        checkFileManager = new CheckFileManager(configDir, modsClientDir);
        
        // Generate CheckSum_init file on server boot with comprehensive error handling
        try {
            LOGGER.info("Generating server CheckSum_init file...");
            checkFileManager.generateServerInitCheckFile();
            LOGGER.info("Server CheckSum_init file generated successfully");
            lastRefreshDate = LocalDate.now(); // Track initial generation
        } catch (IllegalStateException e) {
            LOGGER.error("CheckSum_init generation failed - invalid state: {}", e.getMessage());
            LOGGER.warn("Server will continue but mod verification is DISABLED. Issue: {}", e.getMessage());
        } catch (FileNotFoundException e) {
            LOGGER.error("CheckSum_init generation failed - directory not found: {}", e.getMessage());
            LOGGER.warn("Server will continue but mod verification is DISABLED. Please ensure mods_client directory exists");
        } catch (Exception e) {
            LOGGER.error("Error generating server CheckSum_init file", e);
            LOGGER.warn("Server will continue but mod verification is DISABLED due to unexpected error");
        }
    }
    
    /**
     * Check every server tick for midnight to auto-refresh CheckSum_init
     */
    private void onServerTick(final ServerTickEvent.Pre event) {
        if (!midnightRefreshEnabled || checkFileManager == null) {
            return;
        }
        
        LocalDate today = LocalDate.now();
        
        // Check if it's a new day and we haven't refreshed today yet
        if (lastRefreshDate == null || !lastRefreshDate.equals(today)) {
            LocalTime now = LocalTime.now();
            
            // Check if it's past midnight (between 00:00 and 00:05 to ensure we don't miss it)
            if (now.getHour() == 0 && now.getMinute() < 5) {
                try {
                    LOGGER.info("Auto-refreshing CheckSum_init file at midnight...");
                    checkFileManager.generateServerInitCheckFile();
                    lastRefreshDate = today;
                    LOGGER.info("CheckSum_init file auto-refreshed successfully");
                } catch (Exception e) {
                    LOGGER.error("Error auto-refreshing CheckSum_init file at midnight", e);
                }
            }
        }
    }
    
    private void onServerStopping(final ServerStoppingEvent event) {
        LOGGER.info("RaeYNCheat server stopping");
        PasskeyLogger.logSessionSeparator("Server Stopping");
    }
    
    private void onRegisterCommands(final RegisterCommandsEvent event) {
        RaeYNCommand.register(event.getDispatcher(), event.getBuildContext(), event.getCommandSelection());
    }
    
    public static void recordViolation(UUID playerUUID) {
        recordChecksumViolation(playerUUID);
    }
    
    public static void recordChecksumViolation(UUID playerUUID) {
        int violations = checksumViolations.getOrDefault(playerUUID, 0) + 1;
        checksumViolations.put(playerUUID, violations);
        
        int duration = config.getPunishmentDuration(violations);
        LOGGER.warn("Player " + playerUUID + " has " + violations + " checksum violations. Punishment duration: " + 
            (duration == -1 ? "PERMANENT" : duration + " seconds"));
    }
    
    public static void recordPasskeyViolation(UUID playerUUID) {
        int violations = passkeyViolations.getOrDefault(playerUUID, 0) + 1;
        passkeyViolations.put(playerUUID, violations);
        
        int duration = config.getPasskeyPunishmentDuration(violations);
        LOGGER.warn("Player " + playerUUID + " has " + violations + " passkey violations. Punishment duration: " + 
            (duration == -1 ? "PERMANENT" : duration + " seconds"));
    }
    
    public static int getChecksumViolationCount(UUID playerUUID) {
        return checksumViolations.getOrDefault(playerUUID, 0);
    }
    
    public static int getPasskeyViolationCount(UUID playerUUID) {
        return passkeyViolations.getOrDefault(playerUUID, 0);
    }
    
    public static RaeYNCheatConfig getConfig() {
        return config;
    }
    
    public static void saveConfig() {
        if (config != null && configFilePath != null) {
            config.save(configFilePath);
            LOGGER.info("Configuration saved to " + configFilePath);
        }
    }
    
    public static CheckFileManager getCheckFileManager() {
        return checkFileManager;
    }
}
