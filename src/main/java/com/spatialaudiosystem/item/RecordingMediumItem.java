package com.spatialaudiosystem.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

public class RecordingMediumItem extends Item {
    public RecordingMediumItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        if (stack.has(ModDataComponents.AUDIO_FILE_NAME)) {
            String fileName = stack.get(ModDataComponents.AUDIO_FILE_NAME);
            tooltipComponents.add(Component.translatable("tooltip.spatialaudiosystem.recording_medium.audio", fileName)
                    .withStyle(ChatFormatting.GREEN));
        } else {
            tooltipComponents.add(Component.translatable("tooltip.spatialaudiosystem.recording_medium.empty")
                    .withStyle(ChatFormatting.GRAY));
        }
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
    }

    public static boolean hasAudioData(ItemStack stack) {
        // AUDIO_DATA is server-side only (not network-synced) to prevent corruption.
        // Use AUDIO_FILE_NAME as the presence indicator on both client and server.
        return stack.has(ModDataComponents.AUDIO_FILE_NAME);
    }
}
