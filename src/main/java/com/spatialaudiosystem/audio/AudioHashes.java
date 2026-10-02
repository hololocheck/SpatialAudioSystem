package com.spatialaudiosystem.audio;

import com.spatialaudiosystem.item.ModDataComponents;
import net.minecraft.world.item.ItemStack;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The content hash a client keeps a sound under (notes/CLIENT_AUDIO_CACHE.md §3).
 *
 * <p>Remembered per stored audio id. {@link AudioStorage#save} writes every upload once, under a
 * new UUID, so an id's bytes do not change -- and hashing up to ten megabytes on the server thread
 * for every late listener would be a stall of about a tick each time. The length is kept beside the
 * hash and compared, so bytes that are not the remembered ones are hashed again rather than
 * announced under another sound's name.
 */
public final class AudioHashes {

    /** SHA-256. */
    public static final int LENGTH = 32;

    /** Ids remembered at once; a sound past this is simply hashed again. */
    private static final int REMEMBERED = 256;

    private record Known(int length, byte[] hash) {}

    private static final Map<UUID, Known> KNOWN = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Known> eldest) {
                    return size() > REMEMBERED;
                }
            });

    private AudioHashes() {}

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this Java runtime has no SHA-256", e);
        }
    }

    /**
     * The hash of {@code audio}, the bytes loaded for {@code media} -- remembered by the medium's
     * audio id when it has one. A medium without an id (a pending upload's preview, a legacy item)
     * is hashed every time.
     */
    public static byte[] of(ItemStack media, byte[] audio) {
        UUID id = media == null ? null : media.get(ModDataComponents.AUDIO_ID);
        if (id == null) return sha256(audio);
        Known known = KNOWN.get(id);
        if (known != null && known.length() == audio.length) return known.hash().clone();
        byte[] hash = sha256(audio);
        KNOWN.put(id, new Known(audio.length, hash));
        return hash.clone();
    }
}
