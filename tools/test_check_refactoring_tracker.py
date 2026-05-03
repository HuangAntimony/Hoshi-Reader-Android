import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CHECKER = ROOT / "tools" / "check_refactoring_tracker.py"


class RefactoringTrackerCheckerTest(unittest.TestCase):
    def run_checker(self, content: str) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as tmpdir:
            tracker = Path(tmpdir) / "REFACTORING_TRACKER.md"
            tracker.write_text(textwrap.dedent(content).strip() + "\n", encoding="utf-8")
            return subprocess.run(
                [sys.executable, str(CHECKER), str(tracker)],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )

    def test_accepts_complete_tracker_slice(self) -> None:
        result = self.run_checker(
            """
            # Architecture Refactoring Tracker

            ### R-000 Characterization baseline

            Status: done
            Phase: 0
            Owner: agent
            Depends on: none
            Commit: abc1234
            Scope:
            - Capture the current navigation behavior.
            Non-goals:
            - Do not change app behavior.
            Touched areas:
            - app/src/test
            iOS reference:
            - N/A
            Exit criteria:
            - Android Back behavior is characterized.
            Verification:
            - [x] python3 tools/check_refactoring_tracker.py docs/REFACTORING_TRACKER.md
            Result notes:
            - Characterization checklist is recorded.
            Next handoff:
            - Start R-001.
            """
        )

        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        self.assertIn("ok", result.stdout)

    def test_rejects_done_slice_without_verification(self) -> None:
        result = self.run_checker(
            """
            # Architecture Refactoring Tracker

            ### R-001 Broken done slice

            Status: done
            Phase: 1
            Owner: agent
            Depends on: none
            Commit: abc1234
            Scope:
            - Move route ownership.
            Non-goals:
            - Do not change visuals.
            Touched areas:
            - app/src/main
            iOS reference:
            - N/A
            Exit criteria:
            - Route ownership moved.
            Verification:
            Result notes:
            - Missing verification should fail.
            Next handoff:
            - Continue.
            """
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("R-001", result.stdout)
        self.assertIn("Verification", result.stdout)

    def test_rejects_multiple_in_progress_slices(self) -> None:
        result = self.run_checker(
            """
            # Architecture Refactoring Tracker

            ### R-001 First slice

            Status: in_progress
            Phase: 1
            Owner: agent
            Depends on: none
            Scope:
            - First.
            Non-goals:
            - None.
            Touched areas:
            - app/src/main
            iOS reference:
            - N/A
            Exit criteria:
            - First done.
            Verification:
            - [ ] ./gradlew test
            Result notes:
            - Not complete.
            Next handoff:
            - Continue.

            ### R-002 Second slice

            Status: in_progress
            Phase: 1
            Owner: agent
            Depends on: R-001
            Scope:
            - Second.
            Non-goals:
            - None.
            Touched areas:
            - app/src/main
            iOS reference:
            - N/A
            Exit criteria:
            - Second done.
            Verification:
            - [ ] ./gradlew test
            Result notes:
            - Not complete.
            Next handoff:
            - Continue.
            """
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("multiple in_progress", result.stdout)

    def test_ignores_slice_template_inside_code_fence(self) -> None:
        result = self.run_checker(
            """
            # Architecture Refactoring Tracker

            ```md
            ### R-000 Template example

            Status: todo
            ```

            ### R-000 Real slice

            Status: todo
            Phase: 0
            Owner: unassigned
            Depends on: none
            Scope:
            - Real work.
            Non-goals:
            - None.
            Touched areas:
            - docs
            iOS reference:
            - N/A
            Exit criteria:
            - Real slice is tracked.
            Verification:
            - [ ] python3 tools/check_refactoring_tracker.py docs/REFACTORING_TRACKER.md
            Result notes:
            - Not started.
            Next handoff:
            - Continue.
            """
        )

        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)


if __name__ == "__main__":
    unittest.main()
