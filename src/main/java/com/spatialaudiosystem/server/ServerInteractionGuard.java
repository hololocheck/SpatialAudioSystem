package com.spatialaudiosystem.server;

import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import com.spatialaudiosystem.blockentity.RecordingDeviceBlockEntity;
import com.spatialaudiosystem.menu.PlaybackDeviceMenu;
import com.spatialaudiosystem.menu.RecordingDeviceMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Resolves the block entity a GUI packet is allowed to act on.
 *
 * <p>A {@code BlockPos} off the wire proves nothing: any client can name any loaded
 * position without standing near it or having the screen open. Every handler that
 * mutates a device must go through here so the position is checked against the menu
 * the sender actually has open on the server.
 */
public final class ServerInteractionGuard {

    private ServerInteractionGuard() {}

    /** The playback device the sender has open at {@code pos}, or null if it may not act on it. */
    @Nullable
    public static PlaybackDeviceBlockEntity playbackDevice(@Nullable Player player, BlockPos pos) {
        return resolve(player, pos, PlaybackDeviceMenu.class, PlaybackDeviceMenu::getBlockEntity);
    }

    /** The recording device the sender has open at {@code pos}, or null if it may not act on it. */
    @Nullable
    public static RecordingDeviceBlockEntity recordingDevice(@Nullable Player player, BlockPos pos) {
        return resolve(player, pos, RecordingDeviceMenu.class, RecordingDeviceMenu::getBlockEntity);
    }

    @Nullable
    private static <M extends AbstractContainerMenu, B extends BlockEntity> B resolve(
            @Nullable Player player, BlockPos pos, Class<M> menuType, Function<M, B> blockEntityOf) {

        if (!(player instanceof ServerPlayer sender)) return null;
        if (!menuType.isInstance(sender.containerMenu)) return null;

        B blockEntity = blockEntityOf.apply(menuType.cast(sender.containerMenu));
        if (blockEntity == null) return null;

        // The open menu must be the one for this position, not merely a menu of the right kind.
        if (!blockEntity.getBlockPos().equals(pos)) return null;

        // stillValid checks reach against the menu's own level, so a sender who has changed
        // dimension with the menu still open would otherwise be measured in the wrong world.
        if (blockEntity.getLevel() != sender.level()) return null;

        return sender.containerMenu.stillValid(sender) ? blockEntity : null;
    }
}
