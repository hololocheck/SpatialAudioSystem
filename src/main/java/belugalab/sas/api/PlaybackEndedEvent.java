package belugalab.sas.api;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;

/**
 * Fired on the NeoForge game event bus when an audio playback at {@code pos}
 * has finished playing on at least one client (or was cancelled).
 *
 * <p>Used by integrating mods (e.g. TSU announcement system) to chain the next
 * audio in a sequence without polling.
 *
 * <p>Note: this event fires once per finish notification per player. Listeners
 * that want only the "first finish" semantics should track per-pos state.
 */
public class PlaybackEndedEvent extends Event {

    private final ServerLevel level;
    private final BlockPos pos;

    public PlaybackEndedEvent(ServerLevel level, BlockPos pos) {
        this.level = level;
        this.pos = pos;
    }

    public ServerLevel getLevel() {
        return level;
    }

    public BlockPos getPos() {
        return pos;
    }
}
