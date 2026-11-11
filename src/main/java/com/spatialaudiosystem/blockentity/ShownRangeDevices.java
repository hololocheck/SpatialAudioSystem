package com.spatialaudiosystem.blockentity;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The playback devices a client level holds, so the range renderer finds the ones showing their range without walking
 * every block entity around the player every frame (measured 2026-11-11: 11.4 % of the render thread with 1000
 * Xenophora printers nearby and no SAS block in the world).
 *
 * <p>Client devices only: a device joins when it is given a client level ({@code setLevel}, which every path that puts a
 * block entity into a client chunk runs - the chunk packet, a placed block) and leaves when it is removed (a broken
 * block, a chunk the client dropped). Server devices never join, so the integrated server's thread never touches this set
 * (project CLAUDE.md 5.1). Entries are held weakly, so a client level that is thrown away (a dimension change, a
 * disconnect) without removing its block entities cannot keep them alive, and {@link #chunksShowing} drops those of any
 * level but the one asked about.
 *
 * <p>The answer only picks the chunks the renderer walks; which devices it draws, and in what order, is still decided by
 * the renderer's own walk over those chunks' block entities.
 */
public final class ShownRangeDevices {

    private static final Set<PlaybackDeviceBlockEntity> CLIENT =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private ShownRangeDevices() {}

    static void track(PlaybackDeviceBlockEntity device) {
        CLIENT.add(device);
    }

    static void untrack(PlaybackDeviceBlockEntity device) {
        CLIENT.remove(device);
    }

    /**
     * The chunks ({@link ChunkPos#asLong}) of {@code level}'s devices that show their range, or null when none does - the
     * renderer then walks nothing.
     */
    @Nullable
    public static LongSet chunksShowing(Level level) {
        LongSet chunks = null;
        synchronized (CLIENT) {
            Iterator<PlaybackDeviceBlockEntity> it = CLIENT.iterator();
            while (it.hasNext()) {
                PlaybackDeviceBlockEntity device = it.next();
                if (device.isRemoved() || device.getLevel() != level) {
                    it.remove();
                    continue;
                }
                if (!device.isShowRange()) continue;
                BlockPos pos = device.getBlockPos();
                if (chunks == null) chunks = new LongOpenHashSet();
                chunks.add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
            }
        }
        return chunks;
    }
}
