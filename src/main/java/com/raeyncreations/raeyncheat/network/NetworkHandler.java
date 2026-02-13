package com.raeyncreations.raeyncheat.network;

import com.raeyncreations.raeyncheat.RaeYNCheat;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Network handler for registering and managing packets
 */
public class NetworkHandler {
    
    private static final String PROTOCOL_VERSION = "1";
    
    /**
     * Register all network packets
     */
    public static void register(PayloadRegistrar registrar) {
        RaeYNCheat.LOGGER.info("Registering network packets...");
        
        // Register sync packet (client to server)
        registrar.playToServer(
            SyncPacket.TYPE,
            SyncPacket.STREAM_CODEC,
            NetworkHandler::handleSyncPacket
        );
        
        RaeYNCheat.LOGGER.info("Network packets registered successfully");
    }
    
    /**
     * Handle sync packet on server side
     */
    private static void handleSyncPacket(SyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            SyncPacket.handle(packet, context);
        });
    }
}
