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

public class PunishCommand {
    
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CommandBuildContext buildContext,
                                Commands.CommandSelection commandSelection) {
        dispatcher.register(Commands.literal("raeynpunish")
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
            
            // Record violation
            RaeYNCheat.recordChecksumViolation(playerUUID);
            
            // Get punishment duration based on actual violation count
            int violations = RaeYNCheat.getChecksumViolationCount(playerUUID);
            int duration = RaeYNCheat.getConfig().getPunishmentDuration(violations);
            
            if (duration == -1) {
                // Permanent ban
                targetPlayer.connection.disconnect(Component.literal("You have been permanently banned for mod violations"));
                source.getServer().getPlayerList().getBans().add(
                    new GameProfile(playerUUID, playerName)
                );
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
}
