package com.spatialaudiosystem.blockentity;

import com.spatialaudiosystem.audio.PlaybackSessionRegistry;
import com.spatialaudiosystem.network.ClientPlayAudioPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The endless play count (SAS-AUDIO-008).
 *
 * <p>{@code LOOP_FOREVER} is zero, one step past the largest finite count, and reached by the
 * same wheel that sets every other value. Three things about that can break without any visible
 * symptom, so each is pinned separately here: the wheel must <em>cycle</em> rather than clamp, a
 * saved zero must read back as endless while pre-1.0.6 data must not, and the flag has to
 * survive the wire.
 *
 * <p>The block entity is built without its constructor, as {@code PlaybackTimeoutP1Test} does,
 * so no registries are needed. That also means field initialisers never run — which is exactly
 * how the first version of {@code isLoopArmed} was caught reading a sentinel that was zero
 * instead of -1, and is why the arming test below constructs its state explicitly.
 *
 * <p><b>Mutation controls</b> live in {@code scripts/playback.mutation.py}; each edit named there
 * must turn the test named beside it red.
 */
class PlaybackLoopTest {

    private static final int MAX = PlaybackDeviceBlockEntity.MAX_PLAY_COUNT;
    private static final int ENDLESS = PlaybackDeviceBlockEntity.LOOP_FOREVER;

    // ===== helpers =====

    private static void set(Object target, String field, Object value) {
        try {
            Field f = PlaybackDeviceBlockEntity.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not set " + field, e);
        }
    }

    private static Object call(Object target, String method, Class<?>[] types, Object... args) {
        try {
            Method m = PlaybackDeviceBlockEntity.class.getDeclaredMethod(method, types);
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not call " + method, e);
        }
    }

    /** A device with {@code entries} rows, every count at 1, nothing armed. */
    private static PlaybackDeviceBlockEntity device(int entries) {
        PlaybackDeviceBlockEntity d = com.spatialaudiosystem.blockentity.TestDevices.newBare();
        int[] counts = new int[PlaybackDeviceBlockEntity.PLAYLIST_SIZE];
        Arrays.fill(counts, 1);
        set(d, "playCounts", counts);
        set(d, "entryCount", entries);
        set(d, "loopingEntry", -1);
        return d;
    }

    // ===== the wheel reaches the endless setting =====

    @Test
    @DisplayName("SAS-AUDIO-008: scrolling up past the largest count reaches the endless setting")
    void scrollingUpPastTheMaximumReachesEndless() {
        PlaybackDeviceBlockEntity d = device(1);
        d.setPlayCount(0, MAX);
        assertThat(d.getPlayCount(0)).isEqualTo(MAX);
        assertThat(d.isLoopEntry(0)).isFalse();

        // The screen sends a delta of one; the entity decides where the range wraps.
        d.setPlayCount(0, d.getPlayCount(0) + 1);
        assertThat(d.getPlayCount(0))
                .as("one step past %d is the endless setting, not a clamp back to %d", MAX, MAX)
                .isEqualTo(ENDLESS);
        assertThat(d.isLoopEntry(0)).isTrue();
    }

    @Test
    @DisplayName("SAS-AUDIO-008: scrolling up from the endless setting returns to one")
    void scrollingUpFromEndlessReturnsToOne() {
        PlaybackDeviceBlockEntity d = device(1);
        d.setPlayCount(0, ENDLESS);
        d.setPlayCount(0, d.getPlayCount(0) + 1);

        assertThat(d.getPlayCount(0)).isEqualTo(1);
        assertThat(d.isLoopEntry(0)).isFalse();
    }

    @Test
    @DisplayName("SAS-AUDIO-008: scrolling down from one reaches the endless setting")
    void scrollingDownFromOneReachesEndless() {
        PlaybackDeviceBlockEntity d = device(1);
        d.setPlayCount(0, 1);
        d.setPlayCount(0, d.getPlayCount(0) - 1);

        assertThat(d.getPlayCount(0))
                .as("the cycle is closed in both directions, not floored at one")
                .isEqualTo(ENDLESS);
    }

    @Test
    @DisplayName("SAS-AUDIO-008: scrolling down from the endless setting reaches the largest count")
    void scrollingDownFromEndlessReachesTheMaximum() {
        PlaybackDeviceBlockEntity d = device(1);
        d.setPlayCount(0, ENDLESS);
        d.setPlayCount(0, d.getPlayCount(0) - 1);

        assertThat(d.getPlayCount(0)).isEqualTo(MAX);
    }

    @Test
    @DisplayName("SAS-AUDIO-008: every finite step in between is still reachable")
    void everyFiniteStepIsStillReachable() {
        // Checked one value at a time rather than as a single round trip: a cycle that skipped
        // a value in the middle would still return to where it started.
        PlaybackDeviceBlockEntity d = device(1);
        d.setPlayCount(0, 1);
        for (int expected = 2; expected <= MAX; expected++) {
            d.setPlayCount(0, d.getPlayCount(0) + 1);
            assertThat(d.getPlayCount(0)).as("stepping up to %d", expected).isEqualTo(expected);
        }
    }

    // ===== reading a saved device =====

    @Test
    @DisplayName("SAS-AUDIO-008: a saved endless setting reads back as endless")
    void aSavedEndlessSettingSurvivesReload() {
        PlaybackDeviceBlockEntity d = device(2);
        CompoundTag tag = new CompoundTag();
        int[] saved = new int[PlaybackDeviceBlockEntity.PLAYLIST_SIZE];
        Arrays.fill(saved, 1);
        saved[1] = ENDLESS;
        tag.putIntArray("playCounts", saved);

        call(d, "loadPlayCounts", new Class<?>[]{CompoundTag.class}, tag);

        assertThat(d.isLoopEntry(1)).as("entry 1 was saved endless").isTrue();
        assertThat(d.getPlayCount(0)).as("entry 0 was saved as one").isEqualTo(1);
    }

    @Test
    @DisplayName("SAS-AUDIO-008: a device saved before 1.0.6 reads exactly as it did")
    void preLoopWorldsAreUnchanged() {
        // The migration check has to build the old shape itself. Loading a device this version
        // wrote would exercise the new writer against the new reader and could not see a change
        // in how old data is interpreted.
        //
        // Before 1.0.6 every write went through a clamp with a floor of one, so a stored zero
        // was unreachable -- which is what makes zero safe to spend on the endless setting.
        PlaybackDeviceBlockEntity legacy = device(3);
        CompoundTag old = new CompoundTag();
        old.putIntArray("playCounts", new int[]{1, 5, MAX});

        call(legacy, "loadPlayCounts", new Class<?>[]{CompoundTag.class}, old);

        assertThat(legacy.getPlayCount(0)).isEqualTo(1);
        assertThat(legacy.getPlayCount(1)).isEqualTo(5);
        assertThat(legacy.getPlayCount(2)).isEqualTo(MAX);
        assertThat(legacy.isLoopEntry(0)).isFalse();
        assertThat(legacy.isLoopEntry(1)).isFalse();
        assertThat(legacy.isLoopEntry(2)).isFalse();

        // Older still: no counts key at all.
        PlaybackDeviceBlockEntity ancient = device(3);
        call(ancient, "loadPlayCounts", new Class<?>[]{CompoundTag.class}, new CompoundTag());
        for (int i = 0; i < 3; i++) {
            assertThat(ancient.getPlayCount(i)).as("entry %d defaults to one, not to endless", i).isEqualTo(1);
            assertThat(ancient.isLoopEntry(i)).isFalse();
        }
    }

    @Test
    @DisplayName("SAS-AUDIO-008: a stored count above the maximum is clamped, not read as endless")
    void anOutOfRangeStoredCountIsClamped() {
        PlaybackDeviceBlockEntity d = device(1);
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("playCounts", new int[]{9999});

        call(d, "loadPlayCounts", new Class<?>[]{CompoundTag.class}, tag);

        assertThat(d.getPlayCount(0)).isEqualTo(MAX);
        assertThat(d.isLoopEntry(0)).isFalse();
    }

    // ===== arming =====

    @Test
    @DisplayName("SAS-AUDIO-008: arming is re-derived from the schedule, not trusted from the index")
    void armingIsRederivedFromTheSchedule() {
        PlaybackDeviceBlockEntity d = device(2);
        d.setPlayCount(1, ENDLESS);
        d.armLoop(1);
        assertThat(call(d, "isLoopArmed", new Class<?>[]{})).as("entry 1 is endless and exists").isEqualTo(true);

        // The index alone still says "entry 1" after the entry stops being endless. Anything
        // that reads the index without the rest would keep restarting a sound the schedule no
        // longer asks for.
        int[] counts = new int[PlaybackDeviceBlockEntity.PLAYLIST_SIZE];
        Arrays.fill(counts, 1);
        set(d, "playCounts", counts);
        assertThat(call(d, "isLoopArmed", new Class<?>[]{}))
                .as("entry 1 is no longer endless")
                .isEqualTo(false);
    }

    @Test
    @DisplayName("SAS-AUDIO-008: an armed index past the end of the schedule is not armed")
    void anIndexPastTheScheduleIsNotArmed() {
        PlaybackDeviceBlockEntity d = device(1);
        // Entry 3's count is left endless on purpose. If it were finite, the endless term would
        // also be false and this would pass with the bound removed -- the sample point has to be
        // one where the bound is the only thing deciding.
        d.setPlayCount(3, ENDLESS);
        d.armLoop(3);   // entries were removed under it, leaving one row

        assertThat(d.isLoopEntry(3)).as("the count itself is not what stops this").isTrue();
        assertThat(call(d, "isLoopArmed", new Class<?>[]{})).isEqualTo(false);
    }

    @Test
    @DisplayName("SAS-AUDIO-008: an entity with no initialised fields is not armed")
    void anUninitialisedEntityIsNotArmed() {
        // Zero is both a valid entry index and the JVM's default, so a device whose fields never
        // ran their initialisers reads as "looping entry 0". It must not be armed, and asking
        // must not throw on the counts array that is also still null.
        PlaybackDeviceBlockEntity bare = com.spatialaudiosystem.blockentity.TestDevices.newBare();

        assertThat(call(bare, "isLoopArmed", new Class<?>[]{})).isEqualTo(false);
    }

    @Test
    @DisplayName("SAS-AUDIO-008: turning the endless setting off stops the sound it was producing")
    void turningEndlessOffStopsThePlayback() {
        PlaybackDeviceBlockEntity d = device(1);
        d.setPlayCount(0, ENDLESS);
        d.armLoop(0);
        set(d, "isPlaying", true);

        d.setPlayCount(0, d.getPlayCount(0) + 1);   // endless -> 1

        assertThat(d.getLoopingEntry()).as("disarmed").isEqualTo(-1);
        assertThat(d.isPlaying()).as("and the sound it was producing was stopped").isFalse();
    }

    // ===== restoring the loop after a restart or a chunk reload =====
    //
    // These exist because the first version of the restore could not run at all: it timed the
    // retry as "now - lastAttempt", with Long.MIN_VALUE meaning "never attempted", and that
    // subtraction wraps negative for every game time there is. Nothing was red, because no test
    // called tick() with a device that was actually armed -- the only tick test builds devices
    // with no entries, where isLoopArmed() is false and control never reaches the branch.

    /**
     * A device armed to loop entry 0, as a reload would leave it.
     *
     * <p>Its playlist row is empty, so the restore reaches {@code playMedia} and gives up there.
     * That is enough: what these tests ask is whether the branch is <em>reachable</em>, and the
     * retry stamp moves as soon as it is entered, before anything is played.
     */
    private static PlaybackDeviceBlockEntity armedDevice() {
        PlaybackDeviceBlockEntity d = device(1);
        d.setPlayCount(0, ENDLESS);
        set(d, "playlist", new net.neoforged.neoforge.items.ItemStackHandler(
                PlaybackDeviceBlockEntity.PLAYLIST_SIZE));
        set(d, "loopingEntry", 0);
        return d;
    }

    private static boolean readBoolean(Object target, String field) {
        try {
            java.lang.reflect.Field f = PlaybackDeviceBlockEntity.class.getDeclaredField(field);
            f.setAccessible(true);
            return f.getBoolean(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not read " + field, e);
        }
    }

    private static ServerLevel serverLevelAt(long gameTime) {
        ServerLevel level = org.mockito.Mockito.mock(ServerLevel.class);
        org.mockito.Mockito.when(level.isClientSide()).thenReturn(false);
        org.mockito.Mockito.when(level.getGameTime()).thenReturn(gameTime);
        org.mockito.Mockito.when(level.dimension()).thenReturn(
                ResourceKey.create(Registries.DIMENSION,
                        ResourceLocation.fromNamespaceAndPath("minecraft", "overworld")));
        return level;
    }

    /** True when this tick entered the restore branch, observed by the retry stamp moving. */
    private static boolean tickAttemptsRestore(PlaybackDeviceBlockEntity d, long gameTime) {
        long before = readLong(d, "nextLoopArmTick");
        PlaybackDeviceBlockEntity.tick(serverLevelAt(gameTime), BlockPos.ZERO, null, d);
        return readLong(d, "nextLoopArmTick") != before;
    }

    @Test
    @DisplayName("SAS-AUDIO-008: an armed device reaches the restore in a long-running world")
    void anArmedDeviceReachesRestoreAtALargeGameTime() {
        PlaybackSessionRegistry.clear();
        // The game time of a world that has been up for a while. The first version timed the
        // retry as "now - lastAttempt" with Long.MIN_VALUE meaning never, and that subtraction
        // wraps negative here -- the branch was unreachable at every game time there is.
        assertThat(tickAttemptsRestore(armedDevice(), 1_234_567L)).isTrue();
    }

    @Test
    @DisplayName("SAS-AUDIO-008: a fresh world's game time also reaches the restore")
    void aFreshWorldAlsoReachesTheRestore() {
        PlaybackSessionRegistry.clear();
        assertThat(tickAttemptsRestore(armedDevice(), 0L))
                .as("the other end of the range, so the check is not passing on one lucky value")
                .isTrue();
    }

    @Test
    @DisplayName("SAS-AUDIO-008: restore is retried on an interval, not on every tick")
    void restoreIsThrottled() {
        PlaybackSessionRegistry.clear();
        PlaybackDeviceBlockEntity d = armedDevice();
        long t = 1_000L;

        assertThat(tickAttemptsRestore(d, t)).as("first tick tries").isTrue();

        // The empty row disarms the device, so re-arm before each further tick: what is under
        // test is the interval, not whether the device stayed armed.
        set(d, "loopingEntry", 0);
        assertThat(tickAttemptsRestore(d, t + 1)).as("the very next tick must not").isFalse();
        set(d, "loopingEntry", 0);
        assertThat(tickAttemptsRestore(d, t + 19)).as("still inside the interval").isFalse();
        set(d, "loopingEntry", 0);
        assertThat(tickAttemptsRestore(d, t + 20)).as("and it tries again once it has passed").isTrue();
    }

    @Test
    @DisplayName("SAS-AUDIO-008: a device already sounding is not restarted")
    void aSoundingDeviceIsNotRestarted() {
        PlaybackSessionRegistry.clear();
        PlaybackDeviceBlockEntity d = armedDevice();
        ServerLevel level = serverLevelAt(1_000L);
        PlaybackSessionRegistry.begin(level, BlockPos.ZERO);   // something is playing here

        assertThat(tickAttemptsRestore(d, 1_000L))
                .as("the registry, not isPlaying, is what says the sound is live")
                .isFalse();
        PlaybackSessionRegistry.clear();
    }

    @Test
    @DisplayName("SAS-AUDIO-008: an armed row whose media is gone disarms instead of retrying forever")
    void anArmedRowWithNoMediaDisarms() {
        PlaybackSessionRegistry.clear();
        PlaybackDeviceBlockEntity d = armedDevice();

        tickAttemptsRestore(d, 1_000L);

        assertThat(d.getLoopingEntry())
                .as("nothing there to play, so it stops asking")
                .isEqualTo(-1);
    }

    private static long readLong(Object target, String field) {
        try {
            Field f = PlaybackDeviceBlockEntity.class.getDeclaredField(field);
            f.setAccessible(true);
            return f.getLong(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not read " + field, e);
        }
    }

    // ===== an endless sound must not outlive the thing producing it =====

    /** Set a field declared anywhere up the hierarchy (level and worldPosition live on BlockEntity). */
    private static void setInherited(Object target, String field, Object value) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            } catch (IllegalAccessException e) {
                throw new AssertionError("could not set " + field, e);
            }
        }
        throw new AssertionError("no such field anywhere in the hierarchy: " + field);
    }

    /** An armed, sounding device wired to a server level, so stopPlayback can run end to end. */
    private static PlaybackDeviceBlockEntity soundingDeviceOnAServer() {
        PlaybackDeviceBlockEntity d = armedDevice();
        ServerLevel level = serverLevelAt(1_000L);
        org.mockito.Mockito.when(level.players()).thenReturn(java.util.List.of());
        setInherited(d, "level", level);
        setInherited(d, "worldPosition", BlockPos.ZERO);
        // setChanged reads the block state; air short-circuits the neighbour update it would
        // otherwise do, which is not what these tests are about.
        net.minecraft.world.level.block.state.BlockState air =
                org.mockito.Mockito.mock(net.minecraft.world.level.block.state.BlockState.class);
        org.mockito.Mockito.when(air.isAir()).thenReturn(true);
        setInherited(d, "blockState", air);
        set(d, "isPlaying", true);
        return d;
    }

    @Test
    @DisplayName("SAS-AUDIO-008: taking the medium out of the looping row stops the sound")
    void emptyingTheLoopingRowStopsTheSound() {
        PlaybackSessionRegistry.clear();
        PlaybackDeviceBlockEntity d = soundingDeviceOnAServer();

        // removeEntry and swapEntries stop the sound because the armed index would come to name
        // other media. Emptying the slot in place changes neither the index nor the count, so
        // nothing else notices -- the sound would play on with its source gone.
        d.onPlaylistSlotChanged(0);

        assertThat(d.getLoopingEntry()).as("disarmed").isEqualTo(-1);
        assertThat(d.isPlaying()).as("and stopped").isFalse();
    }

    @Test
    @DisplayName("SAS-AUDIO-008: a change to some other row leaves the loop alone")
    void changingAnotherRowLeavesTheLoopAlone() {
        PlaybackSessionRegistry.clear();
        PlaybackDeviceBlockEntity d = soundingDeviceOnAServer();

        // The sample point that makes the row check mean something: without it, any playlist
        // edit would stop the ambience.
        d.onPlaylistSlotChanged(2);

        assertThat(d.getLoopingEntry()).isEqualTo(0);
        assertThat(d.isPlaying()).isTrue();
    }

    @Test
    @DisplayName("SAS-AUDIO-008: the playlist handler actually calls the row-changed hook")
    void thePlaylistHandlerCallsTheRowChangedHook() throws Exception {
        // The two tests above call onPlaylistSlotChanged directly, because the block entity is
        // built without its field initialisers and its real handler does not exist on such an
        // instance. That leaves the question those tests cannot answer: does anything call it?
        java.nio.file.Path src = null;
        for (java.nio.file.Path base = java.nio.file.Paths.get("").toAbsolutePath();
             base != null; base = base.getParent()) {
            java.nio.file.Path c = base.resolve(
                    "src/main/java/com/spatialaudiosystem/blockentity/PlaybackDeviceBlockEntity.java");
            if (java.nio.file.Files.isRegularFile(c)) { src = c; break; }
        }
        assertThat(src).as("source not found from " + java.nio.file.Paths.get("").toAbsolutePath())
                .isNotNull();

        String text = java.nio.file.Files.readString(src, java.nio.charset.StandardCharsets.UTF_8);
        int handler = text.indexOf("private final ItemStackHandler playlist");
        assertThat(handler).as("the playlist handler declaration").isGreaterThan(-1);

        // The call must be inside the handler's own onContentsChanged, and must be live code.
        // Bounded by brace matching rather than by "the next member": ending the region at the
        // following declaration would also accept the call moved into a sibling method that
        // nothing invokes. Comments are stripped first, so neither // nor a block comment
        // wrapped around the call can satisfy it.
        //
        // What this cannot say: it reads source text, so it establishes that the handler calls
        // the hook, not that the container calls the handler. That part is vanilla NeoForge
        // (SlotItemHandler over this handler) and is not re-checked here.
        int body = text.indexOf("protected void onContentsChanged(int slot) {", handler);
        assertThat(body).as("the handler's onContentsChanged").isGreaterThan(-1);
        String method = methodBodyAt(text, text.indexOf('{', body));

        boolean called = java.util.Arrays.stream(stripComments(method).split("\\R"))
                .map(String::strip)
                .anyMatch(line -> line.equals("onPlaylistSlotChanged(slot);"));
        assertThat(called)
                .as("the playlist handler must call the hook, as a live statement in its own body")
                .isTrue();
    }

    /** The text between {@code openBrace} and its matching close. */
    private static String methodBodyAt(String text, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return text.substring(openBrace, i);
        }
        throw new AssertionError("unbalanced braces from offset " + openBrace);
    }

    /** Blanks out // and block comments so commented-out code cannot satisfy a match. */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        for (int i = 0; i < src.length(); i++) {
            if (src.startsWith("//", i)) {
                while (i < src.length() && src.charAt(i) != '\n') i++;
                out.append('\n');
            } else if (src.startsWith("/*", i)) {
                int close = src.indexOf("*/", i + 2);
                int stop = close < 0 ? src.length() : close + 2;
                // Keep the line count so stripped comments cannot glue two statements together.
                for (int j = i; j < stop; j++) if (src.charAt(j) == '\n') out.append('\n');
                i = stop - 1;
            } else {
                out.append(src.charAt(i));
            }
        }
        return out.toString();
    }

    @Test
    @DisplayName("SAS-AUDIO-008: starting the sequence supersedes an endless entry")
    void startingTheSequenceDisarmsTheLoop() {
        PlaybackSessionRegistry.clear();
        PlaybackDeviceBlockEntity d = soundingDeviceOnAServer();
        ServerLevel level = (ServerLevel) d.getLevel();
        org.mockito.Mockito.when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(d);

        com.spatialaudiosystem.audio.PlaybackScheduler.playAll(level, BlockPos.ZERO);

        // Left armed, the device's own tick would restart the endless entry in the gap the
        // scheduler leaves between tracks, and the playback timeout would stay suppressed for
        // the whole sequence.
        assertThat(d.getLoopingEntry()).isEqualTo(-1);
    }

    @Test
    @DisplayName("SAS-AUDIO-008: Play All on a device with nothing looping leaves its sound alone")
    void startingTheSequenceDoesNotStopANonLoopingSound() {
        // The other half of the gate, and the sample point that makes it mean anything: with the
        // disarm unconditional, Play All silences a sound started from the single media slot,
        // which it never did. Play All is not disabled for an empty schedule, so this is
        // reachable by pressing the button.
        PlaybackSessionRegistry.clear();
        PlaybackDeviceBlockEntity d = soundingDeviceOnAServer();
        set(d, "loopingEntry", -1);                 // nothing endless here
        set(d, "entryCount", 0);                    // an empty schedule
        ServerLevel level = (ServerLevel) d.getLevel();
        org.mockito.Mockito.when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(d);

        com.spatialaudiosystem.audio.PlaybackScheduler.playAll(level, BlockPos.ZERO);

        assertThat(d.isPlaying())
                .as("the single-slot sound is not this sequence's to stop")
                .isTrue();
    }

    // ===== the wire =====

    @Test
    @DisplayName("SAS-AUDIO-008: the endless flag survives the wire in both states")
    void theEndlessFlagRoundTrips() {
        for (boolean loop : new boolean[]{true, false}) {
            ClientPlayAudioPayload sent = new ClientPlayAudioPayload(
                    new BlockPos(4, 64, -9), 0x0123456789ABCDEFL, 4096, "ogg",
                    new BlockPos(1, 2, 3), new BlockPos(4, 5, 6),
                    true, new int[]{8, 7, 6, 5, 4, 3}, loop, 0, true, 0L);

            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            ClientPlayAudioPayload.STREAM_CODEC.encode(buf, sent);
            ClientPlayAudioPayload back = ClientPlayAudioPayload.STREAM_CODEC.decode(buf);

            assertThat(back.loop()).as("loop=%s", loop).isEqualTo(loop);
            assertThat(buf.readableBytes())
                    .as("the reader consumed exactly what the writer produced")
                    .isZero();
            // Pinned alongside so a writer that dropped an earlier field and happened to leave
            // the boolean readable cannot pass.
            assertThat(back.pos()).isEqualTo(sent.pos());
            assertThat(back.playbackId()).isEqualTo(sent.playbackId());
            assertThat(back.totalSize()).isEqualTo(sent.totalSize());
            assertThat(back.format()).isEqualTo(sent.format());
            assertThat(back.attenuationRanges()).isEqualTo(sent.attenuationRanges());
        }
    }

    /**
     * The single medium's endless play, armed the way {@link PlaybackDeviceBlockEntity} arms it:
     * no schedule entry, the endless button on, the slot filled, and that slot is what started.
     */
    private static PlaybackDeviceBlockEntity singleEndlessDevice() {
        PlaybackDeviceBlockEntity d = device(1);
        set(d, "loopingEntry", -1);
        set(d, "normalLoop", true);
        set(d, "playingSingle", true);
        set(d, "isPlaying", true);
        net.neoforged.neoforge.items.ItemStackHandler slots =
                new net.neoforged.neoforge.items.ItemStackHandler(8);
        slots.setStackInSlot(0, new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.PAPER));
        set(d, "inventory", slots);
        return d;
    }

    @Test
    @DisplayName("SAS-AUDIO-008: an endless single medium reaches the restore after a reload")
    void anEndlessSingleMediumReachesRestore() {
        PlaybackSessionRegistry.clear();
        // The branch restoreLoop takes for loopingEntry < 0 had no caller in any test: every
        // restore test built a device armed on a playlist entry, so a mutation that turned the
        // single-medium branch into `return;` left the whole suite green.
        assertThat(tickAttemptsRestore(singleEndlessDevice(), 1_234_567L))
                .as("a loop that does not come back after a chunk reload is not endless")
                .isTrue();
    }

    @Test
    @DisplayName("SAS-AUDIO-008: an empty slot does not keep the single medium armed")
    void anEmptySlotDoesNotReachRestore() {
        PlaybackSessionRegistry.clear();
        PlaybackDeviceBlockEntity d = singleEndlessDevice();
        set(d, "inventory", new net.neoforged.neoforge.items.ItemStackHandler(8));
        // Otherwise a device whose medium was taken out would go on retrying for ever, and the
        // runaway timeout that would have cleaned it up is suppressed while it is armed.
        assertThat(tickAttemptsRestore(d, 1_234_567L)).isFalse();
    }

    @Test
    @DisplayName("SAS-AUDIO-008: the single-medium restore actually runs, not merely gets near")
    void theSingleMediumRestoreBranchRuns() {
        PlaybackSessionRegistry.clear();
        PlaybackDeviceBlockEntity d = singleEndlessDevice();

        PlaybackDeviceBlockEntity.tick(serverLevelAt(1_234_567L), BlockPos.ZERO, null, d);

        // The retry stamp is set one line BEFORE restoreLoop is called, so a test that only
        // watches the stamp cannot tell the branch ran from the branch being dead -- which is
        // what the two tests above do, and why this one reads a value only the branch writes.
        // The slot holds an item with no audio, so playMedia gives up and hands back false.
        assertThat(readBoolean(d, "playingSingle"))
                .as("the branch assigns playingSingle from playMedia; a dead branch leaves it true")
                .isFalse();
    }

    @Test
    @DisplayName("SAS-AUDIO-008: a start with nothing in the slot leaves the schedule's loop alone")
    void anEmptySlotLeavesTheScheduleLoopArmed() {
        PlaybackDeviceBlockEntity d = device(3);
        set(d, "loopingEntry", 2);
        set(d, "inventory", new net.neoforged.neoforge.items.ItemStackHandler(8));

        d.startPlayback();

        // A redstone pulse into an empty slot used to drop this arm without stopping the sound,
        // and the safety-net timeout then ended a running playlist loop ten minutes later. The
        // first version of this test asserted that failure as the requirement (2026-09-02).
        assertThat(d.getLoopingEntry())
                .as("nothing started, so nothing about what is playing changed")
                .isEqualTo(2);
    }

    // ===== the endless button reaches the sound that is playing (2026-09-02) =====

    private static PlaybackSessionRegistry.Replay singleReplay(boolean loop) {
        int[] ranges = new int[6];
        Arrays.fill(ranges, 8);
        return new PlaybackSessionRegistry.Replay(ItemStack.EMPTY, "ogg", null, null, true, ranges, loop);
    }

    /** A device whose single medium is sounding, on a server level with nobody online. */
    private static PlaybackDeviceBlockEntity playingSingleDevice(ServerLevel level, boolean endless) {
        PlaybackDeviceBlockEntity d = device(0);
        setInherited(d, "level", level);
        setInherited(d, "worldPosition", BlockPos.ZERO);
        setInherited(d, "blockState", net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        // Objenesis skips field initialisers, so the inventory is null unless set. An empty
        // slot is what the ON path reads: the restart cannot succeed here (no storage), and the
        // assertion is on the old session being superseded.
        set(d, "inventory", new net.neoforged.neoforge.items.ItemStackHandler(8));
        set(d, "normalLoop", endless);
        set(d, "playingSingle", true);
        set(d, "isPlaying", true);
        return d;
    }

    @Test
    @DisplayName("SAS-AUDIO-005: turning the endless button off retires the sound on the server")
    void withdrawingTheLoopRetiresTheSession() {
        PlaybackSessionRegistry.clear();
        ServerLevel level = serverLevelAt(1_000L);
        PlaybackDeviceBlockEntity d = playingSingleDevice(level, true);
        long id = PlaybackSessionRegistry.begin(level, BlockPos.ZERO, singleReplay(true));

        d.toggleNormalLoop();

        // Retired here, not on the first client's finish report. With the record gone, nobody
        // can be delivered a pass that ends at decode speed and reports the sound finished for
        // everyone still hearing it, and no report can retire it twice.
        assertThat(PlaybackSessionRegistry.currentId(level, BlockPos.ZERO))
                .as("the session is gone the moment endlessness is withdrawn")
                .isEqualTo(PlaybackSessionRegistry.NO_PLAYBACK);
        assertThat(d.isPlaying()).as("the device reads stopped").isFalse();
        assertThat(d.isNormalLoop()).isFalse();
        assertThat(id).isNotEqualTo(PlaybackSessionRegistry.NO_PLAYBACK);
    }

    @Test
    @DisplayName("SAS-AUDIO-005: turning the endless button on restarts the sound through the filtered start")
    void grantingTheLoopRestartsTheSound() {
        PlaybackSessionRegistry.clear();
        ServerLevel level = serverLevelAt(1_000L);
        PlaybackDeviceBlockEntity d = playingSingleDevice(level, false);
        long id = PlaybackSessionRegistry.begin(level, BlockPos.ZERO, singleReplay(false));

        d.toggleNormalLoop();

        // The one-shot went to every player in the dimension; flipping all of them to endless
        // would pin a decode thread on players who cannot hear it, and the far ones would still
        // report the old id finished and retire the sound under the near ones. So the old
        // session ends and a new, filtered one starts. Here the start cannot succeed (no
        // storage), which is why the assertion is on the old id being gone, not on the new one.
        assertThat(PlaybackSessionRegistry.currentId(level, BlockPos.ZERO))
                .as("the one-shot's session is superseded")
                .isNotEqualTo(id);
        assertThat(d.isNormalLoop()).isTrue();
    }

    @Test
    @DisplayName("SAS-AUDIO-005: withdrawing the loop tells every listener to stop repeating")
    void withdrawingTheLoopTellsEveryListener() {
        PlaybackSessionRegistry.clear();
        ServerLevel level = serverLevelAt(1_000L);
        net.minecraft.server.level.ServerPlayer near = org.mockito.Mockito.mock(net.minecraft.server.level.ServerPlayer.class);
        org.mockito.Mockito.when(level.players()).thenReturn(java.util.List.of(near));
        PlaybackDeviceBlockEntity d = playingSingleDevice(level, true);
        long id = PlaybackSessionRegistry.begin(level, BlockPos.ZERO, singleReplay(true));

        try (org.mockito.MockedStatic<net.neoforged.neoforge.network.PacketDistributor> packets =
                     org.mockito.Mockito.mockStatic(net.neoforged.neoforge.network.PacketDistributor.class)) {
            d.toggleNormalLoop();

            // The retirement alone would leave every client looping with nothing left on the
            // server to end it. This message is what makes the end per-client, and no other
            // test executed the loop that sends it (the mocked level had no players).
            packets.verify(() -> net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                    org.mockito.ArgumentMatchers.eq(near),
                    org.mockito.ArgumentMatchers.argThat(p ->
                            p instanceof com.spatialaudiosystem.network.ClientSetLoopPayload s
                                    && s.playbackId() == id && !s.loop())));
        }
    }

    // ===== superseding: every start stops what was sounding (2026-09-02) =====

    /** A medium the device will accept: hasAudioData reads only the file-name component. */
    private static ItemStack mediumWithAudio() {
        ItemStack stack = new ItemStack(net.minecraft.world.item.Items.PAPER);
        stack.set(com.spatialaudiosystem.item.ModDataComponents.AUDIO_FILE_NAME.get(), "hum.wav");
        return stack;
    }

    @Test
    @DisplayName("SAS-AUDIO-008: Play All supersedes an endless single medium, not only an endless entry")
    void playAllSupersedesAnEndlessSingleMedium() {
        PlaybackSessionRegistry.clear();
        ServerLevel level = serverLevelAt(1_000L);
        PlaybackDeviceBlockEntity d = playingSingleDevice(level, true);
        // Endless only counts with a medium in the slot -- the fixture's slot is empty so the
        // restart tests fail their start; this test needs the sound to be genuinely endless.
        net.neoforged.neoforge.items.ItemStackHandler slots = new net.neoforged.neoforge.items.ItemStackHandler(8);
        slots.setStackInSlot(0, mediumWithAudio());
        set(d, "inventory", slots);
        org.mockito.Mockito.when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(d);
        PlaybackSessionRegistry.begin(level, BlockPos.ZERO, singleReplay(true));

        com.spatialaudiosystem.audio.PlaybackScheduler.playAll(level, BlockPos.ZERO);

        // The gate used to read loopingEntry alone, which the endless single medium leaves at
        // -1. A listener who had walked out of range then kept the old sound running with no
        // server record left to stop it, because the filtered start never reached them.
        assertThat(d.isPlaying()).as("the single medium was stopped before the sequence").isFalse();
        assertThat(PlaybackSessionRegistry.currentId(level, BlockPos.ZERO))
                .as("its session is gone").isEqualTo(PlaybackSessionRegistry.NO_PLAYBACK);
    }

    @Test
    @DisplayName("SAS-AUDIO-008: starting the single medium supersedes the schedule's endless entry")
    void startingTheSingleMediumSupersedesTheScheduleLoop() {
        PlaybackDeviceBlockEntity d = soundingDeviceOnAServer();   // armed on entry 0, playing
        net.neoforged.neoforge.items.ItemStackHandler slots = new net.neoforged.neoforge.items.ItemStackHandler(8);
        slots.setStackInSlot(0, mediumWithAudio());
        set(d, "inventory", slots);

        d.startPlayback();

        // Both arms set at once made restoreLoop bring back a playlist entry instead of the
        // medium in the slot after a chunk reload. The empty-slot test above cannot reach this
        // line -- it returns before it -- so this one carries the supersede half on its own.
        assertThat(d.getLoopingEntry()).as("the schedule's arm is dropped by the stop").isEqualTo(-1);
    }

    /** A board as a stack carrying just the components that matter: corners and/or faces. */
    private static ItemStack boardWith(boolean corners, int[] faces) {
        ItemStack stack = new ItemStack(net.minecraft.world.item.Items.PAPER);
        if (corners) {
            stack.set(com.spatialaudiosystem.item.ModDataComponents.RANGE_POS1.get(), new BlockPos(-3, 60, -3));
            stack.set(com.spatialaudiosystem.item.ModDataComponents.RANGE_POS2.get(), new BlockPos(3, 66, 3));
        }
        if (faces != null) {
            stack.set(com.spatialaudiosystem.item.ModDataComponents.ATTENUATION_RANGES.get(),
                    Arrays.stream(faces).boxed().toList());
        }
        return stack;
    }

    /** The face-range array the device hands to delivery for one start, with {@code boardSlot} in slot 1. */
    private static int[] rangesSentFor(ItemStack boardSlot, int preset) {
        PlaybackSessionRegistry.clear();
        ServerLevel level = serverLevelAt(1_000L);
        PlaybackDeviceBlockEntity d = playingSingleDevice(level, false);
        set(d, "isPlaying", false);
        set(d, "attenuationRange", preset);
        net.neoforged.neoforge.items.ItemStackHandler slots = new net.neoforged.neoforge.items.ItemStackHandler(8);
        slots.setStackInSlot(0, mediumWithAudio());
        slots.setStackInSlot(1, boardSlot);
        set(d, "inventory", slots);
        try (org.mockito.MockedStatic<com.spatialaudiosystem.audio.PlaybackDelivery> delivery =
                     org.mockito.Mockito.mockStatic(com.spatialaudiosystem.audio.PlaybackDelivery.class)) {
            delivery.when(() -> com.spatialaudiosystem.audio.PlaybackDelivery.start(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean()))
                    .thenReturn(PlaybackSessionRegistry.NO_PLAYBACK);
            d.startPlayback();
            org.mockito.ArgumentCaptor<int[]> ranges = org.mockito.ArgumentCaptor.forClass(int[].class);
            delivery.verify(() -> com.spatialaudiosystem.audio.PlaybackDelivery.start(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean(), ranges.capture(),
                    org.mockito.ArgumentMatchers.anyBoolean()));
            return ranges.getValue();
        }
    }

    @Test
    @DisplayName("SAS-RANGE-001: without a board the device's own range is every face distance")
    void withoutABoardTheFillIsThePreset() {
        assertThat(rangesSentFor(ItemStack.EMPTY, 40)).containsExactly(40, 40, 40, 40, 40, 40);
    }

    @Test
    @DisplayName("SAS-RANGE-001: a board with corners but no edited faces keeps the board's default, not the preset")
    void aBoardWithCornersButNoFacesKeepsTheBoardDefault() {
        // Until 2026-09-02 the fill was keyed on the per-face component, so this board -- the
        // ordinary one, corners set and faces never touched -- took the device's range as all six
        // face distances. With the default moved to 64 that would have widened every such fade
        // from 8 to 64 while the screen said the preset was not in effect. Review found it.
        assertThat(rangesSentFor(boardWith(true, null), 64)).containsExactly(8, 8, 8, 8, 8, 8);
    }

    @Test
    @DisplayName("SAS-RANGE-001: a board with edited faces but no corners does not replace the preset")
    void aCornerlessBoardDoesNotOverrideThePreset() {
        // No corners means no box, so the radius branch runs on ranges[0]; the board's east face
        // must not become that radius while the screen shows and edits the preset.
        assertThat(rangesSentFor(boardWith(false, new int[]{3, 3, 3, 3, 3, 3}), 24))
                .containsExactly(24, 24, 24, 24, 24, 24);
    }

    @Test
    @DisplayName("SAS-AUDIO-008: starting the single medium passes the endless button's state")
    void startingTheSingleMediumPassesTheEndlessFlag() {
        PlaybackSessionRegistry.clear();
        ServerLevel level = serverLevelAt(1_000L);
        PlaybackDeviceBlockEntity d = playingSingleDevice(level, true);
        set(d, "isPlaying", false);
        net.neoforged.neoforge.items.ItemStackHandler slots = new net.neoforged.neoforge.items.ItemStackHandler(8);
        slots.setStackInSlot(0, mediumWithAudio());
        set(d, "inventory", slots);

        try (org.mockito.MockedStatic<com.spatialaudiosystem.audio.PlaybackDelivery> delivery =
                     org.mockito.Mockito.mockStatic(com.spatialaudiosystem.audio.PlaybackDelivery.class)) {
            delivery.when(() -> com.spatialaudiosystem.audio.PlaybackDelivery.start(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean()))
                    .thenReturn(PlaybackSessionRegistry.NO_PLAYBACK);

            d.startPlayback();

            // The one argument the button controls. A literal false here would make the endless
            // button decorative for a fresh press, and no other test reaches this call.
            delivery.verify(() -> com.spatialaudiosystem.audio.PlaybackDelivery.start(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(BlockPos.ZERO),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.eq(true)));
        }
    }

    @Test
    @DisplayName("SAS-AUDIO-008: starting the single medium with the endless button off starts a one-shot")
    void startingTheSingleMediumWithTheButtonOffStartsAOneShot() {
        PlaybackSessionRegistry.clear();
        ServerLevel level = serverLevelAt(1_000L);
        PlaybackDeviceBlockEntity d = playingSingleDevice(level, false);
        set(d, "isPlaying", false);
        net.neoforged.neoforge.items.ItemStackHandler slots = new net.neoforged.neoforge.items.ItemStackHandler(8);
        slots.setStackInSlot(0, mediumWithAudio());
        set(d, "inventory", slots);

        try (org.mockito.MockedStatic<com.spatialaudiosystem.audio.PlaybackDelivery> delivery =
                     org.mockito.Mockito.mockStatic(com.spatialaudiosystem.audio.PlaybackDelivery.class)) {
            delivery.when(() -> com.spatialaudiosystem.audio.PlaybackDelivery.start(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean()))
                    .thenReturn(PlaybackSessionRegistry.NO_PLAYBACK);

            d.startPlayback();

            // The other half of the pin. The test above samples only the point where the flag is
            // already on, so a call site hardcoded to true -- every press endless -- stayed green.
            delivery.verify(() -> com.spatialaudiosystem.audio.PlaybackDelivery.start(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(BlockPos.ZERO),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.eq(false)));
        }
    }

    @Test
    @DisplayName("SAS-AUDIO-008: taking the medium out of a sounding single slot stops the sound")
    void emptyingTheMediaSlotStopsTheSingleSound() {
        PlaybackSessionRegistry.clear();
        ServerLevel level = serverLevelAt(1_000L);
        PlaybackDeviceBlockEntity d = playingSingleDevice(level, true);   // slot already empty
        PlaybackSessionRegistry.begin(level, BlockPos.ZERO, singleReplay(true));

        call(d, "onMediaSlotChanged", new Class<?>[0]);

        // Without this the clients keep looping while the server's "sounding endlessly" test,
        // which reads the slot, says nothing endless is playing -- so a later Play All started
        // over it without a stop, and listeners out of range kept the old sound for ever.
        assertThat(d.isPlaying()).as("the device stops when its medium is gone").isFalse();
        assertThat(PlaybackSessionRegistry.currentId(level, BlockPos.ZERO))
                .isEqualTo(PlaybackSessionRegistry.NO_PLAYBACK);
    }

    @Test
    @DisplayName("SAS-AUDIO-008: a slot change that leaves the medium in place changes nothing")
    void aMediaSlotChangeWithTheMediumPresentIsIgnored() {
        PlaybackSessionRegistry.clear();
        ServerLevel level = serverLevelAt(1_000L);
        PlaybackDeviceBlockEntity d = playingSingleDevice(level, true);
        net.neoforged.neoforge.items.ItemStackHandler slots = new net.neoforged.neoforge.items.ItemStackHandler(8);
        slots.setStackInSlot(0, mediumWithAudio());
        set(d, "inventory", slots);
        long id = PlaybackSessionRegistry.begin(level, BlockPos.ZERO, singleReplay(true));

        call(d, "onMediaSlotChanged", new Class<?>[0]);

        // The hook fires for any change to the slot, including the insertion that started it.
        assertThat(d.isPlaying()).isTrue();
        assertThat(PlaybackSessionRegistry.currentId(level, BlockPos.ZERO)).isEqualTo(id);
    }

    @Test
    @DisplayName("SAS-AUDIO-008: the media handler calls the slot-changed hook")
    void theMediaHandlerCallsTheSlotChangedHook() throws Exception {
        // The two slot tests above call onMediaSlotChanged directly, because the block entity
        // is built without its field initialisers and its real handler does not exist on such
        // an instance. Delete the one line that wires the handler to the hook and both stay
        // green while every real removal path stops stopping the sound (review, 2026-09-02).
        java.nio.file.Path src = null;
        for (java.nio.file.Path base = java.nio.file.Paths.get("").toAbsolutePath();
             base != null; base = base.getParent()) {
            java.nio.file.Path c = base.resolve(
                    "src/main/java/com/spatialaudiosystem/blockentity/PlaybackDeviceBlockEntity.java");
            if (java.nio.file.Files.isRegularFile(c)) { src = c; break; }
        }
        assertThat(src).isNotNull();
        String text = java.nio.file.Files.readString(src, java.nio.charset.StandardCharsets.UTF_8);
        int handler = text.indexOf("private final ItemStackHandler inventory");
        assertThat(handler).as("the media handler declaration").isGreaterThan(-1);
        int body = text.indexOf("protected void onContentsChanged(int slot) {", handler);
        assertThat(body).as("the handler's onContentsChanged").isGreaterThan(-1);
        String method = methodBodyAt(text, text.indexOf('{', body));

        boolean called = java.util.Arrays.stream(stripComments(method).split("\\R"))
                .map(String::strip)
                .anyMatch(line -> line.equals("if (slot == MEDIA_SLOT) onMediaSlotChanged();"));
        assertThat(called)
                .as("the media handler must call the hook, as a live statement in its own body")
                .isTrue();
    }

    @Test
    @DisplayName("SAS-AUDIO-008: emptying the barred single slot does not stop a schedule track")
    void aScheduleTrackIsNotStoppedByTheMediaSlot() {
        PlaybackSessionRegistry.clear();
        ServerLevel level = serverLevelAt(1_000L);
        PlaybackDeviceBlockEntity d = playingSingleDevice(level, true);   // slot empty
        set(d, "playingSingle", false);                                   // the sound is the schedule's
        long id = PlaybackSessionRegistry.begin(level, BlockPos.ZERO, singleReplay(true));

        call(d, "onMediaSlotChanged", new Class<?>[0]);

        // Schedule mode bars the single slot but never empties it; pulling the leftover medium
        // out while Play All runs must not stop the sequence. Without the playingSingle term
        // in the guard it did, and no other test constructed this state.
        assertThat(d.isPlaying()).isTrue();
        assertThat(PlaybackSessionRegistry.currentId(level, BlockPos.ZERO)).isEqualTo(id);
    }
}
