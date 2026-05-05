package com.spatialaudiosystem.network;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.ModItems;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record SetRangeBoardDataPayload(InteractionHand hand, List<Integer> ranges) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetRangeBoardDataPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SpatialAudioSystem.MOD_ID, "set_range_board_data"));

    public static final StreamCodec<FriendlyByteBuf, SetRangeBoardDataPayload> STREAM_CODEC =
            StreamCodec.of(SetRangeBoardDataPayload::write, SetRangeBoardDataPayload::read);

    private static void write(FriendlyByteBuf buf, SetRangeBoardDataPayload payload) {
        buf.writeBoolean(payload.hand == InteractionHand.MAIN_HAND);
        buf.writeInt(payload.ranges.size());
        for (int v : payload.ranges) buf.writeInt(v);
    }

    private static SetRangeBoardDataPayload read(FriendlyByteBuf buf) {
        InteractionHand hand = buf.readBoolean() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        int size = buf.readInt();
        List<Integer> ranges = new ArrayList<>(size);
        for (int i = 0; i < size; i++) ranges.add(buf.readInt());
        return new SetRangeBoardDataPayload(hand, ranges);
    }

    public static void handle(SetRangeBoardDataPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            ItemStack stack = player.getItemInHand(payload.hand);
            if (!stack.is(ModItems.RANGE_BOARD.get())) return;
            if (payload.ranges.size() != 6) return;
            List<Integer> valid = new ArrayList<>(6);
            for (int v : payload.ranges) valid.add(Math.max(0, Math.min(15, v)));
            stack.set(ModDataComponents.ATTENUATION_RANGES, valid);
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
