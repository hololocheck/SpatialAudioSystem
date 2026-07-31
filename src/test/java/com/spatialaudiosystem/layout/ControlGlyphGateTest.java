package com.spatialaudiosystem.layout;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MANTA_5 Wave 7 / W7-1 (SAS 側): <b>閉じる・再生等の control を raw glyph で描かない</b>。
 *
 * <h2>なぜ SAS にも要るのか</h2>
 * この gate が無かった間、SAS は<b>一度も走査対象になっていなかった</b>。
 * manta と TSU にだけ gate を置いて「control glyph 0 件」と報告していたが、
 * 実際には SAS の 3 layout に閉じる {@code ×} が 3・再生 {@code ▶} が 2・
 * その他 2 の計 7 件が残っていた (2026-07-26 に構造走査で発覚)。
 * <b>gate が見ていない repo は「0 件」の根拠にならない</b>。
 *
 * <h2>2 つの経路を両方見る</h2>
 * <ol>
 *   <li><b>layout JSON</b> — 閉じるボタンはここに居た。Java を grep しても出てこない。</li>
 *   <li><b>Java 文字列リテラル</b> — {@code drawString} の行だけ見ると
 *       変数へ代入してから描く形を取りこぼすので、行ではなくリテラルを見る。</li>
 * </ol>
 *
 * <h2>JSON は parse する (regex ではなく)</h2>
 * manta 側で regex 版が {@code "×"} と escape 保存された glyph を
 * 「バックスラッシュ u 0 0 d 7」の 6 文字として読み、<b>6 件を取りこぼした</b>。
 * parse すれば escape は復号され、この穴は構造的に存在しない。
 */
class ControlGlyphGateTest {

    /**
     * 用途が control の glyph。{@code ↑ ↓ ← →} は意図的に入れない —
     * 操作ヒントや行先表示の content typography として使われる。
     */
    private static final Set<String> CONTROL_GLYPHS = Set.of(
            "×", "✕", "✖",   // close
            "☰", "≡",         // menu
            "⚙",              // settings
            "🔍", "🔎",       // search
            "▼", "▲", "◀", "▶", // dropdown / 並べ替え / 再生
            "⇅", "↕",         // swap
            "✎",              // edit
            "✓", "✔",         // 確定
            // 第 2 波 (2026-07-26): 再生装置の停止 ■ / 録音 ● がここに無く見逃していた。
            "⏸", "⏹",        // 一時停止 / 停止
            // 第 3 波 (2026-07-26 / 6 つめの盲点): 異体字が丸ごと漏れていた。
            // ▼(U+25BC) は入っていたが ▾(U+25BE) は無い。用途が同じなら異体字も入れる。
            "▾", "▴", "▸", "◂",
            // wiki / help 導線の emoji (R4.17.2/3 の名残)。用途は navigation control。
            "📖"
    );

    /**
     * <b>意図的に gate しない glyph</b>: {@code ■ □ ● —}。
     *
     * <p>これらを control として入れたところ layout JSON だけで 10 件以上の誤検出が出た —
     * {@code —} は「値なし」プレースホルダ、{@code ●} は連携状態ドットと色見本、
     * {@code ■} は行頭 bullet。<b>形が汎用すぎて用途を字面から判定できない</b>。
     *
     * <p>実際の control ({@code ■} 停止 / {@code ●} 録音 / {@code —} 水平罫ボタン) は
     * 2026-07-26 にすべて icon 化済み。鳴りすぎる gate は最終的に無効化されるので、
     * 判定できないものを機械化せず<b>用途が一意な {@code ⏸ ⏹} だけ</b>を見る。
     */

    /**
     * <b>content / 装飾</b> — control ではないので恒久的に許可する。
     * ここへ足す変更はレビュー対象 (control を隠す抜け道になりうる)。
     */
    private static final Map<String, Set<String>> ALLOWED = new LinkedHashMap<>();

    static {
        // progress bar の先端装飾。track (x=127,w=40) + fill の終端に置く 8x11 の三角で、
        // 押せない。lucide play は fill="none" の輪郭線かつ viewBox 内側 50% にしか
        // 描かないため、8x11 では実効 4px 程度の中空図形になり **現状より劣化する**。
        // 「control を icon へ」の目的に照らして置換の利得が無いので据え置く。
        ALLOWED.put("recording-device.json", Set.of("▶"));
        // 「×3」= 再生回数の乗算表記。閉じるボタンではない。
        ALLOWED.put("PlaybackDeviceScreenV2.java", Set.of("×"));
    }

    // ===== 経路 1: layout JSON =====

    @Test
    @DisplayName("**layout JSON の \"text\" に control glyph が無い** (閉じる × 再生 ▶ は icon ノードへ)")
    void layoutJsonHasNoControlGlyphText() throws IOException {
        Path dir = locate("src/main/resources/assets/spatialaudiosystem/layouts");

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                String base = p.getFileName().toString();
                Set<String> allowed = ALLOWED.getOrDefault(base, Set.of());
                collect(JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)),
                        base, allowed, violations);
            }
        }
        assertTrue(violations.isEmpty(),
                "control glyph は tag=icon + icon へ置換すること: " + violations);
    }

    /** parse 済みツリーを歩いて {@code text} の control glyph を集める。 */
    private static void collect(JsonElement el, String base, Set<String> allowed, List<String> out) {
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            JsonElement t = o.get("text");
            if (t != null && t.isJsonPrimitive() && t.getAsJsonPrimitive().isString()) {
                String v = t.getAsString().strip();
                if (!v.isEmpty() && !allowed.contains(v) && isControlGlyphUse(v)) {
                    out.add(base + " : \"text\":\"" + v + "\"");
                }
            }
            for (var e : o.entrySet()) collect(e.getValue(), base, allowed, out);
        } else if (el.isJsonArray()) {
            for (JsonElement c : el.getAsJsonArray()) collect(c, base, allowed, out);
        }
    }

    // ===== 経路 2: Java 文字列リテラル =====

    @Test
    @DisplayName("**Java の文字列リテラルに control glyph が無い** (変数代入経由も捕捉する)")
    void javaLiteralsHaveNoControlGlyph() throws IOException {
        Path root = locate("src/main/java");

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                String base = p.getFileName().toString();
                Set<String> allowed = ALLOWED.getOrDefault(base, Set.of());
                for (String lit : stringLiterals(src)) {
                    String t = lit.strip();
                    if (t.isEmpty() || allowed.contains(t) || !isControlGlyphUse(t)) continue;
                    violations.add(base + " : \"" + t + "\"");
                }
            }
        }
        assertTrue(violations.isEmpty(),
                "control glyph は Icons.draw / icon ノードへ置換すること: " + violations);
    }

    // ===== 検出器が赤に振れることの証明 =====

    @Test
    @DisplayName("検出器: glyph 単独と「先頭 glyph + ラベル」を拾い、typography は拾わない")
    void detectorSemantics() {
        assertTrue(isControlGlyphUse("×"));
        assertTrue(isControlGlyphUse(" ▶ "));
        assertTrue(isControlGlyphUse("× 閉じる"), "ラベル付きボタンも control");
        assertTrue(!isControlGlyphUse("×3"), "再生回数の乗算");
        assertTrue(!isControlGlyphUse("16×14"), "寸法");
        assertTrue(!isControlGlyphUse("→ 次へ"), "→ は content typography");
        assertTrue(!isControlGlyphUse("Play"));

        assertTrue(stringLiterals("String s = \"×\"; // 16×14").equals(List.of("×")));
        assertTrue(stringLiterals("String u = \"http://a/b\";").equals(List.of("http://a/b")));

        // **unicode escape も復号する** (2026-07-26 / 5 つめの盲点)。
        // 復号しない実装ではリテラルが "u2714" になり control glyph に見えない。
        assertTrue(stringLiterals("String s = \"\\u2714\";").equals(List.of("✔")));
        assertTrue(stringLiterals("String s = \"\\u2261 目次\";").equals(List.of("≡ 目次")));
        assertTrue(stringLiterals("String s = \"\\uuu2716\";").equals(List.of("✖")));
        assertTrue(stringLiterals("String s = \"\\u2714OK\";").equals(List.of("✔OK")));
        assertTrue(stringLiterals("String s = \"a\\nb\";").equals(List.of("anb")));
    }

    @Test
    @DisplayName("検出器: JSON escape (\\u00d7) の × も parse 経路なら検出する")
    void detectorSeesEscapedGlyph() {
        List<String> hits = new ArrayList<>();
        collect(JsonParser.parseString("{\"tag\":\"div\",\"text\":\"\\u00d7\"}"),
                "escaped.json", Set.of(), hits);
        assertTrue(hits.size() == 1, "escape 済み × も復号して検出する: " + hits);

        List<String> nested = new ArrayList<>();
        collect(JsonParser.parseString("{\"children\":[{\"children\":[{\"text\":\"▶\"}]}]}"),
                "nested.json", Set.of(), nested);
        assertTrue(nested.size() == 1, "深い階層も走査する");
    }

    /**
     * CWD から上へ辿って相対 path を解決する。gradle の test 実行 dir は project dir とは
     * 限らないため ({@code LayoutStrictValidationTest} と同じ方式)。
     * 見つからなければ落とす — 「dir が無いので走査 0 件、よって green」を防ぐ。
     */
    private static Path locate(String rel) {
        for (Path base = Paths.get("").toAbsolutePath(); base != null; base = base.getParent()) {
            Path c = base.resolve(rel);
            if (Files.isDirectory(c)) return c;
        }
        throw new AssertionError("not found: " + rel + " (from " + Paths.get("").toAbsolutePath() + ")");
    }

    /** glyph 単独、または「先頭 glyph + ラベル」なら control。glyph 直後が数字なら倍率 typography。 */
    private static boolean isControlGlyphUse(String s) {
        return isControlGlyphUse(s, CONTROL_GLYPHS);
    }

    private static boolean isControlGlyphUse(String s, Set<String> glyphs) {
        String t = s.strip();
        if (t.isEmpty()) return false;
        if (t.codePoints().allMatch(cp -> glyphs.contains(new String(Character.toChars(cp))))) {
            return true;
        }
        int first = t.codePointAt(0);
        if (!glyphs.contains(new String(Character.toChars(first)))) return false;
        String rest = t.substring(Character.charCount(first));
        if (rest.isBlank()) return false;
        return !Character.isDigit(rest.charAt(0));
    }

    /**
     * Java ソースから文字列リテラルの中身だけを取り出す (comment / char literal は除外)。
     *
     * <p><b>unicode escape は復号する</b> (2026-07-26 / 5 つめの盲点)。{@code "✔"} は
     * ソース上 6 文字だがコンパイル後は {@code ✔} 1 文字で、生のまま読むと {@code u2714} に
     * 見え control glyph として検出できない。JSON を regex で読んで {@code "×"} を
     * 取りこぼしたのと同型の穴で、manta 2 file・TSU 2 file が不可視だった。SAS には
     * 現状 escape 記法の該当が無いが、<b>gate が見ていない repo は「0 件」の根拠にならない</b>。
     */
    private static List<String> stringLiterals(String src) {
        List<String> out = new ArrayList<>();
        final int CODE = 0, STR = 1, CHR = 2, LINE = 3, BLOCK = 4;
        int state = CODE;
        StringBuilder cur = null;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            char next = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            switch (state) {
                case CODE -> {
                    if (c == '"') { state = STR; cur = new StringBuilder(); }
                    else if (c == '\'') state = CHR;
                    else if (c == '/' && next == '/') { state = LINE; i++; }
                    else if (c == '/' && next == '*') { state = BLOCK; i++; }
                }
                case STR -> {
                    if (c == '\\') {
                        int end = unicodeEscapeEnd(src, i);
                        if (end > 0) {
                            if (cur != null) {
                                cur.append((char) Integer.parseInt(src.substring(end - 4, end), 16));
                            }
                            i = end - 1;
                        } else {
                            if (cur != null) cur.append(next);
                            i++;
                        }
                    }
                    else if (c == '"') { out.add(cur.toString()); cur = null; state = CODE; }
                    else if (cur != null) cur.append(c);
                }
                case CHR -> {
                    if (c == '\\') i++;
                    else if (c == '\'') state = CODE;
                }
                case LINE -> { if (c == '\n') state = CODE; }
                case BLOCK -> { if (c == '*' && next == '/') { state = CODE; i++; } }
                default -> { }
            }
        }
        return out;
    }

    /**
     * {@code src[i]} が {@code \}{@code uXXXX} の開始なら 4 桁 hex 直後の index、
     * そうでなければ -1。JLS は {@code u} の連続も認めるのでまとめて読み飛ばす。
     */
    private static int unicodeEscapeEnd(String src, int i) {
        int u = i + 1;
        while (u < src.length() && src.charAt(u) == 'u') u++;
        if (u == i + 1 || u + 4 > src.length()) return -1;
        for (int k = u; k < u + 4; k++) {
            if (Character.digit(src.charAt(k), 16) < 0) return -1;
        }
        return u + 4;
    }
}
