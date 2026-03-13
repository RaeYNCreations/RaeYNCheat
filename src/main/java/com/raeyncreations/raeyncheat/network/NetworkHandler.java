package com.raeyncreations.raeyncheat.network;

import com.raeyncreations.raeyncheat.RaeYNCheat;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers all network packets for RaeYNCheat.
 *
 * Packet directions:
 *   SyncPacket      — CLIENT → SERVER  (passkey + checksum + environment report)
 *   RevalidatePacket — SERVER → CLIENT  (trigger: re-run scan and send a fresh SyncPacket)
 */
public class NetworkHandler {

    public static void register(PayloadRegistrar registrar) {
        RaeYNCheat.LOGGER.info("Registering RaeYNCheat network packets...");

        // Client → Server: full validation payload
        registrar.playToServer(
            SyncPacket.TYPE,
            SyncPacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() -> SyncPacket.handle(packet, ctx))
        );

        // Server → Client: revalidation trigger
        registrar.playToClient(
            RevalidatePacket.TYPE,
            RevalidatePacket.STREAM_CODEC,
            (packet, ctx) -> ctx.enqueueWork(() -> RevalidatePacket.handle(packet, ctx))
        );

        RaeYNCheat.LOGGER.info("RaeYNCheat network packets registered.");
    }
}
