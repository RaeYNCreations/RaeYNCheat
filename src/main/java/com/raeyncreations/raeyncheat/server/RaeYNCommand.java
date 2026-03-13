package com.raeyncreations.raeyncheat.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.raeyncreations.raeyncheat.RaeYNCheat;
import com.raeyncreations.raeyncheat.auth.AuthManager;
import com.raeyncreations.raeyncheat.config.RaeYNCheatConfig;
import com.raeyncreations.raeyncheat.util.PasskeyLogger;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.UserBanListEntry;

import java.util.Date;
import java.util.UUID;

/**
 * Commands for RaeYNCheat. Two permission tiers:
 *
 * ── PLAYER COMMANDS (no OP required) ────────────────────────────────────────
 *
 * /login <password|code>                      — authenticate (password or 2FA code)
 * /password set <password>                   — set account password for the first time
 * /password change <old> <new> <confirm>     — change password (verifies old first)
 * /2fa setup                                 — begin 2FA enrollment (password required first)
 * /2fa disable <password>                    — remove 2FA (requires password confirmation)
 *
 * ── ADMIN COMMANDS (OP level 2 required) ────────────────────────────────────
 *
 * /raeyn auth status <player>                 — show auth record (password/2FA/lockout)
 * /raeyn auth reset <player>                  — wipe password + 2FA (player re-sets next login)
 * /raeyn auth unlock <player>                 — clear lockout without wiping auth
 *
 * /raeyn cheat checksum <player>              — manually flag a checksum violation
 * /raeyn cheat checksum refresh               — rebuild server CheckSum_init
 * /raeyn cheat checksum step [i] [dur]        — get/set/list checksum punishment steps
 * /raeyn cheat passkey <player>               — manually flag a passkey violation
 * /raeyn cheat passkey step [i] [dur]
 * /raeyn cheat env <player>                   — manually flag an environment violation
 * /raeyn cheat env step [i] [dur]
 * /raeyn cheat negotiation <player>           — manually flag a negotiation violation
 * /raeyn cheat negotiation step [i] [dur]
 * /raeyn cheat revalidate <player>            — send immediate revalidation request
 * /raeyn cheat revalidate all                 — revalidate all online players
 * /raeyn cheat status <player>                — show all violation counts for a player
 * /raeyn cheat pardon <player>                — clear ALL violation records for a player
 *
 * /raeyn ddos status                          — show rate-limiter + bot detector state
 * /raeyn ddos resetcircuit                    — manually close the global circuit breaker
 * /raeyn ddos clearip <ip>                    — clear rate-limit + bot state for an IP
 * /raeyn ddos clearuuid <player>              — clear rate-limit + bot state for a UUID
 * /raeyn ddos ipinfo <ip>                     — detailed rate-limit report for a specific IP
 * /raeyn ddos bot status                      — bot detector snapshot only
 * /raeyn ddos bot clearip <ip>                — clear bot-detect state for an IP
 * /raeyn ddos bot clearuuid <player>          — clear bot-detect state for a UUID
 */
public class RaeYNCommand {

    private enum ViolationType {
        CHECKSUM("checksum"), PASSKEY("passkey"), ENV("environment"), NEGOTIATION("negotiation");
        final String label;
        ViolationType(String label) { this.label = label; }
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher,
                                CommandBuildContext buildContext,
                                Commands.CommandSelection commandSelection) {
        dispatcher.register(Commands.literal("raeyn")
            .requires(source -> source.hasPermission(2))

            // ── /raeyn cheat ... ──────────────────────────────────────────────
            .then(Commands.literal("cheat")

                // ── checksum ─────────────────────────────────────────────────
                .then(Commands.literal("checksum")
                    .then(Commands.argument("player", StringArgumentType.string())
                        .executes(ctx -> manualPunish(ctx, ViolationType.CHECKSUM)))
                    .then(Commands.literal("refresh")
                        .executes(RaeYNCommand::refreshChecksumInit))
                    .then(Commands.literal("step")
                        .then(Commands.argument("index", IntegerArgumentType.integer(0, 29))
                            .then(Commands.argument("duration", IntegerArgumentType.integer(-1))
                                .executes(ctx -> setStep(ctx, ViolationType.CHECKSUM)))
                            .executes(ctx -> getStep(ctx, ViolationType.CHECKSUM)))
                        .executes(ctx -> listSteps(ctx, ViolationType.CHECKSUM))))

                // ── passkey ───────────────────────────────────────────────────
                .then(Commands.literal("passkey")
                    .then(Commands.argument("player", StringArgumentType.string())
                        .executes(ctx -> manualPunish(ctx, ViolationType.PASSKEY)))
                    .then(Commands.literal("step")
                        .then(Commands.argument("index", IntegerArgumentType.integer(0, 29))
                            .then(Commands.argument("duration", IntegerArgumentType.integer(-1))
                                .executes(ctx -> setStep(ctx, ViolationType.PASSKEY)))
                            .executes(ctx -> getStep(ctx, ViolationType.PASSKEY)))
                        .executes(ctx -> listSteps(ctx, ViolationType.PASSKEY))))

                // ── environment ───────────────────────────────────────────────
                .then(Commands.literal("env")
                    .then(Commands.argument("player", StringArgumentType.string())
                        .executes(ctx -> manualPunish(ctx, ViolationType.ENV)))
                    .then(Commands.literal("step")
                        .then(Commands.argument("index", IntegerArgumentType.integer(0, 29))
                            .then(Commands.argument("duration", IntegerArgumentType.integer(-1))
                                .executes(ctx -> setStep(ctx, ViolationType.ENV)))
                            .executes(ctx -> getStep(ctx, ViolationType.ENV)))
                        .executes(ctx -> listSteps(ctx, ViolationType.ENV))))

                // ── negotiation ───────────────────────────────────────────────
                .then(Commands.literal("negotiation")
                    .then(Commands.argument("player", StringArgumentType.string())
                        .executes(ctx -> manualPunish(ctx, ViolationType.NEGOTIATION)))
                    .then(Commands.literal("step")
                        .then(Commands.argument("index", IntegerArgumentType.integer(0, 29))
                            .then(Commands.argument("duration", IntegerArgumentType.integer(-1))
                                .executes(ctx -> setStep(ctx, ViolationType.NEGOTIATION)))
                            .executes(ctx -> getStep(ctx, ViolationType.NEGOTIATION)))
                        .executes(ctx -> listSteps(ctx, ViolationType.NEGOTIATION))))

                // ── revalidate ────────────────────────────────────────────────
                .then(Commands.literal("revalidate")
                    .then(Commands.argument("player", StringArgumentType.string())
                        .executes(RaeYNCommand::revalidatePlayer))
                    .then(Commands.literal("all")
                        .executes(RaeYNCommand::revalidateAll)))

                // ── FIX #8: status <player> ───────────────────────────────────
                .then(Commands.literal("status")
                    .then(Commands.argument("player", StringArgumentType.string())
                        .executes(RaeYNCommand::showPlayerStatus)))

                // ── FIX #12: pardon <player> ──────────────────────────────────
                .then(Commands.literal("pardon")
                    .then(Commands.argument("player", StringArgumentType.string())
                        .executes(RaeYNCommand::pardonPlayer)))
            )

            // ── /raeyn ddos ... ───────────────────────────────────────────────
            .then(Commands.literal("ddos")

                .then(Commands.literal("status")
                    .executes(RaeYNCommand::ddosStatus))

                .then(Commands.literal("resetcircuit")
                    .executes(RaeYNCommand::ddosResetCircuit))

                .then(Commands.literal("clearip")
                    .then(Commands.argument("ip", StringArgumentType.string())
                        .executes(RaeYNCommand::ddosClearIp)))

                .then(Commands.literal("clearuuid")
                    .then(Commands.argument("player", StringArgumentType.string())
                        .executes(RaeYNCommand::ddosClearUuid)))

                .then(Commands.literal("ipinfo")
                    .then(Commands.argument("ip", StringArgumentType.string())
                        .executes(RaeYNCommand::ddosIpInfo)))

                .then(Commands.literal("bot")
                    .then(Commands.literal("status")
                        .executes(RaeYNCommand::botStatus))
                    .then(Commands.literal("clearip")
                        .then(Commands.argument("ip", StringArgumentType.string())
                            .executes(RaeYNCommand::botClearIp)))
                    .then(Commands.literal("clearuuid")
                        .then(Commands.argument("player", StringArgumentType.string())
                            .executes(RaeYNCommand::botClearUuid))))
            )
        );

        // ── /login — authenticate during lockdown (all players, no permission required) ──
        dispatcher.register(Commands.literal("login")
            .then(Commands.argument("input", StringArgumentType.greedyString())
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    AuthManager.submitAuth(p, StringArgumentType.getString(ctx, "input"));
                    return 1;
                }))
        );

        // ── /password — account password management (all players) ────────────
        dispatcher.register(Commands.literal("password")

            // /password set <password>
            .then(Commands.literal("set")
                .then(Commands.argument("password", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        AuthManager.setPassword(p, StringArgumentType.getString(ctx, "password"));
                        return 1;
                    })))

            // /password change <old_password> <new_password> <confirm_password>
            // Note: because this command takes three password arguments, passwords containing
            // spaces must be wrapped in double quotes: /password change "my old pass" "newpass1" "newpass1"
            // Passwords without spaces work unquoted. /password set and /login accept spaces freely.
            .then(Commands.literal("change")
                .then(Commands.argument("old_password", StringArgumentType.string())
                    .then(Commands.argument("new_password", StringArgumentType.string())
                        .then(Commands.argument("confirm_password", StringArgumentType.greedyString())
                            .executes(ctx -> {
                                ServerPlayer p = ctx.getSource().getPlayerOrException();
                                AuthManager.changePassword(p,
                                        StringArgumentType.getString(ctx, "old_password"),
                                        StringArgumentType.getString(ctx, "new_password"),
                                        StringArgumentType.getString(ctx, "confirm_password"));
                                return 1;
                            })))))
        );

        // ── /2fa — two-factor authentication management (all players) ─────────
        dispatcher.register(Commands.literal("2fa")

            // /2fa setup — begin TOTP enrollment (requires password to be set first)
            .then(Commands.literal("setup")
                .executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    AuthManager.beginTotpSetup(p);
                    return 1;
                }))

            // /2fa disable <password> — remove 2FA (requires password confirmation)
            .then(Commands.literal("disable")
                .then(Commands.argument("password", StringArgumentType.greedyString())
                    .executes(ctx -> {
                        ServerPlayer p = ctx.getSource().getPlayerOrException();
                        AuthManager.disableTotp(p, StringArgumentType.getString(ctx, "password"));
                        return 1;
                    })))
        );

        // ── /raeyn auth ... — OP level 2 admin commands ───────────────────────
        dispatcher.register(Commands.literal("raeyn")
            .requires(src -> src.hasPermission(2))

            .then(Commands.literal("auth")

                // /raeyn auth status <player>
                .then(Commands.literal("status")
                    .then(Commands.argument("player", StringArgumentType.string())
                        .executes(RaeYNCommand::authStatus)))

                // /raeyn auth reset <player>  — wipes password + 2FA (forces re-setup)
                .then(Commands.literal("reset")
                    .then(Commands.argument("player", StringArgumentType.string())
                        .executes(RaeYNCommand::authReset)))

                // /raeyn auth unlock <player>  — clears lockout without wiping auth
                .then(Commands.literal("unlock")
                    .then(Commands.argument("player", StringArgumentType.string())
                        .executes(RaeYNCommand::authUnlock)))
            )
        );
    }

    // ---------------------------------------------------------------------------
    // Manual punishment — shared across all four violation types
    // ---------------------------------------------------------------------------

    private static int manualPunish(CommandContext<CommandSourceStack> ctx, ViolationType type) {
        String playerName = StringArgumentType.getString(ctx, "player");
        CommandSourceStack source = ctx.getSource();

        if (playerName == null || playerName.length() < 3 || playerName.length() > 16) {
            source.sendFailure(Component.literal("Invalid player name (must be 3–16 characters)"));
            return 0;
        }

        try {
            ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(playerName);
            if (target == null) {
                source.sendFailure(Component.literal("Player not found online: " + playerName));
                return 0;
            }

            UUID uuid  = target.getUUID();
            String admin = source.getTextName();
            RaeYNCheatConfig config = RaeYNCheat.getConfig();
            if (config == null) {
                source.sendFailure(Component.literal("Configuration not loaded. Cannot apply punishment."));
                return 0;
            }

            int violations;
            int duration;
            switch (type) {
                case CHECKSUM    -> { RaeYNCheat.recordChecksumViolation(uuid);    violations = RaeYNCheat.getChecksumViolationCount(uuid);    duration = config.getPunishmentDuration(violations); }
                case PASSKEY     -> { RaeYNCheat.recordPasskeyViolation(uuid);     violations = RaeYNCheat.getPasskeyViolationCount(uuid);     duration = config.getPasskeyPunishmentDuration(violations); }
                case ENV         -> { RaeYNCheat.recordEnvViolation(uuid);         violations = RaeYNCheat.getEnvViolationCount(uuid);         duration = config.getEnvPunishmentDuration(violations); }
                default          -> { RaeYNCheat.recordNegotiationViolation(uuid); violations = RaeYNCheat.getNegotiationViolationCount(uuid); duration = config.getNegotiationPunishmentDuration(violations); }
            }

            String punishmentType = applyPunishment(source, target, duration, violations, type.label);
            PasskeyLogger.logManualViolation(playerName, uuid.toString(), admin, violations, punishmentType);
            RaeYNCheat.LOGGER.info("Admin {} applied {} punishment ({}) to {} (violation #{})",
                    admin, type.label, punishmentType, playerName, violations);
            RaeYNCheat.saveConfig();
            return 1;

        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Error applying {} punishment", type.label, e);
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static String applyPunishment(CommandSourceStack source, ServerPlayer target,
                                           int duration, int violations, String typeLabel) {
        String name = target.getName().getString();
        if (duration == -1) {
            source.getServer().getPlayerList().getBans().add(new UserBanListEntry(
                    target.getGameProfile(), null, "RaeYNCheat", null,
                    "Permanently banned for " + typeLabel + " violation"));
            target.connection.disconnect(Component.literal("Permanently banned for " + typeLabel + " violation."));
            source.sendSuccess(() -> Component.literal(name + " permanently banned for " + typeLabel + "."), true);
            return "PERMANENT BAN";
        } else if (duration > 0) {
            Date end = new Date(System.currentTimeMillis() + duration * 1000L);
            source.getServer().getPlayerList().getBans().add(new UserBanListEntry(
                    target.getGameProfile(), end, "RaeYNCheat", null,
                    "Banned " + duration + "s for " + typeLabel + " (count " + violations + ")"));
            target.connection.disconnect(Component.literal("Banned for " + duration + "s: " + typeLabel + " violation."));
            source.sendSuccess(() -> Component.literal(name + " banned for " + duration + "s (" + typeLabel + ")."), true);
            return "TEMPORARY BAN (" + duration + "s)";
        } else {
            target.sendSystemMessage(Component.literal("Warning: " + typeLabel + " check failed."));
            PlayerConnectionHandler.registerKickCooldown(target.getUUID().toString());
            source.sendSuccess(() -> Component.literal(name + " warned for " + typeLabel + " violation."), true);
            return "KICK";
        }
    }

    // ---------------------------------------------------------------------------
    // FIX #8: status <player>
    // ---------------------------------------------------------------------------

    private static int showPlayerStatus(CommandContext<CommandSourceStack> ctx) {
        String playerName = StringArgumentType.getString(ctx, "player");
        CommandSourceStack source = ctx.getSource();
        try {
            ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(playerName);
            if (target == null) {
                source.sendFailure(Component.literal("Player not found online: " + playerName));
                return 0;
            }

            UUID uuid = target.getUUID();
            int cs = RaeYNCheat.getChecksumViolationCount(uuid);
            int pk = RaeYNCheat.getPasskeyViolationCount(uuid);
            int ev = RaeYNCheat.getEnvViolationCount(uuid);
            int ng = RaeYNCheat.getNegotiationViolationCount(uuid);

            source.sendSuccess(() -> Component.literal(
                    "=== RaeYNCheat status for " + playerName + " ===\n" +
                    "  Checksum violations:    " + cs + "\n" +
                    "  Passkey violations:     " + pk + "\n" +
                    "  Environment violations: " + ev + "\n" +
                    "  Negotiation violations: " + ng + "\n" +
                    "  UUID: " + uuid
            ), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    // ---------------------------------------------------------------------------
    // FIX #12: pardon <player>
    // ---------------------------------------------------------------------------

    private static int pardonPlayer(CommandContext<CommandSourceStack> ctx) {
        String playerName = StringArgumentType.getString(ctx, "player");
        CommandSourceStack source = ctx.getSource();
        try {
            ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(playerName);
            if (target == null) {
                source.sendFailure(Component.literal("Player not found online: " + playerName));
                return 0;
            }

            UUID uuid = target.getUUID();
            RaeYNCheatConfig config = RaeYNCheat.getConfig();
            if (config == null) {
                source.sendFailure(Component.literal("Configuration not loaded."));
                return 0;
            }

            // Clear from DB (primary store) and config map fallback
            com.raeyncreations.raeyncheat.auth.AuthDatabase authDb = RaeYNCheat.getAuthDatabase();
            if (authDb != null) {
                try { authDb.clearViolations(uuid.toString()); }
                catch (Exception e) { RaeYNCheat.LOGGER.warn("DB clearViolations failed for {}: {}", uuid, e.getMessage()); }
            }
            config.clearViolations(uuid);
            ConnectionRateLimiter.clearUuid(uuid.toString());
            ValidationHandler.clearNonce(uuid.toString());
            ValidationHandler.clearPending(uuid.toString());
            RaeYNCheat.saveConfig();

            String admin = source.getTextName();
            PasskeyLogger.logManualViolation(playerName, uuid.toString(), admin, 0, "PARDON - all violations cleared");
            RaeYNCheat.LOGGER.info("Admin {} pardoned {} ({}) — all violation records cleared.", admin, playerName, uuid);
            source.sendSuccess(() -> Component.literal(
                    "Pardoned " + playerName + " — all violation records cleared."), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    // ---------------------------------------------------------------------------
    // DDoS / rate-limiter commands
    // ---------------------------------------------------------------------------

    private static int ddosStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String rlReport  = ConnectionRateLimiter.getStatusReport();
        String botReport = com.raeyncreations.raeyncheat.server.BotDetector.getStatusReport();
        String circuit   = ConnectionRateLimiter.isCircuitOpen()
                ? " *** CIRCUIT BREAKER IS OPEN — flood mode active ***" : "";
        source.sendSuccess(() -> Component.literal(
                "=== RaeYNCheat DDoS / Security Status ===\n" +
                rlReport + "\n" +
                botReport + circuit), false);
        return 1;
    }

    private static int ddosResetCircuit(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!ConnectionRateLimiter.isCircuitOpen()) {
            source.sendSuccess(() -> Component.literal("Circuit breaker is already closed (not in flood mode)."), false);
            return 1;
        }
        ConnectionRateLimiter.resetCircuitBreaker();
        RaeYNCheat.LOGGER.warn("Admin {} manually reset the circuit breaker.", source.getTextName());
        source.sendSuccess(() -> Component.literal("Circuit breaker reset — server accepting connections normally."), true);
        return 1;
    }

    private static int ddosClearIp(CommandContext<CommandSourceStack> ctx) {
        String ip = StringArgumentType.getString(ctx, "ip");
        CommandSourceStack source = ctx.getSource();
        ConnectionRateLimiter.clearIp(ip);
        com.raeyncreations.raeyncheat.server.BotDetector.clearIp(ip);
        RaeYNCheat.LOGGER.info("Admin {} cleared all rate-limit and bot-detect state for IP {}.",
                source.getTextName(), ip);
        source.sendSuccess(() -> Component.literal(
                "Cleared rate-limit and bot-detect state for IP: " + ip), true);
        return 1;
    }

    private static int ddosClearUuid(CommandContext<CommandSourceStack> ctx) {
        String playerName = StringArgumentType.getString(ctx, "player");
        CommandSourceStack source = ctx.getSource();
        try {
            ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(playerName);
            if (target == null) {
                source.sendFailure(Component.literal("Player not found online: " + playerName));
                return 0;
            }
            String uuidStr = target.getUUID().toString();
            ConnectionRateLimiter.clearUuid(uuidStr);
            com.raeyncreations.raeyncheat.server.BotDetector.clearUuid(uuidStr);
            RaeYNCheat.LOGGER.info("Admin {} cleared rate-limit/bot state for {} ({}).",
                    source.getTextName(), playerName, uuidStr);
            source.sendSuccess(() -> Component.literal(
                    "Cleared rate-limit and bot state for " + playerName + " (" + uuidStr + ")"), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int ddosIpInfo(CommandContext<CommandSourceStack> ctx) {
        String ip = StringArgumentType.getString(ctx, "ip");
        CommandSourceStack source = ctx.getSource();
        String report = ConnectionRateLimiter.getIpReport(ip);
        source.sendSuccess(() -> Component.literal(report), false);
        return 1;
    }

    // ---------------------------------------------------------------------------
    // Bot detection commands
    // ---------------------------------------------------------------------------

    private static int botStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal(
                com.raeyncreations.raeyncheat.server.BotDetector.getStatusReport()), false);
        return 1;
    }

    private static int botClearIp(CommandContext<CommandSourceStack> ctx) {
        String ip = StringArgumentType.getString(ctx, "ip");
        CommandSourceStack source = ctx.getSource();
        com.raeyncreations.raeyncheat.server.BotDetector.clearIp(ip);
        RaeYNCheat.LOGGER.info("Admin {} cleared bot-detect state for IP {}.", source.getTextName(), ip);
        source.sendSuccess(() -> Component.literal("Cleared bot-detect state for IP: " + ip), true);
        return 1;
    }

    private static int botClearUuid(CommandContext<CommandSourceStack> ctx) {
        String playerName = StringArgumentType.getString(ctx, "player");
        CommandSourceStack source = ctx.getSource();
        try {
            ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(playerName);
            if (target == null) {
                source.sendFailure(Component.literal("Player not found online: " + playerName));
                return 0;
            }
            String uuidStr = target.getUUID().toString();
            com.raeyncreations.raeyncheat.server.BotDetector.clearUuid(uuidStr);
            source.sendSuccess(() -> Component.literal(
                    "Cleared bot-detect state for " + playerName + " (" + uuidStr + ")"), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    // ---------------------------------------------------------------------------
    // Revalidation commands
    // ---------------------------------------------------------------------------

    private static int revalidatePlayer(CommandContext<CommandSourceStack> ctx) {
        String playerName = StringArgumentType.getString(ctx, "player");
        CommandSourceStack source = ctx.getSource();
        try {
            ServerPlayer target = source.getServer().getPlayerList().getPlayerByName(playerName);
            if (target == null) {
                source.sendFailure(Component.literal("Player not found online: " + playerName));
                return 0;
            }
            RaeYNCheat.sendRevalidationRequest(target);
            source.sendSuccess(() -> Component.literal(
                    "Revalidation request sent to " + playerName + ". Results will appear in cheat.log."), true);
            RaeYNCheat.LOGGER.info("Admin {} triggered revalidation for {}.", source.getTextName(), playerName);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int revalidateAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            var players = source.getServer().getPlayerList().getPlayers();
            if (players.isEmpty()) {
                source.sendFailure(Component.literal("No players online."));
                return 0;
            }
            int count = 0;
            for (ServerPlayer player : players) { RaeYNCheat.sendRevalidationRequest(player); count++; }
            final int sent = count;
            source.sendSuccess(() -> Component.literal(
                    "Revalidation request sent to " + sent + " online player(s)."), true);
            RaeYNCheat.LOGGER.info("Admin {} triggered revalidation for all {} online players.",
                    source.getTextName(), sent);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    // ---------------------------------------------------------------------------
    // Step management
    // ---------------------------------------------------------------------------

    private static int setStep(CommandContext<CommandSourceStack> ctx, ViolationType type) {
        CommandSourceStack source = ctx.getSource();
        int index    = IntegerArgumentType.getInteger(ctx, "index");
        int duration = IntegerArgumentType.getInteger(ctx, "duration");
        try {
            RaeYNCheatConfig config = RaeYNCheat.getConfig();
            if (config == null) { source.sendFailure(Component.literal("Configuration not loaded.")); return 0; }

            boolean ok = switch (type) {
                case CHECKSUM    -> config.setChecksumPunishmentStep(index, duration);
                case PASSKEY     -> config.setPasskeyPunishmentStep(index, duration);
                case ENV         -> config.setEnvPunishmentStep(index, duration);
                default          -> config.setNegotiationPunishmentStep(index, duration);
            };

            if (ok) {
                RaeYNCheat.saveConfig();
                String cap = cap(type.label);
                source.sendSuccess(() -> Component.literal(
                        cap + " step " + index + " set to: " + fmtDuration(duration)), true);
                RaeYNCheat.LOGGER.info("Admin {} set {} step {} to {}.", source.getTextName(), type.label, index, duration);
                return 1;
            }
            source.sendFailure(Component.literal("Failed to set step. Check server logs."));
            return 0;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int getStep(CommandContext<CommandSourceStack> ctx, ViolationType type) {
        CommandSourceStack source = ctx.getSource();
        int index = IntegerArgumentType.getInteger(ctx, "index");
        try {
            RaeYNCheatConfig config = RaeYNCheat.getConfig();
            if (config == null) { source.sendFailure(Component.literal("Configuration not loaded.")); return 0; }

            int duration = switch (type) {
                case CHECKSUM    -> config.getChecksumPunishmentStep(index);
                case PASSKEY     -> config.getPasskeyPunishmentStep(index);
                case ENV         -> config.getEnvPunishmentStep(index);
                default          -> config.getNegotiationPunishmentStep(index);
            };

            if (RaeYNCheatConfig.isInvalidStepIndex(duration)) {
                source.sendFailure(Component.literal("No step at index " + index + " for " + type.label + "."));
                return 0;
            }
            String cap = cap(type.label);
            source.sendSuccess(() -> Component.literal(cap + " step " + index + ": " + fmtDuration(duration)), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int listSteps(CommandContext<CommandSourceStack> ctx, ViolationType type) {
        CommandSourceStack source = ctx.getSource();
        try {
            RaeYNCheatConfig config = RaeYNCheat.getConfig();
            if (config == null) { source.sendFailure(Component.literal("Configuration not loaded.")); return 0; }

            String steps = switch (type) {
                case CHECKSUM    -> config.getChecksumPunishmentStepsString();
                case PASSKEY     -> config.getPasskeyPunishmentStepsString();
                case ENV         -> config.getEnvPunishmentStepsString();
                default          -> config.getNegotiationPunishmentStepsString();
            };
            String cap = cap(type.label);
            source.sendSuccess(() -> Component.literal(cap + " punishment steps: " + steps), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    // ---------------------------------------------------------------------------
    // CheckSum_init refresh
    // ---------------------------------------------------------------------------

    private static int refreshChecksumInit(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            if (RaeYNCheat.getCheckFileManager() == null) {
                source.sendFailure(Component.literal("CheckFileManager not initialized."));
                return 0;
            }
            RaeYNCheat.getCheckFileManager().generateServerInitCheckFile();
            String admin = source.getTextName();
            RaeYNCheat.LOGGER.info("CheckSum_init manually refreshed by {}.", admin);
            source.sendSuccess(() -> Component.literal("CheckSum_init refreshed successfully."), true);
            return 1;
        } catch (Exception e) {
            RaeYNCheat.LOGGER.error("Error refreshing CheckSum_init", e);
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    // ---------------------------------------------------------------------------
    // Auth admin commands  (/raeyn auth ...)
    // ---------------------------------------------------------------------------

    private static int authStatus(CommandContext<CommandSourceStack> ctx) {
        String playerName = StringArgumentType.getString(ctx, "player");
        CommandSourceStack source = ctx.getSource();
        try {
            String uuid = resolveUuid(source, playerName);
            if (uuid == null) {
                source.sendFailure(Component.literal(
                        "Player '" + playerName + "' not found (must be online or have joined before)."));
                return 0;
            }
            String status = AuthManager.getAuthStatus(uuid);
            source.sendSuccess(() -> Component.literal(
                    "§6[Auth] Status for §e" + playerName + "§6:\n§r" + status), false);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int authReset(CommandContext<CommandSourceStack> ctx) {
        String playerName = StringArgumentType.getString(ctx, "player");
        CommandSourceStack source = ctx.getSource();
        try {
            String uuid = resolveUuid(source, playerName);
            if (uuid == null) {
                source.sendFailure(Component.literal(
                        "Player '" + playerName + "' not found (must have joined the server before)."));
                return 0;
            }
            AuthManager.adminReset(uuid, playerName, source.getTextName());
            // Notify online player if present
            ServerPlayer online = source.getServer().getPlayerList().getPlayer(UUID.fromString(uuid));
            if (online != null) {
                online.sendSystemMessage(Component.literal(
                        "§c[Auth] An admin has reset your account security.\n"
                        + "§7You will need to set a new password on your next login."));
            }
            source.sendSuccess(() -> Component.literal(
                    "§a[Auth] Reset all auth for §e" + playerName
                    + "§a. They must set a new password on next login."), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private static int authUnlock(CommandContext<CommandSourceStack> ctx) {
        String playerName = StringArgumentType.getString(ctx, "player");
        CommandSourceStack source = ctx.getSource();
        try {
            String uuid = resolveUuid(source, playerName);
            if (uuid == null) {
                source.sendFailure(Component.literal(
                        "Player '" + playerName + "' not found (must have joined the server before)."));
                return 0;
            }
            AuthManager.adminUnlock(uuid, playerName, source.getTextName());
            source.sendSuccess(() -> Component.literal(
                    "§a[Auth] Unlocked account for §e" + playerName + "§a. "
                    + "They can log in and authenticate now."), true);
            // Notify if online
            ServerPlayer online = source.getServer().getPlayerList().getPlayer(UUID.fromString(uuid));
            if (online != null) {
                online.sendSystemMessage(Component.literal(
                        "§a[Auth] Your account has been unlocked by an admin. You may now log in."));
            }
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Resolve a player name to UUID string.
     * Checks online players first, then falls back to the server's user cache
     * so admin commands work on offline players who have previously joined.
     */
    private static String resolveUuid(CommandSourceStack source, String playerName) {
        // Online check first (fast path)
        ServerPlayer online = source.getServer().getPlayerList().getPlayerByName(playerName);
        if (online != null) return online.getUUID().toString();

        // Usercache lookup — works for any player who has joined this server before
        com.mojang.authlib.GameProfile profile =
                source.getServer().getUserCache() != null
                ? source.getServer().getUserCache().get(playerName).orElse(null)
                : null;
        return profile != null ? profile.getId().toString() : null;
    }

    private static String fmtDuration(int d) {
        if (d == -1) return "PERMANENT BAN";
        if (d ==  0) return "KICK (no ban)";
        return d + " seconds";
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }
}
