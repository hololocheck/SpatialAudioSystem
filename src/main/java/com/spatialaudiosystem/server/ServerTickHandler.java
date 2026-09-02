package com.spatialaudiosystem.server;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.AudioStorage;
import com.spatialaudiosystem.audio.PlaybackDelivery;
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

    /** How often active sounds are offered to players who can hear them but do not have them. */
    private static final int DELIVERY_SWEEP_TICKS = 20;     // 1 second
    private static int sweepCounter = 0;

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // Abandoned transfers hold up to 10 MB each, so they expire on the clock rather
        // than waiting for the same player to start another upload.
        AudioUploadChunkPayload.sweepExpired();

        // Delivery runs on a timer rather than from join / dimension-change events, because the
        // set of players who can hear a sound also changes when nobody fires an event at all —
        // walking into range is the case three separate handlers would have missed.
        if (++sweepCounter >= DELIVERY_SWEEP_TICKS) {
            sweepCounter = 0;
            PlaybackDelivery.sweep(event.getServer());
        }

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
        PlaybackSessionRegistry.forgetPlayer(event.getEntity().getUUID());
    }

    // The events below can leave a client holding no sounds while the server still believes it
    // was sent them. The client stops and forgets every playback when its level is replaced
    // (ClientLifecycleHandler, on LoggingOut and LevelEvent.Unload); without dropping the
    // delivery record the sweep would consider the player already served, and they would never
    // hear a sound that is still playing.
    //
    // Respawn is included, and cannot be left to PlayerChangedDimensionEvent: PlayerList.respawn
    // fires only firePlayerRespawnEvent, and the single call site of firePlayerChangedDimensionEvent
    // is ServerPlayer.changeDimension, which a death respawn never enters. So a player who
    // respawns into another dimension has their client level replaced — and their sounds
    // stopped — with no dimension-change event to notice it.
    //
    // A same-dimension respawn does not replace the client level, so forgetting there is
    // unnecessary. It is harmless anyway, but no longer for the reason this comment used to
    // give. That reason was "the only replayable sounds are endless ones", and since 2026-08-30
    // one-shots are replayable too — a player who joins or walks in mid-sound is started where
    // it has got to. Re-sending a sound to a client that still has it makes that client cancel
    // its previous worker, and a cancelled worker used to report completion under the unchanged
    // playback id, which ended the sound for everyone. What makes it harmless now is
    // AudioManager's `superseded` flag: a session replaced by the same playback id does not
    // report. Delete that flag and this becomes a live defect again.

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        PlaybackSessionRegistry.forgetPlayer(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        PlaybackSessionRegistry.forgetPlayer(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        PlaybackSessionRegistry.forgetPlayer(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        // Playback ids live for one run. In single player the JVM outlives the world, so
        // without this the next world starts holding ids for sounds that no longer exist.
        PlaybackSessionRegistry.clear();
        AudioUploadChunkPayload.clearUploads();
    }
}
