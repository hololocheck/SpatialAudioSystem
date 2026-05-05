package com.spatialaudiosystem.client;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.AudioManager;
import com.spatialaudiosystem.network.PlaybackControlPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Reliable client-side tick handler for audio gain updates and playback notifications.
 * ClientTickEvent fires every tick (20/sec) regardless of screen state,
 * unlike RenderGuiEvent which may not fire in all configurations.
 */
@EventBusSubscriber(modid = SpatialAudioSystem.MOD_ID, value = Dist.CLIENT)
public class ClientTickHandler {

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Update player position cache for audio gain calculation
        AudioManager.updateLocalPlayerPos(mc.player.getX(), mc.player.getY(), mc.player.getZ());

        // Update gain for all active playbacks and collect finished ones
        for (BlockPos finishedPos : AudioManager.tickGain()) {
            // Notify server that playback ended so it updates isPlaying status
            PacketDistributor.sendToServer(new PlaybackControlPayload(finishedPos, false));
        }
    }
}
