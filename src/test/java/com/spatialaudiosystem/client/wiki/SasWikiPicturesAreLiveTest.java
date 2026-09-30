package com.spatialaudiosystem.client.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.manta.api.wiki.WikiLiveScreens;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every screen picture a wiki page shows is drawn live (MANTA_7_CONCEPT C5, decision D-A2): its
 * {@code bws:spatialaudiosystem:wiki/screens/<stem>__<lang>.png} resolves to a screen and a state of the capture table.
 *
 * <p>The table is registered the way the client registers it ({@link SasWikiLiveCapture#registerLive()}, from
 * ModScreens), and each picture is asked of Manta's own resolver ({@link WikiLiveScreens#resolves}), never of a copy of
 * the naming rule here. A picture that does not resolve is drawn from its captured texture: that draw is what C5 counts,
 * and this is the static half of it. Every {@code bws:} url whose path names {@code wiki/screens/} is judged.
 *
 * <p>Not seen here: whether a screen builds and draws at run time ({@code /manta debug live} on a real client counts the
 * texture draws of a registered picture).
 */
class SasWikiPicturesAreLiveTest {

    private static final String PAGES = "src/main/resources/assets/spatialaudiosystem/wiki";
    private static final Pattern PICTURE = Pattern.compile("bws:spatialaudiosystem:[^)\\s]+");

    /** The pages, found upwards from the working directory (the unit tests run in build/minecraft-junit). */
    private static Path wiki() {
        for (Path base = Paths.get("").toAbsolutePath(); base != null; base = base.getParent()) {
            if (Files.isDirectory(base.resolve(PAGES))) return base.resolve(PAGES);
        }
        throw new AssertionError(PAGES + " not found from " + Paths.get("").toAbsolutePath());
    }

    @Test
    void everyScreenPictureAPageShowsIsDrawnLive() throws IOException {
        SasWikiLiveCapture.registerLive();
        Path pages = wiki();
        int judged = 0;
        List<String> notLive = new ArrayList<>();
        for (Path page : files(pages, ".md")) {
            List<String> lines = Files.readAllLines(page, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = PICTURE.matcher(lines.get(i));
                while (m.find()) {
                    String url = m.group();
                    if (!url.contains("wiki/screens/")) continue;
                    judged++;
                    if (!WikiLiveScreens.resolves(url)) {
                        notLive.add(pages.relativize(page).toString().replace('\\', '/') + ":" + (i + 1) + " " + url);
                    }
                }
            }
        }
        assertTrue(judged > 0, "no screen picture read under " + pages + " - nothing was judged");
        assertEquals(List.of(), notLive, "wiki pages show screen pictures no live screen draws (" + judged + " judged)");
    }

    private static List<Path> files(Path root, String extension) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(extension)).sorted().toList();
        }
    }
}
