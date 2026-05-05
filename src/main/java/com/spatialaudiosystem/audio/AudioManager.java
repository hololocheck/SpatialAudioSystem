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
    private final Map<BlockPos, AudioPlayback> activePlaybacks = new ConcurrentHashMap<>();
    private final java.util.Set<BlockPos> pendingPlaybacks = ConcurrentHashMap.newKeySet();
    /** Positions where playback finished naturally (audio thread adds, render thread drains & sends stop). */
    private final java.util.Set<BlockPos> finishedPlaybacks = ConcurrentHashMap.newKeySet();

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
    public static List<BlockPos> tickGain() {
        if (!localPlayerPosValid) return List.of();
        for (AudioPlayback playback : INSTANCE.activePlaybacks.values()) {
            INSTANCE.updateVolumeForPlayers(playback);
        }
        // Drain finished playbacks (audio thread adds, render thread drains)
        if (INSTANCE.finishedPlaybacks.isEmpty()) return List.of();
        List<BlockPos> finished = new ArrayList<>(INSTANCE.finishedPlaybacks);
        INSTANCE.finishedPlaybacks.clear();
        return finished;
    }

    public static AudioManager getInstance() {
        return INSTANCE;
    }

    /**
     * attenuationRanges: int[6] = [East(+X), West(-X), Up(+Y), Down(-Y), South(+Z), North(-Z)]
     */
    public void playAudio(Level level, BlockPos pos, byte[] audioData, String format,
                          BlockPos rangePos1, BlockPos rangePos2, boolean attenuationMode, int[] attenuationRanges) {
        stopAudio(pos);

        // Mark as pending BEFORE starting the thread so isPlaying() returns true immediately
        pendingPlaybacks.add(pos);

        final int[] ranges = Arrays.copyOf(attenuationRanges, 6);

        Thread playThread = new Thread(() -> {
            AudioPlayback thisPlayback = null;
            try {
                if ("mp3".equalsIgnoreCase(format)) {
                    thisPlayback = streamMp3(level, pos, audioData, rangePos1, rangePos2, attenuationMode, ranges);
                } else if ("ogg".equalsIgnoreCase(format)) {
                    thisPlayback = streamOgg(level, pos, audioData, rangePos1, rangePos2, attenuationMode, ranges);
                } else if ("wav".equalsIgnoreCase(format)) {
                    thisPlayback = streamWav(level, pos, audioData, rangePos1, rangePos2, attenuationMode, ranges);
                } else {
                    SpatialAudioSystem.LOGGER.error("Unsupported audio format: {}", format);
                }
            } catch (Exception e) {
                SpatialAudioSystem.LOGGER.error("Failed to play audio at {}", pos, e);
            } finally {
                pendingPlaybacks.remove(pos);
                if (thisPlayback != null) {
                    activePlaybacks.remove(pos, thisPlayback);
                } else {
                    activePlaybacks.remove(pos);
                }
                // Signal that playback ended so render thread can notify the server
                finishedPlaybacks.add(pos);
            }
        }, "SSS-Audio-" + pos.toShortString());
        playThread.setDaemon(true);
        playThread.start();
    }

    private AudioPlayback streamMp3(Level level, BlockPos pos, byte[] audioData,
                                     BlockPos rangePos1, BlockPos rangePos2,
                                     boolean attenuationMode, int[] attenuationRanges) throws Exception {
        ByteArrayInputStream bais = new ByteArrayInputStream(audioData);
        Bitstream bitstream = new Bitstream(bais);
        Decoder decoder = new Decoder();

        Header firstHeader = bitstream.readFrame();
        if (firstHeader == null) {
            SpatialAudioSystem.LOGGER.error("MP3 file has no frames at {}", pos);
            bitstream.close();
            return null;
        }

        SampleBuffer firstOutput = (SampleBuffer) decoder.decodeFrame(firstHeader, bitstream);
        AudioFormat audioFormat = new AudioFormat(
                decoder.getOutputFrequency(), 16, decoder.getOutputChannels(), true, false);
        bitstream.closeFrame();

        DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(audioFormat);

        FloatControl volumeControl = null;
        if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            volumeControl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
        }

        AudioPlayback playback = new AudioPlayback(line, pos, rangePos1, rangePos2,
                volumeControl, attenuationMode, attenuationRanges);
        activePlaybacks.put(pos, playback);

        updateVolumeForPlayers(playback);
        line.start();

        writeFrameToLine(firstOutput, line, playback, level);

        Header header;
        while ((header = bitstream.readFrame()) != null) {
            if (playback.isStopped()) break;
            SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);
            writeFrameToLine(output, line, playback, level);
            bitstream.closeFrame();
        }

        bitstream.close();
        if (!playback.isStopped()) line.drain();
        line.stop();
        line.close();
        return playback;
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

    private AudioPlayback streamOgg(Level level, BlockPos pos, byte[] audioData,
                                     BlockPos rangePos1, BlockPos rangePos2,
                                     boolean attenuationMode, int[] attenuationRanges) throws Exception {
        ByteBuffer buf = MemoryUtil.memAlloc(audioData.length);
        buf.put(audioData).flip();

        long vorbis = 0;
        try {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer errorBuffer = stack.mallocInt(1);
                vorbis = STBVorbis.stb_vorbis_open_memory(buf, errorBuffer, null);
                if (vorbis == 0) {
                    SpatialAudioSystem.LOGGER.error("Failed to open OGG, error: {}", errorBuffer.get(0));
                    return null;
                }
            }

            var vorbisInfo = STBVorbis.stb_vorbis_get_info(vorbis, org.lwjgl.stb.STBVorbisInfo.malloc());
            int channels = vorbisInfo.channels();
            int sampleRate = vorbisInfo.sample_rate();
            vorbisInfo.free();

            AudioFormat audioFormat = new AudioFormat(sampleRate, 16, channels, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(audioFormat);

            FloatControl volumeControl = null;
            if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                volumeControl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            }

            AudioPlayback playback = new AudioPlayback(line, pos, rangePos1, rangePos2,
                    volumeControl, attenuationMode, attenuationRanges);
            activePlaybacks.put(pos, playback);

            updateVolumeForPlayers(playback);
            line.start();

            int chunkSamples = 4096;
            ShortBuffer pcmBuffer = MemoryUtil.memAllocShort(chunkSamples * channels);
            byte[] writeBuffer = new byte[chunkSamples * channels * 2];

            try {
                while (!playback.isStopped()) {
                    pcmBuffer.clear();
                    int samplesRead = STBVorbis.stb_vorbis_get_samples_short_interleaved(
                            vorbis, channels, pcmBuffer);
                    if (samplesRead == 0) break;

                    int totalShorts = samplesRead * channels;
                    float gain = playback.getSoftwareGain();
                    for (int i = 0; i < totalShorts; i++) {
                        short s = pcmBuffer.get(i);
                        if (gain < 0.999f) s = (short) (s * gain);
                        writeBuffer[i * 2] = (byte) (s & 0xFF);
                        writeBuffer[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
                    }
                    line.write(writeBuffer, 0, totalShorts * 2);
                }
            } finally {
                MemoryUtil.memFree(pcmBuffer);
            }

            if (!playback.isStopped()) line.drain();
            line.stop();
            line.close();
            return playback;

        } finally {
            if (vorbis != 0) STBVorbis.stb_vorbis_close(vorbis);
            MemoryUtil.memFree(buf);
        }
    }

    private AudioPlayback streamWav(Level level, BlockPos pos, byte[] audioData,
                                     BlockPos rangePos1, BlockPos rangePos2,
                                     boolean attenuationMode, int[] attenuationRanges) throws Exception {
        ByteArrayInputStream bais = new ByteArrayInputStream(audioData);
        BufferedInputStream bis = new BufferedInputStream(bais);
        AudioInputStream audioStream = AudioSystem.getAudioInputStream(bis);

        AudioFormat baseFormat = audioStream.getFormat();
        AudioFormat pcmFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                baseFormat.getSampleRate(), 16,
                baseFormat.getChannels(), baseFormat.getChannels() * 2,
                baseFormat.getSampleRate(), false);

        AudioInputStream pcmStream;
        if (baseFormat.getEncoding() == AudioFormat.Encoding.PCM_SIGNED && baseFormat.getSampleSizeInBits() == 16) {
            pcmStream = audioStream;
        } else {
            pcmStream = AudioSystem.getAudioInputStream(pcmFormat, audioStream);
        }

        DataLine.Info info = new DataLine.Info(SourceDataLine.class, pcmFormat);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(pcmFormat);

        FloatControl volumeControl = null;
        if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            volumeControl = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
        }

        AudioPlayback playback = new AudioPlayback(line, pos, rangePos1, rangePos2,
                volumeControl, attenuationMode, attenuationRanges);
        activePlaybacks.put(pos, playback);

        updateVolumeForPlayers(playback);
        line.start();

        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = pcmStream.read(buffer)) != -1) {
            if (playback.isStopped()) break;
            float gain = playback.getSoftwareGain();
            if (gain < 0.999f) {
                for (int j = 0; j + 1 < bytesRead; j += 2) {
                    short s = (short) ((buffer[j] & 0xFF) | (buffer[j + 1] << 8));
                    s = (short) (s * gain);
                    buffer[j] = (byte) (s & 0xFF);
                    buffer[j + 1] = (byte) ((s >> 8) & 0xFF);
                }
            }
            line.write(buffer, 0, bytesRead);
        }

        if (!playback.isStopped()) line.drain();
        line.stop();
        line.close();
        pcmStream.close();
        audioStream.close();
        return playback;
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
            // No range defined: distance-based from device
            double dx = px - (playback.getPos().getX() + 0.5);
            double dy = py - (playback.getPos().getY() + 0.5);
            double dz = pz - (playback.getPos().getZ() + 0.5);
            float maxSearchDistance = 160.0f;
            float distFactor = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) / maxSearchDistance;
            linearFactor = 1.0f - Math.min(1.0f, Math.max(0.0f, distFactor));
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

    public void stopAudio(BlockPos pos) {
        pendingPlaybacks.remove(pos);
        AudioPlayback playback = activePlaybacks.remove(pos);
        if (playback != null) playback.stop();
    }

    public boolean isPlaying(BlockPos pos) {
        return activePlaybacks.containsKey(pos) || pendingPlaybacks.contains(pos);
    }

    public void stopAll() {
        pendingPlaybacks.clear();
        for (AudioPlayback playback : activePlaybacks.values()) playback.stop();
        activePlaybacks.clear();
    }

    private static class AudioPlayback {
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
