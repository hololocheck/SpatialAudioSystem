package com.spatialaudiosystem.audio;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.item.ModDataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side file-based audio storage.
 * Audio data is stored as individual files in {@code <world>/spatialaudiosystem_audio/<uuid>.audio}
 * instead of in ItemStack DataComponents, preventing corruption from network sync.
 */
public class AudioStorage {

    private static final String STORAGE_DIR = "spatialaudiosystem_audio";

    private static Path getStorageDir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(STORAGE_DIR);
    }

    /**
     * Save audio data to a new file and return the generated UUID.
     */
    public static UUID save(MinecraftServer server, byte[] audioData) {
        UUID id = UUID.randomUUID();
        try {
            Path dir = getStorageDir(server);
            Files.createDirectories(dir);
            Files.write(dir.resolve(id + ".audio"), audioData);
            trackId(id);
        } catch (IOException e) {
            SpatialAudioSystem.LOGGER.error("Failed to save audio {}", id, e);
        }
        return id;
    }

    /**
     * Load audio data by UUID. Returns null if the file does not exist.
     */
    @Nullable
    public static byte[] load(MinecraftServer server, UUID id) {
        Path file = getStorageDir(server).resolve(id + ".audio");
        if (!Files.exists(file)) return null;
        trackId(id);
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            SpatialAudioSystem.LOGGER.error("Failed to load audio {}", id, e);
            return null;
        }
    }

    /**
     * Delete an audio file by UUID.
     */
    public static void delete(MinecraftServer server, UUID id) {
        try {
            Path file = getStorageDir(server).resolve(id + ".audio");
            Files.deleteIfExists(file);
        } catch (IOException e) {
            SpatialAudioSystem.LOGGER.error("Failed to delete audio {}", id, e);
        }
    }

    /**
     * Migrate an ItemStack from legacy AUDIO_DATA component to file-based storage.
     * If the stack has AUDIO_DATA (real data, not a network placeholder) but no AUDIO_ID,
     * saves the data to a file, sets AUDIO_ID, and removes AUDIO_DATA.
     *
     * @return true if migration was performed
     */
    public static boolean migrateIfNeeded(MinecraftServer server, ItemStack stack) {
        if (stack.has(ModDataComponents.AUDIO_ID)) return false; // already migrated
        if (!stack.has(ModDataComponents.AUDIO_DATA)) return false;

        byte[] data = stack.get(ModDataComponents.AUDIO_DATA);
        if (data == null || data.length < 32) return false; // skip network placeholders

        UUID id = save(server, data);
        stack.set(ModDataComponents.AUDIO_ID, id);
        stack.remove(ModDataComponents.AUDIO_DATA);
        SpatialAudioSystem.LOGGER.info("Migrated audio data to file: {}", id);
        return true;
    }

    /** Maximum allowed audio file size (10 MB). Chunked upload bypasses single-packet limit. */
    public static final int MAX_AUDIO_SIZE = 10 * 1024 * 1024;

    /**
     * Validate audio data size before saving. Returns an error message key or null if OK.
     */
    @Nullable
    public static String validateSize(byte[] audioData) {
        if (audioData.length > MAX_AUDIO_SIZE) {
            return "Audio file too large (" + (audioData.length / 1024 / 1024) + " MB). Maximum is 10 MB.";
        }
        return null;
    }

    /**
     * Clean up orphaned audio files that are not referenced by any known item.
     * Only deletes files older than 1 hour to avoid deleting files for items in unloaded chunks.
     *
     * @param referencedIds set of AUDIO_IDs found in loaded inventories
     */
    public static void cleanupOrphans(MinecraftServer server, Set<UUID> referencedIds) {
        Path dir = getStorageDir(server);
        if (!Files.exists(dir)) return;

        Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
        int deleted = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.audio")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                String uuidStr = name.substring(0, name.length() - ".audio".length());
                try {
                    UUID fileId = UUID.fromString(uuidStr);
                    if (referencedIds.contains(fileId)) continue;

                    // Only delete old files (safety margin for unloaded chunks)
                    BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                    if (attrs.creationTime().toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(file);
                        deleted++;
                    }
                } catch (IllegalArgumentException ignored) {
                    // Not a valid UUID filename, skip
                }
            }
        } catch (IOException e) {
            SpatialAudioSystem.LOGGER.error("Failed to scan audio directory for cleanup", e);
        }

        if (deleted > 0) {
            SpatialAudioSystem.LOGGER.info("Cleaned up {} orphaned audio files", deleted);
        }
    }

    /** Tracks all known AUDIO_IDs. Populated when audio is saved, loaded, or migrated. */
    private static final Set<UUID> knownIds = ConcurrentHashMap.newKeySet();

    /** Register an ID as known (called on save, load, migrate). */
    public static void trackId(UUID id) {
        knownIds.add(id);
    }

    /**
     * Collect all referenced AUDIO_IDs from player inventories and known-ID tracking.
     * Block entity inventories are covered by the knownIds set (populated on load/playback).
     */
    public static Set<UUID> collectReferencedIds(MinecraftServer server) {
        Set<UUID> ids = new HashSet<>(knownIds);
        // Also scan player inventories for completeness
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                ItemStack stack = player.getInventory().getItem(i);
                UUID id = stack.get(ModDataComponents.AUDIO_ID);
                if (id != null) ids.add(id);
            }
        }
        return ids;
    }

    /**
     * Load audio for a given ItemStack. Handles both new (AUDIO_ID) and legacy (AUDIO_DATA) items.
     * Automatically migrates legacy items when possible.
     */
    @Nullable
    public static byte[] loadForItem(MinecraftServer server, ItemStack stack) {
        // Try migration first
        migrateIfNeeded(server, stack);

        // New system: load from file
        UUID id = stack.get(ModDataComponents.AUDIO_ID);
        if (id != null) {
            return load(server, id);
        }

        // Legacy fallback: read directly from component (should not happen after migration)
        byte[] data = stack.get(ModDataComponents.AUDIO_DATA);
        if (data != null && data.length >= 32) {
            return data;
        }

        return null;
    }
}
