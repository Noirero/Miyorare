#!/usr/bin/env python3
"""Route Badge/Nameplate goldens; only proven generic StatsScreen edits may skip."""
from __future__ import annotations

import argparse
import ast
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STATS = "app/src/main/kotlin/org/koitharu/kotatsu/stats/ui/StatsScreen.kt"
WORKFLOWS = {
    "badge": ".github/workflows/exclusive-badge-golden.yml",
    "nameplate": ".github/workflows/exclusive-nameplate-golden.yml",
}
# These helpers draw loading/statistics content, not the equipped cosmetic surface.
# Only their numeric/plain-string literals may vary; calls and structure stay protected.
# Everything else, including imports, profile layout, host styles and new helpers,
# remains part of the conservative visual fingerprint.
GENERIC_HELPERS = frozenset({
    "ReaderJourneyProfileLoadingSkeleton", "ReaderJourneyOverviewGrid",
    "ReaderJourneyOverviewMetric", "WeeklyJourneyCard", "JourneyXpHistoryCard",
    "ReaderJourneyXpGuideCard", "ReaderJourneyXpRuleRow", "AchievementSummary",
    "AchievementCard", "ScopeSelector", "StatsFilterRow", "MaturePrivacyButton",
    "ReaderJourneyHero", "YearInReviewCard", "YearReviewMetricRow", "YearReviewMetric",
    "MetricsGrid", "ModernMetricCard", "TopPickSection", "ReadingHeatmapCard",
    "HeatmapLegend", "ReadingHeatmapGrid", "InsightCard", "RevisitedRow",
    "EmptyMiniCard", "StatsEmptyState", "CategoryFilterChip", "CategoryFilterSheet",
    "CategorySheetRow", "MangaCover",
})
RISKY_HELPER = re.compile(
    r"Badge|Nameplate|RankTheme|Exclusive|Cosmetic|ReaderProfileCard|ReaderAvatar|"
    r"ReaderProfileEditorSheet|ReaderJourneyThemeCard|StatsScreen|AppSettings|"
    r"CompositionLocalProvider|LaunchedEffect|DisposableEffect"
)
# The newly introduced #462 skeleton is absent from the loaded golden fixture.
# Audit its references explicitly; a new call/reference must not silently escape routing.
SKELETON_IDENTIFIERS = frozenset("""
Composable private fun ReaderJourneyProfileLoadingSkeleton Column modifier Modifier
fillMaxWidth padding horizontal STATS_PADDING verticalArrangement Arrangement spacedBy
dp Surface height shape RoundedCornerShape color MaterialTheme colorScheme
surfaceContainerHigh copy alpha horizontalAlignment Alignment CenterHorizontally Box
size clip CircleShape background onSurface width Spacer repeat Row weight f horizontalArrangement
""".split())
LEXER = re.compile(
    r'/\*.*?\*/|//[^\n]*|""".*?"""|"(?:\\.|[^"\\])*"|'
    r"'(?:\\.|[^'\\])*'|`[^`]+`|[A-Za-z_]\w*|\d+(?:\.\d+)?|\s+|[^\w\s]",
    re.DOTALL,
)
FUNCTION = re.compile(
    r"(?m)^(?:@[^\n]+\n)*(?:(?:private|internal|public) )?fun (\w+)\s*\("
)


def tokens(source: str) -> list[tuple[str, int, int]]:
    result = []
    position = 0
    for match in LEXER.finditer(source):
        if match.start() != position:
            raise ValueError("unsupported Kotlin token")
        position = match.end()
        value = match.group()
        if value in ('"', "'", "`"):
            raise ValueError("unterminated Kotlin literal")
        if value.startswith("/*") and "/*" in value[2:]:
            raise ValueError("nested comment requires visual review")
        if not value.isspace() and not value.startswith(("//", "/*")):
            result.append((value, match.start(), match.end()))
    if position != len(source):
        raise ValueError("incomplete tokenization")
    return result


def closing(values: list[str], index: int, opening: str, ending: str) -> int:
    depth = 0
    for cursor in range(index, len(values)):
        if values[cursor] == opening:
            depth += 1
        elif values[cursor] == ending:
            depth -= 1
            if depth == 0:
                return cursor
    raise ValueError("unbalanced Kotlin declaration")


def loaded_screen(values: list[str]) -> list[str]:
    """Compare the existing golden fixture's loaded=true, loading=false surface.

    Only the audited #462 loading guard is normalized. Changed call arguments,
    ambient providers, layout or an unfamiliar condition remain visual-sensitive.
    """
    values = values.copy()
    parameter = ["hasLoadedStats", ":", "Boolean", ","]
    for index in range(len(values) - len(parameter) + 1):
        if values[index:index + len(parameter)] == parameter:
            del values[index:index + len(parameter)]
            break
    guard = ["if", "(", "!", "hasLoadedStats", "&", "&", "isLoading", ")", "{"]
    loading = ['item', '(', '"profile-overview-loading"', ')', '{',
               'ReaderJourneyProfileLoadingSkeleton', '(', ')', '}']
    for index in range(len(values) - len(guard) + 1):
        if values[index:index + len(guard)] != guard:
            continue
        end = closing(values, index + len(guard) - 1, "{", "}")
        if values[index + len(guard):end] != loading or values[end + 1:end + 3] != ["else", "{"]:
            continue
        else_end = closing(values, end + 2, "{", "}")
        values[index:else_end + 1] = values[end + 3:else_end]
        break
    return values


def visual_fingerprint(source: str) -> list[str]:
    lexed = tokens(source)
    values = [value for value, _, _ in lexed]
    replacements = []
    found_profile = found_screen = False
    depth = 0
    depths = []
    for value in values:
        depths.append(depth)
        if value == "{":
            depth += 1
        elif value == "}":
            depth -= 1
        if depth < 0:
            raise ValueError("unbalanced source")
    if depth:
        raise ValueError("unbalanced source")
    for match in FUNCTION.finditer(source):
        name = match.group(1)
        start = next(i for i, (_, offset, _) in enumerate(lexed) if offset >= match.start())
        if lexed[start][1] != match.start() or depths[start]:
            raise ValueError("ambiguous declaration")
        # An annotation can have parentheses; locate the function's parameter list.
        function = next(i for i in range(start, len(values)) if values[i] == "fun")
        if values[function + 1] != name:
            raise ValueError("ambiguous function name")
        parameter = function + 2
        if values[parameter] != "(":
            raise ValueError("unrecognized function signature")
        parameter_end = closing(values, parameter, "(", ")")
        body = parameter_end + 1
        if values[body] != "{":
            continue  # expression/typed-return declarations stay fully protected
        end = closing(values, body, "{", "}")
        if name == "ReaderProfileCard":
            if found_profile:
                raise ValueError("duplicate profile declaration")
            found_profile = True
        if name == "StatsScreen":
            if found_screen:
                raise ValueError("duplicate screen declaration")
            found_screen = True
            replacements.append((start, end + 1, loaded_screen(values[start:end + 1])))
        elif name in GENERIC_HELPERS:
            raw = source[match.start():lexed[end][2]]
            if not RISKY_HELPER.search(raw):
                helper = values[start:end + 1]
                if name == "ReaderJourneyProfileLoadingSkeleton":
                    identifiers = {v for v in helper if re.fullmatch(r"[A-Za-z_]\w*", v)}
                    if identifiers <= SKELETON_IDENTIFIERS and not any("$" in v for v in helper):
                        replacements.append((start, end + 1, []))
                else:
                    # Preserve dependency references, control flow and effects. Plain
                    # labels/numbers below the profile cannot alter its badge crop.
                    shape = ["<literal>" if (v[0].isdigit() or
                             (v.startswith('"') and "$" not in v)) else v for v in helper]
                    replacements.append((start, end + 1, shape))
    if not found_profile or not found_screen:
        raise ValueError("missing audited profile/screen declarations")
    for start, end, replacement in reversed(replacements):
        values[start:end] = replacement
    return values


def stats_requires_visual(before: str | None, after: str | None) -> bool:
    if before is None or after is None:
        return True  # new/deleted/renamed/unreadable host
    try:
        return visual_fingerprint(before) != visual_fingerprint(after)
    except (ValueError, IndexError, StopIteration):
        return True


def workflow_paths(kind: str) -> list[str]:
    # Use the actual workflow paths as the source of truth, without PyYAML/pip.
    source = (ROOT / WORKFLOWS[kind]).read_text(encoding="utf-8")
    section = source.split("    paths:\n", 1)[1].split("\n  workflow_dispatch:", 1)[0]
    paths = []
    for line in section.splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            if not line.startswith("      - "):
                raise ValueError("unsupported workflow path syntax")
            value = ast.literal_eval(line[8:])
            if not isinstance(value, str) or value.startswith("!"):
                raise ValueError("unsupported path rule")
            paths.append(value)
    if not paths:
        raise ValueError("missing visual path rules")
    return paths


def matches(path: str, pattern: str) -> bool:
    # GitHub '*' does not cross '/', while '**' does. No negated rules are used.
    regex = re.escape(pattern).replace(r"\*\*", ".*").replace(r"\*", "[^/]*")
    return re.fullmatch(regex, path) is not None


def requires_visual(kind: str, paths: list[str], before: str | None = None,
                    after: str | None = None) -> bool:
    normalized = [path.strip().replace("\\", "/") for path in paths if path.strip()]
    if not normalized:
        return True
    rules = workflow_paths(kind)
    relevant = [p for p in normalized if any(matches(p, rule) for rule in rules)]
    if any(path != STATS for path in relevant):
        return True  # retain every renderer/asset/test/workflow trigger
    return STATS in relevant and stats_requires_visual(before, after)


def git_source(ref: str) -> str | None:
    result = subprocess.run(["git", "show", f"{ref}:{STATS}"], cwd=ROOT,
                            capture_output=True, text=True)
    return result.stdout if result.returncode == 0 else None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kind", choices=WORKFLOWS, required=True)
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    args = parser.parse_args()
    try:
        # Rename detection disabled so both deleted and added path inputs are classified.
        base = subprocess.check_output(["git", "merge-base", args.base, args.head], cwd=ROOT, text=True).strip()
        paths = subprocess.check_output(["git", "diff", "--name-only", "--no-renames", base, args.head],
                                        cwd=ROOT, text=True).splitlines()
        required = requires_visual(args.kind, paths, git_source(base), git_source(args.head))
    except (subprocess.CalledProcessError, ValueError, IndexError, OSError) as error:
        print(f"Visual routing uncertain; run golden: {error}", file=sys.stderr)
        required = True
    print("true" if required else "false")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
