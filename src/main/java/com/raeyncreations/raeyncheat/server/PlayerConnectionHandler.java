package com.raeyncreations.raeyncheat.server;

import com.raeyncreations.raeyncheat.RaeYNCheat;
import com.raeyncreations.raeyncheat.util.PasskeyLogger;
import com.raeyncreations.raeyncheat.util.EncryptionUtil;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.io.FileNotFoundException;

/**
 * Server-side event handler for player connections
 * Logs passkey-related events when players join
 */
public class PlayerConnectionHandler {
    
    /**
     * Handle player login event
     * This is where passkey validation would occur
     */
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            String playerUUID = player.getUUID().toString();
            String playerUsername = player.getName().getString();
            
            // Log the player connection event
            PasskeyLogger.logSessionSeparator("Player Connected: " + playerUsername + " (UUID: " + playerUUID + ")");
            
            RaeYNCheat.LOGGER.info("Player {} (UUID: {}) connected to server", playerUsername, playerUUID);
            
            // Generate passkey once for error logging
            String expectedPasskey = EncryptionUtil.generatePasskey(playerUUID);
            
            try {
                // Generate server-side check file for this player
                // Note: generateServerCheckFile already logs the passkey generation internally
                if (RaeYNCheat.getCheckFileManager() != null) {
                    RaeYNCheat.getCheckFileManager().generateServerCheckFile(playerUUID, playerUsername);
                    RaeYNCheat.LOGGER.info("Generated server check file for player {}", playerUsername);
                } else {
                    RaeYNCheat.LOGGER.warn("CheckFileManager not initialized, cannot generate check file for player {}", playerUsername);
                }
            } catch (FileNotFoundException e) {
                RaeYNCheat.LOGGER.error("CheckSum_init file not found when generating check file for player {}: {}", 
                    playerUsername, e.getMessage());
                PasskeyLogger.logError(playerUsername, playerUUID, expectedPasskey, 
                    "CHECKSUM_INIT_NOT_FOUND", 
                    "CheckSum_init file not found - server may not have generated it yet", e);
            } catch (IllegalStateException e) {
                RaeYNCheat.LOGGER.error("Invalid state when generating check file for player {}: {}", 
                    playerUsername, e.getMessage());
                PasskeyLogger.logError(playerUsername, playerUUID, expectedPasskey, 
                    "INVALID_STATE", 
                    "CheckSum_init file may be corrupted or empty: " + e.getMessage(), e);
            } catch (Exception e) {
                RaeYNCheat.LOGGER.error("Failed to generate server check file for player " + playerUsername, e);
                PasskeyLogger.logError(playerUsername, playerUUID, expectedPasskey, 
                    "SERVER_CHECK_FILE_GENERATION", 
                    "Failed to generate server check file", e);
            }
        }
    }
    
    /**
     * Handle player logout event
     */
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            String playerUUID = player.getUUID().toString();
            String playerUsername = player.getName().getString();
            
            PasskeyLogger.logSessionSeparator("Player Disconnected: " + playerUsername + " (UUID: " + playerUUID + ")");
            
            RaeYNCheat.LOGGER.info("Player {} (UUID: {}) disconnected from server", playerUsername, playerUUID);
        }
    }
}
