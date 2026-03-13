package com.raeyncreations.raeyncheat.network;

import com.raeyncreations.raeyncheat.RaeYNCheat;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Packet sent from SERVER to CLIENT requesting a fresh environment scan and SyncPacket.
 *
 * FIX #10 (nonce): Each RevalidatePacket now carries a randomly-generated nonce.
 * The client MUST echo this nonce back verbatim in the SyncPacket it sends in response.
 * The server verifies the echo, which prevents:
 *   - Replay attacks (old captured SyncPackets cannot satisfy a new nonce)
 *   - Cheat hooks that intercept the trigger and re-send a pre-recorded clean SyncPacket
 *
 * The nonce is 16 bytes of SecureRandom data, Base64-encoded (24 chars).
 * It is stored server-side in ValidationHandler's pending-nonce map and expires
 * after NONCE_EXPIRY_SECONDS if no response is received.
 */
public record RevalidatePacket(String nonce) implements CustomPacketPayload {

    private static final int MAX_NONCE_LENGTH = 64;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static final Type<RevalidatePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(RaeYNCheat.MOD_ID, "revalidate"));

    public static final StreamCodec<ByteBuf, RevalidatePacket> STREAM_CODEC =
            StreamCodec.composite(
                    new StreamCodec<ByteBuf, String>() {
                        @Override public String decode(ByteBuf buffer) {
                            return new FriendlyByteBuf(buffer).readUtf(MAX_NONCE_LENGTH);
                        }
                        @Override public void encode(ByteBuf buffer, String value) {
                            new FriendlyByteBuf(buffer).writeUtf(value, MAX_NONCE_LENGTH);
                        }
                    },
                    RevalidatePacket::nonce,
                    RevalidatePacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Generate a new RevalidatePacket with a fresh cryptographic nonce. */
    public static RevalidatePacket withFreshNonce() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        return new RevalidatePacket(Base64.getEncoder().encodeToString(bytes));
    }

    /**
     * Handle on the CLIENT side — store the nonce and trigger a fresh scan.
     * The nonce is passed to RaeYNCheatClient so it can be echoed in the SyncPacket.
     */
    /**
     * Handle on the CLIENT side.
     * Note: NetworkHandler already wraps this call in enqueueWork(), so we run directly
     * on the main thread here — no additional enqueueWork needed.
     */
    public static void handle(RevalidatePacket packet, IPayloadContext context) {
        RaeYNCheat.LOGGER.info("Received revalidation request from server (nonce present: {}).",
                packet.nonce() != null && !packet.nonce().isEmpty());
        com.raeyncreations.raeyncheat.client.RaeYNCheatClient.triggerRevalidation(packet.nonce());
    }
}
