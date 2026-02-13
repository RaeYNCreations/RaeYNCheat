package com.raeyncreations.raeyncheat.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.raeyncreations.raeyncheat.RaeYNCheat;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;

public class PunishCommand {
    
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, 
                                CommandRegistryAccess registryAccess,
                                CommandManager.RegistrationEnvironment environment) {
        dispatcher.register(CommandManager.literal("raeynpunish")
            .requires(source -> source.hasPermissionLevel(2))
            .then(CommandManager.argument("player", StringArgumentType.string())
                .executes(context -> punishPlayer(context))
            )
        );
    }
    
    private static int punishPlayer(CommandContext<ServerCommandSource> context) {
        String playerName = StringArgumentType.getString(context, "player");
        ServerCommandSource source = context.getSource();
        
        try {
            // Find player by name
            ServerPlayerEntity targetPlayer = source.getServer().getPlayerManager().getPlayer(playerName);
            
            if (targetPlayer == null) {
                source.sendFeedback(() -> Text.literal("Player not found: " + playerName), false);
                return 0;
            }
            
            UUID playerUUID = targetPlayer.getUuid();
            
            // Record violation
            RaeYNCheatServer.recordViolation(playerUUID);
            
            // Get punishment duration
            int violations = 1; // This would come from the violation tracking system
            int duration = RaeYNCheatServer.getConfig().getPunishmentDuration(violations);
            
            if (duration == -1) {
                // Permanent ban
                targetPlayer.networkHandler.disconnect(Text.literal("You have been permanently banned for mod violations"));
                source.getServer().getPlayerManager().getUserBanList().add(
                    new com.mojang.authlib.GameProfile(playerUUID, playerName)
                );
                source.sendFeedback(() -> Text.literal("Player " + playerName + " has been permanently banned"), true);
            } else if (duration > 0) {
                // Temporary ban
                targetPlayer.networkHandler.disconnect(Text.literal("You have been temporarily banned for " + duration + " seconds"));
                source.sendFeedback(() -> Text.literal("Player " + playerName + " has been kicked (ban duration: " + duration + "s)"), true);
            } else {
                // Just warn
                targetPlayer.sendMessage(Text.literal("Warning: Mod verification failed"), false);
                source.sendFeedback(() -> Text.literal("Player " + playerName + " has been warned"), true);
            }
            
            return 1;
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Error punishing player", e);
            source.sendFeedback(() -> Text.literal("Error punishing player: " + e.getMessage()), false);
            return 0;
        }
    }
}
