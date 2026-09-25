package com.spatialaudiosystem.handy;

import com.spatialaudiosystem.audio.PlaybackScheduler;
import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.SoundHandyItem;
import com.spatialaudiosystem.network.HandyData;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * What the sound handy asks the server to do: the handy host's {@code handy-action} action
 * ({@code network.HandyData}). The action and the argument are bounded by the host's schema before this runs
 * (BELUGAEXPERIENCE R3.6.1); the handler checks that the sender holds a handy and, for anything that names a
 * device, that the device is theirs and loaded. The server is the authority for the selection and the tool
 * mode: the client sets its copy for immediate feedback and the server sets the one that counts.
 */
public final class HandyActions {
    private HandyActions() {}

    public static final int REQUEST_LIST = 0;
    public static final int SELECT = 1;
    public static final int CLEAR_SELECTION = 2;
    public static final int PLAY = 3;
    public static final int STOP = 4;
    /** arg 1 hides the HUD badge for this handy, 0 shows it. */
    public static final int SET_HUD = 5;
    /** Opens the targeted device's screen from anywhere (owner only; the device must be loaded). */
    public static final int OPEN = 6;
    // 7 was SHARE_RANGE ("copy this device's range to every device"), removed in 1.1.0 before
    // release (user decision 2026-09-05); the number stays unused so an old client's 7 is ignored.
    /** Plays the targeted device's medium for the owner only, where they stand (spec §2.4 "test"). */
    public static final int TEST = 8;
    /** Stops the owner's running test. */
    public static final int STOP_TEST = 9;
    /** The middle button: stops the target when it plays, starts it otherwise ("cannot play" without a medium). */
    public static final int TOGGLE_PLAY = 10;
    /** arg 1 turns the range mode (Shift+R) on for this handy, 0 off. */
    public static final int TOGGLE_RANGE = 11;
    /** arg 1 turns the target highlight (Shift+H, the block outlined through terrain) on, 0 off. */
    public static final int TOGGLE_HIGHLIGHT = 12;
    /** The last action the host accepts; the handler ignores what it does not know. */
    public static final int MAX_ACTION = 16;
    public static final int MAX_ARG = 1000;

    /** One action from the player's handy host, on the server thread. {@code pos} is null when none was named. */
    public static void handle(ServerPlayer player, int action, int arg, @Nullable GlobalPos pos) {
        ItemStack handy = SoundHandyItem.held(player);
        if (handy.isEmpty()) return;
        var server = player.server;
        switch (action) {
            case REQUEST_LIST -> SoundDeviceLink.pushList(server, player.getUUID());
            case SELECT -> {
                if (pos == null) return;
                if (!player.getUUID().equals(SoundDeviceRegistry.get(server).ownerOf(pos))) {
                    HandyData.notify(player, "message.spatialaudiosystem.sound_handy.not_owner", 0xFF5555);
                    return;
                }
                handy.set(ModDataComponents.HANDY_SELECTED_DEVICE, pos);
            }
            case CLEAR_SELECTION -> handy.remove(ModDataComponents.HANDY_SELECTED_DEVICE);
            case TOGGLE_RANGE -> {
                if (arg != 0) handy.set(ModDataComponents.HANDY_RANGE_MODE, true);
                else handy.remove(ModDataComponents.HANDY_RANGE_MODE);
            }
            case TOGGLE_HIGHLIGHT -> {
                if (arg != 0) handy.set(ModDataComponents.HANDY_HIGHLIGHT, true);
                else handy.remove(ModDataComponents.HANDY_HIGHLIGHT);
            }
            case SET_HUD -> {
                if (arg != 0) handy.set(ModDataComponents.HANDY_HUD_HIDDEN, true);
                else handy.remove(ModDataComponents.HANDY_HUD_HIDDEN);
            }
            case STOP_TEST -> {
                if (HandyTestPlayback.stop(server, player)) {
                    HandyData.notify(player, "message.spatialaudiosystem.sound_handy.test_stopped", 0xFFAAAA);
                }
            }
            case PLAY, STOP, TOGGLE_PLAY, OPEN, TEST -> {
                GlobalPos target = pos != null ? pos : handy.get(ModDataComponents.HANDY_SELECTED_DEVICE);
                if (target == null) {
                    HandyData.notify(player, "message.spatialaudiosystem.sound_handy.no_selection", 0xFFFF55);
                    return;
                }
                if (!player.getUUID().equals(SoundDeviceRegistry.get(server).ownerOf(target))) {
                    HandyData.notify(player, "message.spatialaudiosystem.sound_handy.not_owner", 0xFF5555);
                    return;
                }
                PlaybackDeviceBlockEntity be = SoundDeviceLink.ownedDevice(server, player.getUUID(), target);
                if (be == null) {
                    HandyData.notify(player, "message.spatialaudiosystem.sound_handy.not_loaded", 0xFFFF55);
                    return;
                }
                switch (action) {
                    case PLAY -> start(server, player, be, target);
                    case STOP -> stop(player, be);
                    case TOGGLE_PLAY -> {
                        if (be.isPlaying()) stop(player, be);
                        else start(server, player, be, target);
                    }
                    case OPEN -> {
                        // The device screen reads the client's copy of the block entity, so
                        // the chunk must be one this client was sent (the screen checks its
                        // own level first; this is the server's side of the same rule).
                        if (!SoundDeviceLink.chunkSentTo(server, player, target)) {
                            HandyData.notify(player, "message.spatialaudiosystem.sound_handy.too_far", 0xFFFF55);
                            return;
                        }
                        player.openMenu(new net.minecraft.world.SimpleMenuProvider(
                                (id, inv, p) -> new com.spatialaudiosystem.menu.PlaybackDeviceMenu(id, inv, be, true),
                                be.getDisplayName()), target.pos());
                    }
                    case TEST -> {
                        if (!HandyTestPlayback.start(server, player, be)) {
                            HandyData.notify(player, "message.spatialaudiosystem.sound_handy.no_medium", 0xFFFF55);
                            return;
                        }
                        HandyData.notify(player, "message.spatialaudiosystem.sound_handy.test_started", 0x55FF55);
                    }
                    default -> { }
                }
                SoundDeviceLink.pushList(server, player.getUUID());
            }
            default -> { }
        }
    }

    /** Starts the target (the whole schedule in schedule mode); "cannot play" when it holds nothing playable. */
    private static void start(MinecraftServer server, ServerPlayer player, PlaybackDeviceBlockEntity be, GlobalPos target) {
        if (!SoundDeviceLink.canPlay(be)) {
            HandyData.notify(player, "message.spatialaudiosystem.sound_handy.cannot_play", 0xFFFF55);
            return;
        }
        ServerLevel level = server.getLevel(target.dimension());
        if (be.isScheduleMode() && level != null) {
            PlaybackScheduler.playAll(level, target.pos());
        } else {
            be.startPlayback();
        }
        HandyData.notify(player, "message.spatialaudiosystem.sound_handy.played", 0x55FF55);
    }

    private static void stop(ServerPlayer player, PlaybackDeviceBlockEntity be) {
        be.stopPlayback();
        HandyData.notify(player, "message.spatialaudiosystem.sound_handy.stopped", 0xFFAAAA);
    }
}
