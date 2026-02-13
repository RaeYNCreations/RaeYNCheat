package com.raeyncreations.raeyncheat.client;

import com.raeyncreations.raeyncheat.RaeYNCheat;
import com.raeyncreations.raeyncheat.util.CheckFileManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public class RaeYNCheatClient implements ClientModInitializer {
    
    private static CheckFileManager checkFileManager;
    
    @Override
    public void onInitializeClient() {
        RaeYNCheat.LOGGER.info("RaeYNCheat client initialized");
        
        // Get paths
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("RaeYNCheat");
        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        
        // Initialize check file manager
        checkFileManager = new CheckFileManager(configDir, modsDir);
        
        // Generate check file on client boot
        generateClientCheckFile();
        
        // Listen for server connection events
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // Regenerate check file when joining server
            generateClientCheckFile();
        });
    }
    
    private void generateClientCheckFile() {
        try {
            // Get player UUID (use a placeholder for now, will be replaced with actual UUID)
            String playerUUID = getPlayerUUID();
            
            RaeYNCheat.LOGGER.info("Generating client check file...");
            checkFileManager.generateClientCheckFile(playerUUID);
            RaeYNCheat.LOGGER.info("Client check file generated successfully");
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Error generating client check file", e);
        }
    }
    
    private String getPlayerUUID() {
        // Try to get actual player UUID, fallback to placeholder
        try {
            var client = net.minecraft.client.MinecraftClient.getInstance();
            if (client.getSession() != null && client.getSession().getUuidOrNull() != null) {
                return client.getSession().getUuidOrNull().toString();
            }
        } catch (Exception e) {
            RaeYNCheat.LOGGER.warn("Could not get player UUID, using placeholder");
        }
        return "00000000-0000-0000-0000-000000000000";
    }
    
    public static CheckFileManager getCheckFileManager() {
        return checkFileManager;
    }
}
