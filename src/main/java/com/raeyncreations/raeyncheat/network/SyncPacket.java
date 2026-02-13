package com.raeyncreations.raeyncheat.network;

import com.raeyncreations.raeyncheat.RaeYNCheat;
import com.raeyncreations.raeyncheat.server.ValidationHandler;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Packet sent from client to server containing passkey and checksum for validation
 */
public record SyncPacket(String passkey, String checksum) implements CustomPacketPayload {
    
    public static final Type<SyncPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RaeYNCheat.MOD_ID, "sync"));
    
    public static final StreamCodec<ByteBuf, SyncPacket> STREAM_CODEC = StreamCodec.composite(
        // Read/write passkey
        new StreamCodec<ByteBuf, String>() {
            @Override
            public String decode(ByteBuf buffer) {
                FriendlyByteBuf buf = new FriendlyByteBuf(buffer);
                return buf.readUtf(32767);
            }
            
            @Override
            public void encode(ByteBuf buffer, String value) {
                FriendlyByteBuf buf = new FriendlyByteBuf(buffer);
                buf.writeUtf(value, 32767);
            }
        },
        SyncPacket::passkey,
        // Read/write checksum
        new StreamCodec<ByteBuf, String>() {
            @Override
            public String decode(ByteBuf buffer) {
                FriendlyByteBuf buf = new FriendlyByteBuf(buffer);
                return buf.readUtf(32767);
            }
            
            @Override
            public void encode(ByteBuf buffer, String value) {
                FriendlyByteBuf buf = new FriendlyByteBuf(buffer);
                buf.writeUtf(value, 32767);
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
            
            RaeYNCheat.LOGGER.info("Received sync packet from player {} (UUID: {})", playerUsername, playerUUID);
            
            // Validate passkey and checksum
            ValidationHandler.validatePlayer(player, packet.passkey(), packet.checksum());
        }
    }
}
