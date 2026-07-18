package com.spatialaudiosystem.client;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.AudioManager;
import com.spatialaudiosystem.network.ClientAudioChunkPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Client-side lifecycle hooks that ensure audio playback stops cleanly when the
 * player leaves a world / disconnects from a server.
 *
 * <p>Without these hooks, {@link AudioManager} leaves background audio threads
 * (and their {@code SourceDataLine}s) running after the world unloads, causing
 * the bug where audio continues playing on the title screen.
 */
@EventBusSubscriber(modid = SpatialAudioSystem.MOD_ID, value = Dist.CLIENT)
public class ClientLifecycleHandler {

    /** Player disconnected from a server (single-player or multi-player). */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        AudioManager.getInstance().stopAll();
        ClientAudioChunkPayload.clearAllSessions();
        ClientArtCache.clear();
    }

    /** Client-side level (world) unloaded — covers dimension changes too, just in case. */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() == null || !event.getLevel().isClientSide()) return;
        AudioManager.getInstance().stopAll();
        ClientAudioChunkPayload.clearAllSessions();
        ClientArtCache.clear();
    }
}
