package com.raeyncreations.raeyncheat.server;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.raeyncreations.raeyncheat.RaeYNCheat;
import com.raeyncreations.raeyncheat.config.RaeYNCheatConfig;
import com.raeyncreations.raeyncheat.util.PasskeyLogger;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.UserBanListEntry;

import javax.annotation.Nullable;
import java.util.Date;
import java.util.UUID;

public class RaeYNCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CommandBuildContext buildContext,
                                Commands.CommandSelection commandSelection) {
        dispatcher.register(Commands.literal("raeyn")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("cheat")
                .then(Commands.literal("checksum")
                    .then(Commands.argument("player", StringArgumentType.string())
                        .executes(context -> punishChecksumViolation(context))
                    )
                    .then(Commands.literal("refresh")
                        .executes(context -> refreshChecksumInit(context))
                    )
                    .then(Commands.literal("step")
                        .then(Commands.argument("index", IntegerArgumentType.integer(0, 29))
                            .then(Commands.argument("duration", IntegerArgumentType.integer(-1))
                                .executes(context -> setChecksumStep(context))
                            )
                            .executes(context -> getChecksumStep(context))
                        )
                        .executes(context -> listChecksumSteps(context))
                    )
                )
                .then(Commands.literal("passkey")
                    .then(Commands.argument("player", StringArgumentType.string())
                        .executes(context -> punishPasskeyViolation(context))
                    )
                    .then(Commands.literal("step")
                        .then(Commands.argument("index", IntegerArgumentType.integer(0, 29))
                            .then(Commands.argument("duration", IntegerArgumentType.integer(-1))
                                .executes(context -> setPasskeyStep(context))
                            )
                            .executes(context -> getPasskeyStep(context))
                        )
                        .executes(context -> listPasskeySteps(context))
                    )
                )
            )
        );
    }
    
    private static int punishChecksumViolation(CommandContext<CommandSourceStack> context) {
        String playerName = StringArgumentType.getString(context, "player");
        CommandSourceStack source = context.getSource();
        
        // Validate player name length (Minecraft usernames are 3-16 characters)
        if (playerName == null || playerName.length() < 3 || playerName.length() > 16) {
            source.sendFailure(Component.literal("Invalid player name (must be 3-16 characters)"));
            return 0;
        }
        
        try {
            // Find player by name
            ServerPlayer targetPlayer = source.getServer().getPlayerList().getPlayerByName(playerName);
            
            if (targetPlayer == null) {
                source.sendFailure(Component.literal("Player not found: " + playerName));
                return 0;
            }
            
            UUID playerUUID = targetPlayer.getUUID();
            
            // Record violation
            RaeYNCheat.recordChecksumViolation(playerUUID);
            
            // Get punishment duration based on actual violation count
            int violations = RaeYNCheat.getChecksumViolationCount(playerUUID);
            RaeYNCheatConfig config = RaeYNCheat.getConfig();
            
            // Handle missing config gracefully
            if (config == null) {
                source.sendFailure(Component.literal("Configuration not loaded. Cannot apply punishment."));
                RaeYNCheat.LOGGER.error("Config not loaded, cannot punish player {}", playerName);
                return 0;
            }
            
            int duration = config.getPunishmentDuration(violations);
            
            if (duration == -1) {
                // Permanent ban
                UserBanListEntry banEntry = new UserBanListEntry(
                    targetPlayer.getGameProfile(),
                    null, // no end date for permanent
                    "Server",
                    null,
                    "Permanently banned for mod violations"
                );
                source.getServer().getPlayerList().getBans().add(banEntry);
                targetPlayer.connection.disconnect(Component.literal("You have been permanently banned for mod violations"));
                source.sendSuccess(() -> Component.literal("Player " + playerName + " has been permanently banned"), true);
            } else if (duration > 0) {
                // Temporary ban
                Date endDate = new Date(System.currentTimeMillis() + duration * 1000L);
                UserBanListEntry banEntry = new UserBanListEntry(
                    targetPlayer.getGameProfile(),
                    endDate,
                    "Server",
                    null,
                    "Temporarily banned for mod violations"
                );
                source.getServer().getPlayerList().getBans().add(banEntry);
                targetPlayer.connection.disconnect(Component.literal("You have been temporarily banned for " + duration + " seconds"));
                source.sendSuccess(() -> Component.literal("Player " + playerName + " has been temporarily banned (duration: " + duration + "s)"), true);
            } else {
                // Just warn
                targetPlayer.sendSystemMessage(Component.literal("Warning: Mod verification failed"));
                source.sendSuccess(() -> Component.literal("Player " + playerName + " has been warned"), true);
            }
            
            return 1;
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Error punishing player", e);
            source.sendFailure(Component.literal("Error punishing player: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int punishPasskeyViolation(CommandContext<CommandSourceStack> context) {
        String playerName = StringArgumentType.getString(context, "player");
        CommandSourceStack source = context.getSource();
        
        // Validate player name length (Minecraft usernames are 3-16 characters)
        if (playerName == null || playerName.length() < 3 || playerName.length() > 16) {
            source.sendFailure(Component.literal("Invalid player name (must be 3-16 characters)"));
            return 0;
        }
        
        try {
            // Find player by name
            ServerPlayer targetPlayer = source.getServer().getPlayerList().getPlayerByName(playerName);
            
            if (targetPlayer == null) {
                source.sendFailure(Component.literal("Player not found: " + playerName));
                return 0;
            }
            
            UUID playerUUID = targetPlayer.getUUID();
            
            // Get admin username
            String adminUsername = source.getTextName();
            
            // Record passkey violation
            RaeYNCheat.recordPasskeyViolation(playerUUID);
            
            // Get punishment duration based on passkey violations
            int violations = RaeYNCheat.getPasskeyViolationCount(playerUUID);
            RaeYNCheatConfig config = RaeYNCheat.getConfig();
            
            // Handle missing config gracefully
            if (config == null) {
                source.sendFailure(Component.literal("Configuration not loaded. Cannot apply punishment."));
                RaeYNCheat.LOGGER.error("Config not loaded, cannot punish player {}", playerName);
                return 0;
            }
            
            int duration = config.getPasskeyPunishmentDuration(violations);
            
            String punishmentType;
            
            if (duration == -1) {
                // Permanent ban
                punishmentType = "PERMANENT BAN";
                UserBanListEntry banEntry = new UserBanListEntry(
                    targetPlayer.getGameProfile(),
                    null,
                    "Server",
                    null,
                    "Permanently banned for passkey verification failures"
                );
                source.getServer().getPlayerList().getBans().add(banEntry);
                targetPlayer.connection.disconnect(Component.literal("You have been permanently banned for passkey verification failures"));
                source.sendSuccess(() -> Component.literal("Player " + playerName + " has been permanently banned for passkey violations"), true);
            } else if (duration > 0) {
                // Temporary ban
                punishmentType = "TEMPORARY BAN (" + duration + " seconds)";
                Date endDate = new Date(System.currentTimeMillis() + duration * 1000L);
                UserBanListEntry banEntry = new UserBanListEntry(
                    targetPlayer.getGameProfile(),
                    endDate,
                    "Server",
                    null,
                    "Temporarily banned for passkey verification failures"
                );
                source.getServer().getPlayerList().getBans().add(banEntry);
                targetPlayer.connection.disconnect(Component.literal("You have been temporarily banned for " + duration + " seconds (passkey verification failed)"));
                source.sendSuccess(() -> Component.literal("Player " + playerName + " has been temporarily banned for passkey violation (duration: " + duration + "s)"), true);
            } else {
                // Just warn
                punishmentType = "WARNING";
                targetPlayer.sendSystemMessage(Component.literal("Warning: Passkey verification failed"));
                source.sendSuccess(() -> Component.literal("Player " + playerName + " has been warned for passkey violation"), true);
            }
            
            // Log manual violation
            PasskeyLogger.logManualViolation(playerName, playerUUID.toString(), adminUsername, violations, punishmentType);
            
            return 1;
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Error punishing player for passkey violation", e);
            source.sendFailure(Component.literal("Error punishing player: " + e.getMessage()));
            return 0;
        }
    }
    
    /**
     * Helper method to format duration value as human-readable text
     */
    private static String formatDuration(int duration) {
        if (duration == -1) {
            return "PERMANENT BAN";
        } else if (duration == 0) {
            return "WARNING only";
        } else {
            return duration + " seconds";
        }
    }
    
    private static int setChecksumStep(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int index = IntegerArgumentType.getInteger(context, "index");
        int duration = IntegerArgumentType.getInteger(context, "duration");
        
        try {
            RaeYNCheatConfig config = RaeYNCheat.getConfig();
            if (config == null) {
                source.sendFailure(Component.literal("Configuration not loaded"));
                return 0;
            }
            
            boolean success = config.setChecksumPunishmentStep(index, duration);
            
            if (success) {
                RaeYNCheat.saveConfig();
                
                source.sendSuccess(() -> Component.literal(
                    "Checksum punishment step " + index + " set to: " + formatDuration(duration)
                ), true);
                
                RaeYNCheat.LOGGER.info("Admin {} set checksum step {} to {}", 
                    source.getTextName(), index, duration);
                return 1;
            } else {
                source.sendFailure(Component.literal("Failed to set punishment step. Check server logs."));
                return 0;
            }
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Error setting checksum step", e);
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int getChecksumStep(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int index = IntegerArgumentType.getInteger(context, "index");
        
        try {
            RaeYNCheatConfig config = RaeYNCheat.getConfig();
            if (config == null) {
                source.sendFailure(Component.literal("Configuration not loaded"));
                return 0;
            }
            
            int duration = config.getChecksumPunishmentStep(index);
            
            if (RaeYNCheatConfig.isInvalidStepIndex(duration)) {
                source.sendFailure(Component.literal("Invalid step index: " + index));
                return 0;
            }
            
            source.sendSuccess(() -> Component.literal(
                "Checksum punishment step " + index + ": " + formatDuration(duration)
            ), false);
            return 1;
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Error getting checksum step", e);
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int listChecksumSteps(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        try {
            RaeYNCheatConfig config = RaeYNCheat.getConfig();
            if (config == null) {
                source.sendFailure(Component.literal("Configuration not loaded"));
                return 0;
            }
            
            String steps = config.getChecksumPunishmentStepsString();
            source.sendSuccess(() -> Component.literal("Checksum punishment steps: " + steps), false);
            return 1;
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Error listing checksum steps", e);
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int setPasskeyStep(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int index = IntegerArgumentType.getInteger(context, "index");
        int duration = IntegerArgumentType.getInteger(context, "duration");
        
        try {
            RaeYNCheatConfig config = RaeYNCheat.getConfig();
            if (config == null) {
                source.sendFailure(Component.literal("Configuration not loaded"));
                return 0;
            }
            
            boolean success = config.setPasskeyPunishmentStep(index, duration);
            
            if (success) {
                RaeYNCheat.saveConfig();
                
                source.sendSuccess(() -> Component.literal(
                    "Passkey punishment step " + index + " set to: " + formatDuration(duration)
                ), true);
                
                RaeYNCheat.LOGGER.info("Admin {} set passkey step {} to {}", 
                    source.getTextName(), index, duration);
                return 1;
            } else {
                source.sendFailure(Component.literal("Failed to set punishment step. Check server logs."));
                return 0;
            }
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Error setting passkey step", e);
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int getPasskeyStep(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int index = IntegerArgumentType.getInteger(context, "index");
        
        try {
            RaeYNCheatConfig config = RaeYNCheat.getConfig();
            if (config == null) {
                source.sendFailure(Component.literal("Configuration not loaded"));
                return 0;
            }
            
            int duration = config.getPasskeyPunishmentStep(index);
            
            if (RaeYNCheatConfig.isInvalidStepIndex(duration)) {
                source.sendFailure(Component.literal("Invalid step index: " + index));
                return 0;
            }
            
            source.sendSuccess(() -> Component.literal(
                "Passkey punishment step " + index + ": " + formatDuration(duration)
            ), false);
            return 1;
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Error getting passkey step", e);
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }
    
    private static int listPasskeySteps(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        try {
            RaeYNCheatConfig config = RaeYNCheat.getConfig();
            if (config == null) {
                source.sendFailure(Component.literal("Configuration not loaded"));
                return 0;
            }
            
            String steps = config.getPasskeyPunishmentStepsString();
            source.sendSuccess(() -> Component.literal("Passkey punishment steps: " + steps), false);
            return 1;
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Error listing passkey steps", e);
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }
    
    /**
     * Manually refresh the CheckSum_init file
     */
    private static int refreshChecksumInit(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        
        try {
            if (RaeYNCheat.getCheckFileManager() == null) {
                source.sendFailure(Component.literal("CheckFileManager not initialized"));
                return 0;
            }
            
            // Refresh the CheckSum_init file
            RaeYNCheat.getCheckFileManager().generateServerInitCheckFile();
            
            String adminUsername = source.getTextName();
            RaeYNCheat.LOGGER.info("CheckSum_init file manually refreshed by {}", adminUsername);
            
            source.sendSuccess(() -> Component.literal("CheckSum_init file has been refreshed successfully"), true);
            return 1;
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Error refreshing CheckSum_init file", e);
            source.sendFailure(Component.literal("Error refreshing CheckSum_init: " + e.getMessage()));
            return 0;
        }
    }
}