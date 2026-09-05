package com.spatialaudiosystem.client;

import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.network.HandyDeviceListPayload;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * The client's copy of the owner's device list, as the server last sent it. Read by the
 * handy screen, the HUD badge and the Shift+wheel selection; written only by
 * {@link HandyDeviceListPayload}. The selection itself lives on the item stack (the server's
 * copy is the one that counts); this only answers "which row is that".
 */
public final class HandyDeviceListClient {
    private HandyDeviceListClient() {}

    private static volatile List<HandyDeviceListPayload.Row> rows = List.of();
    private static volatile long receivedAt;

    public static void accept(List<HandyDeviceListPayload.Row> newRows) {
        rows = List.copyOf(newRows);
        receivedAt = System.currentTimeMillis();
    }

    public static List<HandyDeviceListPayload.Row> rows() {
        return rows;
    }

    /** Whether any list has arrived since the client started. */
    public static boolean hasList() {
        return receivedAt != 0L;
    }

    public static int indexOf(GlobalPos pos) {
        if (pos == null) return -1;
        List<HandyDeviceListPayload.Row> r = rows;
        for (int i = 0; i < r.size(); i++) {
            if (r.get(i).pos().equals(pos)) return i;
        }
        return -1;
    }

    /** The row index the stack's selection points at, or -1. */
    public static int selectedIndex(ItemStack handy) {
        return indexOf(handy.get(ModDataComponents.HANDY_SELECTED_DEVICE));
    }

    public static HandyDeviceListPayload.Row rowAt(int index) {
        List<HandyDeviceListPayload.Row> r = rows;
        return index >= 0 && index < r.size() ? r.get(index) : null;
    }

    /** For a resource reload or a disconnect: nothing is known until the server sends again. */
    public static void clear() {
        rows = List.of();
        receivedAt = 0L;
    }
}
