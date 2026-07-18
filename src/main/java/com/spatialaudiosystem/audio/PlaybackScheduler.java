package com.spatialaudiosystem.audio;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SAS-native sequential playlist scheduler for the Playback Device. Backported from TSU's
 * AnnouncementScheduler without the train-condition / detection-card triggering: a sequence is
 * started manually (the "continuous play" button) and plays the device's playlist entries in
 * order, each {@code playCount} times, advancing on {@link belugalab.sas.api.PlaybackEndedEvent}.
 */
public final class PlaybackScheduler {

    private PlaybackScheduler() {}

    private static final class Seq {
        final BlockPos pos;
        final ResourceKey<Level> dim;
        int entryIdx;
        int iteration;
        boolean playing;
        long fireAtTick = -1;   // deferred next play (1 tick) so the finished sound's stop lands first
        boolean manualTest;
        Seq(BlockPos pos, ResourceKey<Level> dim) { this.pos = pos; this.dim = dim; }
    }

    private static final Map<GlobalPos, Seq> sequences = new ConcurrentHashMap<>();

    private static GlobalPos key(ResourceKey<Level> dim, BlockPos pos) {
        return GlobalPos.of(dim, pos.immutable());
    }

    /** Continuous play: run the playlist from the first entry. */
    public static void playAll(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof PlaybackDeviceBlockEntity)) return;
        Seq s = new Seq(pos, level.dimension());
        s.entryIdx = 0;
        sequences.put(key(level.dimension(), pos), s);
        advance(level, s);
    }

    /** Preview a single playlist entry (no sequence advance). */
    public static void testEntry(ServerLevel level, BlockPos pos, int idx) {
        if (!(level.getBlockEntity(pos) instanceof PlaybackDeviceBlockEntity be)) return;
        if (be.playMedia(be.getPlaylist().getStackInSlot(idx))) {
            Seq s = new Seq(pos, level.dimension());
            s.entryIdx = idx;
            s.playing = true;
            s.manualTest = true;
            sequences.put(key(level.dimension(), pos), s);
            be.setPlayingEntry(idx);
        }
    }

    /** Stop the sequence and the audio. */
    public static void stop(ServerLevel level, BlockPos pos) {
        sequences.remove(key(level.dimension(), pos));
        if (level.getBlockEntity(pos) instanceof PlaybackDeviceBlockEntity be) {
            be.setPlayingEntry(-1);
            be.stopPlayback();
        }
    }

    /** Skip forward to the next non-empty entry and play it. */
    private static void advance(ServerLevel level, Seq s) {
        if (!(level.getBlockEntity(s.pos) instanceof PlaybackDeviceBlockEntity be)) {
            sequences.remove(key(s.dim, s.pos));
            return;
        }
        while (s.entryIdx < be.getEntryCount()) {
            if (!be.getPlaylist().getStackInSlot(s.entryIdx).isEmpty()) {
                s.iteration = 0;
                fire(level, s, be);
                return;
            }
            s.entryIdx++;
        }
        sequences.remove(key(s.dim, s.pos));   // reached the end
        be.setPlayingEntry(-1);
    }

    private static void fire(ServerLevel level, Seq s, PlaybackDeviceBlockEntity be) {
        ItemStack media = be.getPlaylist().getStackInSlot(s.entryIdx);
        if (be.playMedia(media)) {
            s.playing = true;
            if (be.getPlayingEntry() != s.entryIdx) be.setPlayingEntry(s.entryIdx);
        } else {
            s.entryIdx++;
            advance(level, s);
        }
    }

    public static void onPlaybackEnded(belugalab.sas.api.PlaybackEndedEvent event) {
        BlockPos pos = event.getPos();
        ServerLevel level = event.getLevel();
        if (pos == null || level == null) return;
        Seq s = sequences.get(key(level.dimension(), pos));
        if (s == null || !s.playing) return;
        s.playing = false;
        if (s.manualTest) {
            sequences.remove(key(level.dimension(), pos));
            if (level.getBlockEntity(pos) instanceof PlaybackDeviceBlockEntity be) be.setPlayingEntry(-1);
            return;
        }
        s.fireAtTick = level.getGameTime() + 1;
    }

    private static void serverTick(MinecraftServer server) {
        if (sequences.isEmpty()) return;
        for (Seq s : sequences.values()) {
            if (s.playing || s.fireAtTick < 0) continue;
            ServerLevel level = server.getLevel(s.dim);
            if (level == null) continue;
            if (level.getGameTime() < s.fireAtTick) continue;
            s.fireAtTick = -1;
            if (!(level.getBlockEntity(s.pos) instanceof PlaybackDeviceBlockEntity be)) {
                sequences.remove(key(s.dim, s.pos));
                continue;
            }
            s.iteration++;
            if (s.iteration < be.getPlayCount(s.entryIdx)) {
                fire(level, s, be);          // repeat the same entry
            } else {
                s.entryIdx++;
                advance(level, s);           // move to the next entry
            }
        }
    }

    @EventBusSubscriber(modid = SpatialAudioSystem.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
    public static final class Hooks {
        private static volatile MinecraftServer server;
        private static boolean listenerAdded = false;

        @SubscribeEvent
        public static void onServerStarted(ServerStartedEvent e) {
            server = e.getServer();
            if (!listenerAdded) {
                NeoForge.EVENT_BUS.addListener(PlaybackScheduler::onPlaybackEnded);
                listenerAdded = true;
            }
        }

        @SubscribeEvent
        public static void onServerStopping(ServerStoppingEvent e) {
            server = null;
            sequences.clear();
        }

        @SubscribeEvent
        public static void onServerTick(ServerTickEvent.Post e) {
            MinecraftServer s = server;
            if (s != null) serverTick(s);
        }
    }
}
