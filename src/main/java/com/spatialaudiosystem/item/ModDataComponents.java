package com.spatialaudiosystem.item;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import net.minecraft.core.UUIDUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class ModDataComponents {
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, SpatialAudioSystem.MOD_ID);

    public static final Supplier<DataComponentType<byte[]>> AUDIO_DATA = DATA_COMPONENTS.register("audio_data",
            () -> DataComponentType.<byte[]>builder()
                    .persistent(Codec.BYTE_BUFFER.xmap(
                            buf -> {
                                byte[] arr = new byte[buf.remaining()];
                                buf.get(arr);
                                return arr;
                            },
                            arr -> java.nio.ByteBuffer.wrap(arr)
                    ))
                    // Send only a presence flag over the network (1 byte instead of 10MB).
                    // Client receives a 1-byte placeholder; real data stays server-side.
                    .networkSynchronized(StreamCodec.of(
                            (buf, v) -> buf.writeBoolean(v != null && v.length > 0),
                            buf -> buf.readBoolean() ? new byte[]{1} : null
                    ))
                    .cacheEncoding()
                    .build());

    /** UUID reference to server-side audio file. Replaces AUDIO_DATA for new recordings. */
    public static final Supplier<DataComponentType<UUID>> AUDIO_ID = DATA_COMPONENTS.register("audio_id",
            () -> DataComponentType.<UUID>builder()
                    .persistent(UUIDUtil.CODEC)
                    .networkSynchronized(UUIDUtil.STREAM_CODEC)
                    .build());

    public static final Supplier<DataComponentType<String>> AUDIO_FILE_NAME = DATA_COMPONENTS.register("audio_file_name",
            () -> DataComponentType.<String>builder()
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                    .build());

    public static final Supplier<DataComponentType<String>> AUDIO_FORMAT = DATA_COMPONENTS.register("audio_format",
            () -> DataComponentType.<String>builder()
                    .persistent(Codec.STRING)
                    .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                    .build());

    public static final Supplier<DataComponentType<BlockPos>> RANGE_POS1 = DATA_COMPONENTS.register("range_pos1",
            () -> DataComponentType.<BlockPos>builder()
                    .persistent(BlockPos.CODEC)
                    .networkSynchronized(BlockPos.STREAM_CODEC)
                    .build());

    public static final Supplier<DataComponentType<BlockPos>> RANGE_POS2 = DATA_COMPONENTS.register("range_pos2",
            () -> DataComponentType.<BlockPos>builder()
                    .persistent(BlockPos.CODEC)
                    .networkSynchronized(BlockPos.STREAM_CODEC)
                    .build());

    // 6 per-direction attenuation ranges: [East(+X), West(-X), Up(+Y), Down(-Y), South(+Z), North(-Z)]
    public static final Supplier<DataComponentType<List<Integer>>> ATTENUATION_RANGES = DATA_COMPONENTS.register("attenuation_ranges",
            () -> DataComponentType.<List<Integer>>builder()
                    .persistent(Codec.INT.listOf())
                    .networkSynchronized(StreamCodec.of(
                            (buf, list) -> { buf.writeInt(list.size()); for (int v : list) buf.writeInt(v); },
                            buf -> { int n = buf.readInt(); List<Integer> l = new ArrayList<>(n); for (int i = 0; i < n; i++) l.add(buf.readInt()); return l; }
                    ))
                    .build());

    public static final List<Integer> DEFAULT_ATTENUATION_RANGES = List.of(8, 8, 8, 8, 8, 8);

    /**
     * Creates a lightweight copy of the given ItemStack, copying all components
     * EXCEPT AUDIO_DATA (which is too large for network NBT).
     * Centralised here so new components only need to be added in one place.
     */
    /**
     * Create a lightweight copy of the stack for network sync (getUpdateTag).
     * Copies the full stack, then strips only the known-heavy components.
     * This is future-proof: new components are automatically included.
     */
    public static ItemStack createLiteStack(ItemStack stack) {
        ItemStack lite = stack.copy();
        // Remove only the legacy bulk audio data component (the only heavy one)
        lite.remove(AUDIO_DATA);
        return lite;
    }

    /** Returns attenuation ranges as int[6] from the given range board stack. */
    public static int[] getAttenuationRangesArray(ItemStack stack) {
        List<Integer> list = stack.getOrDefault(ATTENUATION_RANGES, DEFAULT_ATTENUATION_RANGES);
        int[] arr = new int[6];
        for (int i = 0; i < 6; i++) arr[i] = i < list.size() ? list.get(i) : 8;
        return arr;
    }

    public static void register(IEventBus eventBus) {
        DATA_COMPONENTS.register(eventBus);
    }
}
