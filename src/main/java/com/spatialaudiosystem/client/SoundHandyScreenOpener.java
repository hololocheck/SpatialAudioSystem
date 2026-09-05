package com.spatialaudiosystem.client;

import com.spatialaudiosystem.screen.SoundHandyScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The thin client-only dispatcher the item calls to open the handy screen (BELUGAEXPERIENCE
 * R3.9.1: the item must not depend on {@code Dist.CLIENT} API directly; R3.8.1: the caller
 * checks {@code level.isClientSide} first).
 */
@OnlyIn(Dist.CLIENT)
public final class SoundHandyScreenOpener {
    private SoundHandyScreenOpener() {}

    public static void open(ItemStack handy) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.setScreen(new SoundHandyScreen(handy));
    }
}
