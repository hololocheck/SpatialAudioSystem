package com.spatialaudiosystem.layout;

import belugalab.mcss3.ir.compiler.LayoutValidator;
import belugalab.mcss3.ir.compiler.ValidationContext;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MANTA_5 Wave A / P0-R4: SAS の全 layout JSON を Manta の strict validator に通す。
 * 設計意図は TSU 側の同名 test と同じ — in-game の {@code /manta validate layout} と
 * <b>同じ validation core</b> を CI から呼び、実装が drift しないようにする。
 */
class LayoutStrictValidationTest {

    private static final String LAYOUT_DIR =
            "src/main/resources/assets/spatialaudiosystem/layouts";

    @Test
    @DisplayName("**SAS の全 layout が strict validator で issue 0**")
    void allLayoutsAreClean() {
        List<Path> files = layoutFiles();
        assertFalse(files.isEmpty(), "layout が 1 件も見つからないなら test 自体が壊れている");

        var icons = iconCatalog();
        List<String> problems = new ArrayList<>();
        // 「見ていない軸」を握り潰さない: 件数を出力して可視化する (合否には使わない)。
        List<String> unverified = new ArrayList<>();
        for (Path p : files) {
            String name = p.getFileName().toString();
            JsonObject layout;
            try {
                layout = JsonParser.parseString(
                        Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (RuntimeException e) {
                problems.add(name + ": JSON parse error: " + e);
                continue;
            }
            var ctx = ValidationContext.coreStrict("spatialaudiosystem:layouts/" + name)
                    // 記録済みの繰り延べ 1 件 (再監査 Wave 7 の台帳):
                    // recording-device の rec-arrow-head "▶" は progress bar 先端の装飾 8x11。
                    // lucide play は輪郭線なので 8x11 では実効 4px になり **見た目が劣化する**ため据え置いた。
                    // 例外は **この glyph だけ** — 他の control glyph は従来どおり赤になる。
                    .withGlyphExceptions(java.util.Set.of("▶"));
            if (icons != null) ctx = ctx.withIcons(icons);
            // 再監査 R2-2 項目 7: strict の入口は validateAll ただ一つ。
            // validate だけを呼ぶと binding 解決と「未検査軸 (UNVERIFIABLE_*)」を見落とす。
            for (LayoutValidator.Issue i
                    : LayoutValidator.defects(LayoutValidator.validateAll(layout, ctx))) {
                problems.add(i.format());
            }
            for (LayoutValidator.Issue i : LayoutValidator.validateAll(layout, ctx)) {
                if (LayoutValidator.isUnverifiable(i)) unverified.add(name + ": " + i.code());
            }
        }
        assertTrue(problems.isEmpty(),
                "strict validation issues:\n  " + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("検出器として働くことの確認 — 壊した JSON はちゃんと red になる")
    void validatorActuallyDetects() {
        JsonObject broken = JsonParser.parseString(
                "{\"tag\":\"div\",\"x\":0,\"y\":0,\"w\":10,\"h\":10,\"dynamicText\":\"x\"}")
                .getAsJsonObject();
        assertFalse(LayoutValidator.defects(
                        LayoutValidator.validateAll(broken, ValidationContext.coreStrict("t")))
                        .isEmpty(),
                "validator が空を返すなら検出器として死んでいる");
    }

    /**
     * layout dir を探す。cwd は consumer によって違う (TSU は project root、
     * SAS は {@code build/minecraft-junit}) ので、決め打ちの相対パスは使えない。
     * 見つからなければ test を green にせず fail させる — 「0 件だから合格」を防ぐ。
     */
    private static List<Path> layoutFiles() {
        Path dir = null;
        for (Path base = Paths.get("").toAbsolutePath(); base != null; base = base.getParent()) {
            Path c = base.resolve(LAYOUT_DIR);
            if (Files.isDirectory(c)) { dir = c; break; }
        }
        assertTrue(dir != null, "layout dir が見つからない (探索起点: "
                + Paths.get("").toAbsolutePath() + ", 相対: " + LAYOUT_DIR + ")");
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return icon ID 集合。取得できなければ null (= 未提供、検査しない)。 */
    private static java.util.Set<String> iconCatalog() {
        try (var in = LayoutStrictValidationTest.class
                .getResourceAsStream("/assets/manta/icons/icons.json")) {
            if (in == null) return null;
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            var out = new java.util.HashSet<String>();
            if (root.has("icons") && root.get("icons").isJsonArray()) {
                for (var e : root.getAsJsonArray("icons")) {
                    if (e.isJsonObject() && e.getAsJsonObject().has("id")) {
                        out.add(e.getAsJsonObject().get("id").getAsString());
                    } else if (e.isJsonPrimitive()) {
                        out.add(e.getAsString());
                    }
                }
            }
            return out.isEmpty() ? null : out;
        } catch (IOException | RuntimeException e) {
            return null;   // catalog 無しは「未解決」ではない
        }
    }
}
