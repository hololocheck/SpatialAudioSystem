package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = SpatialAudioSystem.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class ModNetworking {
    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        // 1.3 split playback_finished out of playback_control so the stop command can be
        //     checked against the sender's open menu without silencing natural completion.
        // 1.4 every audio packet names the playback it belongs to.
        // 1.5 client_play_audio carries a start offset and a synchronised flag; client_set_loop
        //     and catchup_report added. Bumped so a client on the old shape is refused at
        //     login instead of reading the new fields as garbage -- which is the exact shape
        //     of a late joiner ignoring its offset (2026-09-02).
        // 1.6 the sound handy: handy_action / set_device_name (C2S) and handy_device_list (S2C).
        // 1.7 the screens', the handy's and the range board's own payloads moved to manta:data (MANTA_7_CONCEPT C4):
        //     sixteen fewer here, and a client on the 1.6 shape is refused at login.
        final PayloadRegistrar registrar = event.registrar(SpatialAudioSystem.MOD_ID).versioned("1.7");

        registrar.playToServer(
                AudioUploadStartPayload.TYPE,
                AudioUploadStartPayload.STREAM_CODEC,
                AudioUploadStartPayload::handle
        );

        registrar.playToServer(
                AudioUploadChunkPayload.TYPE,
                AudioUploadChunkPayload.STREAM_CODEC,
                AudioUploadChunkPayload::handle
        );

        registrar.optional().playToServer(
                RequestArtPayload.TYPE,
                RequestArtPayload.STREAM_CODEC,
                RequestArtPayload::handle
        );

        registrar.playToServer(
                PlaybackFinishedPayload.TYPE,
                PlaybackFinishedPayload.STREAM_CODEC,
                PlaybackFinishedPayload::handle
        );

        registrar.playToClient(
                ClientPlayAudioPayload.TYPE,
                ClientPlayAudioPayload.STREAM_CODEC,
                ClientPlayAudioPayload::handle
        );

        registrar.playToClient(
                ClientAudioChunkPayload.TYPE,
                ClientAudioChunkPayload.STREAM_CODEC,
                ClientAudioChunkPayload::handle
        );

        registrar.playToClient(
                ClientStopAudioPayload.TYPE,
                ClientStopAudioPayload.STREAM_CODEC,
                ClientStopAudioPayload::handle
        );

        registrar.playToClient(
                ClientSetLoopPayload.TYPE,
                ClientSetLoopPayload.STREAM_CODEC,
                ClientSetLoopPayload::handle
        );

        registrar.playToServer(
                CatchUpReportPayload.TYPE,
                CatchUpReportPayload.STREAM_CODEC,
                CatchUpReportPayload::handle
        );

        // Optional: won't block connection if server/client versions differ

        registrar.optional().playToClient(
                ArtDataPayload.TYPE,
                ArtDataPayload.STREAM_CODEC,
                ArtDataPayload::handle
        );
    }
}
