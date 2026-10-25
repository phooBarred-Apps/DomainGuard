"""Package numbered screenshots into one phone-readable PDF, without uploading it."""

import argparse
import json
from pathlib import Path
from xml.sax.saxutils import escape

from PIL import Image
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.utils import ImageReader
from reportlab.pdfgen import canvas
from reportlab.platypus import Paragraph


def write_preview_pdf(pages, output, title):
    """Share the page layout across reviews; callers provide images, labels and captions."""
    if not pages:
        raise ValueError("A preview needs at least one page.")
    if output.exists():
        raise ValueError("Output already exists; choose a new review filename.")
    prepared = []
    for page in pages:
        image = Path(page["image"]).resolve()
        with Image.open(image) as source:
            width, height = source.size
            source.verify()
        prepared.append((page, image, 380 * height / width))
    output.parent.mkdir(parents=True, exist_ok=True)
    pdf = canvas.Canvas(str(output), pageCompression=1)
    pdf.setTitle(title)
    pdf.setAuthor("DomainGuard")
    heading_style = ParagraphStyle("heading", fontName="Helvetica-Bold", fontSize=16, leading=20)
    caption_style = ParagraphStyle("caption", fontName="Helvetica", fontSize=11, leading=15,
                                   textColor="#444444")
    for index, (page, image, image_height) in enumerate(prepared, 1):
        label = f"{index}. {page['title']}"
        heading = Paragraph(escape(label), heading_style)
        caption = Paragraph(escape(page.get("caption", "")), caption_style)
        heading_height = heading.wrap(380, 10000)[1]
        caption_height = caption.wrap(380, 10000)[1]
        height = 16 + heading_height + 12 + image_height + 12 + caption_height + 36
        pdf.setPageSize((412, height))
        heading.drawOn(pdf, 16, height - 16 - heading_height)
        image_y = height - 16 - heading_height - 12 - image_height
        pdf.drawImage(ImageReader(str(image)), 16, image_y, width=380, height=image_height, mask="auto")
        caption.drawOn(pdf, 16, image_y - 12 - caption_height)
        pdf.setFont("Helvetica", 9)
        pdf.setFillColorRGB(.35, .35, .35)
        pdf.drawRightString(396, 12, f"{index} / {len(prepared)}")
        pdf.bookmarkPage(str(index))
        pdf.addOutlineEntry(label, str(index))
        pdf.showPage()
    pdf.save()
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    manifest_path = args.manifest.resolve()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    pages = [dict(page, image=manifest_path.parent / page["image"]) for page in manifest["pages"]]
    print(write_preview_pdf(pages, args.output.resolve(), manifest["title"]))


if __name__ == "__main__":
    main()
