package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import com.spatialaudiosystem.redstone.RedstoneRule;
import com.spatialaudiosystem.server.ServerInteractionGuard;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C2S: edits to a playback device's redstone output rules, from the redstone dialog.
 *
 * <p>Every field is bounded at decode (R3.6.1): {@code index} is a rule slot, {@code delta} is
 * one wheel notch either way. The server applies the edit through the block entity, which
 * clamps again, and the update tag carries the result back to every client.
 */
public record RedstoneRuleCommandPayload(BlockPos pos, int op, int index, int delta)
        implements CustomPacketPayload {

    public static final int OP_TOGGLE_ENABLED = 0;
    public static final int OP_ADD = 1;
    public static final int OP_REMOVE = 2;            // index
    public static final int OP_CYCLE_TRIGGER = 3;     // index, delta
    public static final int OP_ADJUST_STRENGTH = 4;   // index, delta
    public static final int OP_ADJUST_DELAY = 5;      // index, delta (one notch = DELAY_STEP_TICKS)
    public static final int OP_ADJUST_LENGTH = 6;     // index, delta (one notch = LENGTH_STEP_TICKS)
    public static final int OP_ADJUST_ENTRY = 7;      // index, delta (entry scope, wraps)
    public static final int OP_MOVE = 8;              // index, delta (swap with the neighbour)

    public static final CustomPacketPayload.Type<RedstoneRuleCommandPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "redstone_rule_cmd"));

    public static final StreamCodec<FriendlyByteBuf, RedstoneRuleCommandPayload> STREAM_CODEC =
            StreamCodec.of(RedstoneRuleCommandPayload::write, RedstoneRuleCommandPayload::read);

    private static void write(FriendlyByteBuf buf, RedstoneRuleCommandPayload p) {
        buf.writeBlockPos(p.pos);
        buf.writeVarInt(p.op);
        buf.writeVarInt(p.index);
        buf.writeVarInt(p.delta);
    }

    private static RedstoneRuleCommandPayload read(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int op = buf.readVarInt();
        int index = buf.readVarInt();
        int delta = buf.readVarInt();
        if (op < OP_TOGGLE_ENABLED || op > OP_MOVE) {
            throw new DecoderException("Invalid redstone rule op: " + op);
        }
        if (index < 0 || index >= RedstoneRule.MAX_RULES) {
            throw new DecoderException("Redstone rule index out of range: " + index);
        }
        if (delta < -1 || delta > 1) {
            throw new DecoderException("Redstone rule delta out of range: " + delta);
        }
        return new RedstoneRuleCommandPayload(pos, op, index, delta);
    }

    public static void handle(RedstoneRuleCommandPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            // Same gate as every other device packet: the sender must have this device's
            // screen open and still be allowed to use it.
            PlaybackDeviceBlockEntity be = ServerInteractionGuard.playbackDevice(context.player(), payload.pos);
            if (be == null) return;
            switch (payload.op) {
                case OP_TOGGLE_ENABLED -> be.toggleRedstoneEnabled();
                case OP_ADD -> be.addRedstoneRule();
                case OP_REMOVE -> be.removeRedstoneRule(payload.index);
                case OP_CYCLE_TRIGGER -> be.cycleRedstoneTrigger(payload.index, payload.delta);
                case OP_ADJUST_STRENGTH -> be.adjustRedstoneStrength(payload.index, payload.delta);
                case OP_ADJUST_DELAY -> be.adjustRedstoneDelay(payload.index, payload.delta);
                case OP_ADJUST_LENGTH -> be.adjustRedstoneLength(payload.index, payload.delta);
                case OP_ADJUST_ENTRY -> be.adjustRedstoneEntry(payload.index, payload.delta);
                case OP_MOVE -> be.moveRedstoneRule(payload.index, payload.delta);
                default -> { }
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
