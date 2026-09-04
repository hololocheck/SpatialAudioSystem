package com.spatialaudiosystem.blockentity;

import com.spatialaudiosystem.network.RedstoneRuleCommandPayload;
import com.spatialaudiosystem.redstone.RedstoneOutputPlan;
import com.spatialaudiosystem.redstone.RedstoneRule;
import com.spatialaudiosystem.redstone.RedstoneRule.Trigger;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SAS-RS-002: the rule list on the device, the packet that edits it, and the wiring that turns
 * playback into events.
 *
 * <p>The tick-by-tick output is {@code RedstoneOutputPlanTest}'s. This is the rest: the list's
 * bounds, the wheel's steps, the decode-time refusal, the disk shape, and -- because the block
 * and the tick cannot run here -- source-text gates on the three places the plan is fed from
 * and the two the block reads it at.
 */
class RedstoneRulesTest {

    private static final BlockPos POS = new BlockPos(3, 70, 12);

    private static void set(Object target, String field, Object value) {
        try {
            Field f = PlaybackDeviceBlockEntity.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not set " + field, e);
        }
    }

    /** A device with no world behind it; the rule list touches nothing but its own fields. */
    private static PlaybackDeviceBlockEntity device() {
        PlaybackDeviceBlockEntity d = com.spatialaudiosystem.blockentity.TestDevices.newBare();
        set(d, "redstoneRules", new ArrayList<RedstoneRule>());
        set(d, "redstonePlan", new RedstoneOutputPlan());
        return d;
    }

    @Test
    @DisplayName("SAS-RS-002: up to sixteen rules; the seventeenth is refused")
    void upToSixteenRulesThenRefused() {
        PlaybackDeviceBlockEntity d = device();
        for (int i = 0; i < RedstoneRule.MAX_RULES; i++) {
            assertThat(d.addRedstoneRule()).as("rule " + (i + 1)).isTrue();
        }
        assertThat(d.addRedstoneRule()).as("one past the list").isFalse();
        assertThat(d.getRedstoneRules()).hasSize(RedstoneRule.MAX_RULES);
        assertThat(RedstoneRule.MAX_RULES).as("one rule per schedule entry").isEqualTo(16);
    }

    @Test
    @DisplayName("SAS-RS-003: the entry scope wraps between any and the last entry, and is the playlist's size")
    void theEntryScopeWrapsAndIsPinnedToThePlaylist() {
        assertThat(PlaybackDeviceBlockEntity.MAX_ENTRIES).isEqualTo(16);
        assertThat(RedstoneRule.MAX_ENTRY).as("a rule can name every entry")
                .isEqualTo(PlaybackDeviceBlockEntity.MAX_ENTRIES);
        PlaybackDeviceBlockEntity d = device();
        d.addRedstoneRule();
        assertThat(d.getRedstoneRules().get(0).entry()).as("a new rule is unscoped").isEqualTo(RedstoneRule.ANY_ENTRY);
        d.adjustRedstoneEntry(0, -1);
        assertThat(d.getRedstoneRules().get(0).entry()).as("before any comes the last entry").isEqualTo(16);
        d.adjustRedstoneEntry(0, 1);
        assertThat(d.getRedstoneRules().get(0).entry()).isEqualTo(RedstoneRule.ANY_ENTRY);
        d.adjustRedstoneEntry(0, 1);
        assertThat(d.getRedstoneRules().get(0).entry()).isEqualTo(1);
        d.adjustRedstoneEntry(9, 1);   // past the list: ignored
        assertThat(d.getRedstoneRules()).hasSize(1);
    }

    @Test
    @DisplayName("SAS-RS-002: a new rule is a full-strength lamp with no delay")
    void aNewRuleIsAFullStrengthLamp() {
        PlaybackDeviceBlockEntity d = device();
        d.addRedstoneRule();
        RedstoneRule r = d.getRedstoneRules().get(0);
        assertThat(r.trigger()).isEqualTo(Trigger.PLAYING);
        assertThat(r.strength()).isEqualTo(15);
        assertThat(r.delayTicks()).isZero();
    }

    @Test
    @DisplayName("SAS-RS-002: the trigger wheel wraps at both ends")
    void theTriggerWheelWraps() {
        PlaybackDeviceBlockEntity d = device();
        d.addRedstoneRule();
        d.cycleRedstoneTrigger(0, -1);
        assertThat(d.getRedstoneRules().get(0).trigger()).as("down from the first").isEqualTo(Trigger.END);
        d.cycleRedstoneTrigger(0, 1);
        assertThat(d.getRedstoneRules().get(0).trigger()).as("and back").isEqualTo(Trigger.PLAYING);
        d.cycleRedstoneTrigger(0, 1);
        assertThat(d.getRedstoneRules().get(0).trigger()).isEqualTo(Trigger.START);
    }

    @Test
    @DisplayName("SAS-RS-002: strength, delay and length step by their notch and clamp at their bounds")
    void theValueWheelsStepAndClamp() {
        PlaybackDeviceBlockEntity d = device();
        d.addRedstoneRule();
        d.adjustRedstoneStrength(0, 1);
        assertThat(d.getRedstoneRules().get(0).strength()).as("already at the ceiling").isEqualTo(15);
        for (int i = 0; i < 20; i++) d.adjustRedstoneStrength(0, -1);
        assertThat(d.getRedstoneRules().get(0).strength()).as("a floor of one, never zero").isEqualTo(1);

        d.adjustRedstoneDelay(0, 1);
        assertThat(d.getRedstoneRules().get(0).delayTicks()).as("one notch is half a second").isEqualTo(10);
        d.adjustRedstoneDelay(0, -1);
        d.adjustRedstoneDelay(0, -1);
        assertThat(d.getRedstoneRules().get(0).delayTicks()).as("no negative delay").isZero();
        for (int i = 0; i < 100; i++) d.adjustRedstoneDelay(0, 1);
        assertThat(d.getRedstoneRules().get(0).delayTicks()).isEqualTo(RedstoneRule.MAX_DELAY_TICKS);

        d.adjustRedstoneLength(0, 1);
        assertThat(d.getRedstoneRules().get(0).lengthTicks()).as("one notch is a tenth of a second").isEqualTo(12);
        for (int i = 0; i < 100; i++) d.adjustRedstoneLength(0, -1);
        assertThat(d.getRedstoneRules().get(0).lengthTicks()).as("two ticks, the shortest a circuit sees")
                .isEqualTo(RedstoneRule.MIN_LENGTH_TICKS);
    }

    @Test
    @DisplayName("SAS-RS-002: an edit or removal past the list is ignored")
    void anEditPastTheListIsIgnored() {
        PlaybackDeviceBlockEntity d = device();
        d.addRedstoneRule();
        d.adjustRedstoneStrength(3, -1);
        d.removeRedstoneRule(3);
        assertThat(d.getRedstoneRules()).hasSize(1);
        assertThat(d.getRedstoneRules().get(0).strength()).isEqualTo(15);
        d.removeRedstoneRule(0);
        assertThat(d.getRedstoneRules()).isEmpty();
    }

    @Test
    @DisplayName("SAS-RS-002: a rule survives the disk, clamped")
    void aRuleSurvivesTheDisk() {
        RedstoneRule r = new RedstoneRule(Trigger.STOP, 9, 30, 8, 5);
        assertThat(RedstoneRule.load(r.save())).isEqualTo(r);
        // A rule saved before entry scopes existed (phase 2) is unscoped, not scoped to entry 1.
        net.minecraft.nbt.CompoundTag old = r.save();
        old.remove("entry");
        assertThat(RedstoneRule.load(old).entry()).isEqualTo(RedstoneRule.ANY_ENTRY);
        assertThat(new RedstoneRule(Trigger.END, 5, 0, 10, 99).entry()).as("clamped").isEqualTo(RedstoneRule.MAX_ENTRY);
        // A value written by a newer or hand-edited save lands inside the bounds.
        assertThat(new RedstoneRule(Trigger.END, 99, -5, 1_000))
                .isEqualTo(new RedstoneRule(Trigger.END, 15, 0, RedstoneRule.MAX_LENGTH_TICKS));
    }

    private static RedstoneRuleCommandPayload decode(int op, int index, int delta) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeBlockPos(POS);
        buf.writeVarInt(op);
        buf.writeVarInt(index);
        buf.writeVarInt(delta);
        return RedstoneRuleCommandPayload.STREAM_CODEC.decode(buf);
    }

    @Test
    @DisplayName("SAS-RS-002: the command is bounded at decode")
    void theCommandIsBoundedAtDecode() {
        assertThat(decode(RedstoneRuleCommandPayload.OP_ADJUST_DELAY, 5, -1).index()).isEqualTo(5);
        assertThat(decode(RedstoneRuleCommandPayload.OP_ADJUST_ENTRY, 15, 1).index()).isEqualTo(15);
        assertThat(decode(RedstoneRuleCommandPayload.OP_MOVE, 3, -1).index()).isEqualTo(3);
        assertThatThrownBy(() -> decode(9, 0, 0)).as("one past the last op").isInstanceOf(DecoderException.class);
        assertThatThrownBy(() -> decode(RedstoneRuleCommandPayload.OP_REMOVE, 16, 0))
                .as("one past the rows").isInstanceOf(DecoderException.class);
        assertThatThrownBy(() -> decode(RedstoneRuleCommandPayload.OP_ADJUST_STRENGTH, 0, 2))
                .as("a wheel notch is one, whatever the client claims").isInstanceOf(DecoderException.class);
    }

    @Test
    @DisplayName("SAS-RS-002: the command round-trips")
    void theCommandRoundTrips() {
        RedstoneRuleCommandPayload sent = new RedstoneRuleCommandPayload(POS, RedstoneRuleCommandPayload.OP_CYCLE_TRIGGER, 2, 1);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        RedstoneRuleCommandPayload.STREAM_CODEC.encode(buf, sent);
        assertThat(RedstoneRuleCommandPayload.STREAM_CODEC.decode(buf)).isEqualTo(sent);
    }

    /** The named source file, found by walking up from wherever the test runner started. */
    private static String sourceText(String relative) throws Exception {
        for (java.nio.file.Path base = java.nio.file.Paths.get("").toAbsolutePath();
             base != null; base = base.getParent()) {
            java.nio.file.Path c = base.resolve(relative);
            if (java.nio.file.Files.isRegularFile(c)) {
                return java.nio.file.Files.readString(c, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("source not found from " + java.nio.file.Paths.get("").toAbsolutePath());
    }

    private static String method(String text, String signatureStart) {
        int at = text.indexOf(signatureStart);
        assertThat(at).as(signatureStart).isPositive();
        return text.substring(at, text.indexOf("\n    }\n", at));
    }

    @Test
    @DisplayName("SAS-RS-002: the block is a weak signal source that reads the device's output on every face")
    void theBlockReadsTheDevicesOutput() throws Exception {
        // The block cannot be built here (registries), so its methods are read.
        String block = sourceText("src/main/java/com/spatialaudiosystem/block/PlaybackDeviceBlock.java");
        assertThat(method(block, "protected boolean isSignalSource(")).contains("return true;");
        assertThat(method(block, "protected int getSignal(")).contains("getRedstoneOutput()");
        // Weak power only: a strong source must update the neighbours of every block it powers
        // and the device updates only its own, so dust behind a solid block would go stale.
        assertThat(block).doesNotContain("protected int getDirectSignal(");
        // A device whose own output powers the dust beside it must not read that dust as a
        // fresh press -- but POWERED has to follow the wire first, or a lever pressed while a
        // lamp rule is on is dropped and then read as a rising edge when the output falls.
        String neighbour = method(block, "protected void neighborChanged(");
        int powerWrite = neighbour.indexOf("setValue(POWERED, powered)");
        int guard = neighbour.indexOf("getRedstoneOutput() > 0");
        assertThat(powerWrite).isPositive();
        assertThat(guard).as("the guard sits inside the rising edge, after POWERED is written").isGreaterThan(powerWrite);
    }

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
        throw new AssertionError("no field " + field);
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) {
        try {
            java.lang.reflect.Method m = PlaybackDeviceBlockEntity.class.getDeclaredMethod(name, types);
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not call " + name, e);
        }
    }

    private static net.minecraft.server.level.ServerLevel serverLevel() {
        net.minecraft.server.level.ServerLevel level = org.mockito.Mockito.mock(net.minecraft.server.level.ServerLevel.class);
        org.mockito.Mockito.when(level.isClientSide()).thenReturn(false);
        org.mockito.Mockito.when(level.getGameTime()).thenReturn(50_000L);
        org.mockito.Mockito.when(level.players()).thenReturn(java.util.List.of());
        org.mockito.Mockito.when(level.dimension()).thenReturn(
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "overworld")));
        return level;
    }

    private static PlaybackDeviceBlockEntity deviceOn(net.minecraft.server.level.ServerLevel level) {
        PlaybackDeviceBlockEntity d = device();
        net.minecraft.world.level.block.state.BlockState air =
                org.mockito.Mockito.mock(net.minecraft.world.level.block.state.BlockState.class);
        org.mockito.Mockito.when(air.isAir()).thenReturn(true);
        setInherited(d, "level", level);
        setInherited(d, "worldPosition", BlockPos.ZERO);
        setInherited(d, "blockState", air);
        return d;
    }

    /**
     * A saved device with the output on, a lamp rule at 7 and pulses at 15/13/11: the four
     * strengths tell a resumed lamp (7) from a refired start (15), a stop (13) or an end (11).
     * Saved as playing since {@code startTick}, the way loadAdditional hands the tag over --
     * the fields are restored only after loadRedstone runs, so the tag is what it reads.
     */
    private static net.minecraft.nbt.CompoundTag savedLamp(boolean playing, long startTick) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putBoolean("isPlaying", playing);
        tag.putLong("playbackStartTick", startTick);
        tag.putBoolean("redstoneEnabled", true);
        net.minecraft.nbt.ListTag rules = new net.minecraft.nbt.ListTag();
        rules.add(new RedstoneRule(Trigger.PLAYING, 7, 0, 10).save());
        rules.add(new RedstoneRule(Trigger.START, 15, 0, 100).save());
        rules.add(new RedstoneRule(Trigger.STOP, 13, 0, 100).save());
        rules.add(new RedstoneRule(Trigger.END, 11, 0, 100).save());
        tag.put("redstoneRules", rules);
        return tag;
    }

    @Test
    @DisplayName("SAS-RS-002: a sound that outlived a chunk reload lights its lamp again")
    void aSoundThatOutlivedAReloadRelightsTheLamp() {
        // The registry keeps a sound across a chunk unload, so the loop branch never re-arms
        // and the plan made at the load never sees a start. Without a seed the lamp sat at
        // zero until the sound ended, and the load-time notification put it out.
        com.spatialaudiosystem.audio.PlaybackSessionRegistry.clear();
        try {
            net.minecraft.server.level.ServerLevel level = serverLevel();
            PlaybackDeviceBlockEntity d = deviceOn(level);
            set(d, "isPlaying", true);
            set(d, "playbackStartTick", 49_950L);
            com.spatialaudiosystem.audio.PlaybackSessionRegistry.begin(level, BlockPos.ZERO);

            // Started 2.5 s ago: a refired start pulse (length 100) would still be on.
            call(d, "loadRedstone", new Class<?>[]{net.minecraft.nbt.CompoundTag.class}, savedLamp(true, 49_950L));
            d.reconcileAfterLoad(level);
            d.refreshRedstoneOutput();

            // The lamp (7), not the start pulse (15): a reload is not a start. The first
            // version fed a START event here and refired every start pulse on every reload.
            assertThat(d.getRedstoneOutput()).as("still sounding, so the lamp is on and nothing pulsed").isEqualTo(7);
            assertThat(d.isPlaying()).isTrue();
        } finally {
            com.spatialaudiosystem.audio.PlaybackSessionRegistry.clear();
        }
    }

    @Test
    @DisplayName("SAS-RS-002: a resumed lamp with a delay does not re-run it")
    void aResumedLampDoesNotReRunItsDelay() {
        com.spatialaudiosystem.audio.PlaybackSessionRegistry.clear();
        try {
            net.minecraft.server.level.ServerLevel level = serverLevel();   // game time 50_000
            PlaybackDeviceBlockEntity d = deviceOn(level);
            set(d, "isPlaying", true);
            set(d, "playbackStartTick", 49_000L);   // started fifty seconds ago
            com.spatialaudiosystem.audio.PlaybackSessionRegistry.begin(level, BlockPos.ZERO);
            net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
            tag.putBoolean("isPlaying", true);
            tag.putLong("playbackStartTick", 49_000L);
            tag.putBoolean("redstoneEnabled", true);
            net.minecraft.nbt.ListTag rules = new net.minecraft.nbt.ListTag();
            rules.add(new RedstoneRule(Trigger.PLAYING, 9, 200, 10).save());   // ten-second delay
            tag.put("redstoneRules", rules);

            call(d, "loadRedstone", new Class<?>[]{net.minecraft.nbt.CompoundTag.class}, tag);
            d.reconcileAfterLoad(level);
            d.refreshRedstoneOutput();

            // Resumed from the persisted start, the delay elapsed long ago; seeded at "now"
            // the lamp would go dark for ten seconds after every reload.
            assertThat(d.getRedstoneOutput()).isEqualTo(9);
        } finally {
            com.spatialaudiosystem.audio.PlaybackSessionRegistry.clear();
        }
    }

    @Test
    @DisplayName("SAS-RS-002: a device saved as playing whose sound is gone stays dark")
    void aDeviceWhoseSoundIsGoneStaysDark() {
        // After a server restart the registry is empty: the saved isPlaying is stale and the
        // timeout will clear it. Seeding a start here would light a lamp for nothing.
        com.spatialaudiosystem.audio.PlaybackSessionRegistry.clear();
        net.minecraft.server.level.ServerLevel level = serverLevel();
        PlaybackDeviceBlockEntity d = deviceOn(level);
        set(d, "isPlaying", true);
        set(d, "loopingEntry", -1);

        call(d, "loadRedstone", new Class<?>[]{net.minecraft.nbt.CompoundTag.class}, savedLamp(true, 49_950L));
        d.reconcileAfterLoad(level);
        d.refreshRedstoneOutput();

        // Zero, not the STOP pulse's 13: clearing a stale flag is not a transition.
        assertThat(d.getRedstoneOutput()).isZero();
        // The stale flag is cleared here now that the start tick is persisted and the
        // timeout no longer does it on the first tick; the screen would otherwise show a
        // device as playing for up to ten minutes after a restart.
        assertThat(d.isPlaying()).as("the saved flag was stale").isFalse();
    }

    @Test
    @DisplayName("SAS-RS-002: an end reported into a freshly loaded device still pulses")
    void anEndReportedIntoAFreshlyLoadedDeviceStillPulses() {
        // The finish report force-loads the chunk and ends the sound before any tick runs.
        // The load must already know the device was playing, or the END is not a transition
        // and its pulse is lost -- review, 2026-09-03.
        com.spatialaudiosystem.audio.PlaybackSessionRegistry.clear();
        net.minecraft.server.level.ServerLevel level = serverLevel();
        PlaybackDeviceBlockEntity d = deviceOn(level);
        set(d, "isPlaying", true);
        set(d, "playbackStartTick", 49_000L);

        call(d, "loadRedstone", new Class<?>[]{net.minecraft.nbt.CompoundTag.class}, savedLamp(true, 49_000L));
        d.setIsPlaying(false);   // the report's path, before the first tick
        d.refreshRedstoneOutput();

        assertThat(d.getRedstoneOutput()).as("the END pulse, not the lamp and not silence").isEqualTo(11);
    }

    @Test
    @DisplayName("SAS-RS-002: the first tick clears a stale flag through the real tick")
    void theFirstTickClearsAStaleFlag() {
        // Drives PlaybackDeviceBlockEntity.tick itself, so the wiring from the tick to the
        // reconcile is executed rather than read.
        com.spatialaudiosystem.audio.PlaybackSessionRegistry.clear();
        net.minecraft.server.level.ServerLevel level = serverLevel();
        PlaybackDeviceBlockEntity d = deviceOn(level);
        set(d, "isPlaying", true);
        set(d, "loopingEntry", -1);
        set(d, "inventory", new net.neoforged.neoforge.items.ItemStackHandler(8));
        set(d, "playCounts", new int[PlaybackDeviceBlockEntity.PLAYLIST_SIZE]);
        call(d, "loadRedstone", new Class<?>[]{net.minecraft.nbt.CompoundTag.class}, savedLamp(true, 49_950L));

        PlaybackDeviceBlockEntity.tick(level, BlockPos.ZERO, null, d);

        assertThat(d.isPlaying()).as("stale, and no sound in the registry").isFalse();
        assertThat(d.getRedstoneOutput()).isZero();
        org.mockito.Mockito.verify(level, org.mockito.Mockito.times(1))
                .updateNeighborsAt(org.mockito.ArgumentMatchers.eq(BlockPos.ZERO), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("SAS-RS-002: the load reads the saved playing state from the tag, not from a field restored later")
    void theLoadReadsThePlayingStateFromTheTag() throws Exception {
        // loadAdditional restores isPlaying after loadRedstone runs; a fresh instance has the
        // field false on every real load. The first version read the field: the resume never
        // fired in production while every test, setting the field first, stayed green.
        String be = sourceText("src/main/java/com/spatialaudiosystem/blockentity/PlaybackDeviceBlockEntity.java");
        assertThat(method(be, "private void loadRedstone("))
                .contains("if (tag.getBoolean(\"isPlaying\")) redstonePlan.resume(tag.getLong(\"playbackStartTick\"), soundingEntry);");
        // And the fixture below reaches it the way production does: with the field still false.
        com.spatialaudiosystem.audio.PlaybackSessionRegistry.clear();
        net.minecraft.server.level.ServerLevel level = serverLevel();
        PlaybackDeviceBlockEntity d = deviceOn(level);
        assertThat(d.isPlaying()).as("a fresh instance, as loadStatic makes one").isFalse();
        call(d, "loadRedstone", new Class<?>[]{net.minecraft.nbt.CompoundTag.class}, savedLamp(true, 49_950L));
        set(d, "isPlaying", true);   // loadAdditional restores the field afterwards
        d.setIsPlaying(false);       // an end reported before the first tick
        d.refreshRedstoneOutput();
        assertThat(d.getRedstoneOutput()).as("the END pulse: the load knew it was playing").isEqualTo(11);
    }

    @Test
    @DisplayName("SAS-RS-002: a loop-armed device whose sound is gone is dark until it re-arms")
    void aLoopArmedStaleDeviceIsDarkUntilItReArms() {
        // The loop branch owns an armed device, so its stale flag is not cleared here -- but
        // the provisional playing state is dropped, or a loop whose audio is gone would hold
        // its lamp lit forever with nothing sounding.
        com.spatialaudiosystem.audio.PlaybackSessionRegistry.clear();
        net.minecraft.server.level.ServerLevel level = serverLevel();
        PlaybackDeviceBlockEntity d = deviceOn(level);
        set(d, "isPlaying", true);
        set(d, "normalLoop", true);
        set(d, "playingSingle", true);   // an armed endless single medium: normalLoop + playing + single + medium
        set(d, "loopingEntry", -1);
        net.neoforged.neoforge.items.ItemStackHandler slots = new net.neoforged.neoforge.items.ItemStackHandler(8);
        net.minecraft.world.item.ItemStack medium = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER);
        medium.set(com.spatialaudiosystem.item.ModDataComponents.AUDIO_FILE_NAME.get(), "hum.wav");
        slots.setStackInSlot(0, medium);
        set(d, "inventory", slots);
        assertThat((Boolean) call(d, "isNormalLoopArmed", new Class<?>[]{})).as("the fixture is an armed loop").isTrue();

        call(d, "loadRedstone", new Class<?>[]{net.minecraft.nbt.CompoundTag.class}, savedLamp(true, 49_950L));
        d.reconcileAfterLoad(level);
        d.refreshRedstoneOutput();

        assertThat(d.getRedstoneOutput()).as("nothing is sounding yet").isZero();
        assertThat(d.isPlaying()).as("left to the loop branch").isTrue();
    }

    @Test
    @DisplayName("SAS-RS-002: an armed loop whose sound is gone lights its lamp again when it re-arms")
    void anArmedLoopRelightsWhenItReArms() {
        // The other half of the armed case: dark after the load (above), lit again by the
        // loop branch's re-arm, which is a real start. Driven through the real tick, with
        // only the delivery mocked so the start succeeds without a client.
        com.spatialaudiosystem.audio.PlaybackSessionRegistry.clear();
        net.minecraft.server.level.ServerLevel level = serverLevel();
        PlaybackDeviceBlockEntity d = deviceOn(level);
        set(d, "isPlaying", true);
        set(d, "normalLoop", true);
        set(d, "playingSingle", true);
        set(d, "loopingEntry", -1);
        set(d, "playCounts", new int[PlaybackDeviceBlockEntity.PLAYLIST_SIZE]);
        net.neoforged.neoforge.items.ItemStackHandler slots = new net.neoforged.neoforge.items.ItemStackHandler(8);
        net.minecraft.world.item.ItemStack medium = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER);
        medium.set(com.spatialaudiosystem.item.ModDataComponents.AUDIO_FILE_NAME.get(), "hum.wav");
        slots.setStackInSlot(0, medium);
        set(d, "inventory", slots);
        call(d, "loadRedstone", new Class<?>[]{net.minecraft.nbt.CompoundTag.class}, savedLamp(true, 49_950L));

        try (org.mockito.MockedStatic<com.spatialaudiosystem.audio.PlaybackDelivery> delivery =
                     org.mockito.Mockito.mockStatic(com.spatialaudiosystem.audio.PlaybackDelivery.class)) {
            delivery.when(() -> com.spatialaudiosystem.audio.PlaybackDelivery.start(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean()))
                    .thenReturn(42L);
            // Reconcile (dark), refresh, then the loop branch re-arms: a start, lamp on again.
            PlaybackDeviceBlockEntity.tick(level, BlockPos.ZERO, null, d);
        }

        // A re-arm is a real start, so the start pulse (15) fires over the lamp (7) ...
        assertThat(d.getRedstoneOutput()).as("the start pulse of the re-arm").isEqualTo(15);
        // ... and once it is over, the lamp is what remains.
        org.mockito.Mockito.when(level.getGameTime()).thenReturn(50_200L);
        d.refreshRedstoneOutput();
        assertThat(d.getRedstoneOutput()).as("the lamp, lit by the re-arm").isEqualTo(7);
        assertThat(d.isPlaying()).isTrue();
    }

    @Test
    @DisplayName("SAS-SCHED-010: the schedule taking over hands the single medium back")
    void theScheduleTakingOverHandsTheMediumBack() {
        net.minecraft.server.level.ServerLevel level = serverLevel();
        PlaybackDeviceBlockEntity d = deviceOn(level);
        set(d, "scheduleMode", false);
        set(d, "isPlaying", true);
        set(d, "playingSingle", true);
        set(d, "loopingEntry", -1);
        net.neoforged.neoforge.items.ItemStackHandler slots = new net.neoforged.neoforge.items.ItemStackHandler(
                PlaybackDeviceBlockEntity.SLOT_COUNT);
        net.minecraft.world.item.ItemStack medium = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER);
        slots.setStackInSlot(PlaybackDeviceBlockEntity.MEDIA_SLOT, medium);
        set(d, "inventory", slots);
        java.util.List<net.minecraft.world.item.ItemStack> returned = new ArrayList<>();

        d.toggleScheduleMode(returned::add);

        assertThat(d.isScheduleMode()).isTrue();
        assertThat(returned).as("the medium went back to the player").containsExactly(medium);
        assertThat(slots.getStackInSlot(PlaybackDeviceBlockEntity.MEDIA_SLOT).isEmpty()).as("the slot is empty").isTrue();
        assertThat(d.isPlaying()).as("a playing single medium stops with its slot").isFalse();

        // Turning the schedule off hands nothing back, and an empty slot hands nothing back.
        d.toggleScheduleMode(returned::add);
        d.toggleScheduleMode(returned::add);
        assertThat(d.isScheduleMode()).isTrue();
        assertThat(returned).hasSize(1);
    }

    @Test
    @DisplayName("SAS-SCHED-011: a playlist saved with six slots is widened to sixteen on load")
    void aSixSlotPlaylistIsWidenedOnLoad() throws Exception {
        // ItemStackHandler.deserializeNBT resizes to the saved size; the load copies instead.
        net.neoforged.neoforge.items.ItemStackHandler saved = new net.neoforged.neoforge.items.ItemStackHandler(6);
        saved.setStackInSlot(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER));
        saved.setStackInSlot(5, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BOOK));
        net.neoforged.neoforge.items.ItemStackHandler into = new net.neoforged.neoforge.items.ItemStackHandler(
                PlaybackDeviceBlockEntity.PLAYLIST_SIZE);
        into.setStackInSlot(10, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.APPLE));

        PlaybackDeviceBlockEntity.copyPlaylist(saved, into);

        assertThat(into.getSlots()).isEqualTo(16);
        assertThat(into.getStackInSlot(0).is(net.minecraft.world.item.Items.PAPER)).isTrue();
        assertThat(into.getStackInSlot(5).is(net.minecraft.world.item.Items.BOOK)).isTrue();
        assertThat(into.getStackInSlot(10).isEmpty()).as("a slot the save did not have is emptied").isTrue();
        String be = sourceText("src/main/java/com/spatialaudiosystem/blockentity/PlaybackDeviceBlockEntity.java");
        assertThat(method(be, "protected void loadAdditional(")).as("the load goes through the copy")
                .contains("copyPlaylist(saved, playlist)").doesNotContain("playlist.deserializeNBT(");
    }

    @Test
    @DisplayName("SAS-RS-003: the start owns the playing entry and the stop clears it")
    void theStartOwnsThePlayingEntry() {
        // A row's preview leaves its index behind; a single medium started afterwards must not
        // end under that index, or a rule scoped to the previewed entry pulses for it.
        com.spatialaudiosystem.audio.PlaybackSessionRegistry.clear();
        net.minecraft.server.level.ServerLevel level = serverLevel();
        PlaybackDeviceBlockEntity d = deviceOn(level);
        set(d, "playingEntry", 2);
        set(d, "loopingEntry", -1);
        set(d, "playCounts", new int[PlaybackDeviceBlockEntity.PLAYLIST_SIZE]);
        net.neoforged.neoforge.items.ItemStackHandler slots = new net.neoforged.neoforge.items.ItemStackHandler(
                PlaybackDeviceBlockEntity.SLOT_COUNT);
        set(d, "inventory", slots);
        net.minecraft.world.item.ItemStack medium = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER);
        medium.set(com.spatialaudiosystem.item.ModDataComponents.AUDIO_FILE_NAME.get(), "hum.wav");
        d.addRedstoneRule();
        d.cycleRedstoneTrigger(0, 1);
        d.cycleRedstoneTrigger(0, 1);
        d.cycleRedstoneTrigger(0, 1);              // PLAYING -> START -> STOP -> END
        d.adjustRedstoneEntry(0, 1);
        d.adjustRedstoneEntry(0, 1);
        d.adjustRedstoneEntry(0, 1);               // scoped to entry 3 (= index 2)
        d.setRedstoneEnabled(true);

        try (org.mockito.MockedStatic<com.spatialaudiosystem.audio.PlaybackDelivery> delivery =
                     org.mockito.Mockito.mockStatic(com.spatialaudiosystem.audio.PlaybackDelivery.class)) {
            delivery.when(() -> com.spatialaudiosystem.audio.PlaybackDelivery.start(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean()))
                    .thenReturn(42L);
            assertThat(d.playMedia(medium, false, -1)).as("the single medium starts").isTrue();
        }
        assertThat(d.getPlayingEntry()).as("the single medium is no entry").isEqualTo(-1);

        d.setIsPlaying(false);                     // the finish report
        assertThat(d.getRedstoneOutput()).as("entry 3's end rule stays quiet for the single medium").isZero();

        try (org.mockito.MockedStatic<com.spatialaudiosystem.audio.PlaybackDelivery> delivery =
                     org.mockito.Mockito.mockStatic(com.spatialaudiosystem.audio.PlaybackDelivery.class)) {
            delivery.when(() -> com.spatialaudiosystem.audio.PlaybackDelivery.start(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean()))
                    .thenReturn(43L);
            assertThat(d.playMedia(medium, false, 2)).as("entry 3 starts").isTrue();
        }
        assertThat(d.getPlayingEntry()).isEqualTo(2);

        // The finish report posts PlaybackEndedEvent before it tells the device, and for a
        // row preview the scheduler clears the frame's entry inside that event. The end the
        // device then reports is still entry 3's: the sound's entry is not the frame's.
        d.setPlayingEntry(-1);
        d.setIsPlaying(false);
        assertThat(d.getRedstoneOutput()).as("entry 3's end rule pulses for entry 3's sound").isEqualTo(15);

        // The stop clears the frame's entry itself: every stop path but the scheduler's relies
        // on it. A fresh start first, or the -1 above would be the test's own.
        try (org.mockito.MockedStatic<com.spatialaudiosystem.audio.PlaybackDelivery> delivery =
                     org.mockito.Mockito.mockStatic(com.spatialaudiosystem.audio.PlaybackDelivery.class)) {
            delivery.when(() -> com.spatialaudiosystem.audio.PlaybackDelivery.start(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyBoolean()))
                    .thenReturn(44L);
            assertThat(d.playMedia(medium, false, 2)).isTrue();
        }
        assertThat(d.getPlayingEntry()).isEqualTo(2);
        d.stopPlayback();
        assertThat(d.getPlayingEntry()).as("nothing plays, nothing is the entry").isEqualTo(-1);
    }

    @Test
    @DisplayName("SAS-RS-003: the sounding entry survives a reload: its lamp re-lights and its end still pulses")
    void aReloadKeepsTheSoundingEntry() throws Exception {
        com.spatialaudiosystem.audio.PlaybackSessionRegistry.clear();
        net.minecraft.server.level.ServerLevel level = serverLevel();
        PlaybackDeviceBlockEntity d = deviceOn(level);
        net.minecraft.nbt.CompoundTag tag = savedLamp(true, 49_950L);
        tag.putInt("soundingEntry", 2);
        net.minecraft.nbt.ListTag rules = new net.minecraft.nbt.ListTag();
        rules.add(new RedstoneRule(Trigger.PLAYING, 15, 0, 10, 2).save());
        rules.add(new RedstoneRule(Trigger.END, 11, 0, 100, 2).save());
        rules.add(new RedstoneRule(Trigger.END, 5, 0, 100, 3).save());
        tag.put("redstoneRules", rules);

        call(d, "loadRedstone", new Class<?>[]{net.minecraft.nbt.CompoundTag.class}, tag);
        d.refreshRedstoneOutput();
        assertThat(d.getRedstoneOutput()).as("entry 2's lamp, lit from the saved entry").isEqualTo(15);

        set(d, "isPlaying", true);   // loadAdditional restores the field afterwards
        d.setIsPlaying(false);       // the end, reported after the reload
        d.refreshRedstoneOutput();
        // 11 is entry 2's END pulse with its lamp off; entry 3's 5 would be hidden under it either
        // way -- the scoping itself is pinned by aScopedPulseIgnoresOtherEntries.
        assertThat(d.getRedstoneOutput()).as("entry 2's end pulse, with its lamp off").isEqualTo(11);
    }

    @Test
    @DisplayName("SAS-RS-004: a rule swaps places with its neighbour, and stays put at either end")
    void theRuleOrderCanBeChanged() {
        PlaybackDeviceBlockEntity d = device();
        d.addRedstoneRule();
        d.addRedstoneRule();
        d.addRedstoneRule();
        d.adjustRedstoneStrength(1, -1);
        d.adjustRedstoneStrength(2, -1);
        d.adjustRedstoneStrength(2, -1);
        assertThat(strengths(d)).containsExactly(15, 14, 13);

        d.moveRedstoneRule(2, -1);
        assertThat(strengths(d)).as("the last moved up one").containsExactly(15, 13, 14);
        d.moveRedstoneRule(0, 1);
        assertThat(strengths(d)).as("the first moved down one").containsExactly(13, 15, 14);

        d.moveRedstoneRule(0, -1);   // nothing above the first
        d.moveRedstoneRule(2, 1);    // nothing below the last
        d.moveRedstoneRule(9, 1);    // past the list
        d.moveRedstoneRule(1, 0);    // no direction
        assertThat(strengths(d)).as("the ends and a bad index move nothing").containsExactly(13, 15, 14);
    }

    private static java.util.List<Integer> strengths(PlaybackDeviceBlockEntity d) {
        return d.getRedstoneRules().stream().map(RedstoneRule::strength).toList();
    }

    @Test
    @DisplayName("SAS-UI-004: a canvas inside a clickable node has a click case of its own")
    void aCanvasInsideAClickableNodeHasAClickCase() throws Exception {
        // The engine makes every canvas self-clickable (EventGraphCompiler.selfClickable), so a
        // click on it never reaches the parent: the screen must name the canvas's class, or the
        // parent is dead over its own icon. Manta's owner face handles its canvas itself.
        java.util.Set<String> handledElsewhere = java.util.Set.of("owner-face-canvas");
        String screens = sourceText("src/main/java/com/spatialaudiosystem/screen/PlaybackDeviceScreenV2.java")
                + sourceText("src/main/java/com/spatialaudiosystem/screen/RecordingDeviceScreenV2.java");
        java.nio.file.Path layouts = sourceDir("src/main/resources/assets/spatialaudiosystem/layouts");
        java.util.List<String> canvases = new ArrayList<>();
        java.util.List<String> unhandled = new ArrayList<>();
        try (var files = java.nio.file.Files.list(layouts)) {
            for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) files::iterator) {
                com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(
                        java.nio.file.Files.readString(f)).getAsJsonObject();
                collectCanvases(root, false, f.getFileName().toString(), canvases);
            }
        }
        assertThat(canvases).as("the walk sees the redstone button").anyMatch(c -> c.endsWith("pb-redstone-btn"));
        for (String c : canvases) {
            String cls = c.substring(c.indexOf(' ') + 1);
            if (handledElsewhere.contains(cls)) continue;
            if (!screens.contains("case \"" + cls + "\":")) unhandled.add(c);
        }
        assertThat(unhandled).as("canvas nodes under a clickable parent with no click case").isEmpty();
    }

    /** A directory of the mod's tree, wherever the test is run from (same walk as sourceText). */
    private static java.nio.file.Path sourceDir(String relative) {
        for (java.nio.file.Path base = java.nio.file.Paths.get("").toAbsolutePath();
             base != null; base = base.getParent()) {
            java.nio.file.Path c = base.resolve(relative);
            if (java.nio.file.Files.isDirectory(c)) return c;
        }
        throw new AssertionError("directory not found from " + java.nio.file.Paths.get("").toAbsolutePath());
    }

    /** Every canvas node below a clickable ancestor, as "file class". */
    private static void collectCanvases(com.google.gson.JsonObject node, boolean underClickable,
                                        String file, java.util.List<String> out) {
        boolean clickable = underClickable
                || (node.has("clickable") && node.get("clickable").getAsBoolean());
        if (clickable && node.has("tag") && "canvas".equals(node.get("tag").getAsString())) {
            for (var cls : node.getAsJsonArray("classes")) out.add(file + " " + cls.getAsString());
        }
        for (String key : new String[]{"children"}) {
            if (node.has(key)) {
                for (var child : node.getAsJsonArray(key)) collectCanvases(child.getAsJsonObject(), clickable, file, out);
            }
        }
        if (node.has("template")) collectCanvases(node.getAsJsonObject("template"), clickable, file, out);
    }

    @Test
    @DisplayName("SAS-RS-002: the playback start tick survives the disk")
    void thePlaybackStartTickSurvivesTheDisk() throws Exception {
        // saveAdditional needs a whole device to run, so the two lines are read. Without them a
        // chunk reload read the start as tick 0 and the timeout stopped the sound on the first
        // tick back -- now a real STOP, with pulses, because the plan resumes first.
        String be = sourceText("src/main/java/com/spatialaudiosystem/blockentity/PlaybackDeviceBlockEntity.java");
        assertThat(method(be, "protected void saveAdditional("))
                .contains("tag.putLong(\"playbackStartTick\", playbackStartTick);");
        assertThat(method(be, "protected void loadAdditional("))
                .contains("playbackStartTick = tag.getLong(\"playbackStartTick\");");
        // The sounding entry rides the same tag. Its load half has a behavioural test
        // (aReloadKeepsTheSoundingEntry, which writes its own tag); the save half is a line.
        assertThat(method(be, "protected void saveAdditional("))
                .contains("tag.putInt(\"soundingEntry\", soundingEntry);");
        assertThat(method(be, "private void loadRedstone("))
                .contains("soundingEntry = tag.getInt(\"soundingEntry\");");
    }

    @Test
    @DisplayName("SAS-RS-002: a loaded device tells its neighbours once, even at level zero")
    void aLoadedDeviceTellsItsNeighboursOnce() {
        // A chunk saved mid-playback keeps its dust powered and its lamp lit in their own
        // states. The device comes back at zero, and a refresh that spoke only on a change
        // would leave them lit forever -- review, 2026-09-03.
        com.spatialaudiosystem.audio.PlaybackSessionRegistry.clear();
        net.minecraft.server.level.ServerLevel level = serverLevel();
        PlaybackDeviceBlockEntity d = deviceOn(level);

        call(d, "loadRedstone", new Class<?>[]{net.minecraft.nbt.CompoundTag.class}, new net.minecraft.nbt.CompoundTag());
        d.refreshRedstoneOutput();
        d.refreshRedstoneOutput();

        org.mockito.Mockito.verify(level, org.mockito.Mockito.times(1))
                .updateNeighborsAt(org.mockito.ArgumentMatchers.eq(BlockPos.ZERO), org.mockito.ArgumentMatchers.any());
        assertThat(d.getRedstoneOutput()).isZero();
    }

    @Test
    @DisplayName("SAS-RS-002: playback feeds the plan at its start, its stop and its end, and the tick reads it")
    void playbackFeedsThePlan() throws Exception {
        String be = sourceText("src/main/java/com/spatialaudiosystem/blockentity/PlaybackDeviceBlockEntity.java");
        // Each event names its entry (1-based; 0 = the single medium), so scoped rules can tell.
        assertThat(method(be, "public boolean playMedia(ItemStack mediaStack, boolean loop, int entry)"))
                .contains("soundingEntry = entry + 1;")
                .contains("redstoneEvent(RedstoneOutputPlan.Event.START, soundingEntry)");
        assertThat(method(be, "public void stopPlayback()"))
                .contains("redstoneEvent(RedstoneOutputPlan.Event.STOP, soundingEntry)");
        assertThat(method(be, "public void setIsPlaying(boolean playing)"))
                .contains("redstoneEvent(RedstoneOutputPlan.Event.END, soundingEntry)");
        // The scheduler says which entry it starts, and stops before it forgets the entry.
        String sched = sourceText("src/main/java/com/spatialaudiosystem/audio/PlaybackScheduler.java");
        assertThat(method(sched, "private static void fire(")).contains("be.playMedia(media, loop, s.entryIdx)");
        assertThat(method(sched, "public static void testEntry(")).contains(", false, idx)");
        String stop = method(sched, "public static void stop(");
        assertThat(stop.indexOf("be.stopPlayback()")).as("stop, then forget the entry")
                .isGreaterThanOrEqualTo(0).isLessThan(stop.indexOf("be.setPlayingEntry(-1)"));
        assertThat(method(be, "public static void tick(")).contains("entity.refreshRedstoneOutput();");
        assertThat(method(be, "public static void tick(")).contains("entity.reconcileAfterLoad(afterLoad);");
        assertThat(method(be, "void refreshRedstoneOutput()")).contains("updateNeighborsAt(");
    }
}
