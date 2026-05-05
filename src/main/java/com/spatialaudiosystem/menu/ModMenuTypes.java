package com.spatialaudiosystem.menu;

import com.spatialaudiosystem.SpatialAudioSystem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.network.IContainerFactory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, SpatialAudioSystem.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<PlaybackDeviceMenu>> PLAYBACK_DEVICE_MENU =
            registerMenuType("playback_device_menu", PlaybackDeviceMenu::new);

    public static final DeferredHolder<MenuType<?>, MenuType<RecordingDeviceMenu>> RECORDING_DEVICE_MENU =
            registerMenuType("recording_device_menu", RecordingDeviceMenu::new);

    private static <T extends AbstractContainerMenu> DeferredHolder<MenuType<?>, MenuType<T>> registerMenuType(
            String name, IContainerFactory<T> factory) {
        return MENUS.register(name, () -> IMenuTypeExtension.create(factory));
    }

    public static void register(IEventBus eventBus) {
        MENUS.register(eventBus);
    }
}
