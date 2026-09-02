package com.spatialaudiosystem.layout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SAS-I18N-001: nothing a player reads may be written as a literal.
 *
 * <p>A literal is English in every language, and there is no way to notice from the outside: the
 * lang files stay complete and the key count stays balanced, so neither the existing lang gate
 * nor a diff of the two locales can see it. Found on 2026-08-30 in the range board's tooltip
 * ("Pos1: …", "Attenuation:", "E:0  W:0  U:0 blocks") and in the two upload failure messages,
 * all of which had been English-only since they were written.
 *
 * <p>Scoped to the calls that reach a player -- a tooltip line, a chat message, a screen label.
 * {@code Component.literal} on a value that is already a name or a number is fine and common,
 * so the rule is about literals with prose in them, not about the method.
 */
class UntranslatedTextGateTest {

    private static final Path SOURCE = findSourceTree();

    /**
     * The mod's source tree, wherever the test happens to be run from.
     *
     * <p>A bare relative path resolved against a working directory that is not the project root,
     * and the walk then threw rather than reporting nothing -- which is the better of the two
     * failures, but only because the second test below insists the tree is visible at all.
     */
    private static Path findSourceTree() {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve("src/main/java");
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new AssertionError("could not find src/main/java from " + Path.of("").toAbsolutePath());
    }

    /** `Component.literal("...")` with a non-empty literal inside. */
    private static final Pattern LITERAL =
            Pattern.compile("Component\\.literal\\(\\s*\"([^\"]+)\"", Pattern.DOTALL);

    /**
     * Text that is not translated on purpose.
     *
     * <p>{@code /sas-verify} talks to whoever is running a verification pass from a console, not
     * to a player in the world; its wording is read by a script and pinned by that script's own
     * controls, so translating it would break them.
     */
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "sas-verify",
            // /sas-wiki-capture, an author tool: it reports how many wiki screenshots it
            // wrote, to whoever ran it from a console. No player reaches it.
            "[SAS Wiki]");

    @Test
    @DisplayName("SAS-I18N-001: no player-facing text is written as a literal")
    void noPlayerFacingLiterals() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                // The whole file, not line by line: `Component.literal(` and its string are
                // routinely on separate lines, and three offenders were sitting in the tree
                // while a per-line version of this reported all clear.
                String text = Files.readString(file);
                Matcher m = LITERAL.matcher(text);
                while (m.find()) {
                    String literal = m.group(1);
                    if (ALLOWED_PREFIXES.stream().anyMatch(literal::startsWith)) continue;
                    long line = text.chars().limit(m.start()).filter(c -> c == '\n').count() + 1;
                    offenders.add(file + ":" + line + "  \"" + literal + "\"");
                }
            }
        }
        assertThat(offenders)
                .as("each of these is English in every language; give it a lang key instead")
                .isEmpty();
    }

    @Test
    @DisplayName("SAS-I18N-001: the gate reads a source tree it can actually see")
    void theSourceTreeIsThere() throws IOException {
        // Without this the check above passes on an empty walk -- a path that no longer exists,
        // or a working directory the test was not run from, and it reports all clear.
        try (Stream<Path> files = Files.walk(SOURCE)) {
            assertThat(files.filter(p -> p.toString().endsWith(".java")).count())
                    .as("the gate must be looking at this mod's sources")
                    .isGreaterThan(30);
        }
    }
}
