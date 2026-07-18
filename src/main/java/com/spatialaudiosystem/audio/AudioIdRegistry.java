package com.spatialaudiosystem.audio;

import com.spatialaudiosystem.SpatialAudioSystem;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable set of every audio id this world has ever created.
 *
 * <p>Replaces the process-local set that orphan cleanup used to consult. That set
 * was empty after a restart, so audio held by a chest in an unloaded chunk looked
 * unreferenced and was deleted.
 *
 * <p>Ids are only ever added. Reference counting is deliberately not attempted:
 * an audio id lives in an ItemStack data component, and stacks are copied by
 * creative-mode picking, {@code /give}, and other mods without any hook firing.
 * A count built from the events we can see would run below the real number of
 * holders and delete audio that is still in use, which is the bug this class
 * exists to prevent. Cleanup therefore only reclaims files this world has no
 * record of creating; reclaiming a retired id needs an explicit operator action
 * that can show what it is about to remove.
 */
public class AudioIdRegistry extends SavedData {

    private static final String FILE_NAME = "spatialaudiosystem_audio_ids";
    private static final String TAG_IDS = "KnownIds";
    private static final String TAG_SEEDED = "SeededFromDisk";

    private final Set<UUID> known = ConcurrentHashMap.newKeySet();
    private boolean seeded = false;

    /** Every audio id known to this world (read-only view). */
    public Set<UUID> known() {
        return Collections.unmodifiableSet(known);
    }

    public void track(UUID id) {
        if (id == null) return;
        if (known.add(id)) setDirty();
    }

    /**
     * Whether the audio already in this world has been adopted.
     *
     * <p>A world that predates this registry has audio files but no record of them, and
     * a medium sitting in an unloaded chunk gives cleanup nothing to go on. Until those
     * files are adopted they look like strays.
     */
    public boolean isSeeded() {
        return seeded;
    }

    public void markSeeded() {
        if (!seeded) {
            seeded = true;
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (UUID id : known) list.add(StringTag.valueOf(id.toString()));
        tag.put(TAG_IDS, list);
        tag.putBoolean(TAG_SEEDED, seeded);
        return tag;
    }

    public static AudioIdRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        AudioIdRegistry d = new AudioIdRegistry();
        ListTag list = tag.getList(TAG_IDS, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            try {
                d.known.add(UUID.fromString(list.getString(i)));
            } catch (IllegalArgumentException e) {
                SpatialAudioSystem.LOGGER.warn("[AudioIdRegistry] skipping unparseable audio id", e);
            }
        }
        d.seeded = tag.getBoolean(TAG_SEEDED);
        return d;
    }

    public static final SavedData.Factory<AudioIdRegistry> FACTORY =
            new SavedData.Factory<>(AudioIdRegistry::new, AudioIdRegistry::load, null);

    public static AudioIdRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }
}
