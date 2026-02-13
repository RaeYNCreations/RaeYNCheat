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
            
            // Validate Base64 format (passkey has colon separator, checksum is pure Base64)
            if (!isValidPasskeyFormat(packet.passkey())) {
                RaeYNCheat.LOGGER.error("Received sync packet with invalid passkey format from player {} (UUID: {})", 
                    playerUsername, playerUUID);
                player.connection.disconnect(Component.literal("Invalid sync packet - malformed passkey"));
                return;
            }
            
            if (!isValidChecksumFormat(packet.checksum())) {
                RaeYNCheat.LOGGER.error("Received sync packet with invalid checksum format from player {} (UUID: {})", 
                    playerUsername, playerUUID);
                player.connection.disconnect(Component.literal("Invalid sync packet - malformed checksum"));
                return;
            }
            
            RaeYNCheat.LOGGER.info("Received sync packet from player {} (UUID: {})", playerUsername, playerUUID);
            RaeYNCheat.LOGGER.debug("Sync packet validation passed - Passkey length: {}, Checksum length: {}", 
                packet.passkey().length(), packet.checksum().length());
            
            // Validate passkey and checksum
            ValidationHandler.validatePlayer(player, packet.passkey(), packet.checksum());
        }
    }
    
    /**
     * Validate that a passkey has the correct two-part format with colon separator
     * Format: "PermanentKey:HashedUUID"
     * Both parts should be Base64-encoded strings separated by exactly one colon
     * 
     * @param passkey The passkey string to validate
     * @return true if the passkey has valid two-part Base64 format with colon separator
     */
    private static boolean isValidPasskeyFormat(String passkey) {
        if (passkey == null || passkey.isEmpty()) {
            return false;
        }
        
        // Must contain exactly one colon separator (efficient O(n) check)
        int firstColon = passkey.indexOf(':');
        int lastColon = passkey.lastIndexOf(':');
        
        // If indexOf and lastIndexOf return the same index, there's exactly one colon
        // If indexOf returns -1, there are no colons
        if (firstColon == -1 || firstColon != lastColon) {
            return false;
        }
        
        // Split by colon and validate both parts are non-empty Base64
        String[] parts = passkey.split(":", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return false;
        }
        
        // Both parts must be valid Base64 (no colons allowed in parts)
        return parts[0].matches("^[A-Za-z0-9+/=]+$") && parts[1].matches("^[A-Za-z0-9+/=]+$");
    }
    
    /**
     * Validate that a checksum is pure Base64 format without any separator
     * Checksums are encrypted data and should NOT contain colons
     * 
     * @param checksum The checksum string to validate
     * @return true if the checksum is valid Base64 format (no colons allowed)
     */
    private static boolean isValidChecksumFormat(String checksum) {
        if (checksum == null || checksum.isEmpty()) {
            return false;
        }
        // Pure Base64 only - no colons allowed in checksums
        return checksum.matches("^[A-Za-z0-9+/=]+$");
    }
}
