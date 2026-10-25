"""Release workflow regression checks. All GitHub writes and Android builds are simulated."""

import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SPEC = importlib.util.spec_from_file_location("publisher", Path(__file__).with_name("publish-release.py"))
publisher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(publisher)


class GitHubFixture:
    def __init__(self, commit):
        self.main = commit
        self.pulls = [{"merged_at": "2026-10-25", "merge_commit_sha": commit,
                       "base": {"ref": "main", "repo": {"id": 123}}}]
        self.existing = None
        self.latest_version = "v2.83"
        self.corrupt_asset = False
        self.public = False
        self.writes = []
        self.assets = []

    def request(self, route, data=None, *, method=None, upload=None, optional=False):
        if data is not None or upload is not None or method in ("POST", "PATCH"):
            self.writes.append((method or "POST", route))
        source = f"/repos/{publisher.SOURCE}"
        downloads = f"/repos/{publisher.DOWNLOADS}"
        if route == source:
            return {"id": 123, "full_name": publisher.SOURCE, "private": True}
        if route == source + "/branches/main":
            return {"commit": {"sha": self.main}}
        if route.startswith(source + "/commits/"):
            return self.pulls
        if route == downloads:
            return {"full_name": publisher.DOWNLOADS, "private": False, "default_branch": "main"}
        if route.startswith(downloads + "/releases/tags/"):
            return self.existing
        if route == downloads + "/releases/latest":
            return {"id": 10 if self.public else 9,
                    "tag_name": "v2.84" if self.public else self.latest_version}
        if route == downloads + "/releases":
            return {"id": 10, "html_url": "https://github.com/" + publisher.DOWNLOADS + "/releases/tag/v2.84"}
        if upload is not None:
            result = {"state": "uploaded", "size": upload.stat().st_size,
                      "digest": "sha256:" + ("bad" if self.corrupt_asset else publisher.sha256(upload)),
                      "browser_download_url": f"https://github.com/{publisher.DOWNLOADS}/releases/download/v2.84/{upload.name}"}
            self.assets.append(result)
            return result
        if method == "PATCH":
            self.public = True
            return {"draft": False, "html_url": "https://github.com/" + publisher.DOWNLOADS + "/releases/tag/v2.84"}
        raise AssertionError("Unexpected API call: " + route)


class ReleaseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scratch = publisher.ROOT / ".gradle/verification/publisher-tests"
        cls.scratch.mkdir(parents=True, exist_ok=True)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(dir=self.scratch)
        self.root = Path(self.temporary.name).resolve()
        self.root.relative_to(self.scratch.resolve())
        self.addCleanup(self.temporary.cleanup)
        self.write(".gitignore", ".gradle/\napp/build/\n")
        self.write("app/build.gradle", 'versionName = "2.84"\nversionCode = 2026102501\n')
        self.write(publisher.HISTORY, json.dumps([{"tag_name": "v2.84"}, {"tag_name": "v2.83"}]))
        publisher.git(self.root, "init", "-q")
        publisher.git(self.root, "add", ".")
        publisher.git(self.root, "-c", "user.name=Release tests", "-c", "user.email=tests@example.invalid",
                      "-c", "commit.gpgsign=false", "commit", "-qm", "Fixture source")
        self.commit = publisher.git(self.root, "rev-parse", "HEAD")
        self.github = GitHubFixture(self.commit)
        self.notes = self.write(".gradle/notes.md", "A verified release.")
        self.report = self.write("app/build/test-results/testDebugUnitTest/TEST-example.xml",
                                 '<testsuite tests="72" failures="0" errors="0" skipped="1"/>')
        self.lint = self.write("app/build/reports/lint-results-release.xml", "<issues/>")
        self.apk = self.root / "app/build/outputs/apk/release/DomainGuard-release.apk"
        self.apk.parent.mkdir(parents=True)
        self.package = f"name='{publisher.PACKAGE}' versionCode='2026102501' versionName='2.84'"
        self.certificate = publisher.CERTIFICATE
        self.write_apk()
        self.build = patch.object(publisher, "build_release").start()
        self.addCleanup(patch.stopall)
        original = subprocess.check_output

        def inspect(command, **kwargs):
            if "verify" in command:
                return "Signer #1 certificate SHA-256 digest: " + self.certificate
            if "badging" in command:
                return self.package
            return original(command, **kwargs)

        patch.object(publisher.subprocess, "check_output", side_effect=inspect).start()
        self.download = patch.object(publisher, "verify_download").start()

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def write_apk(self, history=None):
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("res/o2.json", history if history is not None else (self.root / publisher.HISTORY).read_bytes())

    def run_release(self, publish=False):
        with contextlib.redirect_stdout(io.StringIO()):
            return publisher.release(self.root, self.github, "2.84", self.notes, self.root, publish)

    def reject(self, message):
        with self.assertRaisesRegex(RuntimeError, message):
            self.run_release(publish=True)
        self.assertEqual([], self.github.writes)

    def test_check_only_builds_matching_assets_without_uploading(self):
        """Test: Default invocation builds matching APK/source/checksums without writes.
        Why: Reviewing release preparation must not publish or change GitHub.
        """
        self.assertIsNone(self.run_release())
        self.assertEqual([], self.github.writes)
        self.build.assert_called_once()
        directory = next((self.root / ".gradle/verification").glob("release-2.84-*"))
        with zipfile.ZipFile(directory / "DomainGuard-source.zip") as archive:
            self.assertEqual((self.root / "app/build.gradle").read_bytes(), archive.read("DomainGuard/app/build.gradle"))
        verification = json.loads((directory / "verification.json").read_text())
        self.assertEqual(self.commit, verification["sourceCommit"])
        self.assertEqual(72, verification["unitTests"])
        self.assertIn(publisher.sha256(directory / "DomainGuard.apk"), (directory / "SHA256SUMS.txt").read_text())

    def test_publish_verifies_all_uploads_before_making_release_public(self):
        """Test: Explicit publication uploads all three assets before publishing.
        Why: Users must receive an APK with matching source and checksums.
        """
        result = self.run_release(publish=True)
        self.assertEqual(3, len(result["assets"]))
        self.assertEqual(["POST", "POST", "POST", "POST", "PATCH"], [method for method, _ in self.github.writes])
        self.assertEqual(3, self.download.call_count)

    def test_missing_or_invalid_pr_prevents_build_and_publication(self):
        """Test: Direct commits and PRs with the wrong state, base or commit are rejected.
        Why: Publishing must not reintroduce the former direct-to-main workflow.
        """
        valid = copy.deepcopy(self.github.pulls[0])
        variants = [[], [dict(valid, merged_at=None)], [dict(valid, merge_commit_sha="other")],
                    [dict(valid, base={"ref": "other", "repo": {"id": 123}})],
                    [dict(valid, base={"ref": "main", "repo": {"id": 999}})]]
        for pulls in variants:
            with self.subTest(pulls=pulls):
                self.github.pulls = pulls
                self.reject("merged through a pull request")
        self.build.assert_not_called()

    def test_unmerged_head_is_rejected(self):
        """Test: A feature commit is rejected even if it has a PR.
        Why: Release source must be the actual merged main revision.
        """
        self.github.main = "another-commit"
        self.reject("merged main revision")

    def test_source_change_during_build_stops_upload(self):
        """Test: Movement of main while building aborts publication.
        Why: Checked artifacts and published source must remain tied together.
        """
        self.build.side_effect = lambda *_: setattr(self.github, "main", "another-commit")
        self.reject("merged main revision")

    def test_dirty_source_is_rejected(self):
        """Test: Uncommitted source prevents publication.
        Why: The source archive must represent the build inputs.
        """
        self.write("uncommitted.txt", "change")
        self.reject("Commit all source")

    def test_existing_release_is_never_overwritten(self):
        """Test: An existing draft or published tag stops the run.
        Why: Retrying must not replace earlier release assets.
        """
        self.github.existing = {"id": 99}
        self.reject("already exists")
        self.build.assert_not_called()

    def test_older_release_cannot_become_latest(self):
        """Test: A version behind the current public release is rejected.
        Why: An old publisher must not roll back the updater's latest release.
        """
        self.github.latest_version = "v2.85"
        self.reject("newer than")

    def test_requested_version_must_match_source(self):
        """Test: Version arguments cannot override the app version.
        Why: A release tag must describe the installed APK correctly.
        """
        with self.assertRaisesRegex(RuntimeError, "app/build.gradle"):
            publisher.project_version(self.root, "2.85")

    def test_signer_change_is_rejected(self):
        """Test: A validly signed APK with the wrong key is rejected.
        Why: Existing phones require signing continuity for upgrades.
        """
        self.certificate = "0" * 64
        self.reject("certificate changed")

    def test_wrong_package_version_and_debug_build_are_rejected(self):
        """Test: Wrong package, version code/name or debug status prevents upload.
        Why: A build for another target must never be distributed as this update.
        """
        original = self.package
        for package in [original.replace(publisher.PACKAGE, "wrong.app"), original.replace("2.84", "2.83"),
                        original.replace("2026102501", "1"), original + "\napplication-debuggable"]:
            with self.subTest(package=package):
                self.package = package
                self.reject("APK|debuggable")

    def test_failed_or_empty_unit_suite_is_rejected(self):
        """Test: Failures, errors, no tests and fully skipped tests prevent release.
        Why: A successful package build alone is insufficient validation.
        """
        for attributes in ['tests="72" failures="1"', 'tests="72" errors="1"', 'tests="0"',
                           'tests="72" skipped="72"']:
            with self.subTest(attributes=attributes):
                self.report.write_text("<testsuite " + attributes + "/>")
                self.reject("Unit tests did not pass")

    def test_lint_errors_are_rejected(self):
        """Test: Fatal release lint issues prevent publication.
        Why: New release defects must not be hidden by the upstream baseline.
        """
        self.lint.write_text('<issues><issue severity="Fatal"/></issues>')
        self.reject("lint has errors")

    def test_packaged_history_must_match_source(self):
        """Test: Old update history inside an otherwise valid APK is rejected.
        Why: Users must receive the reviewed release history in the actual APK.
        """
        self.write_apk(b"[]")
        self.reject("history differs")

    def test_failed_upload_leaves_release_as_draft(self):
        """Test: A mismatched server checksum prevents making the draft public.
        Why: Partial or corrupt upload must not become the latest update.
        """
        self.github.corrupt_asset = True
        with self.assertRaisesRegex(RuntimeError, "asset verification failed"):
            self.run_release(publish=True)
        self.assertFalse(self.github.public)
        self.assertFalse(any(method == "PATCH" for method, _ in self.github.writes))


if __name__ == "__main__":
    unittest.main(verbosity=2)
