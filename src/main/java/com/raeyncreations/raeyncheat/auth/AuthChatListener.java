package com.raeyncreations.raeyncheat.auth;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.ServerChatEvent;

/**
 * Intercepts all server chat events and cancels them for players who
 * are currently in the auth lockdown state (pending password or 2FA).
 *
 * This prevents players from chatting, spamming, or doing anything
 * in chat while they are frozen in spectator awaiting authentication.
 * Commands (/login, /password, /2fa) go through the command dispatcher and
 * are NOT caught by this listener, so auth commands still work.
 *
 * Registered in RaeYNCheat constructor via NeoForge.EVENT_BUS.
 */
public class AuthChatListener {

    public static void onServerChat(ServerChatEvent event) {
        if (!AuthManager.isInitialized()) return;

        ServerPlayer player = event.getPlayer();
        String uuid = player.getUUID().toString();

        if (AuthManager.isLocked(uuid)) {
            event.setCanceled(true);
            AuthManager.interceptChat(player, event.getMessage().toString());
        }
    }
}
