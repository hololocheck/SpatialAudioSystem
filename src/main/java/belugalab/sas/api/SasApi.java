package belugalab.sas.api;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.AudioStorage;
import com.spatialaudiosystem.audio.PlaybackDelivery;
import com.spatialaudiosystem.audio.PlaybackSessionRegistry;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.ModItems;
import com.spatialaudiosystem.item.RangeBoardItem;
import com.spatialaudiosystem.item.RecordingMediumItem;
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

    /**
     * @return audio duration in integer seconds, or 0 if unknown.
     *         Computed on the recording device at save time and cached as a data component.
     */
    public static int getAudioDurationSeconds(ItemStack recordingMedium) {
        if (recordingMedium == null || recordingMedium.isEmpty()) return 0;
        Integer d = recordingMedium.get(ModDataComponents.AUDIO_DURATION_SEC.get());
        return d != null ? d : 0;
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
     * @param level             server level; the sound lives in it, and only players in it hear it
     * @param pos               source position used as playback handle
     * @param recordingMedium   item stack with audio data (must satisfy {@link #hasAudio})
     * @param rangeBoard        optional range board for spatial attenuation (may be {@link ItemStack#EMPTY})
     * @param attenuationMode   true: fade over the board's face ranges -- 8 blocks per face when
     *                          the board is empty or its faces were never edited (this method
     *                          has no device, so no device range is involved); false: with a
     *                          range box, full volume inside it and silence outside; with no
     *                          box, a fixed gentle fade over 160 blocks, independent of any range
     * @return true if playback was started; false if data could not be loaded
     */
    public static boolean playAudio(ServerLevel level, BlockPos pos,
                                     ItemStack recordingMedium,
                                     ItemStack rangeBoard,
                                     boolean attenuationMode) {
        return playAudio(level, pos, recordingMedium, rangeBoard, attenuationMode, false);
    }

    /**
     * Play audio at the given position, optionally forever.
     *
     * <p>An endless sound is looped by each client on audio it already holds, so it costs one
     * transfer per listener rather than one per repetition, and it does not depend on the
     * end-of-playback report — which is sent by clients and therefore stops arriving when
     * nobody is online. This is the supported way to hold a continuous ambience; chaining
     * {@link PlaybackEndedEvent} back into {@code playAudio} is not, and stops on an empty
     * server.
     *
     * <p>Any sound still playing is delivered to a player who joins, arrives from another
     * dimension, or walks into range, endless or not — and that player is started at the point
     * the sound has reached (their own download time included), which can be ahead of listeners
     * present from the start by those listeners' own initial download. A one-shot handed a
     * position past its end plays nothing and reports itself finished,
     * which is what retires the sound rather than leaving it offerable to the next passer-by --
     * so long as somebody is there to be handed it. A sound nobody was ever in range of has no
     * client to report it and stays registered until {@link #stopAudio}.
     *
     * <p>The initial send differs between the two, and only there: an endless sound goes to the
     * players who can hear it, a one-shot to everyone in the level. Filtering an endless sound
     * is what keeps a decode thread, an open audio line and up to ten megabytes off every player
     * in the dimension for as long as the server runs. Filtering a one-shot is not possible: its
     * {@link PlaybackEndedEvent} is raised from a client's report, so a one-shot delivered to
     * nobody would never complete.
     *
     * @param loop restart at the top instead of ending. Stop it with {@link #stopAudio}.
     * @see #playAudio(ServerLevel, BlockPos, ItemStack, ItemStack, boolean)
     */
    public static boolean playAudio(ServerLevel level, BlockPos pos,
                                     ItemStack recordingMedium,
                                     ItemStack rangeBoard,
                                     boolean attenuationMode,
                                     boolean loop) {
        if (level == null || pos == null) return false;
        if (!hasAudio(recordingMedium)) return false;

        MinecraftServer server = level.getServer();
        if (server == null) return false;
        String format = getAudioFormat(recordingMedium);

        BlockPos rangePos1 = null, rangePos2 = null;
        if (hasRange(rangeBoard)) {
            rangePos1 = getRangePos1(rangeBoard);
            rangePos2 = getRangePos2(rangeBoard);
        }
        int[] attRanges = getAttenuationRanges(rangeBoard);

        // Delivery sends to this level's players and remembers the sound so a later arrival can
        // be sent it too. A sound at (x,y,z) in the Nether is not the same sound as (x,y,z) in
        // the Overworld, and shipping the audio to everyone meant a 10 MB transfer per player
        // who could never hear it.
        long playbackId = PlaybackDelivery.start(level, pos, recordingMedium, format,
                rangePos1, rangePos2, attenuationMode, attRanges, loop);
        if (playbackId == PlaybackSessionRegistry.NO_PLAYBACK) return false;

        SpatialAudioSystem.LOGGER.debug("[SasApi] playAudio pos={} format={} loop={}", pos, format, loop);
        return true;
    }

    /** Stop a playback at the given position. Tells all clients to stop. */
    public static void stopAudio(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return;
        ClientStopAudioPayload payload =
                new ClientStopAudioPayload(pos, PlaybackSessionRegistry.currentId(level, pos));
        PlaybackSessionRegistry.end(level, pos);
        for (ServerPlayer sp : level.players()) {
            PacketDistributor.sendToPlayer(sp, payload);
        }
    }
}
