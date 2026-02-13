package com.raeyncreations.raeyncheat.client;

import com.raeyncreations.raeyncheat.RaeYNCheat;
import com.raeyncreations.raeyncheat.util.CheckFileManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;

@Mod(value = RaeYNCheat.MOD_ID, dist = Dist.CLIENT)
public class RaeYNCheatClient {
    
    private static CheckFileManager checkFileManager;
    
    public RaeYNCheatClient(IEventBus modEventBus) {
        modEventBus.addListener(this::clientSetup);
        
        // Register client events
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
    }
    
    private void clientSetup(final FMLClientSetupEvent event) {
        RaeYNCheat.LOGGER.info("RaeYNCheat client initialized");
        
        // Get paths
        Path configDir = FMLPaths.CONFIGDIR.get().resolve("RaeYNCheat");
        Path modsDir = FMLPaths.GAMEDIR.get().resolve("mods");
        
        // Initialize check file manager
        checkFileManager = new CheckFileManager(configDir, modsDir);
        
        // Generate check file on client boot
        generateClientCheckFile();
    }
    
    private void onPlayerLoggedIn(final ClientPlayerNetworkEvent.LoggingIn event) {
        // Regenerate check file when joining server
        generateClientCheckFile();
    }
    
    private void generateClientCheckFile() {
        try {
            String playerUUID = getPlayerUUID();
            String playerUsername = getPlayerUsername();
            
            RaeYNCheat.LOGGER.info("Generating client check file...");
            checkFileManager.generateClientCheckFile(playerUUID, playerUsername);
            RaeYNCheat.LOGGER.info("Client check file generated successfully");
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Error generating client check file", e);
        }
    }
    
    private String getPlayerUUID() {
        try {
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft.getUser() != null && minecraft.getUser().getProfileId() != null) {
                return minecraft.getUser().getProfileId().toString();
            }
        } catch (Exception e) {
            RaeYNCheat.LOGGER.warn("Could not get player UUID, using placeholder");
        }
        return "00000000-0000-0000-0000-000000000000";
    }
    
    private String getPlayerUsername() {
        try {
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft.getUser() != null && minecraft.getUser().getName() != null) {
                return minecraft.getUser().getName();
            }
        } catch (Exception e) {
            RaeYNCheat.LOGGER.warn("Could not get player username, using placeholder");
        }
        return "Unknown";
    }
    
    public static CheckFileManager getCheckFileManager() {
        return checkFileManager;
    }
}
