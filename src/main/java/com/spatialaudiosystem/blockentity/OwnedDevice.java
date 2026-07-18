package com.spatialaudiosystem.blockentity;

import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A device that remembers who placed it and can be closed to everyone else.
 *
 * <p>The first player to open one becomes its owner; the owner can flip it to private, after which
 * only they may open or operate it. Both devices carried their own copy of that rule, and only one
 * packet handler consulted it — so a device switched to private while someone else had it open
 * stayed operable by that player. Keeping the rule here, and having
 * {@link com.spatialaudiosystem.server.ServerInteractionGuard} enforce it for every device packet,
 * closes that gap in one place.
 */
public interface OwnedDevice {

    @Nullable
    UUID getOwnerUUID();

    void setOwner(UUID uuid, String name);

    boolean isPrivateMode();

    void togglePrivateMode();

    /** Public devices are open to all; private ones are owner-only. */
    default boolean canAccess(Player player) {
        UUID owner = getOwnerUUID();
        return !isPrivateMode() || owner == null || owner.equals(player.getUUID());
    }

    /**
     * Claims the device for a first-time opener and reports whether they may proceed.
     * Returns true for a null player so non-player opens (commands, automation) are unaffected.
     */
    default boolean claimAndAllow(@Nullable Player player) {
        if (player == null) return true;
        if (getOwnerUUID() == null) setOwner(player.getUUID(), player.getName().getString());
        return canAccess(player);
    }

    /** Owner-only public/private flip, used by the owner-face button on both device screens. */
    default boolean toggleOwnerAccess(Player player) {
        UUID owner = getOwnerUUID();
        if (owner != null && !owner.equals(player.getUUID())) return false;
        togglePrivateMode();
        return true;
    }
}
