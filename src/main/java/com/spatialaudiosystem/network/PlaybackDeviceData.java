package com.spatialaudiosystem.network;

import com.manta.api.data.Host;
import com.manta.api.data.MantaData;
import com.manta.api.data.Schema;
import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.PlaybackScheduler;
import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import com.spatialaudiosystem.handy.SoundDeviceLink;
import com.spatialaudiosystem.handy.SoundDeviceRegistry;
import com.spatialaudiosystem.server.ServerInteractionGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The playback device's actions on manta:data (MANTA_7_CONCEPT C4): what PlaybackControlPayload,
 * ToggleAttenuationPayload, ToggleRangeDisplayPayload, SetAttenuationRangePayload, PlaylistCommandPayload,
 * RedstoneRuleCommandPayload and the device screen's SetDeviceNamePayload carried, declared once in the device
 * layout's {@code state} member. The screen sends them through a {@code Mirror}; the block entity's
 * {@link Host} admits exactly the players {@link ServerInteractionGuard} admitted - the sender has this
 * device's menu open, in its level, still valid, with the owner's access - asked again on every action.
 *
 * <p>The params are bounded by the schema before a handler runs, with the bounds the payloads' decoders had
 * (SAS-NET-005: an entry index past the playlist once reached the scheduler and threw).</p>
 */
public final class PlaybackDeviceData {

    /** The layout that declares the actions, read from the jar on both sides (the same bytes, the same hash). */
    static final String LAYOUT = "/assets/spatialaudiosystem/layouts/playback-device.json";

    public static final int PLAYLIST_PLAY_ALL = 0;
    public static final int PLAYLIST_STOP = 1;
    public static final int PLAYLIST_TEST = 2;              // a1 = entry index
    public static final int PLAYLIST_ADJUST_PLAYCOUNT = 3;  // a1 = entry index, a2 = delta
    public static final int PLAYLIST_ADD_ENTRY = 4;         // append a new empty entry
    public static final int PLAYLIST_REMOVE_ENTRY = 5;      // a1 = entry index (media returned to player)
    public static final int PLAYLIST_REORDER = 6;           // a1 = from, a2 = to
    public static final int PLAYLIST_TOGGLE_MODE = 7;       // flip schedule mode (bars/frees the media slot)
    public static final int PLAYLIST_TOGGLE_LOOP = 8;       // flip endless play for the single medium

    public static final int RULE_TOGGLE_ENABLED = 0;
    public static final int RULE_ADD = 1;
    public static final int RULE_REMOVE = 2;            // index
    public static final int RULE_CYCLE_TRIGGER = 3;     // index, delta
    public static final int RULE_ADJUST_STRENGTH = 4;   // index, delta
    public static final int RULE_ADJUST_DELAY = 5;      // index, delta (one notch = DELAY_STEP_TICKS)
    public static final int RULE_ADJUST_LENGTH = 6;     // index, delta (one notch = LENGTH_STEP_TICKS)
    public static final int RULE_ADJUST_ENTRY = 7;      // index, delta (entry scope, wraps)
    public static final int RULE_MOVE = 8;              // index, delta (swap with the neighbour)

    private static Schema schema;

    private PlaybackDeviceData() {
    }

    public static synchronized Schema schema() {
        if (schema == null) {
            schema = MantaData.schemaResource(SpatialAudioSystem.class, LAYOUT);
        }
        return schema;
    }

    public static String channel(Level level, BlockPos pos) {
        return MantaData.channel(SpatialAudioSystem.MOD_ID, "playback", level.dimension(), pos);
    }

    /** The device's host, on the server thread. Closed by the block entity with itself. */
    public static Host open(PlaybackDeviceBlockEntity be, ServerLevel level) {
        BlockPos pos = be.getBlockPos();
        Host host = MantaData.host(level.getServer(), channel(level, pos), schema(),
                player -> ServerInteractionGuard.playbackDevice(player, pos) == be);
        host.on("playback", (args, player) -> {
            // The device owns starting and stopping (its own start sequence sets the start timestamp).
            if ((Boolean) args.get(0)) {
                be.startPlayback();
            } else {
                be.stopPlayback();
            }
        });
        host.on("toggle-attenuation", (args, player) -> be.setAttenuationMode(!be.isAttenuationMode()));
        host.on("toggle-range-display", (args, player) -> be.setShowRange(!be.isShowRange()));
        host.on("set-attenuation-range", (args, player) -> be.setAttenuationRange((Integer) args.get(0)));
        host.on("playlist", (args, player) -> playlist(be, level, player,
                (Integer) args.get(0), (Integer) args.get(1), (Integer) args.get(2)));
        host.on("redstone-rule", (args, player) -> redstone(be,
                (Integer) args.get(0), (Integer) args.get(1), (Integer) args.get(2)));
        host.on("rename", (args, player) -> rename(be, level, player, (String) args.get(0)));
        return host;
    }

    private static void playlist(PlaybackDeviceBlockEntity be, ServerLevel level, ServerPlayer player,
                                 int op, int a1, int a2) {
        BlockPos pos = be.getBlockPos();
        switch (op) {
            case PLAYLIST_PLAY_ALL -> PlaybackScheduler.playAll(level, pos);
            case PLAYLIST_STOP -> PlaybackScheduler.stop(level, pos);
            case PLAYLIST_TEST -> PlaybackScheduler.testEntry(level, pos, a1);
            case PLAYLIST_ADJUST_PLAYCOUNT -> be.setPlayCount(a1, be.getPlayCount(a1) + a2);
            case PLAYLIST_ADD_ENTRY -> be.addEntry();
            case PLAYLIST_REMOVE_ENTRY -> {
                ItemStack media = be.removeEntry(a1);
                if (!media.isEmpty() && !player.getInventory().add(media)) {
                    player.drop(media, false);
                }
            }
            case PLAYLIST_REORDER -> be.swapEntries(a1, a2);
            case PLAYLIST_TOGGLE_MODE -> be.toggleScheduleMode(media -> {
                if (!player.getInventory().add(media)) player.drop(media, false);
            });
            case PLAYLIST_TOGGLE_LOOP -> be.toggleNormalLoop();
            default -> { }
        }
    }

    private static void redstone(PlaybackDeviceBlockEntity be, int op, int index, int delta) {
        switch (op) {
            case RULE_TOGGLE_ENABLED -> be.toggleRedstoneEnabled();
            case RULE_ADD -> be.addRedstoneRule();
            case RULE_REMOVE -> be.removeRedstoneRule(index);
            case RULE_CYCLE_TRIGGER -> be.cycleRedstoneTrigger(index, delta);
            case RULE_ADJUST_STRENGTH -> be.adjustRedstoneStrength(index, delta);
            case RULE_ADJUST_DELAY -> be.adjustRedstoneDelay(index, delta);
            case RULE_ADJUST_LENGTH -> be.adjustRedstoneLength(index, delta);
            case RULE_ADJUST_ENTRY -> be.adjustRedstoneEntry(index, delta);
            case RULE_MOVE -> be.moveRedstoneRule(index, delta);
            default -> { }
        }
    }

    /** The device named from its own screen: only its owner names it, sanitised by the registry's rule. */
    private static void rename(PlaybackDeviceBlockEntity be, ServerLevel level, ServerPlayer player, String name) {
        GlobalPos pos = GlobalPos.of(level.dimension(), be.getBlockPos());
        if (SoundDeviceLink.ownedDevice(player.server, player.getUUID(), pos) == be) {
            be.setDeviceName(SoundDeviceRegistry.sanitizeName(name));
        }
    }
}
