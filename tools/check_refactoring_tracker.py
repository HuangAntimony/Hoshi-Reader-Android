#!/usr/bin/env python3
"""Validate docs/REFACTORING_TRACKER.md for resumable refactoring work."""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass, field
from pathlib import Path


ALLOWED_STATUSES = {"todo", "in_progress", "blocked", "review", "done"}
REQUIRED_FIELDS = ["Status", "Phase", "Owner", "Depends on"]
REQUIRED_SECTIONS = [
    "Scope",
    "Non-goals",
    "Touched areas",
    "iOS reference",
    "Exit criteria",
    "Verification",
    "Result notes",
    "Next handoff",
]
FIELD_RE = re.compile(r"^([A-Za-z][A-Za-z -]*):(?:\s*(.*))?$")
SLICE_RE = re.compile(r"^###\s+(R-\d{3})\s+(.+?)\s*$")


@dataclass
class Slice:
    identifier: str
    title: str
    line: int
    fields: dict[str, str] = field(default_factory=dict)
    sections: dict[str, list[str]] = field(default_factory=dict)

    def section_text(self, name: str) -> str:
        return "\n".join(self.sections.get(name, [])).strip()


def parse_tracker(path: Path) -> list[Slice]:
    slices: list[Slice] = []
    current: Slice | None = None
    current_section: str | None = None
    in_code_fence = False

    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if raw_line.startswith("```"):
            in_code_fence = not in_code_fence
            continue

        if in_code_fence:
            continue

        heading = SLICE_RE.match(raw_line)
        if heading:
            current = Slice(heading.group(1), heading.group(2), line_number)
            slices.append(current)
            current_section = None
            continue

        if current is None:
            continue

        field_match = FIELD_RE.match(raw_line)
        if field_match:
            key = field_match.group(1)
            value = (field_match.group(2) or "").strip()
            if key in REQUIRED_SECTIONS:
                current.sections.setdefault(key, [])
                current_section = key
            else:
                current.fields[key] = value
                current_section = None
            continue

        if current_section is not None:
            current.sections.setdefault(current_section, []).append(raw_line.rstrip())

    return slices


def has_meaningful_section(slice_: Slice, section: str) -> bool:
    return any(line.strip() for line in slice_.sections.get(section, []))


def validate_slice(slice_: Slice) -> list[str]:
    errors: list[str] = []
    prefix = f"{slice_.identifier} line {slice_.line}"

    for field_name in REQUIRED_FIELDS:
        if not slice_.fields.get(field_name):
            errors.append(f"{prefix}: missing {field_name}")

    for section_name in REQUIRED_SECTIONS:
        if section_name not in slice_.sections:
            errors.append(f"{prefix}: missing {section_name}")
        elif not has_meaningful_section(slice_, section_name):
            errors.append(f"{prefix}: empty {section_name}")

    status = slice_.fields.get("Status", "")
    if status and status not in ALLOWED_STATUSES:
        errors.append(f"{prefix}: invalid Status '{status}'")

    phase = slice_.fields.get("Phase", "")
    if phase and not phase.isdigit():
        errors.append(f"{prefix}: Phase must be an integer")

    depends_on = slice_.fields.get("Depends on", "")
    if depends_on and depends_on != "none":
        dependencies = [item.strip() for item in depends_on.split(",")]
        invalid = [item for item in dependencies if not re.fullmatch(r"R-\d{3}", item)]
        if invalid:
            errors.append(f"{prefix}: invalid Depends on value '{depends_on}'")

    if status == "done":
        if not slice_.fields.get("Commit"):
            errors.append(f"{prefix}: done slice must include Commit")
        verification = slice_.section_text("Verification")
        if "[x]" not in verification:
            errors.append(f"{prefix}: done slice must include checked Verification evidence")
        result_notes = slice_.section_text("Result notes")
        if not result_notes or result_notes == "-":
            errors.append(f"{prefix}: done slice must include Result notes")

    if status == "blocked":
        result_notes = slice_.section_text("Result notes")
        if "Blocker:" not in result_notes:
            errors.append(f"{prefix}: blocked slice must record 'Blocker:' in Result notes")
        if "Resume when:" not in result_notes:
            errors.append(f"{prefix}: blocked slice must record 'Resume when:' in Result notes")

    if status == "review" and "Reviewer:" not in slice_.section_text("Result notes"):
        errors.append(f"{prefix}: review slice must record 'Reviewer:' in Result notes")

    return errors


def validate_tracker(slices: list[Slice]) -> list[str]:
    errors: list[str] = []
    if not slices:
        return ["tracker has no refactoring slices"]

    seen: set[str] = set()
    for slice_ in slices:
        if slice_.identifier in seen:
            errors.append(f"{slice_.identifier} line {slice_.line}: duplicate slice id")
        seen.add(slice_.identifier)
        errors.extend(validate_slice(slice_))

    in_progress = [slice_.identifier for slice_ in slices if slice_.fields.get("Status") == "in_progress"]
    if len(in_progress) > 1:
        errors.append(f"multiple in_progress slices: {', '.join(in_progress)}")

    known = {slice_.identifier for slice_ in slices}
    for slice_ in slices:
        depends_on = slice_.fields.get("Depends on", "")
        if depends_on and depends_on != "none":
            for dependency in [item.strip() for item in depends_on.split(",")]:
                if re.fullmatch(r"R-\d{3}", dependency) and dependency not in known:
                    errors.append(f"{slice_.identifier}: unknown dependency {dependency}")

    return errors


def main(argv: list[str]) -> int:
    tracker_path = Path(argv[1]) if len(argv) > 1 else Path("docs/REFACTORING_TRACKER.md")
    if not tracker_path.exists():
        print(f"missing tracker: {tracker_path}")
        return 1

    errors = validate_tracker(parse_tracker(tracker_path))
    if errors:
        print("Refactoring tracker validation failed:")
        for error in errors:
            print(f"- {error}")
        return 1

    print(f"ok: {tracker_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
