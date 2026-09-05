package com.spatialaudiosystem.layout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SAS-LOAD-001: no class holds a deferred registry value in a static field initializer.
 *
 * <p>Measured 2026-09-04 on the client: {@code SoundHandyHudRenderer} kept
 * {@code static final ItemStack DEVICE_ICON = new ItemStack(ModBlocks.PLAYBACK_DEVICE.get())}.
 * {@code @EventBusSubscriber} makes FML load that class during mod construction, before the
 * block registry is bound, so {@code <clinit>} threw
 * "Trying to access unbound value: spatialaudiosystem:playback_device" and the whole mod
 * reached the player as "has failed to load correctly".
 *
 * <p>Nothing else in the build catches this: it compiles, every unit test passes (the tests
 * never load that class), and the dedicated-server test JVM does not load a
 * {@code Dist.CLIENT} subscriber. Only starting the real client did - so the rule lives here,
 * as a source scan, instead of in prose.
 *
 * <p>The fix is always the same shape: keep the holder, resolve on first use.
 */
class RegistryStaticInitGateTest {

    /** One of the mod's registry holders, resolved: {@code ModBlocks.FOO.get()}. */
    private static final String HOLDER_GET =
            "\\bMod(?:Blocks|Items|BlockEntities|MenuTypes|DataComponents|Sounds|CreativeTabs)"
                    + "\\s*\\.\\s*[A-Z0-9_]+\\s*\\.\\s*get\\s*\\(";

    /** A static field whose initializer resolves one. */
    private static final Pattern STATIC_FIELD_HOLDER_GET = Pattern.compile(
            "static\\s+(?:final\\s+)?[\\w.<>\\[\\]]+\\s+\\w+\\s*=[^;]*" + HOLDER_GET, Pattern.DOTALL);

    /**
     * The head of a static initializer block; its body is read by counting braces.
     *
     * <p>Matched against the comment- and string-stripped source, so {@code static /* … *&#47; {}
     * is still seen and a brace inside a literal cannot end the body early (review 2026-09-05).
     */
    private static final Pattern STATIC_BLOCK = Pattern.compile("(?<![\\w.])static\\s*\\{");

    private static final Pattern HOLDER_GET_ANYWHERE = Pattern.compile(HOLDER_GET);

    /** The same walk up the working directory the sibling i18n gate uses (the runner's cwd varies). */
    private static Path sourceRoot() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve("src/main/java/com/spatialaudiosystem");
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new AssertionError("could not find the source tree from " + Paths.get("").toAbsolutePath());
    }

    @Test
    @DisplayName("SAS-LOAD-001: the scan reads a source tree it can actually see")
    void theScanReadsTheSources() throws IOException {
        try (Stream<Path> files = Files.walk(sourceRoot())) {
            long javaFiles = files.filter(p -> p.toString().endsWith(".java")).count();
            assertThat(javaFiles).as("the gate found the source tree").isGreaterThan(50);
        }
    }

    @Test
    @DisplayName("SAS-LOAD-001: the stripper blanks comments and literals without moving anything")
    void theStripperKeepsPositions() {
        String src = "class A { /* } */ static { x(\"}\"); } // }\n int y; }";
        String stripped = strip(src);
        assertThat(stripped.length()).as("positions are preserved").isEqualTo(src.length());
        assertThat(stripped.indexOf("static")).as("code is untouched").isEqualTo(src.indexOf("static"));
        assertThat(stripped.chars().filter(c -> c == '}').count())
                .as("the three braces inside a comment or a literal are gone; two real ones remain")
                .isEqualTo(2);
        // The forms the review named must reach the patterns.
        assertThat(STATIC_BLOCK.matcher(strip("static /* c */ { }")).find())
                .as("a comment between static and the brace no longer hides the block").isTrue();
        // A text block pairs only on a triple quote: a lone " inside must not shift everything
        // after it into "string", which would blank a real holder call and let it pass.
        String withTextBlock = "static { String s = \"\"\"\n a \" b\n\"\"\"; ModBlocks.FOO.get(); }";
        String strippedBlock = strip(withTextBlock);
        assertThat(strippedBlock.length()).isEqualTo(withTextBlock.length());
        assertThat(strippedBlock).as("the holder call after a text block is still visible")
                .contains("ModBlocks.FOO.get()");
    }

    @Test
    @DisplayName("SAS-LOAD-001: no static field initializer resolves a deferred registry holder")
    void noStaticFieldResolvesARegistryHolder() throws IOException {
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.walk(sourceRoot())) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = strip(Files.readString(f));
                // The holder declarations themselves are the registry classes; they hold
                // suppliers, never resolved values, so they are not what this looks for.
                if (f.getFileName().toString().startsWith("Mod")) continue;
                Matcher m = STATIC_FIELD_HOLDER_GET.matcher(src);
                while (m.find()) {
                    bad.add(where(f, src, m.start()) + "  static field: " + squash(m.group()));
                }
                // A static initializer block is the same <clinit>, and the field pattern cannot
                // see it (no type, no field name, no `=`) - review 2026-09-05 named this hole.
                Matcher b = STATIC_BLOCK.matcher(src);
                while (b.find()) {
                    String body = blockBody(src, b.end() - 1);
                    Matcher g = HOLDER_GET_ANYWHERE.matcher(body);
                    if (g.find()) {
                        bad.add(where(f, src, b.start()) + "  static block: " + squash(g.group()));
                    }
                }
            }
        }
        assertThat(bad)
                .as("a static initializer runs at class load, which for an @EventBusSubscriber is "
                        + "mod construction - before the registry is bound. Resolve on first use instead.")
                .isEmpty();
    }

    /**
     * The source with comments and string/char literals blanked out, keeping every character
     * position (so reported line numbers stay true). Without this a brace inside a literal ends
     * a static block's body early - the gate would then miss what follows.
     */
    static String strip(String src) {
        char[] out = src.toCharArray();
        int i = 0;
        while (i < out.length) {
            char c = out[i];
            if (c == '/' && i + 1 < out.length && out[i + 1] == '/') {
                while (i < out.length && out[i] != '\n') out[i++] = ' ';
            } else if (c == '/' && i + 1 < out.length && out[i + 1] == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < out.length && !(out[i] == '*' && i + 1 < out.length && out[i + 1] == '/')) {
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < out.length) { out[i++] = ' '; out[i++] = ' '; }
            } else if (c == '"' && i + 2 < out.length && out[i + 1] == '"' && out[i + 2] == '"') {
                // A text block: only a closing triple quote ends it, so the single quotes inside
                // must not be paired (review 2026-09-05 named this as the remaining hole).
                out[i++] = ' ';
                out[i++] = ' ';
                out[i++] = ' ';
                while (i + 2 < out.length && !(out[i] == '"' && out[i + 1] == '"' && out[i + 2] == '"')) {
                    if (out[i] != '\n') out[i] = ' ';
                    i++;
                }
                for (int q = 0; q < 3 && i < out.length; q++) out[i++] = ' ';
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out[i++] = ' ';
                while (i < out.length && out[i] != quote) {
                    if (out[i] == '\\' && i + 1 < out.length) out[i++] = ' ';
                    if (i < out.length && out[i] != '\n') out[i] = ' ';
                    i++;
                }
                if (i < out.length) out[i++] = ' ';
            } else {
                i++;
            }
        }
        return new String(out);
    }

    /** The text between a {@code {} and its matching close, so a static block can be read whole. */
    private static String blockBody(String src, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return src.substring(openBrace + 1, i);
        }
        return src.substring(openBrace);   // unbalanced source: read to the end rather than skip
    }

    private static String where(Path file, String src, int offset) {
        return file.getFileName() + ":" + ((int) src.substring(0, offset).lines().count() + 1);
    }

    private static String squash(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
