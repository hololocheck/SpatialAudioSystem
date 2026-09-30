package com.spatialaudiosystem.client.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.manta.api.wiki.WikiLiveScreens;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The wiki's playback scripts (Manta 7 Phase 7 slice 2e, MANTA_7_PHASE7_PLAYBACK v11 section 9) pass Manta's own check,
 * {@link WikiLiveScreens#checkPlayback} - never a copy of its rules here: the table is registered as the client
 * registers it ({@link SasWikiLiveCapture#registerLive()}), then every {@code wiki/playback/*.json} is asked.
 *
 * <p>{@link #NOT_PRESSED} is this mod's list of parts no script may click or wheel - what the playback containment cannot
 * see (read 2026-09-30, RecordingDeviceScreenV2:219-236): {@code rec-file-btn} opens a native file dialog
 * (AudioFilePickerService → TinyFileDialogs, on its own thread); it, {@code rec-start-btn} and {@code rec-clear-btn} clear
 * the process-wide RecordingErrorState before their (contained) send. The other parts send to the server through
 * Mirror - contained.
 *
 * <p>Not seen here: whether each step finds its part on the real screen and state - that is the real client's run of each
 * script ({@code /manta debug live}: played, delivered n, no stop).
 */
class SasWikiPlaybackScriptsTest {

    private static final String ASSETS = "src/main/resources/assets/spatialaudiosystem";

    static final Set<String> NOT_PRESSED = Set.of("rec-file-btn", "rec-start-btn", "rec-clear-btn");

    /** The assets, found upwards from the working directory (the unit tests run in build/minecraft-junit). */
    private static Path assets() {
        for (Path base = Paths.get("").toAbsolutePath(); base != null; base = base.getParent()) {
            if (Files.isDirectory(base.resolve(ASSETS))) return base.resolve(ASSETS);
        }
        return Paths.get(ASSETS);
    }

    @Test
    void everyPlaybackScriptPassesMantasCheck() {
        SasWikiLiveCapture.registerLive();
        Path assets = assets();
        WikiLiveScreens.PlaybackCheck check = WikiLiveScreens.checkPlayback(assets, "spatialaudiosystem", NOT_PRESSED);
        assertTrue(check.scripts() > 0, "no playback script read under " + assets.toAbsolutePath() + " - nothing was judged");
        assertEquals(List.of(), check.problems(), check.scripts() + " script(s) judged");
    }
}
