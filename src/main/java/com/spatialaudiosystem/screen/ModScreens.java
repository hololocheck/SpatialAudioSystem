package com.spatialaudiosystem.screen;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.ModItems;
import com.spatialaudiosystem.menu.ModMenuTypes;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = SpatialAudioSystem.MOD_ID,
        bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ModScreens {
    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.PLAYBACK_DEVICE_MENU.get(), PlaybackDeviceScreen::new);
        event.register(ModMenuTypes.RECORDING_DEVICE_MENU.get(), RecordingDeviceScreen::new);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ItemProperties.register(ModItems.RECORDING_MEDIUM.get(),
                    ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "audio_format"),
                    (stack, level, entity, seed) -> {
                        String format = stack.getOrDefault(ModDataComponents.AUDIO_FORMAT.get(), "");
                        return switch (format.toLowerCase()) {
                            case "mp3" -> 1.0f;
                            case "ogg" -> 2.0f;
                            case "wav" -> 3.0f;
                            default -> 0.0f;
                        };
                    });
        });
    }
}
