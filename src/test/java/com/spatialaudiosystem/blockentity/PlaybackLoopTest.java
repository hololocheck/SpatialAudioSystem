package com.spatialaudiosystem.blockentity;

import com.spatialaudiosystem.audio.PlaybackSessionRegistry;
import com.spatialaudiosystem.network.ClientPlayAudioPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
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
        PlaybackDeviceBlockEntity d = new ObjenesisStd().newInstance(PlaybackDeviceBlockEntity.class);
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
        PlaybackDeviceBlockEntity bare = new ObjenesisStd().newInstance(PlaybackDeviceBlockEntity.class);

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
                    true, new int[]{8, 7, 6, 5, 4, 3}, loop);

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
}
