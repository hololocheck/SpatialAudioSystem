package belugalab.sas.api;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.AudioStorage;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.ModItems;
import com.spatialaudiosystem.item.RangeBoardItem;
import com.spatialaudiosystem.item.RecordingMediumItem;
import com.spatialaudiosystem.network.ClientAudioChunkPayload;
import com.spatialaudiosystem.network.ClientPlayAudioPayload;
import com.spatialaudiosystem.network.ClientStopAudioPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.UUID;

/**
 * Public API facade for Spatial Audio System (SAS).
 *
 * <p>Other mods can integrate SAS features through this single class without
 * referencing internal implementation classes (which may move/rename between
 * versions). Use {@link #isInstalled()} for soft dependencies.
 *
 * <p>Typical integration (from another mod):
 * <pre>{@code
 *   if (!SasApi.isInstalled()) return;                // soft-dep guard
 *   if (!SasApi.hasAudio(mediumStack)) return;        // medium has no audio yet
 *   SasApi.playAudio(serverLevel, pos, mediumStack, rangeBoardStack, true);
 * }</pre>
 *
 * <p>For end-of-playback notification, register a {@link PlaybackEndedEvent}
 * listener on the NeoForge game event bus.
 */
public final class SasApi {

    private SasApi() {}

    /** SAS mod id. Useful for soft-dep checks and ResourceLocation lookups. */
    public static final String MOD_ID = "spatialaudiosystem";

    // ========================== Mod presence ==========================

    /** @return true if SAS is loaded in the current runtime. */
    public static boolean isInstalled() {
        try {
            return ModList.get() != null && ModList.get().isLoaded(MOD_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    // ========================== Item type checks ==========================

    /** @return true if {@code stack} is a SAS recording medium item. */
    public static boolean isRecordingMedium(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.getItem() == ModItems.RECORDING_MEDIUM.get();
    }

    /** @return true if {@code stack} is a SAS range board item. */
    public static boolean isRangeBoard(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.getItem() == ModItems.RANGE_BOARD.get();
    }

    // ========================== Recording medium queries ==========================

    /** @return true if the medium has playable audio data attached. */
    public static boolean hasAudio(ItemStack recordingMedium) {
        return RecordingMediumItem.hasAudioData(recordingMedium);
    }

    /** @return the saved file name (e.g. {@code "platform_1.mp3"}) or null if unset. */
    public static String getAudioFileName(ItemStack recordingMedium) {
        if (recordingMedium == null || recordingMedium.isEmpty()) return null;
        return recordingMedium.get(ModDataComponents.AUDIO_FILE_NAME.get());
    }

    /** @return audio format string ({@code "mp3"}, {@code "ogg"}, or {@code "wav"}) or {@code "ogg"} default. */
    public static String getAudioFormat(ItemStack recordingMedium) {
        if (recordingMedium == null || recordingMedium.isEmpty()) return "ogg";
        return recordingMedium.getOrDefault(ModDataComponents.AUDIO_FORMAT.get(), "ogg");
    }

    /** @return audio storage UUID for tracking, or null if not stored. */
    public static UUID getAudioId(ItemStack recordingMedium) {
        if (recordingMedium == null || recordingMedium.isEmpty()) return null;
        return recordingMedium.get(ModDataComponents.AUDIO_ID.get());
    }

    // ========================== Range board queries ==========================

    /** @return true if both range corners are set on the range board. */
    public static boolean hasRange(ItemStack rangeBoard) {
        return RangeBoardItem.hasRange(rangeBoard);
    }

    /** @return first range corner (low/high arbitrary) or null. */
    public static BlockPos getRangePos1(ItemStack rangeBoard) {
        if (rangeBoard == null || rangeBoard.isEmpty()) return null;
        return rangeBoard.get(ModDataComponents.RANGE_POS1.get());
    }

    /** @return second range corner or null. */
    public static BlockPos getRangePos2(ItemStack rangeBoard) {
        if (rangeBoard == null || rangeBoard.isEmpty()) return null;
        return rangeBoard.get(ModDataComponents.RANGE_POS2.get());
    }

    /**
     * @return per-direction attenuation distances {@code [E, W, U, D, S, N]}.
     *         Defaults to 8 blocks per direction if not configured.
     */
    public static int[] getAttenuationRanges(ItemStack rangeBoard) {
        return ModDataComponents.getAttenuationRangesArray(rangeBoard);
    }

    // ========================== Audio data ==========================

    /** Load raw audio bytes from a UUID stored in {@link AudioStorage}. */
    public static byte[] loadAudio(MinecraftServer server, UUID audioId) {
        if (server == null || audioId == null) return null;
        return AudioStorage.load(server, audioId);
    }

    /**
     * Load audio bytes from a recording medium item, including legacy fallback.
     * @return audio bytes or null if the medium has no audio.
     */
    public static byte[] loadAudioFromMedium(MinecraftServer server, ItemStack recordingMedium) {
        if (server == null || !isRecordingMedium(recordingMedium)) return null;
        return AudioStorage.loadForItem(server, recordingMedium);
    }

    // ========================== Playback (server-side) ==========================

    /**
     * Play audio at the given position. Sends metadata + chunked audio to all
     * online players; client-side playback respects range/attenuation.
     *
     * <p>Use a unique {@code pos} per concurrent playback (it is the key for
     * later {@link #stopAudio} calls and for end-of-playback events).
     *
     * @param level             server level (audio is dispatched to all players, level is informational)
     * @param pos               source position used as playback handle
     * @param recordingMedium   item stack with audio data (must satisfy {@link #hasAudio})
     * @param rangeBoard        optional range board for spatial attenuation (may be {@link ItemStack#EMPTY})
     * @param attenuationMode   true: directional attenuation, false: spherical 16-block fade
     * @return true if playback was started; false if data could not be loaded
     */
    public static boolean playAudio(ServerLevel level, BlockPos pos,
                                     ItemStack recordingMedium,
                                     ItemStack rangeBoard,
                                     boolean attenuationMode) {
        if (level == null || pos == null) return false;
        if (!hasAudio(recordingMedium)) return false;

        MinecraftServer server = level.getServer();
        if (server == null) return false;
        byte[] audioData = AudioStorage.loadForItem(server, recordingMedium);
        if (audioData == null) return false;
        String format = getAudioFormat(recordingMedium);

        BlockPos rangePos1 = null, rangePos2 = null;
        if (hasRange(rangeBoard)) {
            rangePos1 = getRangePos1(rangeBoard);
            rangePos2 = getRangePos2(rangeBoard);
        }
        int[] attRanges = getAttenuationRanges(rangeBoard);

        ClientPlayAudioPayload meta = new ClientPlayAudioPayload(
                pos, audioData.length, format, rangePos1, rangePos2, attenuationMode, attRanges);

        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(sp, meta);
            ClientAudioChunkPayload.sendChunked(sp, pos, audioData);
        }
        SpatialAudioSystem.LOGGER.debug("[SasApi] playAudio pos={} size={}B format={}",
                pos, audioData.length, format);
        return true;
    }

    /** Stop a playback at the given position. Tells all clients to stop. */
    public static void stopAudio(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return;
        MinecraftServer server = level.getServer();
        if (server == null) return;
        ClientStopAudioPayload payload = new ClientStopAudioPayload(pos);
        for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
            PacketDistributor.sendToPlayer(sp, payload);
        }
    }
}
