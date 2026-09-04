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

    /**
     * Minecraft's translatable text knows {@code %s} (and {@code %%}) and nothing else, and
     * {@code Language} rewrites {@code %d} / {@code %f} to {@code %s} when the lang file loads,
     * so a {@code %d} on disk is not what runs. Keeping the files at {@code %s} makes the file
     * and the runtime say the same thing -- hygiene, not a display fix (TSU swept its five
     * locales the same way on 2026-08-26). What this gate does catch for real is the class the
     * loader does not rewrite ({@code %x}, {@code %b}, {@code %c}, ...): those make
     * {@code TranslatableContents.decompose} throw and the raw template is shown.
     */
    @Test
    @DisplayName("SAS-I18N-003: lang values format with %s only")
    void langValuesFormatWithPercentSOnly() throws IOException {
        Path lang = SOURCE.resolveSibling("resources").resolve("assets/spatialaudiosystem/lang");
        // Decoded values, not file bytes: ja_jp.json is written as \\uXXXX escapes, and a percent
        // written that way is still a percent to the game. A % with no conversion after it is
        // the bare-percent case vanilla throws on ("100%"), so it is refused too.
        Pattern spec = Pattern.compile("%(\\d+\\$)?([A-Za-z%])?");
        List<String> bad = new ArrayList<>();
        int files = 0;
        int values = 0;
        for (String name : List.of("en_us.json", "ja_jp.json")) {
            com.google.gson.JsonObject doc = com.google.gson.JsonParser.parseString(
                    Files.readString(lang.resolve(name))).getAsJsonObject();
            files++;
            for (var entry : doc.entrySet()) {
                String value = entry.getValue().getAsString();
                values++;
                Matcher m = spec.matcher(value);
                while (m.find()) {
                    String conv = m.group(2);
                    if (conv == null || !(conv.equals("s") || conv.equals("%"))) {
                        bad.add(name + " " + entry.getKey() + " = " + value);
                    }
                }
            }
        }
        assertThat(files).isEqualTo(2);
        assertThat(values).as("the walk read the lang files").isGreaterThan(100);
        assertThat(bad).as("format specifiers Minecraft cannot render").isEmpty();
    }

    /**
     * The English file once carried Japanese for five keys (the fallback every other locale
     * lands on), which no key-set comparison can see: the keys were all there.
     */
    @Test
    @DisplayName("SAS-I18N-004: the English lang values carry no Japanese")
    void englishValuesCarryNoJapanese() throws IOException {
        Pattern japanese = Pattern.compile("[\\u3040-\\u30ff\\u4e00-\\u9fff\\uff01-\\uff60\\uff66-\\uff9d]");
        com.google.gson.JsonObject en = lang("en_us.json");
        List<String> bad = new ArrayList<>();
        for (var entry : en.entrySet()) {
            String value = entry.getValue().getAsString();
            if (japanese.matcher(value).find()) bad.add(entry.getKey() + " = " + value);
        }
        assertThat(en.size()).as("the file was read").isGreaterThan(100);
        assertThat(bad).as("English values written in Japanese").isEmpty();
    }

    /**
     * A label that does not fit is cut with an ellipsis ("While pla...") or, in a bordered
     * box too short for a line of text, sits on the frame -- both seen on the real device on
     * 2026-09-03. Widths come from vanilla's own ascii.png, read the way its bitmap provider
     * reads it (rightmost inked column + 1, plus one of spacing; the space is 4); anything
     * outside ascii.png is counted as a full-width unifont glyph (9). Bold adds one per glyph.
     */
    @Test
    @DisplayName("SAS-UI-005: every static label fits its box in both locales, by vanilla's glyph widths")
    void staticLabelsFitTheirBoxes() throws Exception {
        VanillaAscii metrics = VanillaAscii.load();
        Path layouts = SOURCE.resolveSibling("resources").resolve("assets/spatialaudiosystem/layouts");
        java.util.Map<String, com.google.gson.JsonObject> langs = java.util.Map.of(
                "en_us", lang("en_us.json"), "ja_jp", lang("ja_jp.json"));
        List<String> bad = new ArrayList<>();
        int labels = 0;
        try (Stream<Path> files = Files.list(layouts)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                List<com.google.gson.JsonObject> nodes = new ArrayList<>();
                collectNodes(com.google.gson.JsonParser.parseString(Files.readString(f)).getAsJsonObject(), nodes);
                for (com.google.gson.JsonObject n : nodes) {
                    if (!n.has("translatableKey") || !n.has("w")) continue;
                    if (n.has("tag") && "icon".equals(n.get("tag").getAsString())) continue;
                    labels++;
                    String where = f.getFileName() + " " + n.getAsJsonArray("classes").get(0).getAsString();
                    String key = n.get("translatableKey").getAsString();
                    int w = n.get("w").getAsInt();
                    int h = n.has("h") ? n.get("h").getAsInt() : 0;
                    int border = n.has("borderWidth") ? n.get("borderWidth").getAsInt() : 0;
                    boolean bold = n.has("fontWeight") && "bold".equals(n.get("fontWeight").getAsString());
                    // TextNode pads left/right by borderWidth + 1 and centres a 9 px line; a
                    // bordered box needs one clear pixel above and below the line as well.
                    int avail = w - 2 * (border + 1);
                    int needH = border > 0 ? 9 + 2 * (border + 1) : 9;
                    if (h < needH) bad.add(where + ": h=" + h + " < " + needH);
                    for (var lang : langs.entrySet()) {
                        com.google.gson.JsonElement v = lang.getValue().get(key);
                        if (v == null) {
                            bad.add(where + ": " + key + " missing in " + lang.getKey());
                            continue;
                        }
                        int tw = metrics.width(v.getAsString(), bold);
                        if (tw > avail) {
                            bad.add(where + " [" + lang.getKey() + "] \"" + v.getAsString() + "\" " + tw + "px > " + avail + "px");
                        }
                    }
                }
            }
        }
        // The wheel cells show lang values too, through textKey: the widest value each can hold
        // is known, so it is measured against the cell the same way ("While pla..." was one).
        java.util.Map<String, List<String>> cellValues = java.util.Map.of(
                "pb-rs-trigger", List.of("gui.spatialaudiosystem.rs_trigger_playing", "gui.spatialaudiosystem.rs_trigger_start",
                        "gui.spatialaudiosystem.rs_trigger_stop", "gui.spatialaudiosystem.rs_trigger_end"),
                "pb-rs-entry", List.of("gui.spatialaudiosystem.rs_entry_any", "=#16"),
                "pb-rs-strength", List.of("=15"),
                "pb-rs-delay", List.of("=30.0 s", "=30.0 \u79d2"),
                "pb-rs-length", List.of("=5.0 s", "=5.0 \u79d2"),
                "pb-count-display", List.of("=\u00d7\u221e", "=\u00d710"),
                // The range row composes a key (with its widest argument) and a suffix key; the
                // screen trims it at runtime, so an overflow here is a label cut on the device.
                "pb-atten-range", List.of(
                        "gui.spatialaudiosystem.attenuation_range|64+gui.spatialaudiosystem.range_jukebox",
                        "gui.spatialaudiosystem.attenuation_range|64+gui.spatialaudiosystem.range_unused",
                        "gui.spatialaudiosystem.range_board_active"));
        int cells = 0;
        try (Stream<Path> files = Files.list(layouts)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                List<com.google.gson.JsonObject> nodes = new ArrayList<>();
                collectNodes(com.google.gson.JsonParser.parseString(Files.readString(f)).getAsJsonObject(), nodes);
                for (com.google.gson.JsonObject n : nodes) {
                    if (!n.has("classes") || !n.has("w")) continue;
                    String cls = n.getAsJsonArray("classes").get(0).getAsString();
                    List<String> values = cellValues.get(cls);
                    if (values == null) continue;
                    cells++;
                    int border = n.has("borderWidth") ? n.get("borderWidth").getAsInt() : 0;
                    int avail = n.get("w").getAsInt() - 2 * (border + 1);
                    for (String v : values) {
                        List<String> texts = new ArrayList<>();
                        if (v.startsWith("=")) {
                            texts.add(v.substring(1));
                        } else {
                            // "key|arg+key" composes per locale: each part is a key, optionally
                            // formatted with one %s argument, and the parts are concatenated.
                            for (var lang : langs.values()) {
                                StringBuilder sb = new StringBuilder();
                                for (String part : v.split("\\+")) {
                                    String key = part;
                                    String arg = null;
                                    int bar = part.indexOf('|');
                                    if (bar >= 0) {
                                        key = part.substring(0, bar);
                                        arg = part.substring(bar + 1);
                                    }
                                    com.google.gson.JsonElement e = lang.get(key);
                                    assertThat(e).as(key).isNotNull();
                                    sb.append(arg == null ? e.getAsString() : e.getAsString().replace("%s", arg));
                                }
                                texts.add(sb.toString());
                            }
                        }
                        for (String t : texts) {
                            int tw = metrics.width(t, false);
                            if (tw > avail) bad.add(f.getFileName() + " " + cls + " \"" + t + "\" " + tw + "px > " + avail + "px");
                        }
                    }
                }
            }
        }
        assertThat(cells).as("the wheel cells were found").isEqualTo(cellValues.size());
        assertThat(labels).as("the walk saw the labels").isGreaterThan(10);
        assertThat(bad).as("labels that overflow their box, or boxes shorter than a line").isEmpty();
    }

    private static com.google.gson.JsonObject lang(String name) throws IOException {
        Path p = SOURCE.resolveSibling("resources").resolve("assets/spatialaudiosystem/lang").resolve(name);
        return com.google.gson.JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }

    /** Every node of a layout: children and repeat templates alike. */
    private static void collectNodes(com.google.gson.JsonObject node, List<com.google.gson.JsonObject> out) {
        out.add(node);
        if (node.has("children")) {
            for (var c : node.getAsJsonArray("children")) collectNodes(c.getAsJsonObject(), out);
        }
        if (node.has("template")) collectNodes(node.getAsJsonObject("template"), out);
    }

    /** Vanilla's ascii bitmap font, measured the way BitmapProvider measures it. */
    static final class VanillaAscii {
        private final java.util.Map<Integer, Integer> advance = new java.util.HashMap<>();

        static VanillaAscii load() throws Exception {
            VanillaAscii m = new VanillaAscii();
            com.google.gson.JsonObject def = com.google.gson.JsonParser.parseString(new String(
                    resource("assets/minecraft/font/include/default.json"), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            com.google.gson.JsonArray rows = null;
            for (var p : def.getAsJsonArray("providers")) {
                com.google.gson.JsonObject o = p.getAsJsonObject();
                if (o.has("file") && "minecraft:font/ascii.png".equals(o.get("file").getAsString())) {
                    rows = o.getAsJsonArray("chars");
                }
            }
            assertThat(rows).as("the ascii provider of include/default.json").isNotNull();
            java.awt.image.BufferedImage png = javax.imageio.ImageIO.read(
                    new java.io.ByteArrayInputStream(resource("assets/minecraft/textures/font/ascii.png")));
            int cols = rows.get(0).getAsString().codePointCount(0, rows.get(0).getAsString().length());
            int cellW = png.getWidth() / cols;
            int cellH = png.getHeight() / rows.size();
            for (int r = 0; r < rows.size(); r++) {
                int[] cps = rows.get(r).getAsString().codePoints().toArray();
                for (int c = 0; c < cps.length; c++) {
                    if (cps[c] == 0) continue;
                    int inked = 0;
                    for (int x = cellW - 1; x >= 0 && inked == 0; x--) {
                        for (int y = 0; y < cellH; y++) {
                            if (((png.getRGB(c * cellW + x, r * cellH + y) >>> 24) & 0xFF) != 0) {
                                inked = x + 1;
                                break;
                            }
                        }
                    }
                    m.advance.put(cps[c], inked + 1);
                }
            }
            assertThat(m.advance.get((int) 'A')).as("a glyph vanilla draws 5 wide").isEqualTo(6);
            return m;
        }

        int width(String text, boolean bold) {
            int w = 0;
            for (int cp : text.codePoints().toArray()) {
                Integer a = cp == ' ' ? Integer.valueOf(4) : advance.get(cp);
                // bold draws every glyph twice, one pixel apart -- the space included
                w += (a != null ? a : 9) + (bold ? 1 : 0);
            }
            return w;
        }

        /** From the test classpath, or from the client-assets jar on it (the MDG layout). */
        private static byte[] resource(String name) throws Exception {
            try (var in = ClassLoader.getSystemResourceAsStream(name)) {
                if (in != null) return in.readAllBytes();
            }
            for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
                if (!entry.endsWith(".jar")) continue;
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(entry)) {
                    var e = zip.getEntry(name);
                    if (e != null) {
                        try (var in = zip.getInputStream(e)) { return in.readAllBytes(); }
                    }
                } catch (java.util.zip.ZipException ignored) { }
            }
            throw new AssertionError("not on the test classpath: " + name);
        }
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
