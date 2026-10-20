#!/usr/bin/env python3
"""Publish a unique APK prerelease, then retire only older APK previews."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile


REPOSITORY = "HuangAntimony/Hoshi-Reader-Android"
PREVIEW_TAG = re.compile(r"^apk-preview-([0-9a-f]{7})-[0-9]+-[0-9]+$")
OWNER_MARKER = "<!-- hoshi-apk-preview -->"


class AssetMismatchError(RuntimeError):
    pass


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


class GitHub:
    def __init__(self, repo):
        self.repo = repo
        self.root = f"repos/{repo}"

    def request(self, method, endpoint, body=None, missing_ok=False):
        command = ["gh", "api", "--method", method, f"{self.root}/{endpoint}"]
        if body is not None:
            command += ["--input", "-"]
        result = subprocess.run(
            command, input=json.dumps(body) if body is not None else None,
            capture_output=True, text=True, timeout=120,
        )
        if result.returncode:
            if missing_ok and "(HTTP 404)" in result.stderr:
                return None
            raise RuntimeError(result.stderr.strip() or result.stdout.strip())
        return json.loads(result.stdout) if result.stdout.strip() else None

    def main_sha(self):
        return self.request("GET", "git/ref/heads/main")["object"]["sha"]

    def list_releases(self):
        # The tag endpoint only returns published releases; include drafts so a
        # failed first upload can resume instead of creating a second release.
        page = 1
        result = []
        while True:
            releases = self.request("GET", f"releases?per_page=100&page={page}")
            result.extend(releases)
            if len(releases) < 100:
                return result
            page += 1

    def create_draft(self, tag, sha, notes):
        return self.request("POST", "releases", {
            "tag_name": tag, "target_commitish": sha, "name": "Hoshi Reader Preview",
            "body": notes,
            "draft": True, "prerelease": True, "make_latest": "false",
        })

    def assets(self, release_id):
        assets = []
        page = 1
        while True:
            batch = self.request("GET", f"releases/{release_id}/assets?per_page=100&page={page}")
            assets.extend(batch)
            if len(batch) < 100:
                return assets
            page += 1

    def upload(self, tag, path):
        subprocess.run(
            ["gh", "release", "upload", tag, str(path), "--repo", self.repo],
            check=True, timeout=600,
        )

    def verify(self, asset, path):
        # Download through the authenticated API: this also works for the first draft.
        with tempfile.TemporaryDirectory() as scratch:
            remote = Path(scratch) / "asset"
            with remote.open("wb") as output:
                subprocess.run(
                    ["gh", "api", f"{self.root}/releases/assets/{asset['id']}",
                     "-H", "Accept: application/octet-stream"],
                    stdout=output, check=True, timeout=600,
                )
            if remote.stat().st_size != path.stat().st_size or sha256(remote) != sha256(path):
                raise AssetMismatchError(f"Remote asset checksum mismatch: {path.name}")

    def ensure_tag(self, tag, sha):
        current = self.request("GET", f"git/ref/tags/{tag}", missing_ok=True)
        if current is None:
            annotated = self.request("POST", "git/tags", {
                "tag": tag, "message": f"{OWNER_MARKER}\nCommit: {sha}\n",
                "object": sha, "type": "commit",
            })
            self.request("POST", "git/refs", {"ref": f"refs/tags/{tag}", "sha": annotated["sha"]})
            return
        if current["object"]["type"] != "tag":
            raise ValueError("Existing tag does not belong to the APK preview workflow")
        annotated = self.request("GET", f"git/tags/{current['object']['sha']}")
        if not is_owned_tag(tag, annotated) or annotated["object"]["sha"] != sha:
            raise ValueError("Existing tag points to another commit")

    def publish(self, release_id, notes):
        return self.request("PATCH", f"releases/{release_id}", {
            "body": notes,
            "draft": False, "prerelease": True, "make_latest": "false",
        })

    def delete_asset(self, asset_id):
        self.request("DELETE", f"releases/assets/{asset_id}")

    def delete_release(self, release_id):
        self.request("DELETE", f"releases/{release_id}")

    def owned_preview_tags(self):
        result = []
        for ref in self.request("GET", "git/matching-refs/tags/apk-preview-"):
            tag = ref["ref"].removeprefix("refs/tags/")
            if PREVIEW_TAG.fullmatch(tag) and ref["object"]["type"] == "tag":
                annotated = self.request("GET", f"git/tags/{ref['object']['sha']}")
                if is_owned_tag(tag, annotated):
                    result.append(tag)
        return result

    def delete_tag(self, tag):
        self.request("DELETE", f"git/refs/tags/{tag}", missing_ok=True)


def is_owned_preview(release):
    return (
        PREVIEW_TAG.fullmatch(release["tag_name"]) is not None
        and release["prerelease"]
        and OWNER_MARKER in (release.get("body") or "")
    )


def is_owned_tag(tag, annotated):
    match = PREVIEW_TAG.fullmatch(tag)
    return (
        match is not None and annotated["tag"] == tag
        and OWNER_MARKER in annotated["message"].splitlines()
        and annotated["object"]["type"] == "commit"
        and annotated["object"]["sha"].startswith(match[1])
    )


def unreleased_notes(changelog):
    lines = changelog.read_text().splitlines()
    content = []
    found = False
    for line in lines:
        if line.startswith("## ["):
            if found:
                break
            found = line == "## [Unreleased]"
        elif found:
            content.append(line)
    return "\n".join(content).strip()


def publish_preview(api, *, directory, sha, build_id, changelog, run_url, built_at):
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Expected the full checkout commit SHA")
    if not re.fullmatch(r"[0-9]+-[0-9]+", build_id):
        raise ValueError("Expected run_id-run_attempt for the build ID")
    tag = f"apk-preview-{sha[:7]}-{build_id}"
    prefix = f"Hoshi-Reader-preview-{sha[:7]}-{build_id}"
    apks = [directory / f"{prefix}-{abi}.apk" for abi in ("arm64-v8a", "armeabi-v7a")]
    for path in [*apks, directory / "LICENSE"]:
        if not path.is_file() or path.stat().st_size == 0:
            raise FileNotFoundError(f"Missing or empty release payload: {path}")
    notes = (
        f"{OWNER_MARKER}\n"
        "> [!NOTE]\n"
        "> **Development preview**\n"
        ">\n"
        "> Try upcoming features before the next stable release. This build has passed "
        "automated checks but may still contain bugs or breaking changes.\n"
        ">\n"
        "> It replaces your existing app and keeps your data. Data changes may affect "
        "compatibility with older builds.\n"
        ">\n"
        "> New previews must be downloaded manually from GitHub Releases. Updates to "
        "newer stable releases remain available as usual.\n\n"
        f"- Commit: [{sha}](https://github.com/{REPOSITORY}/tree/{sha})\n"
        f"- Built at (UTC): {built_at}\n"
        f"- [Build workflow]({run_url})\n\n"
        "<details>\n"
        "<summary>Unreleased changes</summary>\n\n"
        f"{unreleased_notes(changelog)}\n\n"
        "</details>\n"
    )
    if api.main_sha() != sha:
        print("Skipping preview: this commit is no longer main.")
        return False
    release = next((item for item in api.list_releases() if item["tag_name"] == tag), None)
    if release is not None and not is_owned_preview(release):
        raise ValueError("The preview tag belongs to an unrelated release")
    api.ensure_tag(tag, sha)
    release = release or api.create_draft(tag, sha, notes)
    existing = {asset["name"]: asset for asset in api.assets(release["id"])}
    # A new release owns its LICENSE; no replacement of another release is needed.
    payloads = [*apks, directory / "LICENSE"]
    for path in payloads:
        asset = existing.get(path.name)
        if asset is not None:
            try:
                api.verify(asset, path)
                continue
            except AssetMismatchError:
                if not release["draft"]:
                    raise  # Never modify a published build, including on retry.
                api.delete_asset(asset["id"])
        api.upload(tag, path)
        uploaded = {item["name"]: item for item in api.assets(release["id"])}
        api.verify(uploaded[path.name], path)

    # A newer push can arrive during the large uploads.
    if api.main_sha() != sha:
        print("Skipping preview promotion: main changed during upload.")
        return False
    if release["draft"]:
        release = api.publish(release["id"], notes)
    if release["draft"] or not is_owned_preview(release):
        raise RuntimeError("New APK preview was not completely published")

    releases = api.list_releases()
    owned_tags = set(api.owned_preview_tags())
    for old in releases:
        if old["tag_name"] != tag and is_owned_preview(old):
            api.delete_release(old["id"])
            if old["tag_name"] in owned_tags:
                api.delete_tag(old["tag_name"])

    # A failed cleanup may leave a tag after deleting its release. This reserved
    # namespace, annotation marker and SHA must match; unrelated tags stay.
    remaining = {item["tag_name"] for item in api.list_releases()}
    for old_tag in api.owned_preview_tags():
        if old_tag != tag and old_tag not in remaining:
            api.delete_tag(old_tag)
    print(f"Published {tag}")
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--build-id", required=True)
    parser.add_argument("--changelog", type=Path, default=Path("docs/CHANGELOG.md"))
    parser.add_argument("--run-url", required=True)
    parser.add_argument("--built-at", default=datetime.now(timezone.utc).isoformat())
    args = parser.parse_args()
    publish_preview(GitHub(REPOSITORY), **vars(args))


if __name__ == "__main__":
    main()
