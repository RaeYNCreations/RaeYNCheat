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
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Files;
import java.nio.file.Path;

@Mod(value = RaeYNCheat.MOD_ID, dist = Dist.CLIENT)
public class RaeYNCheatClient {
    
    private static volatile CheckFileManager checkFileManager;
    
    public RaeYNCheatClient(IEventBus modEventBus) {
        modEventBus.addListener(this::clientSetup);
        
        // Register client events
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
    }
    
    private void clientSetup(final FMLClientSetupEvent event) {
        RaeYNCheat.LOGGER.info("RaeYNCheat client initialized");
        
        try {
            // Get paths
            Path configDir = FMLPaths.CONFIGDIR.get().resolve("RaeYNCheat");
            Path modsDir = FMLPaths.GAMEDIR.get().resolve("mods");
            
            // Validate mods directory exists
            if (!java.nio.file.Files.exists(modsDir)) {
                RaeYNCheat.LOGGER.warn("mods directory does not exist at: {}. Client check file generation is DISABLED.", modsDir);
                return; // Exit early, checkFileManager remains null
            }
            
            // Initialize check file manager only if directory exists
            checkFileManager = new CheckFileManager(configDir, modsDir);
            
            // Generate check file on client boot
            generateClientCheckFile();
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Error during client initialization", e);
            RaeYNCheat.LOGGER.warn("Client check file generation is DISABLED due to initialization failure");
            checkFileManager = null;
        }
    }
    
    private void onPlayerLoggedIn(final ClientPlayerNetworkEvent.LoggingIn event) {
        // Regenerate check file and send to server when joining
        if (checkFileManager != null) {
            generateClientCheckFileAndSync();
        } else {
            RaeYNCheat.LOGGER.debug("CheckFileManager not initialized, skipping client check file generation and sync");
        }
    }
    
    private void generateClientCheckFile() {
        // Only generate if checkFileManager is initialized
        if (checkFileManager == null) {
            RaeYNCheat.LOGGER.debug("CheckFileManager not initialized, skipping check file generation");
            return;
        }
        
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
    
    private void generateClientCheckFileAndSync() {
        // Only generate and sync if checkFileManager is initialized
        if (checkFileManager == null) {
            RaeYNCheat.LOGGER.debug("CheckFileManager not initialized, skipping check file generation and sync");
            return;
        }
        
        try {
            String playerUUID = getPlayerUUID();
            String playerUsername = getPlayerUsername();
            
            RaeYNCheat.LOGGER.info("Generating client check file...");
            checkFileManager.generateClientCheckFile(playerUUID, playerUsername);
            RaeYNCheat.LOGGER.info("Client check file generated successfully");
            
            // Read the generated CheckSum file
            Path configDir = FMLPaths.CONFIGDIR.get().resolve("RaeYNCheat");
            Path checkSumFile = configDir.resolve("CheckSum");
            
            if (!Files.exists(checkSumFile)) {
                RaeYNCheat.LOGGER.error("CheckSum file not found after generation at: {}", checkSumFile);
                RaeYNCheat.LOGGER.error("Cannot sync with server - check file generation failed");
                return;
            }
            
            String clientChecksum = Files.readString(checkSumFile);
            
            // Validate checksum is not null or empty
            if (clientChecksum == null || clientChecksum.trim().isEmpty()) {
                RaeYNCheat.LOGGER.error("Generated CheckSum file is empty or invalid");
                RaeYNCheat.LOGGER.error("Cannot sync with server - invalid checksum");
                return;
            }
            
            // Generate passkey
            String clientPasskey = EncryptionUtil.generatePasskey(playerUUID);
            
            // Validate passkey is not null or empty
            if (clientPasskey == null || clientPasskey.trim().isEmpty()) {
                RaeYNCheat.LOGGER.error("Generated passkey is empty or invalid");
                RaeYNCheat.LOGGER.error("Cannot sync with server - invalid passkey");
                return;
            }
            
            // Send sync packet to server
            RaeYNCheat.LOGGER.info("Sending sync packet to server (passkey length: {}, checksum length: {})...", 
                clientPasskey.length(), clientChecksum.length());
            PacketDistributor.sendToServer(new SyncPacket(clientPasskey, clientChecksum));
            RaeYNCheat.LOGGER.info("Sync packet sent to server successfully");
            
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Error generating client check file and syncing", e);
        }
    }
    
    private String getPlayerUUID() {
        try {
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft != null && minecraft.getUser() != null) {
                var profileId = minecraft.getUser().getProfileId();
                if (profileId != null) {
                    return profileId.toString();
                }
            }
        } catch (Exception e) {
            RaeYNCheat.LOGGER.warn("Could not get player UUID, using placeholder");
        }
        return "00000000-0000-0000-0000-000000000000";
    }
    
    private String getPlayerUsername() {
        try {
            var minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft != null && minecraft.getUser() != null) {
                var name = minecraft.getUser().getName();
                if (name != null) {
                    return name;
                }
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
