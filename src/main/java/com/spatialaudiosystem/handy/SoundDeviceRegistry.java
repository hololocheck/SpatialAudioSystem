package com.spatialaudiosystem.handy;

import com.spatialaudiosystem.SpatialAudioSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The server's registry of playback devices per owner: what the sound handy lists and may
 * operate. One per server (the overworld's data storage), keyed by the owner's UUID.
 *
 * <p>The owner is the placer (set at placement since 1.1.0) or, for devices placed before
 * that, the first player who opened the device -- the same rule {@code OwnedDevice} has always
 * applied. A device enters the registry at placement, or at the first open that settles its
 * owner, and leaves it when the block is removed. Another player's device is never listed:
 * every read is by owner, and every write checks the owner it was recorded under.
 */
public class SoundDeviceRegistry extends SavedData {
    private static final String FILE_NAME = "spatialaudiosystem_sound_devices";
    private static final String TAG_DEVICES = "Devices";

    /** Names are shown in a 12-row list and a title: 32 code points is the room they have. */
    public static final int MAX_NAME_CODE_POINTS = 32;
    /** The list payload is capped at 64 rows; the registry refuses the 65th so the two agree. */
    public static final int MAX_DEVICES_PER_OWNER = 64;

    /** One registered device; {@code name} is null while the device is unnamed. */
    public record Entry(GlobalPos pos, @Nullable String name) {}

    private final Map<UUID, LinkedHashMap<GlobalPos, String>> byOwner = new HashMap<>();
    private final Map<GlobalPos, UUID> ownerByPos = new HashMap<>();

    /**
     * Records {@code pos} under {@code owner}. A device already recorded under another owner
     * moves (the block entity's owner is the authority; this mirrors it). False when nothing
     * changed, or when the owner already has {@link #MAX_DEVICES_PER_OWNER} devices.
     */
    public boolean register(UUID owner, GlobalPos pos, @Nullable String name) {
        if (owner == null || pos == null) return false;
        String clean = sanitizeName(name);
        UUID previous = ownerByPos.get(pos);
        LinkedHashMap<GlobalPos, String> mine = byOwner.computeIfAbsent(owner, k -> new LinkedHashMap<>());
        if (owner.equals(previous)) {
            String stored = mine.get(pos);
            if (java.util.Objects.equals(stored, clean == null ? "" : clean)) return false;
            mine.put(pos, clean == null ? "" : clean);
            setDirty();
            return true;
        }
        if (mine.size() >= MAX_DEVICES_PER_OWNER) return false;
        if (previous != null) {
            LinkedHashMap<GlobalPos, String> theirs = byOwner.get(previous);
            if (theirs != null) {
                theirs.remove(pos);
                if (theirs.isEmpty()) byOwner.remove(previous);
            }
        }
        mine.put(pos, clean == null ? "" : clean);
        ownerByPos.put(pos, owner);
        setDirty();
        return true;
    }

    /** Forgets {@code pos} whoever owned it. False when it was not recorded. */
    public boolean unregister(GlobalPos pos) {
        UUID owner = ownerByPos.remove(pos);
        if (owner == null) return false;
        LinkedHashMap<GlobalPos, String> mine = byOwner.get(owner);
        if (mine != null) {
            mine.remove(pos);
            if (mine.isEmpty()) byOwner.remove(owner);
        }
        setDirty();
        return true;
    }

    /** Renames a device the caller owns. False for another owner's device or an unknown one. */
    public boolean rename(UUID owner, GlobalPos pos, @Nullable String name) {
        if (owner == null || !owner.equals(ownerByPos.get(pos))) return false;
        return register(owner, pos, name);
    }

    /** The owner's devices in registration order; a copy. */
    public List<Entry> devicesOf(UUID owner) {
        LinkedHashMap<GlobalPos, String> mine = byOwner.get(owner);
        if (mine == null) return List.of();
        List<Entry> out = new ArrayList<>(mine.size());
        for (Map.Entry<GlobalPos, String> e : mine.entrySet()) {
            out.add(new Entry(e.getKey(), e.getValue().isEmpty() ? null : e.getValue()));
        }
        return out;
    }

    @Nullable
    public UUID ownerOf(GlobalPos pos) {
        return ownerByPos.get(pos);
    }

    /**
     * A name as the registry keeps it: trimmed, control characters dropped, at most
     * {@link #MAX_NAME_CODE_POINTS} code points, and null when nothing is left.
     */
    @Nullable
    public static String sanitizeName(@Nullable String raw) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder();
        raw.codePoints().filter(cp -> cp >= 0x20 && cp != 0x7f).forEach(sb::appendCodePoint);
        String s = sb.toString().trim();
        if (s.isEmpty()) return null;
        int[] cps = s.codePoints().toArray();
        if (cps.length > MAX_NAME_CODE_POINTS) s = new String(cps, 0, MAX_NAME_CODE_POINTS).trim();
        return s.isEmpty() ? null : s;
    }

    // ------------------------------------------------------------------ persistence

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, LinkedHashMap<GlobalPos, String>> owner : byOwner.entrySet()) {
            for (Map.Entry<GlobalPos, String> device : owner.getValue().entrySet()) {
                CompoundTag t = new CompoundTag();
                t.putUUID("Owner", owner.getKey());
                t.putString("Dim", device.getKey().dimension().location().toString());
                BlockPos p = device.getKey().pos();
                t.putInt("X", p.getX());
                t.putInt("Y", p.getY());
                t.putInt("Z", p.getZ());
                if (!device.getValue().isEmpty()) t.putString("Name", device.getValue());
                list.add(t);
            }
        }
        tag.put(TAG_DEVICES, list);
        return tag;
    }

    public static SoundDeviceRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        SoundDeviceRegistry d = new SoundDeviceRegistry();
        ListTag list = tag.getList(TAG_DEVICES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            try {
                UUID owner = t.getUUID("Owner");
                ResourceLocation dim = ResourceLocation.parse(t.getString("Dim"));
                GlobalPos pos = GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dim),
                        new BlockPos(t.getInt("X"), t.getInt("Y"), t.getInt("Z")));
                String name = t.contains("Name") ? t.getString("Name") : null;
                d.byOwner.computeIfAbsent(owner, k -> new LinkedHashMap<>()).put(pos, name == null ? "" : name);
                d.ownerByPos.put(pos, owner);
            } catch (RuntimeException e) {
                SpatialAudioSystem.LOGGER.warn("[SoundDeviceRegistry] skipping an unreadable entry", e);
            }
        }
        return d;
    }

    public static final SavedData.Factory<SoundDeviceRegistry> FACTORY =
            new SavedData.Factory<>(SoundDeviceRegistry::new, SoundDeviceRegistry::load, null);

    public static SoundDeviceRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_NAME);
    }

    /** The overworld key, for callers that only have a level. */
    public static ResourceKey<Level> dimensionOf(Level level) {
        return level.dimension();
    }
}
