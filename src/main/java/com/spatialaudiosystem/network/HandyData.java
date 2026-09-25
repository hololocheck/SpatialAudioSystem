package com.spatialaudiosystem.network;

import com.manta.api.data.Host;
import com.manta.api.data.MantaData;
import com.manta.api.data.Schema;
import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.blockentity.PlaybackDeviceBlockEntity;
import com.spatialaudiosystem.handy.HandyActions;
import com.spatialaudiosystem.handy.HandyDeviceRow;
import com.spatialaudiosystem.handy.HandyRangeEdit;
import com.spatialaudiosystem.handy.SoundDeviceLink;
import com.spatialaudiosystem.handy.SoundDeviceRegistry;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.ModItems;
import com.spatialaudiosystem.item.SoundHandyItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The player's tools on manta:data (MANTA_7_CONCEPT C4): what the handy's and the range board's own payloads
 * carried - HandyActionPayload, HandyRangeEditPayload, SetDeviceNamePayload and SetRangeBoardDataPayload in,
 * HandyDeviceListPayload and ClientNotifyPayload out. Each player has one host that only they reach, opened
 * at login (Manta closes it at logout), declared by the state-only document {@link #DOCUMENT}.
 *
 * <p>The notice is an event, not state: {@link #notify} moves {@code notice-seq}, and the client shows
 * {@code notice} when it sees the counter move (client.HandyClient). Two notices in one server tick show the
 * last - one action sends one.</p>
 */
@EventBusSubscriber(modid = SpatialAudioSystem.MOD_ID)
public final class HandyData {

    static final String DOCUMENT = "/assets/spatialaudiosystem/manta/tools-state.json";
    static final String DOC = "tools";
    /** tools-state.json's maxLen of the strings a player controls, in UTF-8 bytes. */
    static final int NAME_BYTES = 128;
    static final int FILE_BYTES = 384;
    static final int FORMAT_BYTES = 48;
    static final int NOTICE_BYTES = 768;

    private static Schema schema;
    /** The open host of each player; server thread only (login, logout and every caller run there). */
    private static final Map<UUID, Host> HOSTS = new HashMap<>();

    private HandyData() {
    }

    public static synchronized Schema schema() {
        if (schema == null) {
            schema = MantaData.schemaResource(SpatialAudioSystem.class, DOCUMENT);
        }
        return schema;
    }

    public static String channel(UUID player) {
        return MantaData.channel(SpatialAudioSystem.MOD_ID, DOC, player);
    }

    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Host host = MantaData.host(player, SpatialAudioSystem.MOD_ID, DOC, schema());
        // Params in tools-state.json's declaration order.
        host.on("handy-action", (args, p) -> HandyActions.handle(p, (Integer) args.get(0), (Integer) args.get(1),
                (Boolean) args.get(2) ? globalPos(args, 3) : null));
        host.on("handy-range-edit", (args, p) -> HandyRangeEdit.apply(p, new HandyRangeEdit((Integer) args.get(0),
                (Integer) args.get(1), (Integer) args.get(2),
                (Boolean) args.get(3) ? Optional.of(blockPos(args, 4)) : Optional.empty())));
        host.on("set-device-name", (args, p) -> rename(p, globalPos(args, 0), (String) args.get(4)));
        host.on("range-board-data", (args, p) -> rangeBoard(p, args));
        HOSTS.put(player.getUUID(), host);
        // The HUD and the handy screen read the list; it is known from the start. Taking the handy in hand
        // still asks for it (REQUEST_LIST), which also gives a handy with no target one.
        setDevices(player, SoundDeviceLink.rows(player.server, player.getUUID()));
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        HOSTS.remove(event.getEntity().getUUID());
    }

    /** The owner's device list on their host - the whole list is the value, as the payload carried it. */
    public static void setDevices(ServerPlayer player, List<HandyDeviceRow> rows) {
        Host host = HOSTS.get(player.getUUID());
        if (host == null) {
            return;
        }
        List<List<Object>> out = new ArrayList<>(rows.size());
        for (HandyDeviceRow r : rows) {
            BlockPos p = r.pos().pos();
            out.add(List.<Object>of(r.pos().dimension().location().toString(), p.getX(), p.getY(), p.getZ(),
                    fit(r.name(), NAME_BYTES), r.loaded(), r.playing(), r.hasMedium(), r.hasBoard(),
                    fit(r.mediumFile(), FILE_BYTES), fit(r.mediumFormat(), FORMAT_BYTES)));
        }
        host.setList("handy-devices", out);
    }

    /**
     * A HUD notification for the player, below the item name: a lang key with tab-separated arguments the client
     * translates, or a text the server already translated (the range board's). Nothing for a client player.
     */
    public static void notify(Player player, String message, int color) {
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }
        Host host = HOSTS.get(sp.getUUID());
        if (host == null) {
            return;
        }
        host.set("notice", fit(message, NOTICE_BYTES));
        host.set("notice-color", color);
        host.set("notice-seq", ((Integer) host.get("notice-seq") + 1) & 0xFFFF);
    }

    /** A device named from the handy screen: the handy in hand, the device the sender's and loaded. */
    private static void rename(ServerPlayer player, @Nullable GlobalPos pos, String name) {
        if (pos == null || SoundHandyItem.held(player).isEmpty()) {
            return;
        }
        PlaybackDeviceBlockEntity be = SoundDeviceLink.ownedDevice(player.server, player.getUUID(), pos);
        if (be != null) {
            be.setDeviceName(SoundDeviceRegistry.sanitizeName(name));
        }
    }

    /** The range board in hand: its six face ranges (0..15 each; the clamp stays the item's last word). */
    private static void rangeBoard(ServerPlayer player, List<Object> args) {
        ItemStack stack = player.getItemInHand(InteractionHand.valueOf((String) args.get(0)));
        if (!stack.is(ModItems.RANGE_BOARD.get())) {
            return;
        }
        List<Integer> valid = new ArrayList<>(6);
        for (int i = 1; i <= 6; i++) {
            valid.add(Math.max(0, Math.min(15, (Integer) args.get(i))));
        }
        stack.set(ModDataComponents.ATTENUATION_RANGES, valid);
    }

    /** dim, x, y, z from {@code args} at {@code from}; null for a dimension id that does not parse. */
    @Nullable
    private static GlobalPos globalPos(List<Object> args, int from) {
        ResourceLocation dim = ResourceLocation.tryParse((String) args.get(from));
        return dim == null ? null
                : GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dim), blockPos(args, from + 1));
    }

    private static BlockPos blockPos(List<Object> args, int from) {
        return new BlockPos((Integer) args.get(from), (Integer) args.get(from + 1), (Integer) args.get(from + 2));
    }

    /**
     * {@code s} as the codec carries it (NFC), cut at a code point so its UTF-8 fits {@code maxBytes}. A string
     * over its declaration makes the host refuse the whole value, and these are strings a player controls - a
     * device name, a medium's file name - so none of theirs can make a push throw. (The old list payload
     * refused a file name past 128 characters on the way out, so an owner with such a medium got no list.)
     */
    static String fit(String s, int maxBytes) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFC);
        if (n.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return n;
        }
        StringBuilder out = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < n.length(); ) {
            int cp = n.codePointAt(i);
            int len = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + len > maxBytes) {
                break;
            }
            out.appendCodePoint(cp);
            bytes += len;
            i += Character.charCount(cp);
        }
        return out.toString();
    }
}
