"""Mutation controls for mantaConsumerGates (build.gradle): Manta's consumer-reading gates, run over this tree from Manta's
committed tree. Each mutant is a copy of this repo (under build/mutation/consumer-gates/, outside %TEMP%) with ONE change,
and `gradlew mantaConsumerGates` on it must FAIL WITH THE VERDICT the change is for - and only that one. A build that failed
without it is DETECTOR-FAIL, never red. Before any mutant, `gradlew check --dry-run` on the unchanged copy must list the
task (the mutants call it by name), the unchanged copy must pass with both gates' PASS, and no mutant's needle may appear
in that clean run.

  unpushed-node   the first keyed layout node (files by path, nodes in document order) given a key nothing here pushes:
                  the slot vocabulary's FAIL
  by-hand-<set>   a new class in that source set spanning two BlockPos by hand, the shape WorldOutline.blockSpan owns:
                  the reimplementation rules' FAIL - one per source set in SOURCE_SETS
  waived-gone     the file a waiver in Manta excuses here, removed: that waiver then matches nothing, which Manta's test
                  fails on - and so must this tree's check (the user, 2026-10-29). Only where Manta waives a file here
  no-layouts      a tree with no layouts directory: COULD NOT TELL, never a pass
  old-manta       Manta's last commit before the entry points (2.91.1's C2 receipt): refused
  tree-<gate>     a change ConsumerTreeGates' gate of that name fails, and no other gate: a method hit-testing against a
                  layer origin with no scale, a vanilla drawString, a raster width beside MantaText.drawInBatch, a screen
                  scaling its panel before super.render, a dropdownDown call, a page root keyed popup-slide, the hand
                  copy of the glyph set one glyph short, a CODING_GUIDELINES.md naming a Manta class no keep rule covers,
                  a wiki page with a paragraph written on two lines
  tree-shadow     a label of the file FontMix's record of shadowed labels names here drawn without its shadow (the
                  user, 2026-10-29: the record is asked too). Only where Manta records one here
  before-tree     Manta's last commit before ConsumerTreeGates: refused

Xenophora's copy of this harness replays two shapes from that repo's history. This repo shipped neither, so its changes
are made up: each is the smallest that should give its gate's verdict, and the clean run shows it is not there already.
The same file sits in TSU, SAS, ASC and Orca; only the block marked "this consumer" differs.

--ref names the Manta ref the copies ask (-PmantaGateRef; the task's default, origin/master, when omitted): a Manta branch
that changes the entry points is asked before it reaches origin.

    python scripts/manta_consumer_gates.mutation.py [--ref <manta ref>] [name ...]
"""
import json
import os
import shutil
import subprocess
import sys

# --- this consumer ---
MODID = "spatialaudiosystem"
MANTA_FROM_ROOT = ("..", "manta")
SOURCE_SETS = {"main": "src/main/java/com/spatialaudiosystem"}
# (file, rule) of a waiver Manta's ConsumerReimplementationRules keeps for this repo; None where it keeps none.
WAIVED = ("src/main/java/com/spatialaudiosystem/audio/SpatialGain.java", "camera-relative AABB spanning two BlockPos")
# (file, one shadowed draw in it) from the record of shadowed labels Manta's ConsumerTreeGates keeps for this repo; None
# where it keeps none.
SHADOWED = None
# ---

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANTA = os.path.abspath(os.path.join(ROOT, *MANTA_FROM_ROOT))
WORK = os.path.join(ROOT, "build", "mutation", "consumer-gates")
GRADLEW = "gradlew.bat" if os.name == "nt" else "gradlew"
COPIED = ("src", "gradle", "build.gradle", "settings.gradle", "gradle.properties", GRADLEW)
# The governance documents ConsumerTreeGates reads at the root, copied where this repo has them.
OPTIONAL = ("CODING_GUIDELINES.md", "PERMISSION_MATRIX.md")
LAYOUTS = f"src/main/resources/assets/{MODID}/layouts"
WIKI_PAGE = f"src/main/resources/assets/{MODID}/wiki/en_us/cgate-mutant.md"
BEFORE_THE_ENTRY_POINTS = "18c66a73c63e"
BEFORE_THE_TREE_GATES = "5c8226d092b1"
UNPUSHED = "cgate-mutant-unpushed-key"
BY_HAND = "CgateMutantSpan"
BY_HAND_RULE = "camera-relative AABB spanning two BlockPos"

SLOT_FAIL = "slot vocabulary: FAIL"
RULES_FAIL = "reimplementation rules: FAIL"
TREE_GATES = ("hit arithmetic", "vanilla font", "font mix", "outside panel transform", "dropdown motion",
              "control glyph copy", "governance linkability", "wiki lines")
TREE_FAIL = {g: f"  {g}: FAIL" for g in TREE_GATES}
CLEAN = ("RESULT: PASS - every textKey node reaches a key or a key pattern this consumer pushes",
         "RESULT: PASS - no finding but a waived one", "RESULT: PASS - eight gates, no finding",
         "mantaConsumerGates: PASS")


def first_keyed(node):
    """The first dict, in document order, with a non-empty string textKey: what the slot vocabulary gate counts as a
    keyed node."""
    if isinstance(node, dict):
        if isinstance(node.get("textKey"), str) and node["textKey"]:
            return node
        children = node.values()
    elif isinstance(node, list):
        children = node
    else:
        return None
    for child in children:
        found = first_keyed(child)
        if found is not None:
            return found
    return None


def unpush(tree):
    layouts = os.path.join(tree, *LAYOUTS.split("/"))
    names = sorted(os.path.relpath(os.path.join(base, n), layouts).replace(os.sep, "/")
                   for base, _dirs, files in os.walk(layouts) for n in files if n.endswith(".json"))
    for rel in names:
        path = os.path.join(layouts, *rel.split("/"))
        with open(path, encoding="utf-8") as fh:
            doc = json.load(fh)
        node = first_keyed(doc)
        if node is not None:
            print(f"  unpushed-node: {rel}: textKey {node['textKey']!r} -> {UNPUSHED!r}", flush=True)
            node["textKey"] = UNPUSHED
            with open(path, "w", encoding="utf-8", newline="\n") as fh:
                json.dump(doc, fh, ensure_ascii=False, indent=2)
            return
    raise SystemExit("REFUSED: no layout here has a keyed node - a control with nothing to unpush is not a control")


def by_hand(source_dir):
    """A change adding a class to source_dir that spans two BlockPos by hand."""
    package = source_dir.split("/java/", 1)[1].replace("/", ".")
    text = (f"package {package};\n\nimport net.minecraft.core.BlockPos;\n\nfinal class {BY_HAND} {{\n"
            "    static int maxX(BlockPos a, BlockPos b) {\n        return Math.max(a.getX(), b.getX()) + 1;\n    }\n}\n")

    def add(tree):
        folder = os.path.join(tree, *source_dir.split("/"))
        path = os.path.join(folder, BY_HAND + ".java")
        if not os.path.isdir(folder) or os.path.exists(path):
            raise SystemExit(f"REFUSED: {source_dir} is no package directory of the copy, or {BY_HAND}.java is already "
                             f"there - the control must add a class of its own to a real source set")
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
    return add


def add_class(source_dir, name, body):
    """A change adding class `name` - `body` under its package line - to the source set at source_dir."""
    package = source_dir.split("/java/", 1)[1].replace("/", ".")

    def add(tree):
        folder = os.path.join(tree, *source_dir.split("/"))
        path = os.path.join(folder, name + ".java")
        if not os.path.isdir(folder) or os.path.exists(path):
            raise SystemExit(f"REFUSED: {source_dir} is no package directory of the copy, or {name}.java is already "
                             f"there - the control must add a class of its own to a real source set")
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(f"package {package};\n\n{body}")
    return add


def popup_slide(tree):
    """The first layout's root (files by path) keyed popup-slide, the overlay dropdowns' old key."""
    layouts = os.path.join(tree, *LAYOUTS.split("/"))
    names = sorted(os.path.relpath(os.path.join(base, n), layouts).replace(os.sep, "/")
                   for base, _dirs, files in os.walk(layouts) for n in files if n.endswith(".json"))
    if not names:
        raise SystemExit("REFUSED: no layout here - a control with no page to key is not a control")
    path = os.path.join(layouts, *names[0].split("/"))
    with open(path, encoding="utf-8") as fh:
        doc = json.load(fh)
    if not isinstance(doc, dict):
        raise SystemExit(f"REFUSED: {names[0]} holds no object - the control keys a page's root")
    print(f"  tree-dropdown-layout: {names[0]}: root animationKey {doc.get('animationKey')!r} -> 'popup-slide'", flush=True)
    doc["animationKey"] = "popup-slide"
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(doc, fh, ensure_ascii=False, indent=2)


def short_glyph(tree):
    """This repo's hand copy of ControlGlyphGateTest with the first glyph of its CONTROL_GLYPHS removed."""
    tests = os.path.join(tree, "src", "test", "java")
    copies = sorted(os.path.join(base, n) for base, _dirs, files in os.walk(tests) for n in files
                    if n == "ControlGlyphGateTest.java")
    if len(copies) != 1:
        raise SystemExit(f"REFUSED: {len(copies)} hand copies of ControlGlyphGateTest - the control shortens one")
    with open(copies[0], encoding="utf-8", newline="") as fh:
        text = fh.read()
    start = text.find("CONTROL_GLYPHS = Set.of(")
    first = text.find('"', start) if start >= 0 else -1
    close = text.find('"', first + 1) if first >= 0 else -1
    if close < 0 or text.find(");", start) < close:
        raise SystemExit("REFUSED: no glyph literal in the copy's CONTROL_GLYPHS - nothing to remove")
    cut = close + 1 + (1 if text[close + 1] == "," else 0)
    print(f"  tree-glyph-copy: {text[first:close + 1]} removed from the hand copy", flush=True)
    with open(copies[0], "w", encoding="utf-8", newline="") as fh:
        fh.write(text[:first] + text[cut:])


def govern(tree):
    """A CODING_GUIDELINES.md (added to, where this repo has one) naming ControlGlyphs, a Manta class no keep rule covers."""
    with open(os.path.join(tree, "CODING_GUIDELINES.md"), "a", encoding="utf-8", newline="\n") as fh:
        fh.write("\nControlGlyphs\n")


def wrapped_page(tree):
    """A wiki page of its own (in a wiki of its own where this repo has none) with a paragraph written on two lines."""
    path = os.path.join(tree, *WIKI_PAGE.split("/"))
    if os.path.exists(path):
        raise SystemExit(f"REFUSED: {WIKI_PAGE} is already in the copy - the control adds a page of its own")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("A paragraph\nwritten on two lines.\n")


def others(gate):
    """Every gate's FAIL needle but `gate`'s: a tree mutant must fail its own gate and no other."""
    return [SLOT_FAIL, RULES_FAIL] + [TREE_FAIL[g] for g in TREE_GATES if g != gate]


# name, change (a callable, a removed file / directory, (path, old, new), or None), extra gradle args, needles it must
# print, needles it must not.
MUTANTS = [("unpushed-node", unpush, [], [SLOT_FAIL, f"textKey={UNPUSHED}"], [RULES_FAIL])]
MUTANTS += [(f"by-hand-{name}", by_hand(source_dir), [], [RULES_FAIL, f"{source_dir}/{BY_HAND}.java:", BY_HAND_RULE],
             [SLOT_FAIL]) for name, source_dir in SOURCE_SETS.items()]
if WAIVED:
    MUTANTS.append(("waived-gone", WAIVED[0], [],
                    [RULES_FAIL, "1 waiver(s) matching nothing", f"{WAIVED[0]} / {WAIVED[1]}"], [SLOT_FAIL]))
MUTANTS += [("no-layouts", LAYOUTS, [], ["slot vocabulary: COULD NOT TELL", "COULD NOT TELL - no layouts",
                                          "  dropdown motion: COULD NOT TELL - no layouts under"], [RULES_FAIL]),
            ("old-manta", None, [f"-PmantaGateRef={BEFORE_THE_ENTRY_POINTS}"], ["lacks the consumer gates' entry points"],
             [])]
MAIN = next(iter(SOURCE_SETS.values()))
MUTANTS += [
    ("tree-hit", add_class(MAIN, "CgateMutantHit", "final class CgateMutantHit {\n    boolean over(double mx) {\n"
                                                   "        return mx >= dialogX() + 10;\n    }\n\n    int dialogX() {\n"
                                                   "        return 0;\n    }\n}\n"),
     [], [TREE_FAIL["hit arithmetic"], f"{MAIN}/CgateMutantHit.java:"], others("hit arithmetic")),
    ("tree-vanilla-font", add_class(MAIN, "CgateMutantFont", "final class CgateMutantFont {\n"
                                                             "    void d(GuiGraphics g, Font font) {\n"
                                                             "        g.drawString(font, \"x\", 0, 0, 0xFFFFFF);\n    }\n}\n"),
     [], [TREE_FAIL["vanilla font"], f"{MAIN[len('src/'):]}/CgateMutantFont.java:"], others("vanilla font")),
    ("tree-font-mix", add_class(MAIN, "CgateMutantMix", "final class CgateMutantMix {\n    void d() {\n"
                                                        "        int w = com.manta.api.text.MantaText.uiWidth(font, s);\n"
                                                        "        com.manta.api.text.MantaText.drawInBatch(font, s, -w / 2f, 0, 0,"
                                                        " false, m, b, mode, 0);\n    }\n}\n"),
     [], [TREE_FAIL["font mix"], f"{MAIN}/CgateMutantMix.java:"], others("font mix")),
    ("tree-panel", add_class(MAIN, "CgateMutantPanel", "final class CgateMutantPanel extends "
                                                       "com.manta.api.screen.JsonLayoutPlainScreen {\n"
                                                       "    public void render(GuiGraphics g, int mx, int my, float t) {\n"
                                                       "        g.pose().scale(0.5f, 0.5f, 1f);\n"
                                                       "        super.render(g, mx, my, t);\n    }\n}\n"),
     [], [TREE_FAIL["outside panel transform"], f"{MAIN}/CgateMutantPanel.java:"], others("outside panel transform")),
    ("tree-dropdown-java", add_class(MAIN, "CgateMutantDropdown", "final class CgateMutantDropdown {\n"
                                                                  "    Object a = com.manta.api.anim.Animation.dropdownDown(280, 136);\n}\n"),
     [], [TREE_FAIL["dropdown motion"], f"{MAIN}/CgateMutantDropdown.java:", "calls the preset dropdownDown("],
     others("dropdown motion")),
    ("tree-dropdown-layout", popup_slide, [], [TREE_FAIL["dropdown motion"], "animationKey popup-slide"],
     others("dropdown motion")),
    ("tree-glyph-copy", short_glyph, [], [TREE_FAIL["control glyph copy"], ": missing ["], others("control glyph copy")),
    ("tree-governance", govern, [], [TREE_FAIL["governance linkability"], "ControlGlyphs (com.manta.design.ControlGlyphs)"],
     others("governance linkability")),
    ("tree-wiki-lines", wrapped_page, [], [TREE_FAIL["wiki lines"], "cgate-mutant.md:2"], others("wiki lines")),
]
if SHADOWED:
    MUTANTS.append(("tree-shadow", (SHADOWED[0], SHADOWED[1], SHADOWED[1].replace(", true);", ", false);")), [],
                    [TREE_FAIL["font mix"], f"{SHADOWED[0]}: recorded"], others("font mix")))
MUTANTS.append(("before-tree", None, [f"-PmantaGateRef={BEFORE_THE_TREE_GATES}"],
                ["lacks the consumer gates' entry points"], []))


def copy_tree(name):
    target = os.path.join(WORK, name)
    if os.path.exists(target):
        shutil.rmtree(target)
    os.makedirs(target)
    for item in COPIED + tuple(o for o in OPTIONAL if os.path.exists(os.path.join(ROOT, o))):
        source = os.path.join(ROOT, item)
        if os.path.isdir(source):
            shutil.copytree(source, os.path.join(target, item))
        else:
            shutil.copy2(source, os.path.join(target, item))
    return target


def mutate(tree, change):
    if change is None:
        return
    if callable(change):
        change(tree)
        return
    if isinstance(change, str):
        path = os.path.join(tree, *change.split("/"))
        if os.path.isfile(path):
            os.remove(path)
        elif os.path.isdir(path):
            shutil.rmtree(path)
        else:
            raise SystemExit(f"REFUSED: {change} is not in the copy - a control that removes nothing is not a control")
        return
    relpath, old, new = change
    path = os.path.join(tree, relpath)
    with open(path, encoding="utf-8", newline="") as fh:
        text = fh.read()
    # The sources may be checked out with CRLF; the anchors are written with LF.
    text = text.replace("\r\n", "\n")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"REFUSED: {old[:70]!r} occurs {count} times in {relpath} - a control that does not apply "
                         f"exactly once is not a control")
    with open(path, "w", encoding="utf-8", newline="") as fh:
        fh.write(text.replace(old, new))


def run_gates(tree, ref, extra):
    args = [os.path.join(tree, GRADLEW), "-p", tree, "mantaConsumerGates", f"-PmantaRoot={MANTA}", "--no-build-cache",
            "--console=plain"]
    if ref and not any(a.startswith("-PmantaGateRef=") for a in extra):
        args.append(f"-PmantaGateRef={ref}")
    run = subprocess.run(args + extra, capture_output=True, text=True, encoding="utf-8", errors="replace", cwd=tree)
    return run.returncode, (run.stdout + run.stderr).replace("\r\n", "\n")


def check_reaches_the_gates(tree):
    """Whether `gradlew check` - the condition a push waits on - runs mantaConsumerGates: the mutants call the task by
    name, which says nothing about `check` reaching it."""
    run = subprocess.run([os.path.join(tree, GRADLEW), "-p", tree, "check", "--dry-run", f"-PmantaRoot={MANTA}",
                          "--console=plain"], capture_output=True, text=True, encoding="utf-8", errors="replace", cwd=tree)
    lines = (run.stdout + run.stderr).replace("\r\n", "\n").split("\n")
    return run.returncode == 0 and ":mantaConsumerGates SKIPPED" in lines


def verdict(code, out, want, unwanted):
    """RED: the build failed with every needle it is for and none of the other gate's. A failed build without them is
    DETECTOR-FAIL (it failed for something else), a passing one STILL GREEN."""
    if code == 0:
        return "STILL GREEN"
    if all(n in out for n in want) and not any(n in out for n in unwanted):
        return "RED"
    return "DETECTOR-FAIL"


# A verdict that reads a failure for another reason as red is not a verdict: planted, checked on every run.
if (verdict(1, "x slot vocabulary: FAIL y", ["slot vocabulary: FAIL"], []) != "RED"
        or verdict(1, "Could not resolve all files", ["slot vocabulary: FAIL"], []) != "DETECTOR-FAIL"
        or verdict(1, "slot vocabulary: FAIL reimplementation rules: FAIL", ["slot vocabulary: FAIL"],
                   ["reimplementation rules: FAIL"]) != "DETECTOR-FAIL"
        or verdict(0, "slot vocabulary: FAIL", ["slot vocabulary: FAIL"], []) != "STILL GREEN"):
    raise SystemExit("REFUSED: the verdict cannot tell the gate's red from another failure - no control can be read")


def main(args):
    # A tail printed for a reader must not end the run that holds the remaining verdicts: the console may be cp932, and
    # gradle's output, read here as UTF-8, can carry U+FFFD (measured 2026-10-29 - the print raised and the rest never ran).
    sys.stdout.reconfigure(errors="backslashreplace")
    ref = None
    if "--ref" in args:
        at = args.index("--ref")
        ref = args[at + 1]
        del args[at:at + 2]
    print(f"manta: {MANTA} at {ref or 'origin/master (the task default)'}", flush=True)
    chosen = [m for m in MUTANTS if not args or m[0] in args]
    unknown = [a for a in args if a not in [m[0] for m in MUTANTS]]
    if unknown:
        print(f"REFUSED: unknown mutant(s) {unknown}")
        return 2
    os.makedirs(WORK, exist_ok=True)
    unchanged = copy_tree("unchanged")
    reached = check_reaches_the_gates(unchanged)
    print(f"check reaches mantaConsumerGates: {'yes' if reached else 'NO'}", flush=True)
    if not reached:
        print("RESULT: FAIL -- `gradlew check` does not run the gates, so no push waits on them")
        return 1
    code, out = run_gates(unchanged, ref, [])
    present = [n for m in chosen for n in m[3] if n in out]
    ok = code == 0 and all(n in out for n in CLEAN) and not present
    print(f"unchanged copy: {'PASS' if ok else 'FAIL'} (gradle exit {code}"
          f"{'; needles already in the clean run: ' + str(present) if present else ''})", flush=True)
    if not ok:
        print(out[-3000:])
        print("RESULT: FAIL -- the unchanged copy does not pass cleanly; no control can be read")
        return 1
    bad = 0
    for name, change, extra, want, unwanted in chosen:
        tree = copy_tree(name)
        mutate(tree, change)
        code, out = run_gates(tree, ref, extra)
        result = verdict(code, out, want, unwanted)
        print(f"{name}: {result} (gradle exit {code})", flush=True)
        if result != "RED":
            bad += 1
            print(out[-2500:])
    print(f"RESULT: {'PASS' if bad == 0 else 'FAIL'} -- {len(chosen)} mutants, {bad} not red with their own verdict")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
