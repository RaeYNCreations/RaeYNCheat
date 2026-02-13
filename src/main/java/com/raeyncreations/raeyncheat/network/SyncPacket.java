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
 * Packet sent from client to server containing passkey and checksum for validation
 */
public record SyncPacket(String passkey, String checksum) implements CustomPacketPayload {
    
    // Maximum allowed lengths to prevent DoS attacks
    private static final int MAX_PASSKEY_LENGTH = 512;
    private static final int MAX_CHECKSUM_LENGTH = 4096;
    
    public static final Type<SyncPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RaeYNCheat.MOD_ID, "sync"));
    
    public static final StreamCodec<ByteBuf, SyncPacket> STREAM_CODEC = StreamCodec.composite(
        // Read/write passkey
        new StreamCodec<ByteBuf, String>() {
            @Override
            public String decode(ByteBuf buffer) {
                FriendlyByteBuf buf = new FriendlyByteBuf(buffer);
                return buf.readUtf(MAX_PASSKEY_LENGTH);
            }
            
            @Override
            public void encode(ByteBuf buffer, String value) {
                FriendlyByteBuf buf = new FriendlyByteBuf(buffer);
                if (value.length() > MAX_PASSKEY_LENGTH) {
                    throw new IllegalArgumentException("Passkey exceeds maximum length of " + MAX_PASSKEY_LENGTH);
                }
                buf.writeUtf(value, MAX_PASSKEY_LENGTH);
            }
        },
        SyncPacket::passkey,
        // Read/write checksum
        new StreamCodec<ByteBuf, String>() {
            @Override
            public String decode(ByteBuf buffer) {
                FriendlyByteBuf buf = new FriendlyByteBuf(buffer);
                return buf.readUtf(MAX_CHECKSUM_LENGTH);
            }
            
            @Override
            public void encode(ByteBuf buffer, String value) {
                FriendlyByteBuf buf = new FriendlyByteBuf(buffer);
                if (value.length() > MAX_CHECKSUM_LENGTH) {
                    throw new IllegalArgumentException("Checksum exceeds maximum length of " + MAX_CHECKSUM_LENGTH);
                }
                buf.writeUtf(value, MAX_CHECKSUM_LENGTH);
            }
        },
        SyncPacket::checksum,
        SyncPacket::new
    );
    
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
    
    /**
     * Handle packet on server side
     */
    public static void handle(SyncPacket packet, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            String playerUUID = player.getUUID().toString();
            String playerUsername = player.getName().getString();
            
            // Validate packet fields are not null
            if (packet.passkey() == null || packet.checksum() == null) {
                RaeYNCheat.LOGGER.error("Received sync packet with null fields from player {} (UUID: {})", 
                    playerUsername, playerUUID);
                player.connection.disconnect(Component.literal("Invalid sync packet - null fields"));
                return;
            }
            
            // Validate packet fields are not empty
            if (packet.passkey().trim().isEmpty() || packet.checksum().trim().isEmpty()) {
                RaeYNCheat.LOGGER.error("Received sync packet with empty fields from player {} (UUID: {})", 
                    playerUsername, playerUUID);
                player.connection.disconnect(Component.literal("Invalid sync packet - empty fields"));
                return;
            }
            
            RaeYNCheat.LOGGER.info("Received sync packet from player {} (UUID: {})", playerUsername, playerUUID);
            
            // Validate passkey and checksum
            ValidationHandler.validatePlayer(player, packet.passkey(), packet.checksum());
        }
    }
}
