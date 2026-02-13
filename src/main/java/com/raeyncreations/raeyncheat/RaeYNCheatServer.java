package com.raeyncreations.raeyncheat.server;

import com.raeyncreations.raeyncheat.RaeYNCheat;
import com.raeyncreations.raeyncheat.config.RaeYNCheatConfig;
import com.raeyncreations.raeyncheat.util.CheckFileManager;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RaeYNCheatServer implements DedicatedServerModInitializer {
    
    private static CheckFileManager checkFileManager;
    private static RaeYNCheatConfig config;
    private static final Map<UUID, Integer> playerViolations = new HashMap<>();
    
    @Override
    public void onInitializeServer() {
        RaeYNCheat.LOGGER.info("RaeYNCheat server initialized");
        
        // Get paths
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("RaeYNCheat");
        Path modsClientDir = FabricLoader.getInstance().getGameDir().resolve("mods_client");
        Path configFile = configDir.resolve("config.json");
        
        // Load config
        config = RaeYNCheatConfig.load(configFile);
        
        // Initialize check file manager
        checkFileManager = new CheckFileManager(configDir, modsClientDir);
        
        // Generate CheckSum_init file on server boot
        generateServerInitCheckFile();
        
        // Listen for player connection events
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            UUID playerUUID = handler.getPlayer().getUuid();
            onPlayerConnect(playerUUID);
        });
        
        // Register commands
        CommandRegistrationCallback.EVENT.register(PunishCommand::register);
    }
    
    private void generateServerInitCheckFile() {
        try {
            RaeYNCheat.LOGGER.info("Generating server CheckSum_init file...");
            checkFileManager.generateServerInitCheckFile();
            RaeYNCheat.LOGGER.info("Server CheckSum_init file generated successfully");
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Error generating server CheckSum_init file", e);
        }
    }
    
    private void onPlayerConnect(UUID playerUUID) {
        try {
            RaeYNCheat.LOGGER.info("Player connecting: " + playerUUID);
            
            // Generate server-side CheckSum for this player
            checkFileManager.generateServerCheckFile(playerUUID.toString());
            
            // TODO: Implement actual check comparison with client
            // For now, just log that the server check file was generated
            RaeYNCheat.LOGGER.info("Server check file generated for player: " + playerUUID);
            
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Error processing player connection", e);
        }
    }
    
    public static void recordViolation(UUID playerUUID) {
        int violations = playerViolations.getOrDefault(playerUUID, 0) + 1;
        playerViolations.put(playerUUID, violations);
        
        int duration = config.getPunishmentDuration(violations);
        RaeYNCheat.LOGGER.warn("Player " + playerUUID + " has " + violations + " violations. Punishment duration: " + 
            (duration == -1 ? "PERMANENT" : duration + " seconds"));
    }
    
    public static RaeYNCheatConfig getConfig() {
        return config;
    }
    
    public static CheckFileManager getCheckFileManager() {
        return checkFileManager;
    }
}
