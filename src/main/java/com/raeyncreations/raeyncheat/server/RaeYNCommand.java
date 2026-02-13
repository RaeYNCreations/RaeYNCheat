package com.raeyncreations.raeyncheat.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.raeyncreations.raeyncheat.RaeYNCheat;
import com.raeyncreations.raeyncheat.util.PasskeyLogger;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

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
            int duration = RaeYNCheat.getConfig().getPunishmentDuration(violations);
            
            if (duration == -1) {
                // Permanent ban
                source.getServer().getPlayerList().ban(
                    targetPlayer.getGameProfile(),
                    Component.literal("Permanently banned for mod violations")
                );
                targetPlayer.connection.disconnect(Component.literal("You have been permanently banned for mod violations"));
                source.sendSuccess(() -> Component.literal("Player " + playerName + " has been permanently banned"), true);
            } else if (duration > 0) {
                // Temporary ban
                targetPlayer.connection.disconnect(Component.literal("You have been temporarily banned for " + duration + " seconds"));
                source.sendSuccess(() -> Component.literal("Player " + playerName + " has been kicked (ban duration: " + duration + "s)"), true);
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
            int duration = RaeYNCheat.getConfig().getPasskeyPunishmentDuration(violations);
            
            String punishmentType;
            
            if (duration == -1) {
                // Permanent ban
                punishmentType = "PERMANENT BAN";
                source.getServer().getPlayerList().ban(
                    targetPlayer.getGameProfile(),
                    Component.literal("Permanently banned for passkey verification failures")
                );
                targetPlayer.connection.disconnect(Component.literal("You have been permanently banned for passkey verification failures"));
                source.sendSuccess(() -> Component.literal("Player " + playerName + " has been permanently banned for passkey violations"), true);
            } else if (duration > 0) {
                // Temporary ban
                punishmentType = "TEMPORARY BAN (" + duration + " seconds)";
                targetPlayer.connection.disconnect(Component.literal("You have been temporarily banned for " + duration + " seconds (passkey verification failed)"));
                source.sendSuccess(() -> Component.literal("Player " + playerName + " has been kicked for passkey violation (ban duration: " + duration + "s)"), true);
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
    
    private static int setChecksumStep(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int index = IntegerArgumentType.getInteger(context, "index");
        int duration = IntegerArgumentType.getInteger(context, "duration");
        
        try {
            boolean success = RaeYNCheat.getConfig().setChecksumPunishmentStep(index, duration);
            
            if (success) {
                RaeYNCheat.saveConfig();
                
                String durationText;
                if (duration == -1) {
                    durationText = "PERMANENT BAN";
                } else if (duration == 0) {
                    durationText = "WARNING only";
                } else {
                    durationText = duration + " seconds";
                }
                
                source.sendSuccess(() -> Component.literal(
                    "Checksum punishment step " + index + " set to: " + durationText
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
            int duration = RaeYNCheat.getConfig().getChecksumPunishmentStep(index);
            
            if (duration == -999) {
                source.sendFailure(Component.literal("Invalid step index: " + index));
                return 0;
            }
            
            String durationText;
            if (duration == -1) {
                durationText = "PERMANENT BAN";
            } else if (duration == 0) {
                durationText = "WARNING only";
            } else {
                durationText = duration + " seconds";
            }
            
            source.sendSuccess(() -> Component.literal(
                "Checksum punishment step " + index + ": " + durationText
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
            String steps = RaeYNCheat.getConfig().getChecksumPunishmentStepsString();
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
            boolean success = RaeYNCheat.getConfig().setPasskeyPunishmentStep(index, duration);
            
            if (success) {
                RaeYNCheat.saveConfig();
                
                String durationText;
                if (duration == -1) {
                    durationText = "PERMANENT BAN";
                } else if (duration == 0) {
                    durationText = "WARNING only";
                } else {
                    durationText = duration + " seconds";
                }
                
                source.sendSuccess(() -> Component.literal(
                    "Passkey punishment step " + index + " set to: " + durationText
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
            int duration = RaeYNCheat.getConfig().getPasskeyPunishmentStep(index);
            
            if (duration == -999) {
                source.sendFailure(Component.literal("Invalid step index: " + index));
                return 0;
            }
            
            String durationText;
            if (duration == -1) {
                durationText = "PERMANENT BAN";
            } else if (duration == 0) {
                durationText = "WARNING only";
            } else {
                durationText = duration + " seconds";
            }
            
            source.sendSuccess(() -> Component.literal(
                "Passkey punishment step " + index + ": " + durationText
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
            String steps = RaeYNCheat.getConfig().getPasskeyPunishmentStepsString();
            source.sendSuccess(() -> Component.literal("Passkey punishment steps: " + steps), false);
            return 1;
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Error listing passkey steps", e);
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }
}
