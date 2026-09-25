package com.spatialaudiosystem.handy;

import net.minecraft.core.GlobalPos;

/**
 * One of the owner's playback devices, as the server knows it right now - a row of the list the player's
 * handy host carries ({@code network.HandyData}): where, what it is called (empty = unnamed), what it is
 * doing, whether it holds something playable and a range board (the HUD's "cannot play / cannot edit"), and
 * the medium it would play - its file name and format, empty when there is none (the mini HUD shows the
 * medium's icon and file, user's real-device note 2026-09-05). An unloaded device reports false and empty
 * for all of them - the server cannot look inside it.
 */
public record HandyDeviceRow(GlobalPos pos, String name, boolean loaded, boolean playing, boolean hasMedium,
                             boolean hasBoard, String mediumFile, String mediumFormat) {

    /** The shape before the medium fields: no medium information. */
    public HandyDeviceRow(GlobalPos pos, String name, boolean loaded, boolean playing, boolean hasMedium,
                          boolean hasBoard) {
        this(pos, name, loaded, playing, hasMedium, hasBoard, "", "");
    }
}
