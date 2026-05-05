package com.spatialaudiosystem.item;

import com.spatialaudiosystem.SpatialAudioSystem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SpatialAudioSystem.MOD_ID);

    public static final DeferredItem<Item> RECORDING_MEDIUM = ITEMS.register("recording_medium",
            () -> new RecordingMediumItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<Item> RANGE_BOARD = ITEMS.register("range_board",
            () -> new RangeBoardItem(new Item.Properties().stacksTo(1)));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
