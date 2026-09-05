package com.spatialaudiosystem.creative;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.block.ModBlocks;
import com.spatialaudiosystem.item.ModItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SpatialAudioSystem.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> STATION_SOUND_TAB =
            CREATIVE_MODE_TABS.register("station_sound_tab", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.spatialaudiosystem"))
                    .icon(() -> new ItemStack(ModBlocks.PLAYBACK_DEVICE.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ModBlocks.PLAYBACK_DEVICE.get());
                        output.accept(ModBlocks.RECORDING_DEVICE.get());
                        output.accept(ModItems.RECORDING_MEDIUM.get());
                        output.accept(ModItems.RANGE_BOARD.get());
                        output.accept(ModItems.SOUND_HANDY.get());
                    })
                    .build());

    public static void register(IEventBus eventBus) {
        CREATIVE_MODE_TABS.register(eventBus);
    }
}
