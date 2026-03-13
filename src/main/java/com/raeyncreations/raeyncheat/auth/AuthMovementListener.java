package com.raeyncreations.raeyncheat.auth;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces movement freeze for players in auth lockdown.
 *
 * Spectator mode alone prevents block interaction, item use, and combat,
 * but a player CAN still fly around freely in spectator. We want them
 * completely frozen — unable to scout the server or observe anything
 * while waiting to authenticate.
 *
 * Strategy:
 *   - Record the player's spawn/join position when they enter lockdown.
 *   - On every server tick, if they've moved from that position, teleport them back.
 *   - This is zero-tolerance: even a single block of drift is corrected immediately.
 *
 * The teleport uses the player's current Y-rotation and pitch so it's invisible
 * (no camera snap). The player simply cannot move.
 *
 * Positions are stored per-UUID and removed when auth completes or the player
 * disconnects.
 */
public class AuthMovementListener {

    /** UUID → locked position (set when player enters auth lockdown) */
    private static final ConcurrentHashMap<String, double[]> lockedPositions = new ConcurrentHashMap<>();

    /** Call this when a player enters auth lockdown to record their freeze position. */
    public static void lockPosition(ServerPlayer player) {
        lockedPositions.put(
                player.getUUID().toString(),
                new double[]{player.getX(), player.getY(), player.getZ()}
        );
    }

    /** Call this when auth completes or player disconnects to release the freeze. */
    public static void unlockPosition(String uuid) {
        lockedPositions.remove(uuid);
    }

    /** NeoForge PlayerTickEvent.Pre handler — registered in RaeYNCheat constructor. */
    public static void onPlayerTick(PlayerTickEvent.Pre event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!AuthManager.isInitialized()) return;

        String uuid = player.getUUID().toString();
        if (!AuthManager.isLocked(uuid)) return;

        double[] pos = lockedPositions.get(uuid);
        if (pos == null) {
            // Position not recorded yet — record now (edge case on first tick)
            lockPosition(player);
            return;
        }

        double dx = player.getX() - pos[0];
        double dy = player.getY() - pos[1];
        double dz = player.getZ() - pos[2];

        // Tolerance of 0.01 blocks to avoid floating-point noise causing infinite teleports
        if (dx * dx + dy * dy + dz * dz > 0.0001) {
            // Teleport back silently — preserve look direction so it's seamless
            player.teleportTo(pos[0], pos[1], pos[2]);
            // Zero out velocity so client doesn't drift again next tick
            player.setDeltaMovement(0, 0, 0);
        }

        // Also re-enforce spectator and no-fly on every tick
        // (Client packets can temporarily override these)
        if (player.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
            player.setGameMode(GameType.SPECTATOR);
        }
        if (player.getAbilities().mayfly || player.getAbilities().flying) {
            player.getAbilities().mayfly  = false;
            player.getAbilities().flying  = false;
            player.onUpdateAbilities();
        }
    }
}
