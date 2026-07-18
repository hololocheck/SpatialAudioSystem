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
        final PayloadRegistrar registrar = event.registrar(SpatialAudioSystem.MOD_ID).versioned("1.4");

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

        registrar.playToServer(
                StartRecordingPayload.TYPE,
                StartRecordingPayload.STREAM_CODEC,
                StartRecordingPayload::handle
        );

        registrar.playToServer(
                TestPlayRecordingPayload.TYPE,
                TestPlayRecordingPayload.STREAM_CODEC,
                TestPlayRecordingPayload::handle
        );

        registrar.optional().playToServer(
                RequestArtPayload.TYPE,
                RequestArtPayload.STREAM_CODEC,
                RequestArtPayload::handle
        );

        registrar.playToServer(
                ToggleRangeDisplayPayload.TYPE,
                ToggleRangeDisplayPayload.STREAM_CODEC,
                ToggleRangeDisplayPayload::handle
        );

        registrar.playToServer(
                PlaybackControlPayload.TYPE,
                PlaybackControlPayload.STREAM_CODEC,
                PlaybackControlPayload::handle
        );

        registrar.playToServer(
                PlaybackFinishedPayload.TYPE,
                PlaybackFinishedPayload.STREAM_CODEC,
                PlaybackFinishedPayload::handle
        );

        registrar.playToServer(
                ClearAudioPayload.TYPE,
                ClearAudioPayload.STREAM_CODEC,
                ClearAudioPayload::handle
        );

        registrar.playToServer(
                ToggleAttenuationPayload.TYPE,
                ToggleAttenuationPayload.STREAM_CODEC,
                ToggleAttenuationPayload::handle
        );

        registrar.playToServer(
                SetAttenuationRangePayload.TYPE,
                SetAttenuationRangePayload.STREAM_CODEC,
                SetAttenuationRangePayload::handle
        );

        registrar.playToServer(
                SetRangeBoardDataPayload.TYPE,
                SetRangeBoardDataPayload.STREAM_CODEC,
                SetRangeBoardDataPayload::handle
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

        // Optional: won't block connection if server/client versions differ
        registrar.optional().playToClient(
                ClientNotifyPayload.TYPE,
                ClientNotifyPayload.STREAM_CODEC,
                ClientNotifyPayload::handle
        );

        registrar.optional().playToClient(
                RecordingErrorPayload.TYPE,
                RecordingErrorPayload.STREAM_CODEC,
                RecordingErrorPayload::handle
        );

        registrar.optional().playToClient(
                ArtDataPayload.TYPE,
                ArtDataPayload.STREAM_CODEC,
                ArtDataPayload::handle
        );
    }
}
