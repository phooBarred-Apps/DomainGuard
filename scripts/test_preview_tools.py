"""Preview packaging and publication regressions without real GitHub writes."""

import contextlib
import copy
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from PIL import Image
from pypdf import PdfReader

from preview_pdf import write_preview_pdf


SPEC = importlib.util.spec_from_file_location("preview", Path(__file__).with_name("publish-preview.py"))
preview = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(preview)


class GitHubFixture:
    def __init__(self):
        self.release = dict(id=10, tag_name="v2.83", name="DomainGuard 2.83", body="Release notes",
                            target_commitish="main", draft=False, prerelease=False, published_at="2026-10-24")
        self.assets = [dict(id=1, name="DomainGuard.apk", size=123, digest="sha256:apk", state="uploaded")]
        self.writes = 0
        self.corrupt = False

    def request(self, route, data=None, *, method=None, upload=None):
        if upload:
            self.writes += 1
            asset = dict(id=2, name=upload.name, size=upload.stat().st_size, state="uploaded",
                         digest="sha256:" + ("wrong" if self.corrupt else preview.sha256(upload)),
                         browser_download_url=f"https://github.com/{preview.DOWNLOADS}/releases/download/v2.83/{upload.name}")
            self.assets.append(asset)
            return copy.deepcopy(asset)
        if "/assets?" in route:
            return copy.deepcopy(self.assets)
        if "/releases/tags/" in route:
            return copy.deepcopy(self.release)
        return dict(full_name=preview.DOWNLOADS, private=False)


class PreviewTests(unittest.TestCase):
    def setUp(self):
        scratch = Path(__file__).resolve().parents[1] / ".gradle/verification/preview-tool-tests"
        scratch.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=scratch)
        self.root = Path(self.temporary.name).resolve()
        self.root.relative_to(scratch.resolve())
        self.addCleanup(self.temporary.cleanup)
        image = self.root / "screen.png"
        Image.new("RGB", (412, 800), "#303030").save(image)
        self.pdf = self.root / "DomainGuard-example.pdf"
        self.pages = [dict(title="Current - collapsed", image=image),
                      dict(title="Proposed - expanded with a longer heading that wraps naturally", image=image,
                           caption="Proposed layout, not implemented.")]
        write_preview_pdf(self.pages, self.pdf, "Example review")
        self.github = GitHubFixture()

    def publish(self, enabled=True):
        with contextlib.redirect_stdout(io.StringIO()):
            return preview.publish_preview(self.github, "v2.83", self.pdf, enabled)

    def test_pdf_preserves_every_page_and_labels_in_one_phone_width_file(self):
        """Test: Two screenshots become two labelled pages and bookmarks in one PDF.
        Why: All review alternatives must remain readable together on a phone.
        """
        reader = PdfReader(self.pdf)
        self.assertEqual(2, len(reader.pages))
        self.assertEqual(2, len(reader.outline))
        self.assertTrue(all(float(page.mediabox.width) == 412 for page in reader.pages))
        self.assertIn("1. Current", reader.pages[0].extract_text())
        self.assertIn("not implemented", reader.pages[1].extract_text())

    def test_packager_does_not_replace_an_earlier_review(self):
        """Test: An existing output filename is rejected before modification.
        Why: Previously reviewed artifacts must remain recoverable.
        """
        original = self.pdf.read_bytes()
        with self.assertRaisesRegex(ValueError, "already exists"):
            write_preview_pdf(self.pages, self.pdf, "Replacement")
        self.assertEqual(original, self.pdf.read_bytes())

    def test_check_only_never_uploads(self):
        """Test: Checking a new preview makes no GitHub writes.
        Why: Local preparation must not publish an unreviewed document.
        """
        self.assertIsNone(self.publish(False))
        self.assertEqual(0, self.github.writes)

    def test_publish_preserves_existing_assets_and_reuses_identical_preview(self):
        """Test: Upload one PDF, preserve the APK and reuse the same asset on retry.
        Why: Preview sharing must neither modify releases nor duplicate uploads.
        """
        original = copy.deepcopy(self.github.assets[0])
        with patch.object(preview, "verify_download") as download:
            first = self.publish()
            second = self.publish()
            self.assertEqual(2, download.call_count)
        self.assertEqual(first, second)
        self.assertEqual(original, self.github.assets[0])
        self.assertEqual(1, self.github.writes)

    def test_existing_name_with_different_content_is_rejected(self):
        """Test: A conflicting preview filename stops without replacing the asset.
        Why: Existing public review links must keep pointing to the reviewed file.
        """
        self.github.assets.append(dict(id=2, name=self.pdf.name, size=1, digest="sha256:different"))
        with self.assertRaisesRegex(RuntimeError, "different contents"):
            self.publish()
        self.assertEqual(0, self.github.writes)

    def test_wrong_upload_digest_is_rejected(self):
        """Test: A server checksum mismatch prevents reporting successful publication.
        Why: A phone link is useful only when it serves the intended PDF.
        """
        self.github.corrupt = True
        with self.assertRaisesRegex(RuntimeError, "upload verification failed"):
            self.publish()

    def test_apk_upload_is_not_a_preview_operation(self):
        """Test: The preview tool rejects an APK before any request to publish it.
        Why: Sharing previews must not become a second APK publisher.
        """
        with self.assertRaisesRegex(RuntimeError, "Use one PDF"):
            preview.publish_preview(self.github, "v2.83", self.root / "DomainGuard.apk", True)
        self.assertEqual(0, self.github.writes)


if __name__ == "__main__":
    unittest.main(verbosity=2)
