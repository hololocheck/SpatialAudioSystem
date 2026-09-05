package com.spatialaudiosystem.handy;

import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.network.HandyDeviceListPayload;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Where the block world meets the {@link SoundDeviceRegistry}: placement, the first open that
 * settles an owner, a rename, and removal each update the registry and push the owner's list
 * to their client when they are online. Server only; every entry point checks that.
 */
public final class SoundDeviceLink {
    private SoundDeviceLink() {}

    /** A device was placed by a player: the owner is known from the start. */
    public static void onPlaced(ServerLevel level, BlockPos pos, PlaybackDeviceBlockEntity be) {
        onOwnerKnown(be);
    }

    /**
     * The device's owner is settled (placement, or the first open of a pre-1.1.0 device).
     * Idempotent: registering the same device under the same owner changes nothing.
     */
    public static void onOwnerKnown(PlaybackDeviceBlockEntity be) {
        if (!(be.getLevel() instanceof ServerLevel level)) return;
        UUID owner = be.getOwnerUUID();
        if (owner == null) return;
        MinecraftServer server = level.getServer();
        GlobalPos pos = GlobalPos.of(level.dimension(), be.getBlockPos());
        if (SoundDeviceRegistry.get(server).register(owner, pos, be.getDeviceName())) {
            pushListTargeting(server, owner, pos);
        }
    }

    /** The device's name changed on the server. */
    public static void onRenamed(PlaybackDeviceBlockEntity be) {
        if (!(be.getLevel() instanceof ServerLevel level)) return;
        UUID owner = be.getOwnerUUID();
        if (owner == null) return;
        MinecraftServer server = level.getServer();
        GlobalPos pos = GlobalPos.of(level.dimension(), be.getBlockPos());
        if (SoundDeviceRegistry.get(server).rename(owner, pos, be.getDeviceName())) {
            pushList(server, owner);
        }
    }

    /** The block is gone: the link is cut and the owner's list shrinks. */
    public static void onRemoved(ServerLevel level, BlockPos pos) {
        MinecraftServer server = level.getServer();
        SoundDeviceRegistry registry = SoundDeviceRegistry.get(server);
        GlobalPos gp = GlobalPos.of(level.dimension(), pos);
        UUID owner = registry.ownerOf(gp);
        if (registry.unregister(gp) && owner != null) {
            pushList(server, owner);
        }
    }

    /** Sends the owner their current list, when they are online. */
    public static void pushList(MinecraftServer server, UUID owner) {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player == null) return;
        List<HandyDeviceListPayload.Row> rows = rows(server, owner);
        ensureTarget(player, rows, null);
        PacketDistributor.sendToPlayer(player, new HandyDeviceListPayload(rows));
    }

    /**
     * A handy with no target adopts one, so a device is usable the moment it is placed instead
     * of only after opening the screen or walking the wheel (user's real-device note 2026-09-05).
     *
     * <p>{@code preferred} is the device that prompted this - the one just placed - and wins when
     * it is in the list; otherwise the first row does. A handy that already points somewhere is
     * left alone: the player's own choice outranks a convenience.
     */
    private static void ensureTarget(ServerPlayer player, List<HandyDeviceListPayload.Row> rows,
                                     @Nullable GlobalPos preferred) {
        ItemStack handy = com.spatialaudiosystem.item.SoundHandyItem.held(player);
        if (handy.isEmpty() || rows.isEmpty()) return;
        GlobalPos current = handy.get(ModDataComponents.HANDY_SELECTED_DEVICE);
        boolean stillMine = current != null && rows.stream().anyMatch(r -> r.pos().equals(current));
        if (stillMine) return;
        GlobalPos next = preferred != null && rows.stream().anyMatch(r -> r.pos().equals(preferred))
                ? preferred : rows.get(0).pos();
        handy.set(ModDataComponents.HANDY_SELECTED_DEVICE, next);
    }

    /** The list push after a device the owner just placed, which becomes their target. */
    private static void pushListTargeting(MinecraftServer server, UUID owner, GlobalPos placed) {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player == null) return;
        List<HandyDeviceListPayload.Row> rows = rows(server, owner);
        ensureTarget(player, rows, placed);
        PacketDistributor.sendToPlayer(player, new HandyDeviceListPayload(rows));
    }

    /** The owner's devices with what the server knows right now about each. */
    public static List<HandyDeviceListPayload.Row> rows(MinecraftServer server, UUID owner) {
        List<SoundDeviceRegistry.Entry> entries = SoundDeviceRegistry.get(server).devicesOf(owner);
        List<HandyDeviceListPayload.Row> out = new ArrayList<>(entries.size());
        for (SoundDeviceRegistry.Entry e : entries) {
            boolean loaded = false, playing = false, hasMedium = false, hasBoard = false;
            String mediumFile = "", mediumFormat = "";
            ServerLevel level = server.getLevel(e.pos().dimension());
            if (level != null && level.isLoaded(e.pos().pos())) {
                BlockEntity entity = level.getBlockEntity(e.pos().pos());
                if (entity instanceof PlaybackDeviceBlockEntity be) {
                    loaded = true;
                    playing = be.isPlaying();
                    hasMedium = canPlay(be);
                    hasBoard = hasBoard(be);
                    ItemStack medium = mediumOf(be);
                    mediumFile = medium.getOrDefault(ModDataComponents.AUDIO_FILE_NAME, "");
                    mediumFormat = medium.getOrDefault(ModDataComponents.AUDIO_FORMAT, "");
                }
            }
            out.add(new HandyDeviceListPayload.Row(e.pos(), e.name() == null ? "" : e.name(), loaded, playing, hasMedium, hasBoard,
                    mediumFile, mediumFormat));
        }
        return out;
    }

    /**
     * The medium the handy would start, for the row's file name and format: the single slot's;
     * in schedule mode the entry that is sounding, else the first entry with audio. Empty when
     * the device holds nothing playable (the same answer as {@link #canPlay}, as a stack).
     */
    public static ItemStack mediumOf(PlaybackDeviceBlockEntity be) {
        if (be.isScheduleMode()) {
            int sounding = be.getPlayingEntry();
            if (sounding >= 0 && sounding < be.getEntryCount()) {
                ItemStack s = be.getPlaylist().getStackInSlot(sounding);
                if (com.spatialaudiosystem.item.RecordingMediumItem.hasAudioData(s)) return s;
            }
            for (int i = 0; i < be.getEntryCount(); i++) {
                ItemStack s = be.getPlaylist().getStackInSlot(i);
                if (com.spatialaudiosystem.item.RecordingMediumItem.hasAudioData(s)) return s;
            }
            return ItemStack.EMPTY;
        }
        ItemStack s = be.getInventory().getStackInSlot(PlaybackDeviceBlockEntity.MEDIA_SLOT);
        return com.spatialaudiosystem.item.RecordingMediumItem.hasAudioData(s) ? s : ItemStack.EMPTY;
    }

    /**
     * What the handy's row shows for a loaded device, as one string. The device's tick compares
     * it with the last one it saw ({@link RowChangeDetector}) and re-sends the owner's list on a
     * change: a sound that ended on its own, a medium taken out or put in, a board slotted -
     * none of them is a handy action, so nothing else would push (real-device note 2026-09-05:
     * the mini HUD stayed "playing" after the sound had ended, until the handy was re-held).
     */
    public static String rowSignature(PlaybackDeviceBlockEntity be) {
        ItemStack medium = mediumOf(be);
        // The playable flag is its own letter: a medium whose file name is empty would
        // otherwise read the same as no medium (review 2026-09-05).
        return (be.isPlaying() ? "P" : "p") + (canPlay(be) ? "M" : "m") + (hasBoard(be) ? "B" : "b") + "|"
                + medium.getOrDefault(ModDataComponents.AUDIO_FILE_NAME, "") + "|"
                + medium.getOrDefault(ModDataComponents.AUDIO_FORMAT, "");
    }

    /** A loaded device's visible state changed (or it just loaded): the owner's list is re-sent. */
    public static void onStateChanged(PlaybackDeviceBlockEntity be) {
        if (!(be.getLevel() instanceof ServerLevel level)) return;
        UUID owner = be.getOwnerUUID();
        if (owner == null) return;
        pushList(level.getServer(), owner);
    }

    /** Whether the device holds something the handy can start: a medium with audio, or a schedule with a track. */
    public static boolean canPlay(PlaybackDeviceBlockEntity be) {
        if (be.isScheduleMode()) {
            for (int i = 0; i < be.getEntryCount(); i++) {
                if (com.spatialaudiosystem.item.RecordingMediumItem.hasAudioData(be.getPlaylist().getStackInSlot(i))) return true;
            }
            return false;
        }
        return com.spatialaudiosystem.item.RecordingMediumItem.hasAudioData(
                be.getInventory().getStackInSlot(PlaybackDeviceBlockEntity.MEDIA_SLOT));
    }

    /** Whether the device's range slot holds a range board (the handy's range mode needs one). */
    public static boolean hasBoard(PlaybackDeviceBlockEntity be) {
        return be.getInventory().getStackInSlot(PlaybackDeviceBlockEntity.RANGE_SLOT)
                .is(com.spatialaudiosystem.item.ModItems.RANGE_BOARD.get());
    }

    /** The loaded device at {@code pos} owned by {@code owner}, or null. */
    public static PlaybackDeviceBlockEntity ownedDevice(MinecraftServer server, UUID owner, GlobalPos pos) {
        if (pos == null || !owner.equals(SoundDeviceRegistry.get(server).ownerOf(pos))) return null;
        ServerLevel level = server.getLevel(pos.dimension());
        if (level == null || !level.isLoaded(pos.pos())) return null;
        return level.getBlockEntity(pos.pos()) instanceof PlaybackDeviceBlockEntity be ? be : null;
    }

    /**
     * Whether the device's chunk has been sent to the player's client: the same dimension, and
     * the player is among the chunk's trackers. A remote open needs the client's copy of the
     * block entity (the device screen reads it, and the menu's client constructor throws
     * without it), so this is the reach of "open device". Asked of the chunk map rather than
     * the view distance: the client's own render distance can be smaller than the server's.
     */
    public static boolean chunkSentTo(MinecraftServer server, ServerPlayer player, GlobalPos target) {
        ServerLevel level = server.getLevel(target.dimension());
        if (level == null || player.level() != level) return false;
        net.minecraft.world.level.ChunkPos chunk = new net.minecraft.world.level.ChunkPos(target.pos());
        return level.getChunkSource().chunkMap.getPlayers(chunk, false).contains(player);
    }
}
