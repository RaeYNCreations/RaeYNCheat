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
     * Server check file generation is now triggered by client sync packet
     */
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            String playerUUID = player.getUUID().toString();
            String playerUsername = player.getName().getString();
            
            // Log the player connection event
            PasskeyLogger.logSessionSeparator("Player Connected: " + playerUsername + " (UUID: " + playerUUID + ")");
            
            RaeYNCheat.LOGGER.info("Player {} (UUID: {}) connected to server - waiting for client sync packet", playerUsername, playerUUID);
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
