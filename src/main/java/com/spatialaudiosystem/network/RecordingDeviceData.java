package com.spatialaudiosystem.network;

import com.manta.api.data.Host;
import com.manta.api.data.MantaData;
import com.manta.api.data.Schema;
import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.AudioStorage;
import com.spatialaudiosystem.audio.PlaybackSessionRegistry;
import com.spatialaudiosystem.blockentity.RecordingDeviceBlockEntity;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.server.ServerInteractionGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The recording device's actions on manta:data (MANTA_7_CONCEPT C4): what ClearAudioPayload, StartRecordingPayload
 * and TestPlayRecordingPayload carried, declared once in the device layout's {@code state} member, and the start
 * refusal RecordingErrorPayload sent back. The block entity's {@link Host} admits exactly the players
 * {@link ServerInteractionGuard} admitted - the sender has this device's menu open, in its level, still valid,
 * with the owner's access - asked again on every action.
 *
 * <p>The refusal is an event, not state: {@code recording-error} holds the reason and {@code recording-error-seq}
 * moves, and the screen shows the reason when it sees the counter move. It goes to every viewer of the device,
 * not only to the one who pressed start (the payload went to that player alone): the device refused to start.</p>
 */
public final class RecordingDeviceData {

    /** The layout that declares the state, read from the jar on both sides (the same bytes, the same hash). */
    static final String LAYOUT = "/assets/spatialaudiosystem/layouts/recording-device.json";

    private static final int[] NO_ATTENUATION = {8, 8, 8, 8, 8, 8};

    private static Schema schema;

    private RecordingDeviceData() {
    }

    public static synchronized Schema schema() {
        if (schema == null) {
            schema = MantaData.schemaResource(SpatialAudioSystem.class, LAYOUT);
        }
        return schema;
    }

    public static String channel(Level level, BlockPos pos) {
        return MantaData.channel(SpatialAudioSystem.MOD_ID, "recording", level.dimension(), pos);
    }

    /** The device's host, on the server thread. Closed by the block entity with itself. */
    public static Host open(RecordingDeviceBlockEntity device, ServerLevel level) {
        BlockPos pos = device.getBlockPos();
        Host host = MantaData.host(level.getServer(), channel(level, pos), schema(),
                player -> ServerInteractionGuard.recordingDevice(player, pos) == device);
        host.on("clear-audio", (args, player) -> {
            device.clearPendingAudio();
            device.clearMediaAudioData();
        });
        host.on("start-recording", (args, player) -> {
            int result = device.startRecording();
            if (result != RecordingDeviceBlockEntity.START_OK) {
                host.set("recording-error", result);
                host.set("recording-error-seq", ((Integer) host.get("recording-error-seq") + 1) & 0xFFFF);
            }
        });
        host.on("test-play", (args, player) -> {
            if ((Boolean) args.get(0)) {
                startPreview(level, device, pos);
            } else {
                stopPreview(level, pos);
            }
        });
        return host;
    }

    /**
     * Preview ("test play") the finished medium in the output slot, or the pending upload. Reuses the normal
     * playback broadcast, so the preview plays at the device for every nearby client just like a playback
     * device would.
     */
    private static void startPreview(ServerLevel level, RecordingDeviceBlockEntity device, BlockPos pos) {
        byte[] audio;
        String format;
        if (device.getPendingAudioData() != null) {
            // A file was picked/uploaded but not yet written: preview it directly.
            audio = device.getPendingAudioData();
            format = device.getPendingFormat() != null ? device.getPendingFormat() : "ogg";
        } else {
            // Otherwise preview the finished medium in the output slot.
            ItemStack medium = device.getInventory().getStackInSlot(RecordingDeviceBlockEntity.OUTPUT_SLOT);
            audio = AudioStorage.loadForItem(level.getServer(), medium);
            if (audio == null) return;   // nothing to preview (no pending audio, empty output slot)
            format = medium.getOrDefault(ModDataComponents.AUDIO_FORMAT, "ogg");
        }

        long playbackId = PlaybackSessionRegistry.begin(level, pos);
        // A preview from the recording screen is one-shot, and deliberately not registered as
        // replayable: it is a check on the medium you are holding, not a sound placed in the
        // world for others to walk into.
        ClientPlayAudioPayload meta = new ClientPlayAudioPayload(
                pos, playbackId, audio.length, format, null, null, false, NO_ATTENUATION,
                // No loop, and no catching up: the preview always starts at the top,
                // because it is a check on the medium rather than a sound already running.
                // Not synchronised: a preview is a check on the medium in your hand, so it
                // starts at the top rather than wherever a shared sound has got to.
                false, 0, false, 0L);
        for (ServerPlayer sp : level.players()) {
            PacketDistributor.sendToPlayer(sp, meta);
            ClientAudioChunkPayload.sendChunked(sp, pos, playbackId, audio);
        }
    }

    private static void stopPreview(ServerLevel level, BlockPos pos) {
        ClientStopAudioPayload stop =
                new ClientStopAudioPayload(pos, PlaybackSessionRegistry.currentId(level, pos));
        PlaybackSessionRegistry.end(level, pos);
        for (ServerPlayer sp : level.players()) {
            PacketDistributor.sendToPlayer(sp, stop);
        }
    }
}
