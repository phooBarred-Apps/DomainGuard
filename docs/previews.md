# Preview tools

Use one numbered PDF for each review, containing all requested alternatives and
states. Keep screenshots and the review manifest together under an ignored
`.gradle/verification/<review-name>/` folder. Prefer actual app screenshots when
showing current behavior; label proposed mockups clearly.

`scripts/preview_pdf.py` provides the shared layout and packaging function.
Its input is a JSON manifest, with image paths relative to that manifest:

```json
{
  "title": "DomainGuard - example review",
  "pages": [
    {"title": "Current - collapsed", "image": "current.png"},
    {"title": "Proposed - expanded", "image": "proposed.png", "caption": "Proposed layout, not implemented."}
  ]
}
```

Install the local tooling dependencies `reportlab`, `Pillow` and `pypdf` if
needed. They are not Android app dependencies. Existing PDF/image renderers can
call `write_preview_pdf` with their page data instead of copying packaging code.

```powershell
python -B scripts/preview_pdf.py --manifest .gradle/verification/example/manifest.json --output .gradle/verification/example/output/pdf/DomainGuard-example.pdf
```

Pages have a phone-friendly 412-point width, preserve image proportions and
include numbered headings, captions and bookmarks. Long titles wrap. The tool
refuses to overwrite an existing PDF. Render and inspect the completed pages
before sharing them; successful generation alone does not prove a good layout.

Use the single preview uploader for the reviewed PDF. It checks without
uploading by default. Choose an actual existing public release tag explicitly:

```powershell
python -B scripts/publish-preview.py --tag v2.83 --file .gradle/verification/example/output/pdf/DomainGuard-example.pdf
```

Only when sharing that preview is authorized, add `--publish`. This uploads one
PDF asset, verifies its digest and anonymous phone download, and leaves release
metadata and existing assets unchanged. An identical existing asset is reused;
a name collision with different contents stops the operation. It never creates
a release, changes which release is latest, or uploads an APK. GitHub credentials
come from the same shared helper as the APK publisher.

The superseded local preview uploaders and PDF generators are preserved under
`.gradle/verification/archive/preview-tools-20261025/` with paths and SHA-256
hashes in its manifest. Existing previews and public links are retained. Consult
an archived generator only to recover historical artwork; do not create another
active publisher or packaging script for the next review.
