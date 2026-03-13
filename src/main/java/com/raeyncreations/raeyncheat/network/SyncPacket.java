package com.raeyncreations.raeyncheat.network;

import com.raeyncreations.raeyncheat.RaeYNCheat;
import com.raeyncreations.raeyncheat.server.ValidationHandler;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Packet sent from CLIENT to SERVER containing:
 *   - passkey    : two-part derived key for identity verification
 *   - checksum   : encrypted aggregate hash of the client's mods/ folder
 *   - envReport  : encrypted EnvironmentScanner report (JVM args, extra JARs, ModList, ClassLoader)
 *   - nonce      : echo of the nonce sent in the triggering RevalidatePacket
 *
 * FIX #2/#10 (replay / nonce): The nonce field echoes the value from the RevalidatePacket
 * that triggered this sync. The server verifies it against the pending nonce for this player.
 * For the initial login sync (not triggered by a RevalidatePacket), the client sends an
 * empty nonce — the server accepts empty nonces only on first login before any RevalidatePacket
 * has been issued for this player.
 *
 * The envReport is encrypted with the same passkey as the checksum, so the server
 * decrypts it using the same validated key. This means a cheater cannot send a clean
 * environment report without first knowing the correct passkey.
 */
public record SyncPacket(String passkey, String checksum, String envReport, String nonce)
        implements CustomPacketPayload {

    private static final int MAX_PASSKEY_LENGTH    = 512;
    private static final int MAX_CHECKSUM_LENGTH   = 4096;
    private static final int MAX_ENV_REPORT_LENGTH = 8192;
    private static final int MAX_NONCE_LENGTH      = 64;

    public static final Type<SyncPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RaeYNCheat.MOD_ID, "sync"));

    public static final StreamCodec<ByteBuf, SyncPacket> STREAM_CODEC = StreamCodec.composite(
            new StreamCodec<ByteBuf, String>() {
                @Override public String decode(ByteBuf buffer) {
                    return new FriendlyByteBuf(buffer).readUtf(MAX_PASSKEY_LENGTH);
                }
                @Override public void encode(ByteBuf buffer, String value) {
                    if (value.length() > MAX_PASSKEY_LENGTH)
                        throw new IllegalArgumentException("Passkey exceeds max length");
                    new FriendlyByteBuf(buffer).writeUtf(value, MAX_PASSKEY_LENGTH);
                }
            },
            SyncPacket::passkey,
            new StreamCodec<ByteBuf, String>() {
                @Override public String decode(ByteBuf buffer) {
                    return new FriendlyByteBuf(buffer).readUtf(MAX_CHECKSUM_LENGTH);
                }
                @Override public void encode(ByteBuf buffer, String value) {
                    if (value.length() > MAX_CHECKSUM_LENGTH)
                        throw new IllegalArgumentException("Checksum exceeds max length");
                    new FriendlyByteBuf(buffer).writeUtf(value, MAX_CHECKSUM_LENGTH);
                }
            },
            SyncPacket::checksum,
            new StreamCodec<ByteBuf, String>() {
                @Override public String decode(ByteBuf buffer) {
                    return new FriendlyByteBuf(buffer).readUtf(MAX_ENV_REPORT_LENGTH);
                }
                @Override public void encode(ByteBuf buffer, String value) {
                    if (value.length() > MAX_ENV_REPORT_LENGTH)
                        throw new IllegalArgumentException("Environment report exceeds max length");
                    new FriendlyByteBuf(buffer).writeUtf(value, MAX_ENV_REPORT_LENGTH);
                }
            },
            SyncPacket::envReport,
            new StreamCodec<ByteBuf, String>() {
                @Override public String decode(ByteBuf buffer) {
                    return new FriendlyByteBuf(buffer).readUtf(MAX_NONCE_LENGTH);
                }
                @Override public void encode(ByteBuf buffer, String value) {
                    new FriendlyByteBuf(buffer).writeUtf(value != null ? value : "", MAX_NONCE_LENGTH);
                }
            },
            SyncPacket::nonce,
            SyncPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SyncPacket packet, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;

        String playerUUID     = player.getUUID().toString();
        String playerUsername = player.getName().getString();

        // FIX: Shadow-ban check — bots that passed the connection filter but were
        // flagged as suspicious get their SyncPacket silently dropped here.
        // They see no kick message, no error — they just hang. Wastes bot time.
        if (com.raeyncreations.raeyncheat.server.BotDetector.isShadowBanned(playerUUID)) {
            RaeYNCheat.LOGGER.info("[SyncPacket] Shadow-banned UUID {} ({}) — packet silently dropped.",
                    playerUUID, playerUsername);
            return;
        }

        // FIX #2 (rate limiting): Reject packets arriving faster than the configured cooldown.
        if (!com.raeyncreations.raeyncheat.server.ConnectionRateLimiter
                .allowSyncPacket(playerUUID, playerUsername)) {
            RaeYNCheat.LOGGER.warn("SyncPacket rate-limited for {} ({}) — dropping.", playerUsername, playerUUID);
            // Don't disconnect — just drop. Disconnecting on a rate-limited packet would itself
            // be exploitable as a griefing vector (send lots of packets to get someone kicked).
            return;
        }

        if (packet.passkey() == null || packet.checksum() == null || packet.envReport() == null) {
            RaeYNCheat.LOGGER.error("Null fields in sync packet from {} ({})", playerUsername, playerUUID);
            player.connection.disconnect(Component.literal("Invalid sync packet - null fields"));
            return;
        }

        if (packet.passkey().trim().isEmpty() || packet.checksum().trim().isEmpty()
                || packet.envReport().trim().isEmpty()) {
            RaeYNCheat.LOGGER.error("Empty fields in sync packet from {} ({})", playerUsername, playerUUID);
            player.connection.disconnect(Component.literal("Invalid sync packet - empty fields"));
            return;
        }

        if (!isValidBase64Format(packet.passkey()) || !isValidBase64Format(packet.checksum())
                || !isValidBase64Format(packet.envReport())) {
            RaeYNCheat.LOGGER.error("Malformed Base64 in sync packet from {} ({})", playerUsername, playerUUID);
            player.connection.disconnect(Component.literal("Invalid sync packet - malformed data"));
            return;
        }

        RaeYNCheat.LOGGER.info("Received sync packet from {} ({})", playerUsername, playerUUID);
        ValidationHandler.validatePlayer(player, packet.passkey(), packet.checksum(),
                packet.envReport(), packet.nonce() != null ? packet.nonce() : "");
    }

    /** Allow Base64 chars plus colon (passkey separator). */
    private static boolean isValidBase64Format(String data) {
        return data != null && !data.isEmpty() && data.matches("^[A-Za-z0-9+/=:\\-_]+$");
    }
}
