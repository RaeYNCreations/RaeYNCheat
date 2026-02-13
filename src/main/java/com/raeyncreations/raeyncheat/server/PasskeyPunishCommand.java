package com.raeyncreations.raeyncheat.server;

import com.mojang.authlib.GameProfile;
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

public class PasskeyPunishCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CommandBuildContext buildContext,
                                Commands.CommandSelection commandSelection) {
        dispatcher.register(Commands.literal("raeynpasskeyban")
            .requires(source -> source.hasPermission(2))
            .then(Commands.argument("player", StringArgumentType.string())
                .executes(context -> punishPlayer(context))
            )
        );
    }
    
    private static int punishPlayer(CommandContext<CommandSourceStack> context) {
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
                targetPlayer.connection.disconnect(Component.literal("You have been permanently banned for passkey verification failures"));
                source.getServer().getPlayerList().getBans().add(
                    new GameProfile(playerUUID, playerName)
                );
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
