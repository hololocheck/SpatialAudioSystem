package com.spatialaudiosystem.client;

import com.manta.api.data.Mirror;
import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.handy.HandyDeviceRow;
import com.spatialaudiosystem.network.HandyData;
import com.spatialaudiosystem.screen.SoundHandyHudRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The client end of the player's tools host ({@link HandyData}): open while the client is in a world. It
 * sends the handy's and the range board's input as actions, and each tick takes what arrived - the device
 * list into {@link HandyDeviceListClient}, and a notice whose counter moved onto the HUD. No page draws this
 * mirror, so the tick also asks for a snapshot that has not come.
 */
@EventBusSubscriber(modid = SpatialAudioSystem.MOD_ID, value = Dist.CLIENT)
public final class HandyClient {

    private static Mirror mirror;
    private static int seenRevision = -1;
    /** The notice counter last seen; -1 until the first snapshot, whose notice is history, not news. */
    private static int seenNotice = -1;

    private HandyClient() {
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (mirror != null) {
            mirror.close();
        }
        mirror = Mirror.open(HandyData.channel(event.getPlayer().getUUID()), HandyData.schema());
        seenRevision = -1;
        seenNotice = -1;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (mirror != null) {
            mirror.close();
            mirror = null;
        }
        HandyDeviceListClient.clear();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Mirror m = mirror;
        if (m == null) {
            return;
        }
        m.poll();
        int revision = m.revision();
        if (revision < 0 || revision == seenRevision) {
            return;
        }
        seenRevision = revision;
        HandyDeviceListClient.accept(rows((List<?>) m.get("handy-devices")));
        int notice = (Integer) m.get("notice-seq");
        if (seenNotice >= 0 && notice != seenNotice) {
            SoundHandyHudRenderer.route(resolve((String) m.get("notice")), (Integer) m.get("notice-color"));
        }
        seenNotice = notice;
    }

    /** A tool action, when the client is in a world; before that there is nobody to send it to. */
    public static void send(String action, Object... args) {
        if (mirror != null) {
            mirror.send(action, args);
        }
    }

    /** A handy action (HandyActions' numbers), at {@code pos} or at none - HandyActionPayload's of / at. */
    public static void action(int action, int arg, @Nullable GlobalPos pos) {
        if (pos == null) {
            send("handy-action", action, arg, false, "", 0, 0, 0);
        } else {
            send("handy-action", action, arg, true, pos.dimension().location().toString(),
                    pos.pos().getX(), pos.pos().getY(), pos.pos().getZ());
        }
    }

    /** One of the owner's devices named from the handy screen - SetDeviceNamePayload. */
    public static void rename(GlobalPos pos, String name) {
        send("set-device-name", pos.dimension().location().toString(), pos.pos().getX(), pos.pos().getY(),
                pos.pos().getZ(), name);
    }

    /** The list field's rows (tools-state.json's row order) as the rows the HUD and the screen read. */
    private static List<HandyDeviceRow> rows(List<?> raw) {
        List<HandyDeviceRow> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            List<?> r = (List<?>) o;
            ResourceLocation dim = ResourceLocation.tryParse((String) r.get(0));
            if (dim == null) {
                continue;
            }
            GlobalPos pos = GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dim),
                    new BlockPos((Integer) r.get(1), (Integer) r.get(2), (Integer) r.get(3)));
            out.add(new HandyDeviceRow(pos, (String) r.get(4), (Boolean) r.get(5), (Boolean) r.get(6),
                    (Boolean) r.get(7), (Boolean) r.get(8), (String) r.get(9), (String) r.get(10)));
        }
        return out;
    }

    /**
     * A notice sent as a lang key ("message." prefix, arguments after tabs) is translated here, in the
     * client's own language; a plain text is shown as it is. The handy sends keys; the range board still
     * sends texts the server translated.
     */
    static String resolve(String message) {
        if (message == null || !message.startsWith("message.")) return message;
        String[] parts = message.split("\t");
        Object[] args = new Object[parts.length - 1];
        System.arraycopy(parts, 1, args, 0, args.length);
        return net.minecraft.network.chat.Component.translatable(parts[0], args).getString();
    }
}
