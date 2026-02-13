package com.raeyncreations.raeyncheat.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.raeyncreations.raeyncheat.RaeYNCheat;
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
                )
                .then(Commands.literal("passkey")
                    .then(Commands.argument("player", StringArgumentType.string())
                        .executes(context -> punishPasskeyViolation(context))
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
            
            // Record passkey violation
            RaeYNCheat.recordPasskeyViolation(playerUUID);
            
            // Get punishment duration based on passkey violations
            int violations = RaeYNCheat.getPasskeyViolationCount(playerUUID);
            int duration = RaeYNCheat.getConfig().getPasskeyPunishmentDuration(violations);
            
            if (duration == -1) {
                // Permanent ban
                source.getServer().getPlayerList().ban(
                    targetPlayer.getGameProfile(),
                    Component.literal("Permanently banned for passkey verification failures")
                );
                targetPlayer.connection.disconnect(Component.literal("You have been permanently banned for passkey verification failures"));
                source.sendSuccess(() -> Component.literal("Player " + playerName + " has been permanently banned for passkey violations"), true);
            } else if (duration > 0) {
                // Temporary ban
                targetPlayer.connection.disconnect(Component.literal("You have been temporarily banned for " + duration + " seconds (passkey verification failed)"));
                source.sendSuccess(() -> Component.literal("Player " + playerName + " has been kicked for passkey violation (ban duration: " + duration + "s)"), true);
            } else {
                // Just warn
                targetPlayer.sendSystemMessage(Component.literal("Warning: Passkey verification failed"));
                source.sendSuccess(() -> Component.literal("Player " + playerName + " has been warned for passkey violation"), true);
            }
            
            return 1;
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Error punishing player for passkey violation", e);
            source.sendFailure(Component.literal("Error punishing player: " + e.getMessage()));
            return 0;
        }
    }
}
