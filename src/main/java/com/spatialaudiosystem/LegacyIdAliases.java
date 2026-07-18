package com.spatialaudiosystem;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Makes worlds saved before the 1.0.4 rename readable.
 *
 * <p>1.0.3 shipped as {@code stationsoundsystem}. The rename changed only the namespace,
 * so every saved reference — blocks in chunks, items in inventories, block entity types,
 * and the data components carrying the audio id — still names a mod that no longer
 * exists. Without an alias the blocks load as air and the media lose the component that
 * says which recording they hold.
 *
 * <p>Registry lookups resolve through these aliases, so this covers the component ids
 * inside an ItemStack as well as the item id itself. Aliases apply only where the new
 * name is absent, so nothing here can shadow a current registration.
 */
public final class LegacyIdAliases {

    private static final String LEGACY_MOD_ID = "stationsoundsystem";

    private static final List<String> BLOCKS = List.of("playback_device", "recording_device");

    /** Block items are registered under the block's own name. */
    private static final List<String> ITEMS =
            List.of("playback_device", "recording_device", "recording_medium", "range_board");

    private static final List<String> BLOCK_ENTITIES = List.of("playback_device", "recording_device");

    private static final List<String> MENUS = List.of("playback_device_menu", "recording_device_menu");

    /** {@code audio_duration_sec} is deliberately absent: it postdates the rename. */
    private static final List<String> DATA_COMPONENTS = List.of(
            "audio_data", "audio_id", "audio_file_name", "audio_format",
            "range_pos1", "range_pos2", "attenuation_ranges");

    private LegacyIdAliases() {}

    public static void register() {
        alias(BuiltInRegistries.BLOCK, BLOCKS);
        alias(BuiltInRegistries.ITEM, ITEMS);
        alias(BuiltInRegistries.BLOCK_ENTITY_TYPE, BLOCK_ENTITIES);
        alias(BuiltInRegistries.MENU, MENUS);
        alias(BuiltInRegistries.DATA_COMPONENT_TYPE, DATA_COMPONENTS);
    }

    private static void alias(Registry<?> registry, List<String> paths) {
        for (String path : paths) {
            registry.addAlias(
                    ResourceLocation.fromNamespaceAndPath(LEGACY_MOD_ID, path),
                    ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, path));
        }
    }
}
