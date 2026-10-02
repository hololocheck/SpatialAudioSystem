package com.spatialaudiosystem.client;

import com.spatialaudiosystem.network.AudioCacheAnswerPayload;
import com.spatialaudiosystem.network.ClientAudioChunkPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Where the play payload meets the cache (notes/CLIENT_AUDIO_CACHE.md §2).
 *
 * <p>Reading and writing a kept sound happen on one worker thread: never on the network thread,
 * which must answer within a round trip, and never on the main thread, which would stutter for a
 * ten-megabyte read and hash. One thread, so a read never meets its own file half-written.
 */
public final class ClientAudioCacheHook {

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "SAS-AudioCache");
        thread.setDaemon(true);
        return thread;
    });

    private ClientAudioCacheHook() {}

    /** Whether the announced bytes are kept here. Called on the network thread; reads no bytes. */
    public static boolean has(byte[] hash, int size) {
        return AudioCache.get().has(hash, size);
    }

    /**
     * Hands the kept bytes to the transfer that is waiting for them. The caller has already queued
     * that transfer's session on the main thread, and this hands over through the same queue, so
     * the bytes cannot arrive before it. A file that fails its check is a late request instead:
     * the server keeps the offer open for exactly that.
     */
    public static void fill(BlockPos pos, long playbackId, byte[] hash) {
        WORKER.execute(() -> {
            byte[] bytes = AudioCache.get().read(hash);
            if (bytes == null) {
                try {
                    PacketDistributor.sendToServer(new AudioCacheAnswerPayload(pos, playbackId, false));
                } catch (RuntimeException disconnected) {
                    // Left the server while the file was being read: there is no one to ask, and no sound to play.
                    com.spatialaudiosystem.SpatialAudioSystem.LOGGER.debug(
                            "Could not ask for audio {} after the cache missed it", pos, disconnected);
                }
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (mc.level != null) ClientAudioChunkPayload.completeFromCache(pos, playbackId, bytes, mc.level);
            });
        });
    }

    /** Keeps a sound that arrived by transfer, so the next announcement of it needs no transfer. */
    public static void keep(byte[] hash, byte[] audio) {
        WORKER.execute(() -> AudioCache.get().store(hash, audio));
    }
}
