package com.spatialaudiosystem.server;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.AudioStorage;
import com.spatialaudiosystem.audio.PlaybackSessionRegistry;
import com.spatialaudiosystem.network.AudioUploadChunkPayload;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Server-side tick handler for periodic orphaned audio file cleanup.
 * Runs cleanup every 5 minutes (6000 ticks).
 */
@EventBusSubscriber(modid = SpatialAudioSystem.MOD_ID)
public class ServerTickHandler {

    private static final int CLEANUP_INTERVAL_TICKS = 6000; // 5 minutes
    private static int tickCounter = 0;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // Abandoned transfers hold up to 10 MB each, so they expire on the clock rather
        // than waiting for the same player to start another upload.
        AudioUploadChunkPayload.sweepExpired();

        tickCounter++;
        if (tickCounter < CLEANUP_INTERVAL_TICKS) return;
        tickCounter = 0;

        MinecraftServer server = event.getServer();
        try {
            AudioStorage.sweep(server);
        } catch (Exception e) {
            SpatialAudioSystem.LOGGER.error("Error during audio cleanup", e);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        AudioUploadChunkPayload.cancelUpload(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        // Playback ids live for one run. In single player the JVM outlives the world, so
        // without this the next world starts holding ids for sounds that no longer exist.
        PlaybackSessionRegistry.clear();
        AudioUploadChunkPayload.clearUploads();
    }
}
