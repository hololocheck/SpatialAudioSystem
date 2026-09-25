package com.spatialaudiosystem.handy;

import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.ModItems;
import com.spatialaudiosystem.item.RangeBoardItem;
import com.spatialaudiosystem.item.SoundHandyItem;
import com.spatialaudiosystem.network.HandyData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The sound handy's range mode (Shift+R) editing the range board that sits in the targeted device - the same
 * three operations the board offers in the hand (spec §2.9): the box's two corners, clearing them, and one
 * face's attenuation distance. It arrives as the handy host's {@code handy-range-edit} action (the wheel,
 * {@code network.HandyData}) or from the item's own clicks, which call {@link #apply} directly so the wheel and
 * the click share one authority and one set of refusals.
 *
 * <p>Owner-only, loaded-only, and only while the handy's range mode is on; a device without a board refuses
 * with "cannot edit". The board is edited in place in the device's range slot, so the device re-reads it on
 * the next playback and the client sees it through the update tag.
 */
public record HandyRangeEdit(int op, int face, int value, Optional<BlockPos> corner) {
    public static final int SET_POS1 = 0;
    public static final int SET_POS2 = 1;
    public static final int CLEAR = 2;
    /** {@code face} = attenuation index (East 0 / West 1 / Up 2 / Down 3 / South 4 / North 5), {@code value} = ±1 step. */
    public static final int STEP_FACE = 3;
    public static final int MAX_OP = 3;
    public static final int FACES = 6;
    public static final int MAX_STEP = 1000;
    public static final int MIN_FACE_RANGE = 0;
    public static final int MAX_FACE_RANGE = 15;

    public static HandyRangeEdit corner(int op, BlockPos pos) {
        return new HandyRangeEdit(op, 0, 0, Optional.of(pos));
    }

    public static HandyRangeEdit clear() {
        return new HandyRangeEdit(CLEAR, 0, 0, Optional.empty());
    }

    public static HandyRangeEdit stepFace(int face, int step) {
        return new HandyRangeEdit(STEP_FACE, face, step, Optional.empty());
    }

    /** The new face value after one step, clamped to the board's range; pure for the ledger. */
    public static int steppedFace(int current, int step) {
        return Math.max(MIN_FACE_RANGE, Math.min(MAX_FACE_RANGE, current + Integer.signum(step)));
    }

    /** The server-side edit, on the server thread. */
    public static void apply(ServerPlayer player, HandyRangeEdit edit) {
        ItemStack handy = SoundHandyItem.held(player);
        if (handy.isEmpty() || !handy.getOrDefault(ModDataComponents.HANDY_RANGE_MODE, false)) return;
        GlobalPos target = handy.get(ModDataComponents.HANDY_SELECTED_DEVICE);
        PlaybackDeviceBlockEntity be = target == null ? null
                : SoundDeviceLink.ownedDevice(player.server, player.getUUID(), target);
        if (be == null) {
            HandyData.notify(player, "message.spatialaudiosystem.sound_handy.not_loaded", 0xFFFF55);
            return;
        }
        ItemStack board = be.getInventory().getStackInSlot(PlaybackDeviceBlockEntity.RANGE_SLOT);
        if (!board.is(ModItems.RANGE_BOARD.get())) {
            HandyData.notify(player, "message.spatialaudiosystem.sound_handy.no_board", 0xFFFF55);
            return;
        }
        switch (edit.op) {
            case SET_POS1 -> edit.corner.ifPresent(p -> {
                board.set(ModDataComponents.RANGE_POS1, p);
                HandyData.notify(player, "message.spatialaudiosystem.pos1_set\t" + p.getX() + "\t" + p.getY() + "\t" + p.getZ(), 0x55FF55);
            });
            case SET_POS2 -> edit.corner.ifPresent(p -> {
                board.set(ModDataComponents.RANGE_POS2, p);
                HandyData.notify(player, "message.spatialaudiosystem.pos2_set\t" + p.getX() + "\t" + p.getY() + "\t" + p.getZ(), 0x55FF55);
            });
            case CLEAR -> {
                board.remove(ModDataComponents.RANGE_POS1);
                board.remove(ModDataComponents.RANGE_POS2);
                HandyData.notify(player, "message.spatialaudiosystem.range_cleared", 0xFFFF55);
            }
            case STEP_FACE -> {
                List<Integer> ranges = new ArrayList<>(board.getOrDefault(
                        ModDataComponents.ATTENUATION_RANGES, ModDataComponents.DEFAULT_ATTENUATION_RANGES));
                while (ranges.size() < FACES) ranges.add(8);
                ranges.set(edit.face, steppedFace(ranges.get(edit.face), edit.value));
                board.set(ModDataComponents.ATTENUATION_RANGES, ranges);
            }
            default -> { }
        }
        be.markRangeBoardEdited();
    }

    /** Whether the stack in a device's range slot is a board with both corners (a box to show). */
    public static boolean hasBox(ItemStack board) {
        return board.is(ModItems.RANGE_BOARD.get()) && RangeBoardItem.hasRange(board);
    }
}
