package com.spatialaudiosystem.server;

import com.spatialaudiosystem.SpatialAudioSystem;
import com.spatialaudiosystem.audio.AudioStorage;
import com.spatialaudiosystem.audio.PlaybackDelivery;
import com.spatialaudiosystem.audio.PlaybackSessionRegistry;
import com.spatialaudiosystem.item.ModDataComponents;
import com.spatialaudiosystem.item.ModItems;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * A self-driving pass over the endless-playback behaviour, for real-hardware verification.
 *
 * <p>Run with {@code /sas-verify}, or arm it for one named player by writing their name into
 * {@code sas-verify.request} in the server directory. It puts a listener outside the range of an
 * endless sound, starts it, brings them into range, and writes what happened to the
 * {@code SAS-Verify} logger, ending with one completion line. The tone then keeps playing for a
 * bounded listening window before the pass stops it itself; {@code /sas-verify stop} ends the
 * whole thing early. No sound outlives the pass that started it.
 *
 * <p><b>What it deliberately no longer does.</b> An earlier version put its subject in spectator so
 * a teleport could not suffocate them, and hopped dimensions to check re-delivery. Both are gone.
 * Borrowing someone's game mode created several ways to strand them in it — a crash, a stop, a
 * second pass overwriting the note of what to give back — and the machinery to make that safe grew
 * larger than the behaviour it was verifying. Teleports land on the surface instead, and the
 * dimension case is covered by {@code changingDimensionDropsTheDeliveryRecord} and
 * {@code forgettingAPlayerMakesSoundsPendingAgain}, which do not need to move a person through the
 * Nether to establish it.
 *
 * <p>The audio is a one-second tone generated here rather than a file on disk, so the pass needs no
 * setup, and so the loop comes round three times inside one phase.
 */
@EventBusSubscriber(modid = SpatialAudioSystem.MOD_ID)
public final class SasVerifyPass {

    private static final org.slf4j.Logger SIGNAL =
            org.slf4j.LoggerFactory.getLogger("SAS-Verify");

    /** Attenuation range of the sound under test. Far is outside it, near is inside. */
    private static final int RANGE = 8;
    private static final int FAR_OFFSET = 40;

    /** Ticks between phases. The loop is one second, so this leaves room for three passes. */
    private static final int PHASE_TICKS = 100;   // 5 s

    /**
     * File naming the one player a pass may start for on join.
     *
     * <p>It carries a name because without one it fires for whoever logs in first, and on a shared
     * test server that is somebody who did not ask to be teleported. An empty file names nobody
     * rather than everybody: the wildcard is the behaviour being removed, not the default.
     */
    private static final String ARM_FILE = "sas-verify.request";

    // Every way this class declines to start says "refused", so a driver can ask one question
    // rather than knowing each phrasing. Two of them used to be worded differently, and a run
    // that hit them waited out its whole deadline and then blamed a timeout.

    private SasVerifyPass() {}

    private static Pass active;
    /**
     * Ticks the tone keeps playing after the verification phases, for a person to listen to.
     *
     * <p>Inside the pass on purpose. Leaving the sound running past the end meant its identity
     * had to be held in a static the pass no longer owned, and every version of that was a
     * single slot a second pass overwrote -- orphaning the first sound with nothing able to name
     * it. Bounding the window here means no sound outlives the pass that started it, so there
     * is nothing to hold.
     */
    private static final int LISTEN_TICKS = 2400;   // 2 minutes

    /** The phase whose wait is the listening window rather than an ordinary phase gap. */
    private static final int LISTENING_PHASE = 5;

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("sas-verify")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("stop").executes(ctx -> {
                    // Ends the running pass early, tone included. There is nothing else to
                    // stop: a pass always stops its own sound before it finishes.
                    Pass pass = active;
                    active = null;
                    if (pass == null) {
                        ctx.getSource().sendFailure(Component.literal("sas-verify: nothing running"));
                        return 0;
                    }
                    pass.stopSound();
                    ctx.getSource().sendSuccess(() -> Component.literal("sas-verify: stopped"), false);
                    return 1;
                }))
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendFailure(Component.literal("sas-verify needs a player"));
                        return 0;
                    }
                    return start(player, "command") ? 1 : 0;
                }));
    }

    /**
     * Starts a pass, unless one is already running.
     *
     * <p>Refusing matters: a second start would drop the first {@code Pass}, leaving its sound
     * running with nothing tracking it and {@code /sas-verify stop} unable to reach it.
     *
     * @param via {@code join} or {@code command} -- which call site this is, logged on refusal
     *            because the two differ in whether the arm marker has already been consumed.
     */
    private static boolean start(ServerPlayer player, String via) {
        if (active != null) {
            // via is the whole point of this line for a driver: the two call sites differ in
            // whether the arm marker was already consumed. From a join it was (deleted just
            // above), so no later join can fire that pass and the run waiting on it is dead;
            // from the command nothing was consumed and that run can still start. The refusal
            // is otherwise identical, and a driver that cannot tell them apart has to guess --
            // it either kills a run that would have passed or waits out its full deadline.
            SIGNAL.warn("refused reason=already-running via={} for={}",
                    via, player.getGameProfile().getName());
            return false;
        }
        BlockPos device = player.blockPosition().above();
        active = new Pass(player, device);
        SIGNAL.info("begin device={},{},{} player={} range={}",
                device.getX(), device.getY(), device.getZ(),
                player.getGameProfile().getName(), RANGE);
        return true;
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        java.nio.file.Path marker = player.server.getServerDirectory().resolve(ARM_FILE);
        if (!java.nio.file.Files.isRegularFile(marker)) return;

        String wanted;
        try {
            wanted = java.nio.file.Files.readString(marker).strip();
        } catch (java.io.IOException e) {
            SIGNAL.warn("refused reason=unreadable-marker file={}", ARM_FILE);
            return;
        }
        if (wanted.isEmpty() || !wanted.equals(player.getGameProfile().getName())) {
            // Logged, unlike the unarmed case above: this one means somebody asked for a pass
            // and named an account that is not the one signing in, which a driver otherwise
            // waits out to its full deadline and then blames on a timeout.
            SIGNAL.warn("refused reason=not-the-named-player wanted={} joined={}",
                    wanted.isEmpty() ? "(none)" : wanted, player.getGameProfile().getName());
            return;
        }

        try {
            java.nio.file.Files.delete(marker);
        } catch (java.io.IOException e) {
            SIGNAL.warn("refused reason=undeletable-marker file={}", ARM_FILE);
            return;
        }
        start(player, "join");
    }

    @SubscribeEvent
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        // In single player the JVM outlives the world. A pass left running would resume on the
        // next world's ticks still holding the dead ServerLevel, register a sound under it, and
        // then send every stop to that level's empty player list -- the orphan this class was
        // restructured to make impossible. ServerTickHandler clears the playback registry here
        // for the same reason.
        active = null;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        Pass pass = active;
        if (pass == null) return;
        if (pass.advance(event.getServer())) active = null;
    }

    /** The scripted situations, one per phase, driven off the server tick. */
    private static final class Pass {
        private final UUID playerId;
        private final BlockPos device;
        private final ServerLevel level;
        /** Where the subject stood when the pass began -- somewhere they demonstrably can. */
        private final net.minecraft.world.phys.Vec3 origin;
        private int phase;
        private int ticks;
        private long playbackId = PlaybackSessionRegistry.NO_PLAYBACK;

        Pass(ServerPlayer player, BlockPos device) {
            this.playerId = player.getUUID();
            this.device = device;
            this.level = player.serverLevel();
            this.origin = player.position();
        }


        /** Stops this pass's tone. Safe to call twice: the registry ignores the second. */
        void stopSound() {
            belugalab.sas.api.SasApi.stopAudio(level, device);
            // stopAudio returns void and logs nothing on any branch, so a line saying "stopped"
            // said only that this method was entered -- it would have read the same if the stop
            // had been dropped. Asking the registry afterwards gives the line something it can
            // be wrong about. It is a statement about the server's session, not about whether
            // a particular client's line went quiet; nothing here can see that.
            boolean gone = PlaybackSessionRegistry.currentId(level, device)
                    == PlaybackSessionRegistry.NO_PLAYBACK;
            SIGNAL.info("stopped device={},{},{} session={}",
                    device.getX(), device.getY(), device.getZ(), gone ? "gone" : "STILL-PRESENT");
        }

        /** @return true when the pass is over. */
        boolean advance(MinecraftServer server) {
            // The last wait is the listening window, which is longer than a phase: it is there
            // for a person to hear the loop point, which is the one thing no log can answer.
            int budget = phase == LISTENING_PHASE ? LISTEN_TICKS : PHASE_TICKS;
            if (++ticks < budget && phase > 0) return false;
            ticks = 0;

            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                SIGNAL.warn("aborted reason=player-gone phase={}", phase);
                stopSound();
                return true;
            }

            switch (phase++) {
                case 0 -> {
                    // Outside the range when the sound starts, so the initial send has to skip
                    // them. Landing on the surface rather than at the device's own Y, which forty
                    // blocks away is as likely to be inside a hill as in open air.
                    teleportToSurface(player, device.getX() + FAR_OFFSET, device.getZ());
                    SIGNAL.info("phase=1-placed-far offset={}", FAR_OFFSET);
                }
                case 1 -> {
                    playbackId = startEndlessTone(server);
                    SIGNAL.info("phase=2-started id={}", String.format("%016x", playbackId));
                }
                case 2 -> {
                    // Back to the exact spot they were standing on when the pass began. An
                    // offset from the device is not the same thing -- it is a block nobody has
                    // stood in, which is the hazard the removed spectator swap used to cover.
                    player.teleportTo(origin.x, origin.y, origin.z);
                    SIGNAL.info("phase=3-walked-in back-to-origin=true");
                }
                case 3 -> SIGNAL.info("phase=4-listening");
                case 4 ->
                    // Written while the tone is still playing, so a driver that returns here can
                    // tell someone to listen. The pass keeps ticking through the window and stops
                    // the sound itself, which is why nothing has to remember it afterwards.
                    SIGNAL.info("complete device={},{},{} id={} phases={} listen-window-ticks={}",
                            device.getX(), device.getY(), device.getZ(),
                            String.format("%016x", playbackId), phase - 1, LISTEN_TICKS);
                default -> {
                    stopSound();
                    return true;
                }
            }
            return false;
        }

        private void teleportToSurface(ServerPlayer player, int x, int z) {
            BlockPos surface = level.getHeightmapPos(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
            player.teleportTo(x + 0.5, surface.getY(), z + 0.5);
        }

        private long startEndlessTone(MinecraftServer server) {
            ItemStack medium = new ItemStack(ModItems.RECORDING_MEDIUM.get());
            UUID audioId = AudioStorage.save(server, oneSecondTone());
            medium.set(ModDataComponents.AUDIO_ID, audioId);
            medium.set(ModDataComponents.AUDIO_FORMAT, "wav");
            medium.set(ModDataComponents.AUDIO_FILE_NAME, "sas-verify-tone.wav");

            int[] ranges = new int[6];
            java.util.Arrays.fill(ranges, RANGE);
            return PlaybackDelivery.start(level, device, medium, "wav", null, null, true, ranges, true);
        }
    }

    /**
     * One second of 16-bit mono PCM at 44.1 kHz, as a RIFF/WAVE file.
     *
     * <p>Generated rather than read so the pass needs no asset, and short so the loop point comes
     * round three times within one phase.
     */
    static byte[] oneSecondTone() {
        int rate = 44100;
        int samples = rate;
        ByteBuffer pcm = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < samples; i++) {
            pcm.putShort((short) (Math.sin(2 * Math.PI * 440 * i / rate) * 8000));
        }
        byte[] data = pcm.array();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes());
        header.putInt(36 + data.length);
        header.put("WAVE".getBytes());
        header.put("fmt ".getBytes());
        header.putInt(16);                       // PCM chunk size
        header.putShort((short) 1);              // PCM
        header.putShort((short) 1);              // mono
        header.putInt(rate);
        header.putInt(rate * 2);                 // byte rate
        header.putShort((short) 2);              // block align
        header.putShort((short) 16);             // bits per sample
        header.put("data".getBytes());
        header.putInt(data.length);
        out.writeBytes(header.array());
        out.writeBytes(data);
        return out.toByteArray();
    }
}
