package com.spatialaudiosystem;

import com.spatialaudiosystem.block.ModBlocks;
import com.spatialaudiosystem.blockentity.ModBlockEntities;
import com.spatialaudiosystem.creative.ModCreativeTabs;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.ModItems;
import com.spatialaudiosystem.menu.ModMenuTypes;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(SpatialAudioSystem.MOD_ID)
public class SpatialAudioSystem {
    public static final String MOD_ID = "spatialaudiosystem";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public SpatialAudioSystem(IEventBus modEventBus) {
        // Before anything can be loaded from a world save.
        LegacyIdAliases.register();

        // Manta serves this mod's wiki pages (assets/spatialaudiosystem/wiki/**) alongside its own.
        com.manta.MantaBootstrap.registerWikiMod(MOD_ID);

        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenuTypes.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        ModDataComponents.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("SpatialAudioSystem initialized");
    }
}
