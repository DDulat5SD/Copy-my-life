"""Fail CI when a test class did not really run.

Most CopyMyLife tests switch themselves off when their environment is missing
(@EnabledIfEnvironmentVariable on TEST_DB_URL, assumptions on Chrome or node).
Maven still reports success in that case, so this script checks the Surefire
reports instead: every test class under src/test/java must have a report with
at least one test and no skipped tests, unless it is explicitly allowed to skip.

Usage:
    python check_test_reports.py <reports-dir> <test-source-dir>
        [--allow-skip SimpleClassName ...] [--only SimpleClassName ...]

--only checks just the named classes, for a job that runs a subset of the suite.
"""

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# The class-name patterns Maven Surefire runs by default.
SUREFIRE_SUFFIXES = ("Test", "Tests", "TestCase")


def test_classes(source_dir: Path) -> list[str]:
    classes = []
    for file in sorted(source_dir.rglob("*.java")):
        name = file.stem
        if not (name.endswith(SUREFIRE_SUFFIXES) or name.startswith("Test")):
            continue
        text = file.read_text(encoding="utf-8")
        if "@Test" not in text and "@ParameterizedTest" not in text:
            continue
        classes.append(".".join(file.relative_to(source_dir).with_suffix("").parts))
    return classes


def read_report(report: Path) -> dict[str, int]:
    suite = ET.parse(report).getroot()
    return {key: int(float(suite.get(key, "0"))) for key in ("tests", "skipped", "failures", "errors")}


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("reports_dir", type=Path)
    parser.add_argument("source_dir", type=Path)
    parser.add_argument("--allow-skip", action="append", default=[], metavar="CLASS")
    parser.add_argument("--only", action="append", default=[], metavar="CLASS")
    args = parser.parse_args(argv)

    allowed = set(args.allow_skip)
    problems = []
    totals = {"tests": 0, "skipped": 0, "failures": 0, "errors": 0}
    classes = test_classes(args.source_dir)
    names = {fqcn.rsplit(".", 1)[-1] for fqcn in classes}
    for option, given in (("--allow-skip", allowed), ("--only", set(args.only))):
        problems += [f"{option} {name}: no such test class" for name in sorted(given - names)]
    if args.only:
        classes = [fqcn for fqcn in classes if fqcn.rsplit(".", 1)[-1] in args.only]

    for fqcn in classes:
        simple = fqcn.rsplit(".", 1)[-1]
        report = args.reports_dir / f"TEST-{fqcn}.xml"
        if not report.is_file():
            if simple not in allowed:
                problems.append(f"{simple}: no Surefire report (the class did not run)")
            continue
        counts = read_report(report)
        for key in totals:
            totals[key] += counts[key]
        if simple in allowed:
            continue
        if counts["tests"] == 0:
            problems.append(f"{simple}: ran 0 tests")
        elif counts["skipped"]:
            problems.append(f"{simple}: {counts['skipped']} of {counts['tests']} tests skipped")

    print(f"Test classes: {len(classes)} (allowed to skip: {', '.join(sorted(allowed)) or 'none'})")
    print("Totals: {tests} tests, {skipped} skipped, {failures} failures, {errors} errors".format(**totals))
    if problems:
        print("\nTests that did not really run:")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print("Every test class ran with no skipped tests.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
