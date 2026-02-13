package com.raeyncreations.raeyncheat.server;

import com.raeyncreations.raeyncheat.RaeYNCheat;
import com.raeyncreations.raeyncheat.config.RaeYNCheatConfig;
import com.raeyncreations.raeyncheat.util.CheckFileManager;
import com.raeyncreations.raeyncheat.util.EncryptionUtil;
import com.raeyncreations.raeyncheat.util.PasskeyLogger;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Handles validation of player passkeys and checksums
 */
public class ValidationHandler {
    
    /**
     * Validate a player's passkey and checksum
     * Flow: 
     * 1. Validate client passkey matches server-expected passkey
     * 2. Use validated passkey to encrypt CheckSum_init to generate expected checksum
     * 3. Compare client checksum with server-generated checksum
     */
    public static void validatePlayer(ServerPlayer player, String clientPasskey, String clientChecksum) {
        String playerUUID = player.getUUID().toString();
        String playerUsername = player.getName().getString();
        
        RaeYNCheat.LOGGER.info("Validating player {} (UUID: {})", playerUsername, playerUUID);
        
        CheckFileManager checkFileManager = RaeYNCheat.getCheckFileManager();
        
        // If check file manager is not initialized, REJECT connection (fail-closed security)
        if (checkFileManager == null) {
            RaeYNCheat.LOGGER.error("CheckFileManager not initialized - REJECTING connection for player {} (UUID: {})", 
                playerUsername, playerUUID);
            PasskeyLogger.logError(playerUsername, playerUUID, clientPasskey, 
                "VALIDATION_FAILED", "CheckFileManager not initialized - mod verification system unavailable", null);
            player.connection.disconnect(Component.literal("Server mod verification system unavailable - connection rejected"));
            return;
        }
        
        boolean passkeyValid = false;
        boolean checksumValid = false;
        
        // Step 1: Validate passkey first
        try {
            RaeYNCheat.LOGGER.info("Step 1/2: Validating passkey for player {} (UUID: {})...", playerUsername, playerUUID);
            RaeYNCheat.LOGGER.debug("Client passkey (first 20 chars): {}...", 
                clientPasskey.length() > 20 ? clientPasskey.substring(0, 20) : clientPasskey);
            
            passkeyValid = checkFileManager.validatePasskey(clientPasskey, playerUUID, playerUsername);
            
            if (!passkeyValid) {
                RaeYNCheat.LOGGER.warn("Passkey validation FAILED for player {} (UUID: {})", playerUsername, playerUUID);
                handlePasskeyViolation(player);
                return; // Don't proceed if passkey fails
            }
            
            RaeYNCheat.LOGGER.info("Passkey validation PASSED for player {} (UUID: {})", playerUsername, playerUUID);
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Error validating passkey for player {}", playerUsername, e);
            PasskeyLogger.logError(playerUsername, playerUUID, clientPasskey, 
                "PASSKEY_VALIDATION_ERROR", "Exception during passkey validation: " + e.getMessage(), e);
            handlePasskeyViolation(player);
            return;
        }
        
        // Step 2 & 3: Generate server checksum using validated passkey, then compare
        try {
            // Generate server-side check file using the validated passkey
            RaeYNCheat.LOGGER.info("Step 2/2: Generating server checksum for player {} using validated passkey...", playerUsername);
            checkFileManager.generateServerCheckFile(playerUUID, playerUsername, clientPasskey);
            
            // Read the generated server checksum (encrypted, for direct comparison)
            String serverChecksum = checkFileManager.readEncryptedCheckSum();
            
            RaeYNCheat.LOGGER.debug("Client checksum (first 50 chars): {}", 
                clientChecksum != null && clientChecksum.length() > 50 ? 
                clientChecksum.substring(0, 50) + "..." : clientChecksum);
            RaeYNCheat.LOGGER.debug("Server checksum (first 50 chars): {}", 
                serverChecksum != null && serverChecksum.length() > 50 ? 
                serverChecksum.substring(0, 50) + "..." : serverChecksum);
            
            // Validate both checksums are not null or empty
            if (clientChecksum == null || clientChecksum.trim().isEmpty()) {
                RaeYNCheat.LOGGER.error("Client checksum is null or empty for player {}", playerUsername);
                PasskeyLogger.logError(playerUsername, playerUUID, clientChecksum, 
                    "CLIENT_CHECKSUM_INVALID", "Client checksum is null or empty", null);
                handleChecksumViolation(player);
                return;
            }
            
            if (serverChecksum == null || serverChecksum.trim().isEmpty()) {
                RaeYNCheat.LOGGER.error("Server checksum generation resulted in null or empty for player {}", playerUsername);
                PasskeyLogger.logError(playerUsername, playerUUID, clientChecksum, 
                    "SERVER_CHECKSUM_INVALID", "Server checksum is null or empty", null);
                handleChecksumViolation(player);
                return;
            }
            
            // Compare checksums by decrypting first (encrypted checksums have random IVs, so direct comparison fails)
            checksumValid = checkFileManager.compareCheckSums(clientChecksum, serverChecksum, 
                clientPasskey, playerUUID, playerUsername);
            
            RaeYNCheat.LOGGER.debug("Checksum comparison result: {}", checksumValid);
            
            if (!checksumValid) {
                RaeYNCheat.LOGGER.warn("Checksum validation FAILED for player {} (UUID: {})", playerUsername, playerUUID);
                RaeYNCheat.LOGGER.warn("Client checksum length: {}", clientChecksum.length());
                RaeYNCheat.LOGGER.warn("Server checksum length: {}", serverChecksum.length());
                
                PasskeyLogger.logValidationFailure(playerUsername, playerUUID, clientChecksum, serverChecksum, 
                    "Checksum mismatch - Client mods do not match server expectations");
                
                handleChecksumViolation(player);
                return;
            }
            
            RaeYNCheat.LOGGER.info("Checksum validation PASSED for player {} (UUID: {})", playerUsername, playerUUID);
            PasskeyLogger.logValidationSuccess(playerUsername, playerUUID, clientChecksum, serverChecksum);
            
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Error validating checksum for player {}", playerUsername, e);
            PasskeyLogger.logError(playerUsername, playerUUID, clientChecksum, 
                "CHECKSUM_VALIDATION_ERROR", "Exception during checksum validation: " + e.getMessage(), e);
            handleChecksumViolation(player);
            return;
        }
        
        RaeYNCheat.LOGGER.info("Player {} (UUID: {}) validation completed successfully", playerUsername, playerUUID);
    }
    
    /**
     * Handle passkey validation failure
     */
    private static void handlePasskeyViolation(ServerPlayer player) {
        UUID playerUUID = player.getUUID();
        String playerUsername = player.getName().getString();
        
        // Record violation
        RaeYNCheat.recordPasskeyViolation(playerUUID);
        
        int violationCount = RaeYNCheat.getPasskeyViolationCount(playerUUID);
        RaeYNCheatConfig config = RaeYNCheat.getConfig();
        
        if (config == null) {
            // No config, kick player with default message
            player.connection.disconnect(Component.literal("Passkey validation failed"));
            RaeYNCheat.LOGGER.warn("Kicked player {} for passkey violation (config not loaded)", playerUsername);
            return;
        }
        
        int punishmentDuration = config.getPasskeyPunishmentDuration(violationCount);
        
        if (punishmentDuration == -1) {
            // Permanent ban
            player.connection.disconnect(Component.literal("Permanently banned for passkey validation failure"));
            RaeYNCheat.LOGGER.warn("Permanently banned player {} for passkey violation (violation count: {})", 
                playerUsername, violationCount);
            
            // Add to blacklist (TODO: implement blacklist system)
            
        } else if (punishmentDuration > 0) {
            // Temporary ban
            player.connection.disconnect(Component.literal(
                "Banned for " + punishmentDuration + " seconds for passkey validation failure (violation " + violationCount + ")"));
            RaeYNCheat.LOGGER.warn("Banned player {} for {} seconds (passkey violation count: {})", 
                playerUsername, punishmentDuration, violationCount);
            
            // Schedule unban (TODO: implement timed ban system)
            
        } else {
            // Kick only (duration = 0)
            player.connection.disconnect(Component.literal("Passkey validation failed"));
            RaeYNCheat.LOGGER.warn("Kicked player {} for passkey violation (violation count: {})", 
                playerUsername, violationCount);
        }
    }
    
    /**
     * Handle checksum validation failure
     */
    private static void handleChecksumViolation(ServerPlayer player) {
        UUID playerUUID = player.getUUID();
        String playerUsername = player.getName().getString();
        
        // Record violation
        RaeYNCheat.recordChecksumViolation(playerUUID);
        
        int violationCount = RaeYNCheat.getChecksumViolationCount(playerUUID);
        RaeYNCheatConfig config = RaeYNCheat.getConfig();
        
        if (config == null) {
            // No config, kick player with default message
            player.connection.disconnect(Component.literal("Mod verification failed - unauthorized mods detected"));
            RaeYNCheat.LOGGER.warn("Kicked player {} for checksum violation (config not loaded)", playerUsername);
            return;
        }
        
        int punishmentDuration = config.getPunishmentDuration(violationCount);
        
        if (punishmentDuration == -1) {
            // Permanent ban
            player.connection.disconnect(Component.literal("Permanently banned for using unauthorized mods"));
            RaeYNCheat.LOGGER.warn("Permanently banned player {} for checksum violation (violation count: {})", 
                playerUsername, violationCount);
            
            // Add to blacklist (TODO: implement blacklist system)
            
        } else if (punishmentDuration > 0) {
            // Temporary ban
            player.connection.disconnect(Component.literal(
                "Banned for " + punishmentDuration + " seconds for using unauthorized mods (violation " + violationCount + ")"));
            RaeYNCheat.LOGGER.warn("Banned player {} for {} seconds (checksum violation count: {})", 
                playerUsername, punishmentDuration, violationCount);
            
            // Schedule unban (TODO: implement timed ban system)
            
        } else {
            // Kick only (duration = 0)
            player.connection.disconnect(Component.literal("Mod verification failed - unauthorized mods detected"));
            RaeYNCheat.LOGGER.warn("Kicked player {} for checksum violation (violation count: {})", 
                playerUsername, violationCount);
        }
    }
}
