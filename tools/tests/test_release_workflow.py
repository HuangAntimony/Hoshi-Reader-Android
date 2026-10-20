import os
from pathlib import Path
import json
import subprocess
import tempfile
import unittest
import zipfile

import yaml


ROOT = Path(__file__).resolve().parents[2]
SHA = "0123456789abcdef0123456789abcdef01234567"


class PreviewConcurrencyTest(unittest.TestCase):
    def test_publisher_queues_all_completed_builds_without_interrupting_publication(self):
        workflow = yaml.safe_load((ROOT / ".github/workflows/android-ci.yml").read_text())
        concurrency = workflow["jobs"]["publish-preview"]["concurrency"]
        self.assertEqual("preview-apk-publish", concurrency["group"])
        self.assertIs(False, concurrency["cancel-in-progress"])
        self.assertEqual("max", concurrency["queue"])


class ReleasePayloadCheckTest(unittest.TestCase):
    def run_check(self, *, preview=False, declares_commit=True, embedded_commit=True):
        workflow = yaml.safe_load((ROOT / ".github/workflows/build-release-apks.yml").read_text())
        build = next(step for step in workflow["jobs"]["build"]["steps"] if step.get("id") == "assets")
        # Execute the workflow's Python payload validator against generated APK fixtures.
        validator = build["run"].split("<<'PYCODE'\n", 1)[1].split("\nPYCODE", 1)[0]
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            output = directory / "app/build/outputs/apk/release"
            output.mkdir(parents=True)
            (output / "output-metadata.json").write_text(json.dumps({
                "applicationId": "moe.antimony.hoshi",
                "elements": [{"versionName": "1.4.0", "versionCode": 10400}],
            }))
            generated = directory / "app/build/generated/source/buildConfig/release/moe/antimony/hoshi/BuildConfig.java"
            generated.parent.mkdir(parents=True)
            generated.write_text(
                f'public final class BuildConfig {{ public static final String BUILD_COMMIT_SHA = "{SHA}"; }}'
                if declares_commit else "public final class BuildConfig {}"
            )
            with zipfile.ZipFile(output / "app-release.apk", "w") as apk:
                apk.writestr("lib/arm64-v8a/libhoshiepub.so", b"fixture")
                apk.writestr("classes.dex", SHA.encode() if embedded_commit else b"legacy bytecode fixture")
            env = dict(os.environ, ABI="arm64-v8a", COMMIT_SHA=SHA,
                       VERSION_NAME="" if preview else "1.4.0",
                       VERSION_CODE="" if preview else "10400", PREVIEW=str(preview).lower())
            return subprocess.run(["python3", "-c", validator], cwd=directory, env=env,
                                  capture_output=True, text=True, timeout=10)

    def test_historical_formal_tag_without_provenance_remains_rebuildable(self):
        result = self.run_check(declares_commit=False, embedded_commit=False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("::warning::", result.stdout)

    def test_new_formal_build_must_embed_its_checkout_sha(self):
        result = self.run_check(embedded_commit=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("APK is missing the checkout SHA", result.stderr)
        result = self.run_check()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_preview_always_requires_embedded_provenance(self):
        result = self.run_check(preview=True, declares_commit=False, embedded_commit=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("APK is missing the checkout SHA", result.stderr)
        result = self.run_check(preview=True)
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
