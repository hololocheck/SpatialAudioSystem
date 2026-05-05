package com.spatialaudiosystem.blockentity;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.block.ModBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, SpatialAudioSystem.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PlaybackDeviceBlockEntity>> PLAYBACK_DEVICE =
            BLOCK_ENTITIES.register("playback_device",
                    () -> BlockEntityType.Builder.of(PlaybackDeviceBlockEntity::new,
                            ModBlocks.PLAYBACK_DEVICE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RecordingDeviceBlockEntity>> RECORDING_DEVICE =
            BLOCK_ENTITIES.register("recording_device",
                    () -> BlockEntityType.Builder.of(RecordingDeviceBlockEntity::new,
                            ModBlocks.RECORDING_DEVICE.get()).build(null));

    public static void register(IEventBus eventBus) {
        BLOCK_ENTITIES.register(eventBus);
    }
}
