package com.spatialaudiosystem.audio;

import com.spatialaudiosystem.SpatialAudioSystem;
import javazoom.jl.decoder.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.*;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AudioManager {
    /**
     * Positive signal that a loop actually came round again, on its own logger.
     *
     * <p>Silence proves nothing: a sound that stopped and a sound still playing look identical
     * from outside, which is why an endless playback needs a line saying it restarted rather than
     * an absence of errors.
     */
    private static final org.slf4j.Logger LOOP_SIGNAL =
            org.slf4j.LoggerFactory.getLogger("SAS-Loop");
    /**
     * Restarts logged per playback before the signal falls silent.
     *
     * <p>Bounded because an endless ambience would otherwise write a line every few seconds for
     * the life of the server. Three is enough to show a loop is looping rather than having played
     * once; the driver asserts that many and no more.
     */
    private static final int LOOP_LOG_PASSES = 3;

    private static final AudioManager INSTANCE = new AudioManager();
    /**
     * The sound occupying each position, from the moment it is asked for until its worker
     * exits. A session exists before the decoder does, so a stop arriving while the file
     * is still being opened has something to mark; previously the stop looked for a
     * playback that had not been published yet, found nothing, and the sound started anyway.
     */
    private final Map<BlockPos, PlaybackSession> sessions = new ConcurrentHashMap<>();
    /** Playbacks that finished naturally (audio thread adds, render thread drains & reports). */
    private final java.util.Set<FinishedPlayback> finishedPlaybacks = ConcurrentHashMap.newKeySet();

    /** Passed to {@link #stopAudio} to mean "whatever is playing here". */
    public static final long ANY_PLAYBACK = 0L;

    /** A finished sound: the place it played and which sound it was. */
    public record FinishedPlayback(BlockPos pos, long playbackId) {}

    // Local player position updated from the main thread every frame.
    // The audio thread reads these volatile fields to avoid calling level.getNearestPlayer()
    // from a background thread (ConcurrentModificationException on ClientLevel's player list).
    private static volatile double localPlayerX = 0, localPlayerY = 0, localPlayerZ = 0;
    private static volatile boolean localPlayerPosValid = false;

    public static void updateLocalPlayerPos(double x, double y, double z) {
        localPlayerX = x;
        localPlayerY = y;
        localPlayerZ = z;
        localPlayerPosValid = true;
    }

    /**
     * Called every render frame from the main thread to update gain for all active playbacks.
     * Also drains finished playbacks and returns their positions so the caller can notify the server.
     */
    public static List<FinishedPlayback> tickGain() {
        if (!localPlayerPosValid) return List.of();
        for (PlaybackSession session : INSTANCE.sessions.values()) {
            AudioPlayback playback = session.playback();
            if (playback != null) INSTANCE.updateVolumeForPlayers(playback);
        }
        // Drain finished playbacks (audio thread adds, render thread drains)
        if (INSTANCE.finishedPlaybacks.isEmpty()) return List.of();
        List<FinishedPlayback> finished = new ArrayList<>(INSTANCE.finishedPlaybacks);
        INSTANCE.finishedPlaybacks.clear();
        return finished;
    }

    public static AudioManager getInstance() {
        return INSTANCE;
    }

    /**
     * attenuationRanges: int[6] = [East(+X), West(-X), Up(+Y), Down(-Y), South(+Z), North(-Z)]
     */
    public void playAudio(Level level, BlockPos pos, long playbackId, byte[] audioData, String format,
                          BlockPos rangePos1, BlockPos rangePos2, boolean attenuationMode, int[] attenuationRanges) {
        playAudio(level, pos, playbackId, audioData, format, rangePos1, rangePos2,
                attenuationMode, attenuationRanges, false);
    }

    /**
     * @param loop when true the decoder restarts at the top instead of finishing, on the same
     *             open line. Looping costs no further network traffic and produces no completion
     *             report, so an endless ambience does not depend on a client → server → client
     *             round trip that stops happening the moment nobody is online.
     */
    public void playAudio(Level level, BlockPos pos, long playbackId, byte[] audioData, String format,
                          BlockPos rangePos1, BlockPos rangePos2, boolean attenuationMode,
                          int[] attenuationRanges, boolean loop) {
        PlaybackSession session = new PlaybackSession(
                pos, playbackId, rangePos1, rangePos2, attenuationMode, attenuationRanges, loop);

        // Published before the worker exists, so a stop in the next millisecond has
        // something to cancel rather than racing an unpublished playback.
        PlaybackSession previous = sessions.put(pos, session);
        if (previous != null) previous.cancel();

        Thread playThread = new Thread(() -> {
            try {
                decodeAndPlay(session, level, audioData, format);
            } catch (Exception e) {
                SpatialAudioSystem.LOGGER.error("Failed to play audio at {}", pos, e);
            } finally {
                sessions.remove(pos, session);
                // A looping sound only ever ends because something stopped it, and whatever
                // stopped it already knows. Reporting here would tell the server a sound it
                // just cancelled has finished, and for a loop that was never restarted by a
                // report in the first place it can only cause a spurious restart.
                if (!session.loop) {
                    // Report whichever sound this was. The server decides whether it is still
                    // the one playing there.
                    finishedPlaybacks.add(new FinishedPlayback(pos, playbackId));
                }
            }
        }, "SSS-Audio-" + pos.toShortString());
        playThread.setDaemon(true);
        playThread.start();
    }

    /** Records that a looping playback began its {@code pass}-th time through the audio. */
    private static void signalLoopRestart(PlaybackSession session, String format, int pass) {
        if (pass > LOOP_LOG_PASSES) return;
        LOOP_SIGNAL.info("restart pos={},{},{} id={} pass={} format={}",
                session.pos.getX(), session.pos.getY(), session.pos.getZ(),
                String.format("%016x", session.playbackId), pass, format);
    }

    private void decodeAndPlay(PlaybackSession session, Level level, byte[] audioData, String format)
            throws Exception {
        if ("mp3".equalsIgnoreCase(format)) {
            streamMp3(session, level, audioData);
        } else if ("ogg".equalsIgnoreCase(format)) {
            streamOgg(session, level, audioData);
        } else if ("wav".equalsIgnoreCase(format)) {
            streamWav(session, level, audioData);
        } else {
            SpatialAudioSystem.LOGGER.error("Unsupported audio format: {}", format);
        }
    }

    /** The part of playback that writes decoded PCM to an open line. */
    private interface PcmWriter {
        void write(SourceDataLine line, AudioPlayback playback) throws Exception;
    }

    /**
     * Opens a line for {@code format}, runs {@code writer} against it, and closes it
     * whatever happens. Every decoder shares this so the line cannot outlive a failure
     * in one of them.
     */
    private void runOnLine(PlaybackSession session, AudioFormat format, PcmWriter writer) throws Exception {
        if (session.isCancelled()) return;

        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(format);
        try {
            FloatControl volumeControl = line.isControlSupported(FloatControl.Type.MASTER_GAIN)
                    ? (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN)
                    : null;

            AudioPlayback playback = new AudioPlayback(line, session.pos, session.rangePos1, session.rangePos2,
                    volumeControl, session.attenuationMode, session.attenuationRanges);
            session.attach(playback);
            // A stop that arrived while the file was being decoded lands on the session,
            // and attach hands it straight to the playback.
            if (playback.isStopped()) return;

            updateVolumeForPlayers(playback);
            line.start();

            writer.write(line, playback);

            if (!playback.isStopped()) line.drain();
        } finally {
            try {
                line.stop();
                line.close();
            } catch (Exception ignored) {
                // Already closed by AudioPlayback.stop(); nothing left to release.
            }
        }
    }

    private void streamMp3(PlaybackSession session, Level level, byte[] audioData) throws Exception {
        // The line's format has to be known before the line is opened, and for MP3 that means
        // decoding a frame. Doing it on a throwaway stream lets every real pass — including a
        // loop's second and later passes — start from frame 0 through one code path.
        AudioFormat audioFormat = probeMp3Format(session, audioData);
        if (audioFormat == null) return;

        final Bitstream[] stream = {null};
        try {
            runOnLine(session, audioFormat, (line, playback) -> {
                int pass = 0;
                do {
                    if (++pass > 1) signalLoopRestart(session, "mp3", pass);
                    closeQuietly(stream[0]);
                    stream[0] = new Bitstream(new ByteArrayInputStream(audioData));
                    Decoder decoder = new Decoder();
                    boolean producedThisPass = false;

                    Header header;
                    while ((header = stream[0].readFrame()) != null) {
                        if (playback.isStopped()) break;
                        SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, stream[0]);
                        writeFrameToLine(output, line, playback, level);
                        stream[0].closeFrame();
                        producedThisPass = true;
                    }
                    // A pass that produced nothing must not be restarted, or an undecodable
                    // file would spin this thread at full speed forever.
                    if (!producedThisPass) break;
                } while (session.loop && !playback.isStopped());
            });
        } finally {
            closeQuietly(stream[0]);
        }
    }

    /** The output format of {@code audioData}, or null if it holds no decodable frame. */
    private AudioFormat probeMp3Format(PlaybackSession session, byte[] audioData) throws Exception {
        Bitstream probe = new Bitstream(new ByteArrayInputStream(audioData));
        try {
            Header firstHeader = probe.readFrame();
            if (firstHeader == null) {
                SpatialAudioSystem.LOGGER.error("MP3 file has no frames at {}", session.pos);
                return null;
            }
            Decoder decoder = new Decoder();
            decoder.decodeFrame(firstHeader, probe);
            return new AudioFormat(
                    decoder.getOutputFrequency(), 16, decoder.getOutputChannels(), true, false);
        } finally {
            closeQuietly(probe);
        }
    }

    private static void closeQuietly(Bitstream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (Exception ignored) {
            // In-memory stream; there is no OS handle to leak and nothing a caller could do.
        }
    }

    private void writeFrameToLine(SampleBuffer output, SourceDataLine line,
                                   AudioPlayback playback, Level level) {
        float gain = playback.getSoftwareGain();
        short[] samples = output.getBuffer();
        int len = output.getBufferLength();
        byte[] bytes = new byte[len * 2];
        for (int i = 0; i < len; i++) {
            short s = gain < 0.999f ? (short) (samples[i] * gain) : samples[i];
            bytes[i * 2] = (byte) (s & 0xFF);
            bytes[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        line.write(bytes, 0, len * 2);
    }

    private void streamOgg(PlaybackSession session, Level level, byte[] audioData) throws Exception {
        ByteBuffer buf = MemoryUtil.memAlloc(audioData.length);
        buf.put(audioData).flip();

        long vorbis = 0;
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer errorBuffer = stack.mallocInt(1);
                vorbis = STBVorbis.stb_vorbis_open_memory(buf, errorBuffer, null);
                if (vorbis == 0) {
                    SpatialAudioSystem.LOGGER.error("Failed to open OGG, error: {}", errorBuffer.get(0));
                    return;
                }
            }

            var vorbisInfo = STBVorbis.stb_vorbis_get_info(vorbis, org.lwjgl.stb.STBVorbisInfo.malloc());
            int channels = vorbisInfo.channels();
            int sampleRate = vorbisInfo.sample_rate();
            vorbisInfo.free();

            AudioFormat audioFormat = new AudioFormat(sampleRate, 16, channels, true, false);
            final long vorbisHandle = vorbis;

            runOnLine(session, audioFormat, (line, playback) -> {
                int chunkSamples = 4096;
                ShortBuffer pcmBuffer = MemoryUtil.memAllocShort(chunkSamples * channels);
                byte[] writeBuffer = new byte[chunkSamples * channels * 2];
                // A pass that produced nothing must not be restarted: a file that decodes to
                // no samples would otherwise spin this thread at full speed forever.
                boolean producedThisPass = false;
                int pass = 1;
                try {
                    while (!playback.isStopped()) {
                        pcmBuffer.clear();
                        int samplesRead = STBVorbis.stb_vorbis_get_samples_short_interleaved(
                                vorbisHandle, channels, pcmBuffer);
                        if (samplesRead == 0) {
                            if (!session.loop || !producedThisPass) break;
                            // Seeking the open decoder keeps the line running, so the loop
                            // point carries no gap and costs no network traffic.
                            STBVorbis.stb_vorbis_seek_start(vorbisHandle);
                            producedThisPass = false;
                            signalLoopRestart(session, "ogg", ++pass);
                            continue;
                        }
                        producedThisPass = true;

                        int totalShorts = samplesRead * channels;
                        float gain = playback.getSoftwareGain();
                        for (int i = 0; i < totalShorts; i++) {
                            short sample = pcmBuffer.get(i);
                            if (gain < 0.999f) sample = (short) (sample * gain);
                            writeBuffer[i * 2] = (byte) (sample & 0xFF);
                            writeBuffer[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
                        }
                        line.write(writeBuffer, 0, totalShorts * 2);
                    }
                } finally {
                    MemoryUtil.memFree(pcmBuffer);
                }
            });
        } finally {
            if (vorbis != 0) STBVorbis.stb_vorbis_close(vorbis);
            MemoryUtil.memFree(buf);
        }
    }

    private void streamWav(PlaybackSession session, Level level, byte[] audioData) throws Exception {
        AudioFormat pcmFormat;
        try (AudioInputStream probe = AudioSystem.getAudioInputStream(
                new BufferedInputStream(new ByteArrayInputStream(audioData)))) {
            AudioFormat base = probe.getFormat();
            pcmFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    base.getSampleRate(), 16,
                    base.getChannels(), base.getChannels() * 2,
                    base.getSampleRate(), false);
        }

        final AudioInputStream[] stream = {null};
        try {
            runOnLine(session, pcmFormat, (line, playback) -> {
                byte[] buffer = new byte[4096];
                int pass = 0;
                do {
                    if (++pass > 1) signalLoopRestart(session, "wav", pass);
                    closeStreamQuietly(stream[0]);
                    stream[0] = openWavPcm(audioData, pcmFormat);
                    boolean producedThisPass = false;

                    int bytesRead;
                    while ((bytesRead = stream[0].read(buffer)) != -1) {
                        if (playback.isStopped()) break;
                        if (bytesRead > 0) producedThisPass = true;
                        float gain = playback.getSoftwareGain();
                        if (gain < 0.999f) {
                            for (int j = 0; j + 1 < bytesRead; j += 2) {
                                short sample = (short) ((buffer[j] & 0xFF) | (buffer[j + 1] << 8));
                                sample = (short) (sample * gain);
                                buffer[j] = (byte) (sample & 0xFF);
                                buffer[j + 1] = (byte) ((sample >> 8) & 0xFF);
                            }
                        }
                        line.write(buffer, 0, bytesRead);
                    }
                    // A pass that produced nothing must not be restarted, or a file with no
                    // frames would spin this thread at full speed forever.
                    if (!producedThisPass) break;
                } while (session.loop && !playback.isStopped());
            });
        } finally {
            closeStreamQuietly(stream[0]);
        }
    }

    /** A fresh decoded-PCM view of {@code audioData}, so each loop pass starts at sample 0. */
    private static AudioInputStream openWavPcm(byte[] audioData, AudioFormat pcmFormat) throws Exception {
        AudioInputStream source = AudioSystem.getAudioInputStream(
                new BufferedInputStream(new ByteArrayInputStream(audioData)));
        AudioFormat base = source.getFormat();
        boolean alreadyPcm = base.getEncoding() == AudioFormat.Encoding.PCM_SIGNED
                && base.getSampleSizeInBits() == 16;
        // Closing the converting stream closes the source it wraps, so the caller owns one thing.
        return alreadyPcm ? source : AudioSystem.getAudioInputStream(pcmFormat, source);
    }

    private static void closeStreamQuietly(AutoCloseable stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (Exception ignored) {
            // In-memory stream; there is no OS handle to leak and nothing a caller could do.
        }
    }

    private void updateVolumeForPlayers(AudioPlayback playback) {
        // Use the position cached by the main thread. If not yet set, skip this update
        // rather than calling level.getNearestPlayer() from a background thread, which
        // would cause ConcurrentModificationException on ClientLevel's player list.
        if (!localPlayerPosValid) return;

        // The same predicate the server uses to decide whether a joining player gets sent this
        // audio at all. Kept as one implementation on purpose: two copies would drift into
        // shipping megabytes to someone who hears silence, or leaving a player standing inside
        // an audible box with nothing playing, and neither shows up as an error. See SpatialGain.
        float linearFactor = SpatialGain.linearGain(
                localPlayerX, localPlayerY, localPlayerZ,
                playback.getPos(), playback.getRangePos1(), playback.getRangePos2(),
                playback.isAttenuationMode(), playback.getAttenuationRanges());

        applyGain(playback, linearFactor);
    }

    private void applyGain(AudioPlayback playback, float linearFactor) {
        FloatControl vc = playback.getVolumeControl();
        if (vc != null) {
            // Hardware gain handles volume — keep softwareGain at 1.0 so PCM data is unmodified
            if (linearFactor <= 0.0f) {
                vc.setValue(vc.getMinimum());
            } else {
                float dB = (float) (20.0 * Math.log10(Math.max(linearFactor, 1e-5f)));
                vc.setValue(Math.max(vc.getMinimum(), Math.min(vc.getMaximum(), dB)));
            }
            playback.setSoftwareGain(1.0f);
        } else {
            // Hardware gain control not available — use software gain applied during PCM write
            playback.setSoftwareGain(linearFactor);
        }
    }

    /**
     * Stops the sound at {@code pos}.
     *
     * @param playbackId the sound to stop, or {@link #ANY_PLAYBACK} for whatever is there.
     *                   A stop naming a playback that has already been replaced is ignored.
     */
    public void stopAudio(BlockPos pos, long playbackId) {
        PlaybackSession session = sessions.get(pos);
        if (session == null) return;
        if (playbackId != ANY_PLAYBACK && session.playbackId != playbackId) return;

        sessions.remove(pos, session);
        session.cancel();
    }

    public boolean isPlaying(BlockPos pos) {
        return sessions.containsKey(pos);
    }

    public void stopAll() {
        for (PlaybackSession session : sessions.values()) session.cancel();
        sessions.clear();
        finishedPlaybacks.clear();
    }

    /**
     * One sound at one position, owned by the manager rather than by the worker thread.
     *
     * <p>Cancellation has to be observable before the line exists: the file may still be
     * decoding when the stop arrives. {@link #attach} closes that window from the other
     * side, so a stop is honoured whichever order the two happen in.
     */
    static final class PlaybackSession {
        final BlockPos pos;
        final long playbackId;
        final BlockPos rangePos1;
        final BlockPos rangePos2;
        final boolean attenuationMode;
        final int[] attenuationRanges;
        /** Restart at the top on reaching the end, rather than ending. */
        final boolean loop;

        private volatile boolean cancelled;
        private volatile AudioPlayback playback;

        PlaybackSession(BlockPos pos, long playbackId, BlockPos rangePos1, BlockPos rangePos2,
                        boolean attenuationMode, int[] attenuationRanges, boolean loop) {
            this.pos = pos;
            this.playbackId = playbackId;
            this.rangePos1 = rangePos1;
            this.rangePos2 = rangePos2;
            this.attenuationMode = attenuationMode;
            this.attenuationRanges = Arrays.copyOf(attenuationRanges, 6);
            this.loop = loop;
        }

        boolean isCancelled() {
            return cancelled;
        }

        AudioPlayback playback() {
            return playback;
        }

        /** Publishes the line's playback, or stops it at once if the stop got here first. */
        void attach(AudioPlayback opened) {
            playback = opened;
            if (cancelled) opened.stop();
        }

        void cancel() {
            cancelled = true;
            AudioPlayback current = playback;
            if (current != null) current.stop();
        }
    }

    static class AudioPlayback {
        private final SourceDataLine line;
        private final BlockPos pos;
        private final BlockPos rangePos1;
        private final BlockPos rangePos2;
        private final FloatControl volumeControl;
        private final boolean attenuationMode;
        private final int[] attenuationRanges; // [East,West,Up,Down,South,North]
        private volatile boolean stopped = false;
        private volatile float softwareGain = 0.0f; // start muted; tickGain sets correct value

        public AudioPlayback(SourceDataLine line, BlockPos pos,
                             BlockPos rangePos1, BlockPos rangePos2,
                             FloatControl volumeControl, boolean attenuationMode, int[] attenuationRanges) {
            this.line = line;
            this.pos = pos;
            this.rangePos1 = rangePos1;
            this.rangePos2 = rangePos2;
            this.volumeControl = volumeControl;
            this.attenuationMode = attenuationMode;
            this.attenuationRanges = Arrays.copyOf(attenuationRanges, 6);
            // Start hardware gain at minimum too, so no sound leaks before tickGain runs
            if (volumeControl != null) {
                volumeControl.setValue(volumeControl.getMinimum());
            }
        }

        public void stop() {
            stopped = true;
            try { line.stop(); line.close(); } catch (Exception ignored) {}
        }

        public boolean isStopped() { return stopped; }
        public BlockPos getPos() { return pos; }
        public BlockPos getRangePos1() { return rangePos1; }
        public BlockPos getRangePos2() { return rangePos2; }
        public FloatControl getVolumeControl() { return volumeControl; }
        public boolean isAttenuationMode() { return attenuationMode; }
        public int[] getAttenuationRanges() { return attenuationRanges; }
        public float getSoftwareGain() { return softwareGain; }
        public void setSoftwareGain(float gain) { this.softwareGain = gain; }
    }
}
