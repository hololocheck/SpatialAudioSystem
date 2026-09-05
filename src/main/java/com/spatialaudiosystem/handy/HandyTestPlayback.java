package com.spatialaudiosystem.handy;

import com.spatialaudiosystem.audio.AudioStorage;
import com.spatialaudiosystem.audio.PlaybackSessionRegistry;
import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.network.ClientAudioChunkPayload;
import com.spatialaudiosystem.network.ClientPlayAudioPayload;
import com.spatialaudiosystem.network.ClientStopAudioPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The handy's "test" button (spec §2.4): the targeted device's medium, played for its owner
 * only, where the owner stands — the recording screen's preview route
 * ({@code TestPlayRecordingPayload}) sent to one player instead of the level.
 *
 * <p>The session is keyed on the player's block position at the start, never on the device,
 * so a test cannot take over the device's own world playback session at its position. One
 * test per player: starting another stops the first. The map is process-local on purpose —
 * a test is a check on the medium, not a sound placed in the world, and does not survive a
 * restart.
 */
public final class HandyTestPlayback {
    private HandyTestPlayback() {}

    private record Session(ResourceKey<Level> dimension, BlockPos pos, long playbackId) {}

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final int[] NO_ATTENUATION = {8, 8, 8, 8, 8, 8};

    /** Starts the test; false when the device's media slot holds nothing to play. */
    public static boolean start(MinecraftServer server, ServerPlayer player, PlaybackDeviceBlockEntity device) {
        if (!(player.level() instanceof ServerLevel level)) return false;
        ItemStack medium = device.getInventory().getStackInSlot(PlaybackDeviceBlockEntity.MEDIA_SLOT);
        byte[] audio = AudioStorage.loadForItem(server, medium);
        if (audio == null) return false;
        stop(server, player);
        String format = medium.getOrDefault(ModDataComponents.AUDIO_FORMAT, "ogg");
        BlockPos pos = player.blockPosition();
        long playbackId = PlaybackSessionRegistry.begin(level, pos);
        SESSIONS.put(player.getUUID(), new Session(level.dimension(), pos, playbackId));
        // One-shot, unsynchronised and from the top, like the recording screen's preview.
        PacketDistributor.sendToPlayer(player, new ClientPlayAudioPayload(
                pos, playbackId, audio.length, format, null, null, false, NO_ATTENUATION,
                false, 0, false, 0L));
        ClientAudioChunkPayload.sendChunked(player, pos, playbackId, audio);
        return true;
    }

    /** Stops the player's running test; false when none is running. */
    public static boolean stop(MinecraftServer server, ServerPlayer player) {
        Session s = SESSIONS.remove(player.getUUID());
        if (s == null) return false;
        ServerLevel level = server.getLevel(s.dimension());
        // End the registry session only while it is still ours: a device placed later on the
        // spot the player stood on owns that key now, and its world playback must survive.
        if (level != null && PlaybackSessionRegistry.currentId(level, s.pos()) == s.playbackId()) {
            PlaybackSessionRegistry.end(level, s.pos());
        }
        PacketDistributor.sendToPlayer(player, new ClientStopAudioPayload(s.pos(), s.playbackId()));
        return true;
    }
}
