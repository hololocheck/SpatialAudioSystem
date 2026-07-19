package com.spatialaudiosystem.audio;

import com.spatialaudiosystem.SpatialAudioSystem;
import javazoom.jl.decoder.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
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
        PlaybackSession session = new PlaybackSession(
                pos, playbackId, rangePos1, rangePos2, attenuationMode, attenuationRanges);

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
                // Report whichever sound this was. The server decides whether it is still
                // the one playing there.
                finishedPlaybacks.add(new FinishedPlayback(pos, playbackId));
            }
        }, "SSS-Audio-" + pos.toShortString());
        playThread.setDaemon(true);
        playThread.start();
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
        ByteArrayInputStream bais = new ByteArrayInputStream(audioData);
        Bitstream bitstream = new Bitstream(bais);
        Decoder decoder = new Decoder();
        try {
            Header firstHeader = bitstream.readFrame();
            if (firstHeader == null) {
                SpatialAudioSystem.LOGGER.error("MP3 file has no frames at {}", session.pos);
                return;
            }

            SampleBuffer firstOutput = (SampleBuffer) decoder.decodeFrame(firstHeader, bitstream);
            AudioFormat audioFormat = new AudioFormat(
                    decoder.getOutputFrequency(), 16, decoder.getOutputChannels(), true, false);
            bitstream.closeFrame();

            runOnLine(session, audioFormat, (line, playback) -> {
                writeFrameToLine(firstOutput, line, playback, level);

                Header header;
                while ((header = bitstream.readFrame()) != null) {
                    if (playback.isStopped()) break;
                    SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                    writeFrameToLine(output, line, playback, level);
                    bitstream.closeFrame();
                }
            });
        } finally {
            try {
                bitstream.close();
            } catch (Exception ignored) {
                // Decoding already failed; the stream is in-memory and holds no OS handle.
            }
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
                try {
                    while (!playback.isStopped()) {
                        pcmBuffer.clear();
                        int samplesRead = STBVorbis.stb_vorbis_get_samples_short_interleaved(
                                vorbisHandle, channels, pcmBuffer);
                        if (samplesRead == 0) break;

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
        ByteArrayInputStream bais = new ByteArrayInputStream(audioData);
        BufferedInputStream bis = new BufferedInputStream(bais);

        try (AudioInputStream audioStream = AudioSystem.getAudioInputStream(bis)) {
            AudioFormat baseFormat = audioStream.getFormat();
            AudioFormat pcmFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    baseFormat.getSampleRate(), 16,
                    baseFormat.getChannels(), baseFormat.getChannels() * 2,
                    baseFormat.getSampleRate(), false);

            boolean alreadyPcm = baseFormat.getEncoding() == AudioFormat.Encoding.PCM_SIGNED
                    && baseFormat.getSampleSizeInBits() == 16;
            AudioInputStream pcmStream = alreadyPcm
                    ? audioStream
                    : AudioSystem.getAudioInputStream(pcmFormat, audioStream);
            try {
                runOnLine(session, pcmFormat, (line, playback) -> {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = pcmStream.read(buffer)) != -1) {
                        if (playback.isStopped()) break;
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
                });
            } finally {
                // Only close the converting stream; the try-with-resources owns the source.
                if (pcmStream != audioStream) pcmStream.close();
            }
        }
    }

    private void updateVolumeForPlayers(AudioPlayback playback) {
        // Use the position cached by the main thread. If not yet set, skip this update
        // rather than calling level.getNearestPlayer() from a background thread, which
        // would cause ConcurrentModificationException on ClientLevel's player list.
        if (!localPlayerPosValid) return;
        double px = localPlayerX, py = localPlayerY, pz = localPlayerZ;

        float linearFactor;
        if (playback.getRangePos1() != null && playback.getRangePos2() != null) {
            BlockPos rp1 = playback.getRangePos1();
            BlockPos rp2 = playback.getRangePos2();
            AABB rangeBounds = new AABB(
                    Math.min(rp1.getX(), rp2.getX()), Math.min(rp1.getY(), rp2.getY()), Math.min(rp1.getZ(), rp2.getZ()),
                    Math.max(rp1.getX(), rp2.getX()) + 1, Math.max(rp1.getY(), rp2.getY()) + 1, Math.max(rp1.getZ(), rp2.getZ()) + 1);

            // Use attenuation settings captured at playback start (thread-safe, no level access)
            boolean attenuationMode = playback.isAttenuationMode();
            int[] ranges = playback.getAttenuationRanges();

            if (rangeBounds.contains(px, py, pz)) {
                linearFactor = 1.0f;
            } else if (!attenuationMode) {
                linearFactor = 0.0f;
            } else {
                // Per-direction attenuation: indices [East,West,Up,Down,South,North]
                double factorX = computeAxisFactor(px, rangeBounds.minX, rangeBounds.maxX, ranges[1], ranges[0]);
                double factorY = computeAxisFactor(py, rangeBounds.minY, rangeBounds.maxY, ranges[3], ranges[2]);
                double factorZ = computeAxisFactor(pz, rangeBounds.minZ, rangeBounds.maxZ, ranges[5], ranges[4]);
                linearFactor = (float) Math.max(0.0, factorX * factorY * factorZ);
            }
        } else {
            // No range defined: distance from the device itself.
            double dx = px - (playback.getPos().getX() + 0.5);
            double dy = py - (playback.getPos().getY() + 0.5);
            double dz = pz - (playback.getPos().getZ() + 0.5);
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (playback.isAttenuationMode()) {
                // Attenuation on: fade over the device's configured range (the server fills
                // the ranges array with it when no board is inserted). This branch used to
                // ignore the mode entirely and always fall through to the 160-block curve,
                // which at normal distances is inaudible - the toggle appeared to do nothing.
                int[] ranges = playback.getAttenuationRanges();
                int range = (ranges != null && ranges.length > 0) ? ranges[0] : 8;
                linearFactor = range <= 0 ? 0.0f : (float) Math.max(0.0, 1.0 - dist / range);
            } else {
                // Attenuation off: the old gentle ambient falloff.
                float maxSearchDistance = 160.0f;
                linearFactor = 1.0f - Math.min(1.0f, Math.max(0.0f, (float) dist / maxSearchDistance));
            }
        }

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

    /** Compute linear fade factor for one axis. */
    private static double computeAxisFactor(double p, double min, double max, int negRange, int posRange) {
        if (p < min) {
            double dist = min - p;
            return negRange <= 0 ? 0.0 : Math.max(0.0, 1.0 - dist / negRange);
        } else if (p > max) {
            double dist = p - max;
            return posRange <= 0 ? 0.0 : Math.max(0.0, 1.0 - dist / posRange);
        }
        return 1.0;
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

        private volatile boolean cancelled;
        private volatile AudioPlayback playback;

        PlaybackSession(BlockPos pos, long playbackId, BlockPos rangePos1, BlockPos rangePos2,
                        boolean attenuationMode, int[] attenuationRanges) {
            this.pos = pos;
            this.playbackId = playbackId;
            this.rangePos1 = rangePos1;
            this.rangePos2 = rangePos2;
            this.attenuationMode = attenuationMode;
            this.attenuationRanges = Arrays.copyOf(attenuationRanges, 6);
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
