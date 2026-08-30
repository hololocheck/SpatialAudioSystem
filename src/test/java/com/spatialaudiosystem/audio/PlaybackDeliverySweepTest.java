package com.spatialaudiosystem.audio;

import com.spatialaudiosystem.network.ClientPlayAudioPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The delivery sweep (SAS-AUDIO-009).
 *
 * <p>The registry knows who has not been sent an active sound and {@link SpatialGain} knows who
 * can hear it; this is the glue that puts the two together and actually sends. It is worth its
 * own test because the failure it exists to prevent is silence, which no other client can show,
 * and because glue is where a correct predicate and a correct registry can still be wired up the
 * wrong way round.
 *
 * <p>The sweep is driven directly rather than through a server tick: what is under test is the
 * decision, not the timer.
 */
class PlaybackDeliverySweepTest {

    private static final BlockPos DEVICE = new BlockPos(0, 64, 0);
    private static final ResourceKey<net.minecraft.world.level.Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));

    private MockedStatic<PacketDistributor> packets;
    private MockedStatic<AudioStorage> storage;
    private List<Object> sent;
    private List<ServerPlayer> sentTo;

    private ServerLevel level;
    private MinecraftServer server;

    @BeforeEach
    void setUp() {
        PlaybackSessionRegistry.clear();
        sent = new ArrayList<>();
        sentTo = new ArrayList<>();

        server = mock(MinecraftServer.class);
        level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(OVERWORLD);
        when(level.getServer()).thenReturn(server);
        when(server.getAllLevels()).thenReturn(List.of(level));
        // Stubbed so a mutant that iterates server.overworld() instead of getAllLevels()
        // visits a real level and fails an assertion, rather than throwing on a null and
        // satisfying the control with a crash.
        when(server.overworld()).thenReturn(level);

        storage = mockStatic(AudioStorage.class);
        storage.when(() -> AudioStorage.loadForItem(any(), any())).thenReturn(new byte[64]);

        packets = mockStatic(PacketDistributor.class);
        // sendToPlayer takes the payload plus a varargs tail. The stub has to be written with
        // the same argument count the production call actually uses -- an extra any() matches a
        // call site that passes an extra payload, and silently matches nothing otherwise.
        packets.when(() -> PacketDistributor.sendToPlayer(any(), any()))
                .thenAnswer(inv -> {
                    sentTo.add(inv.getArgument(0));
                    sent.add(inv.getArgument(1));
                    return null;
                });
    }

    @AfterEach
    void tearDown() {
        packets.close();
        storage.close();
        PlaybackSessionRegistry.clear();
    }

    private static ServerPlayer playerAt(String name, double x) {
        ServerPlayer p = mock(ServerPlayer.class);
        UUID id = UUID.nameUUIDFromBytes(name.getBytes());
        when(p.getUUID()).thenReturn(id);
        // The delivery signal reads the profile. Stubbed so these tests take the same path a
        // real player does, rather than the null fallback that exists for safety only.
        when(p.getGameProfile()).thenReturn(new com.mojang.authlib.GameProfile(id, name));
        when(p.getX()).thenReturn(x);
        when(p.getY()).thenReturn(64.5);
        when(p.getZ()).thenReturn(0.5);
        return p;
    }

    /** An endless sound at DEVICE with no range board and an 8-block attenuation range. */
    private long startEndlessSound() {
        int[] ranges = new int[6];
        java.util.Arrays.fill(ranges, 8);
        return PlaybackSessionRegistry.begin(level, DEVICE, new PlaybackSessionRegistry.Replay(
                ItemStack.EMPTY, "ogg", null, null, true, ranges, true));
    }

    /** Audio chunk payloads sent so far. Without these a client prepares a download that
     *  never completes, so it never reaches AudioManager and the listener hears nothing. */
    private List<com.spatialaudiosystem.network.ClientAudioChunkPayload> chunkPayloads() {
        return sent.stream()
                .filter(com.spatialaudiosystem.network.ClientAudioChunkPayload.class::isInstance)
                .map(com.spatialaudiosystem.network.ClientAudioChunkPayload.class::cast)
                .toList();
    }

    /** Play payloads sent so far, which is what actually starts a sound on a client. */
    private List<ClientPlayAudioPayload> playPayloads() {
        return sent.stream()
                .filter(ClientPlayAudioPayload.class::isInstance)
                .map(ClientPlayAudioPayload.class::cast)
                .toList();
    }

    // ===== starting a sound (SAS-AUDIO-010) =====
    //
    // start() and sweep() treat an endless sound and a one-shot differently on purpose, and the
    // asymmetry is the point: an endless sound is filtered by audibility and stays offerable,
    // a one-shot is sent to everyone once and never again. Each half is pinned separately.

    private static int[] range8() {
        int[] r = new int[6];
        java.util.Arrays.fill(r, 8);
        return r;
    }

    private long startAt(boolean loop, ServerPlayer... players) {
        when(level.players()).thenReturn(List.of(players));
        return PlaybackDelivery.start(level, DEVICE, ItemStack.EMPTY, "ogg",
                null, null, true, range8(), loop);
    }

    @Test
    @DisplayName("SAS-AUDIO-010: starting an endless sound skips players who cannot hear it")
    void startingAnEndlessSoundSkipsPlayersOutOfRange() {
        ServerPlayer near = playerAt("near", 2.5);
        ServerPlayer far = playerAt("far", 20.5);

        startAt(true, near, far);

        // Without this filter every player in the dimension would hold a decode thread, an open
        // audio line and the whole file for as long as the server runs: the client has no
        // distance-based stop, only an explicit one.
        assertThat(sentTo).contains(near).doesNotContain(far);

        // What was sent, not merely that something was. sentTo is satisfied by the metadata
        // alone, so on its own it cannot see the initial send losing its audio or its endless
        // flag -- and the metadata only opens a download session the chunks have to complete.
        assertThat(playPayloads()).singleElement().satisfies(payload -> {
            assertThat(payload.pos()).isEqualTo(DEVICE);
            assertThat(payload.loop())
                    .as("a one-shot here reports completion and ends the sound for everyone")
                    .isTrue();
        });
        assertThat(chunkPayloads()).as("the audio itself must follow the metadata").isNotEmpty();
        assertThat(PlaybackSessionRegistry.pendingFor(OVERWORLD, near.getUUID()))
                .as("a listener who was just sent it must not be sent it again next sweep")
                .isEmpty();
    }

    @Test
    @DisplayName("SAS-AUDIO-010: starting a one-shot sends to everyone in the level")
    void startingAOneShotSendsToTheWholeLevel() {
        ServerPlayer near = playerAt("near", 2.5);
        ServerPlayer far = playerAt("far", 20.5);

        startAt(false, near, far);

        // A one-shot has only this chance to reach a listener; there is no later sweep for it.
        assertThat(sentTo).contains(near, far);
    }

    @Test
    @DisplayName("SAS-AUDIO-010: an endless sound stays offerable to players who arrive later")
    void anEndlessSoundIsRegisteredForLaterDelivery() {
        ServerPlayer near = playerAt("near", 2.5);
        long id = startAt(true, near);
        assertThat(id).isNotEqualTo(PlaybackSessionRegistry.NO_PLAYBACK);

        assertThat(PlaybackSessionRegistry.pendingFor(OVERWORLD, playerAt("later", 3.5).getUUID()))
                .hasSize(1);
    }

    @Test
    @DisplayName("SAS-AUDIO-010: a one-shot is never offered to anyone afterwards")
    void aOneShotIsNotRegisteredForLaterDelivery() {
        long id = startAt(false, playerAt("near", 2.5));
        assertThat(id).isNotEqualTo(PlaybackSessionRegistry.NO_PLAYBACK);

        // Restarting a short sound from the top for a late arrival puts them out of sync with
        // everyone still hearing the original, and a one-shot at a position with no block entity
        // has nothing that ever ends its session -- it would be pushed, long stale, forever.
        assertThat(PlaybackSessionRegistry.pendingFor(OVERWORLD, playerAt("later", 3.5).getUUID()))
                .isEmpty();
        assertThat(PlaybackSessionRegistry.currentId(level, DEVICE))
                .as("it is still an active sound, just not a replayable one")
                .isEqualTo(id);
    }

    @Test
    @DisplayName("SAS-AUDIO-010: a sound whose audio cannot be read starts nothing")
    void audioThatCannotBeReadStartsNothing() {
        storage.when(() -> AudioStorage.loadForItem(any(), any())).thenReturn(null);

        long id = startAt(true, playerAt("near", 2.5));

        assertThat(id).isEqualTo(PlaybackSessionRegistry.NO_PLAYBACK);
        assertThat(sent).isEmpty();
        assertThat(PlaybackSessionRegistry.currentId(level, DEVICE))
                .as("nothing registered, so no later arrival is offered a sound that does not exist")
                .isEqualTo(PlaybackSessionRegistry.NO_PLAYBACK);
    }

    @Test
    @DisplayName("SAS-AUDIO-009: a player inside the audible region is sent the running sound")
    void aPlayerInRangeIsSentTheSound() {
        long id = startEndlessSound();
        ServerPlayer near = playerAt("near", 2.5);          // 2 blocks away, range 8
        when(level.players()).thenReturn(List.of(near));

        PlaybackDelivery.sweep(server);

        assertThat(playPayloads()).singleElement().satisfies(p -> {
            assertThat(p.pos()).isEqualTo(DEVICE);
            assertThat(p.playbackId()).isEqualTo(id);
            assertThat(p.loop()).as("the endless flag has to reach the late arrival too").isTrue();
        });
        assertThat(sentTo).contains(near);
        // The metadata only opens a download session; without the chunks it never completes
        // and AudioManager is never reached. Asserted here because every other assertion in
        // this class reads play payloads, so a sendChunked that did nothing would be invisible.
        assertThat(chunkPayloads()).as("the audio itself must follow the metadata").isNotEmpty();
    }

    @Test
    @DisplayName("SAS-AUDIO-009: a player outside the audible region is sent nothing")
    void aPlayerOutOfRangeIsSentNothing() {
        startEndlessSound();
        // 20 blocks out with a range of 8: the gain is zero, so there is nothing to hear and
        // nothing worth transferring.
        ServerPlayer far = playerAt("far", 20.5);
        when(level.players()).thenReturn(List.of(far));

        PlaybackDelivery.sweep(server);

        assertThat(playPayloads()).isEmpty();
        assertThat(sentTo).isEmpty();
    }

    @Test
    @DisplayName("SAS-AUDIO-009: walking into range later is enough; no event is needed")
    void walkingIntoRangeIsEnough() {
        startEndlessSound();
        ServerPlayer wanderer = playerAt("wanderer", 20.5);
        when(level.players()).thenReturn(List.of(wanderer));

        PlaybackDelivery.sweep(server);
        assertThat(playPayloads()).as("still out of range").isEmpty();

        // No join, no dimension change: the player simply moved. This is the case three separate
        // event handlers would each have missed.
        when(wanderer.getX()).thenReturn(2.5);
        PlaybackDelivery.sweep(server);

        assertThat(playPayloads()).hasSize(1);
    }

    @Test
    @DisplayName("SAS-AUDIO-009: a sound is not sent to the same player twice")
    void aSoundIsNotSentTwice() {
        startEndlessSound();
        ServerPlayer near = playerAt("near", 2.5);
        when(level.players()).thenReturn(List.of(near));

        PlaybackDelivery.sweep(server);
        PlaybackDelivery.sweep(server);
        PlaybackDelivery.sweep(server);

        assertThat(playPayloads())
                .as("an endless sound stays active forever; re-sending every sweep would be a flood")
                .hasSize(1);
    }

    @Test
    @DisplayName("SAS-AUDIO-009: after the client forgets its sounds, the sweep sends them again")
    void afterForgettingThePlayerIsServedAgain() {
        startEndlessSound();
        ServerPlayer near = playerAt("near", 2.5);
        when(level.players()).thenReturn(List.of(near));

        PlaybackDelivery.sweep(server);
        assertThat(playPayloads()).hasSize(1);

        // The client stops and forgets every sound when its level is replaced -- logging out,
        // changing dimension, and respawning into a different one. (A same-dimension respawn
        // does not replace it; the record is dropped there too, harmlessly, because only
        // endless sounds are replayable and a loop never reports completion.)
        PlaybackSessionRegistry.forgetPlayer(near.getUUID());
        PlaybackDelivery.sweep(server);

        assertThat(playPayloads()).hasSize(2);
    }

    // ===== the lifecycle handlers (SAS-AUDIO-011) =====
    //
    // Every path that replaces a client's level makes it stop and forget its sounds, so the
    // server's delivery record has to be dropped there or the player is never offered the sound
    // again. Each handler is invoked here rather than asserted about in prose: the respawn one
    // was removed once on a false premise, and nothing went red, because the tests around it
    // called forgetPlayer themselves.

    /** Arms a delivered endless sound for ALICE and returns the dimension it is in. */
    private void aDeliveredSoundFor(java.util.UUID player) {
        long id = startEndlessSound();
        PlaybackSessionRegistry.markDelivered(OVERWORLD, DEVICE, id, player);
        assertThat(PlaybackSessionRegistry.pendingFor(OVERWORLD, player)).isEmpty();
    }

    private static <E extends net.neoforged.neoforge.event.entity.player.PlayerEvent> E eventFor(
            Class<E> type, java.util.UUID player) {
        E event = mock(type);
        ServerPlayer entity = mock(ServerPlayer.class);
        when(entity.getUUID()).thenReturn(player);
        when(event.getEntity()).thenReturn(entity);
        return event;
    }

    @Test
    @DisplayName("SAS-AUDIO-011: respawning drops the delivery record")
    void respawnDropsTheDeliveryRecord() {
        java.util.UUID p = UUID.nameUUIDFromBytes("respawner".getBytes());
        aDeliveredSoundFor(p);

        // PlayerList.respawn fires only the respawn event -- a cross-dimension respawn replaces
        // the client level with no dimension-change event to notice it.
        com.spatialaudiosystem.server.ServerTickHandler.onPlayerRespawn(
                eventFor(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent.class, p));

        assertThat(PlaybackSessionRegistry.pendingFor(OVERWORLD, p)).hasSize(1);
    }

    @Test
    @DisplayName("SAS-AUDIO-011: changing dimension drops the delivery record")
    void changingDimensionDropsTheDeliveryRecord() {
        java.util.UUID p = UUID.nameUUIDFromBytes("traveller".getBytes());
        aDeliveredSoundFor(p);

        com.spatialaudiosystem.server.ServerTickHandler.onPlayerChangedDimension(
                eventFor(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent.class, p));

        assertThat(PlaybackSessionRegistry.pendingFor(OVERWORLD, p)).hasSize(1);
    }

    @Test
    @DisplayName("SAS-AUDIO-011: logging in drops any delivery record left over")
    void loggingInDropsTheDeliveryRecord() {
        java.util.UUID p = UUID.nameUUIDFromBytes("returner".getBytes());
        aDeliveredSoundFor(p);

        com.spatialaudiosystem.server.ServerTickHandler.onPlayerLoggedIn(
                eventFor(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent.class, p));

        assertThat(PlaybackSessionRegistry.pendingFor(OVERWORLD, p)).hasSize(1);
    }

    @Test
    @DisplayName("SAS-AUDIO-011: logging out drops the delivery record")
    void loggingOutDropsTheDeliveryRecord() {
        java.util.UUID p = UUID.nameUUIDFromBytes("leaver".getBytes());
        aDeliveredSoundFor(p);

        com.spatialaudiosystem.server.ServerTickHandler.onPlayerLoggedOut(
                eventFor(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent.class, p));

        assertThat(PlaybackSessionRegistry.pendingFor(OVERWORLD, p)).hasSize(1);
    }

    @Test
    @DisplayName("SAS-AUDIO-011: the handler class is registered on the server-side game bus")
    void theHandlerClassIsRegisteredOnTheBus() {
        // The four tests above prove each handler does the right thing when called. Nothing in
        // them says the game ever calls it, and what does the calling is not on the methods: it
        // is the single class-level @EventBusSubscriber. There is no EVENT_BUS.register anywhere
        // in src/main, so deleting that one line — or narrowing it to Dist.CLIENT, the form used
        // one directory over in ClientLifecycleHandler — silently stops every handler here,
        // including the delivery sweep, while every other test stays green.
        net.neoforged.fml.common.EventBusSubscriber subscriber =
                com.spatialaudiosystem.server.ServerTickHandler.class
                        .getAnnotation(net.neoforged.fml.common.EventBusSubscriber.class);

        assertThat(subscriber).as("nothing else registers these handlers").isNotNull();
        // AutomaticEventSubscriber filters on three terms, and this is the third: it compares
        // modid() against the mod container's id and, when they differ, skips the class with no
        // log above debug and no throw. A typo here is therefore silent -- the whole class stops
        // running and nothing anywhere says so.
        assertThat(subscriber.modid())
                .as("a modid that does not match the container is a silent skip")
                .isEqualTo(com.spatialaudiosystem.SpatialAudioSystem.MOD_ID);
        // Stated, but not evidence: putting these handlers on the mod-loading bus stops the test
        // JVM from starting at all, so no mutation can make this line fail rather than make the
        // whole suite die. The wrong bus is caught -- far more loudly -- by that.
        assertThat(subscriber.bus())
                .as("these are game events, not mod-loading ones")
                .isEqualTo(net.neoforged.fml.common.EventBusSubscriber.Bus.GAME);
        // Both dists, not "contains DEDICATED_SERVER". AutomaticEventSubscriber registers the
        // class only when value() contains FMLEnvironment.dist, and in single player that is
        // CLIENT -- so narrowing to the server alone silently kills all of this on the
        // integrated server, which is where most of these devices are actually used. Seven
        // sibling classes in this tree do narrow with an explicit value, so the edit is a
        // plausible one; asserting containment in one direction would not see it.
        assertThat(subscriber.value())
                .as("both the integrated server (dist CLIENT) and a dedicated one must run these")
                .containsExactlyInAnyOrder(
                        net.neoforged.api.distmarker.Dist.CLIENT,
                        net.neoforged.api.distmarker.Dist.DEDICATED_SERVER);
    }

    @Test
    @DisplayName("SAS-AUDIO-011: the server tick actually runs the delivery sweep")
    void theServerTickRunsTheDeliverySweep() {
        // Every other test here drives PlaybackDelivery.sweep directly, so the one line in
        // onServerTick that calls it is reached by none of them: delete that line, or raise the
        // interval past what the counter reaches, and late delivery is dead with the whole suite
        // green. This is the only test that goes through the handler.
        resetTickCounters();
        ServerPlayer near = playerAt("ticker", 2.5);
        when(level.players()).thenReturn(List.of(near));
        startEndlessSound();
        assertThat(playPayloads()).as("nothing sent before the ticks").isEmpty();

        net.neoforged.neoforge.event.tick.ServerTickEvent.Post event =
                mock(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class);
        when(event.getServer()).thenReturn(server);
        for (int i = 0; i < 20; i++) {
            com.spatialaudiosystem.server.ServerTickHandler.onServerTick(event);
        }

        assertThat(playPayloads())
                .as("20 ticks is the sweep interval; the sound should have reached the listener")
                .hasSize(1);
    }

    /** Zero the handler's static counters, so one test's ticks cannot decide another's. */
    private static void resetTickCounters() {
        for (String field : new String[]{"sweepCounter", "tickCounter"}) {
            try {
                java.lang.reflect.Field f =
                        com.spatialaudiosystem.server.ServerTickHandler.class.getDeclaredField(field);
                f.setAccessible(true);
                f.setInt(null, 0);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("could not reset " + field, e);
            }
        }
    }

    @Test
    @DisplayName("SAS-AUDIO-011: every handler this feature depends on carries @SubscribeEvent")
    void allFourHandlersAreSubscribed() {
        // Invoking a method proves it does the right thing, not that the bus will pick it up.
        // Read off the compiled class rather than the source text, so an unannotated handler is
        // caught as well as a deleted one. A rename is not checked and does not need to be: the
        // bus dispatches on the annotation and the parameter type, not on the name.
        //
        // onServerTick is in here because it is the only caller of PlaybackDelivery.sweep: the
        // sweep tests drive sweep() directly, so without this line the entire late-delivery
        // feature could be unsubscribed and every test would still pass.
        java.util.Map<String, Class<?>> expected = java.util.Map.of(
                "onPlayerRespawn", net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent.class,
                "onPlayerChangedDimension", net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent.class,
                "onPlayerLoggedIn", net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent.class,
                "onPlayerLoggedOut", net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent.class,
                "onServerTick", net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class,
                "onServerStopped", net.neoforged.neoforge.event.server.ServerStoppedEvent.class);

        java.util.Set<Class<?>> subscribed = new java.util.HashSet<>();
        for (java.lang.reflect.Method m
                : com.spatialaudiosystem.server.ServerTickHandler.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(net.neoforged.bus.api.SubscribeEvent.class)
                    && m.getParameterCount() == 1) {
                subscribed.add(m.getParameterTypes()[0]);
            }
        }

        expected.forEach((name, type) -> assertThat(subscribed)
                .as("%s must stay subscribed: %s replaces the client's level", name, type.getSimpleName())
                .contains(type));
    }

    @Test
    @DisplayName("SAS-AUDIO-009: the sweep covers every dimension, not just the overworld")
    void theSweepCoversEveryDimension() {
        // Every other test here gives the server a single level, so replacing getAllLevels()
        // with server.overworld() would keep them all green while delivery died everywhere else.
        // "arriving from another dimension" is half of what this feature was asked for, so the
        // second level is the sample point that makes the loop mean something.
        ServerLevel nether = mock(ServerLevel.class);
        ResourceKey<net.minecraft.world.level.Level> netherKey = ResourceKey.create(
                Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether"));
        when(nether.dimension()).thenReturn(netherKey);
        when(nether.getServer()).thenReturn(server);
        when(server.getAllLevels()).thenReturn(List.of(level, nether));

        ServerPlayer inNether = playerAt("nether-dweller", 2.5);
        when(level.players()).thenReturn(List.of());
        when(nether.players()).thenReturn(List.of(inNether));

        int[] ranges = new int[6];
        java.util.Arrays.fill(ranges, 8);
        long id = PlaybackSessionRegistry.begin(nether, DEVICE, new PlaybackSessionRegistry.Replay(
                ItemStack.EMPTY, "ogg", null, null, true, ranges, true));

        PlaybackDelivery.sweep(server);

        assertThat(playPayloads())
                .as("a sound in the nether must reach the player standing next to it")
                .singleElement()
                .satisfies(p -> assertThat(p.playbackId()).isEqualTo(id));
        assertThat(sentTo).contains(inNether);
    }

    @Test
    @DisplayName("SAS-AUDIO-009: a sweep with nothing playing sends nothing")
    void anIdleSweepSendsNothing() {
        ServerPlayer near = playerAt("near", 2.5);
        when(level.players()).thenReturn(List.of(near));

        PlaybackDelivery.sweep(server);

        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("SAS-AUDIO-009: a sound whose audio has gone is not retried every sweep")
    void missingAudioIsNotRetriedForever() {
        startEndlessSound();
        ServerPlayer near = playerAt("near", 2.5);
        UUID nearId = near.getUUID();
        when(level.players()).thenReturn(List.of(near));
        storage.when(() -> AudioStorage.loadForItem(any(), any())).thenReturn(null);

        PlaybackDelivery.sweep(server);
        PlaybackDelivery.sweep(server);

        assertThat(sent).as("nothing could be sent").isEmpty();
        assertThat(PlaybackSessionRegistry.pendingFor(OVERWORLD, nearId))
                .as("and it stopped asking, rather than reading the file once a second forever")
                .isEmpty();
    }
}
