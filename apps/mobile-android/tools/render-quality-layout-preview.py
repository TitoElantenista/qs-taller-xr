#!/usr/bin/env python3
"""Render the JVM-tested production layout as a visibly marked PDF sample.

Run the QualityReportLayoutTest first (:app:testDebugUnitTest). Android uses PdfDocument/Paint
for the real export; this desktop preview draws the same positioned instructions as SVG with
Noto Sans and converts them with rsvg-convert. No Python packages are needed. Photos are
complete, unmodified frames from the user's test video.

    python3 tools/render-quality-layout-preview.py [--png DIR]
"""
from pathlib import Path
import argparse
import base64
import html
import json
import subprocess
import tempfile

MOBILE = Path(__file__).resolve().parents[1]
ROOT = MOBILE.parents[1]
REPORTS = MOBILE / "app/build/reports"
OUTPUT = ROOT / "docs/report-examples/auftrag-qs-muster"
ASSETS = OUTPUT / "assets"
PHOTOS = [ASSETS / "ar-01-20.png", ASSETS / "ar-01-25.png"]
SAMPLES = {
    "quality-layout-preview.json": "QS-Pruefbericht-0.31.0-Layoutvorschau.pdf",
    "quality-collection-preview.json": "QS-Sammelbericht-0.28.0-Layoutvorschau.pdf",
    "quality-assembly-preview.json": "QS-Pruefbericht-Baugruppe-0.30.0-Layoutvorschau.pdf",
}
# Android draws text from its top with the font ascent; Roboto's is about 0.93 em.
ASCENT = 0.93


_CACHE: dict[Path, str] = {}


def photo_uri(image_id: str) -> str:
    digits = "".join(ch for ch in image_id if ch.isdigit())
    source = PHOTOS[(int(digits) - 1 if digits else 0) % len(PHOTOS)]
    if source not in _CACHE:
        # About the resolution the app embeds (2.6 px per point), so the sample stays small.
        jpeg = subprocess.run(["magick", str(source), "-resize", "x600>", "-quality", "85", "jpeg:-"],
                              check=True, capture_output=True).stdout
        _CACHE[source] = "data:image/jpeg;base64," + base64.b64encode(jpeg).decode()
    return _CACHE[source]


def page_svg(page: dict, width: float, height: float) -> str:
    parts = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}pt" height="{height}pt" '
             f'viewBox="0 0 {width} {height}"><rect width="100%" height="100%" fill="white"/>']
    for d in page["drawings"]:
        kind, x, y, w, h, color = d["kind"], d["x"], d["y"], d["width"], d["height"], d["color"]
        if kind == "text":
            weight = "bold" if d["bold"] else "normal"
            parts.append(f'<text x="{x}" y="{y + d["size"] * ASCENT}" font-family="Noto Sans" font-size="{d["size"]}" '
                         f'font-weight="{weight}" fill="{color}" xml:space="preserve">{html.escape(d["text"])}</text>')
        elif kind == "rect":
            parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{d["radius"]}" fill="{color}"/>')
        elif kind == "line":
            parts.append(f'<line x1="{x}" y1="{y}" x2="{x + w}" y2="{y}" stroke="{color}" stroke-width="0.6"/>')
        elif kind == "logo":
            logo = "data:image/png;base64," + base64.b64encode((ASSETS / "muster-logo.png").read_bytes()).decode()
            parts.append(f'<image x="{x}" y="{y}" width="{w}" height="{h}" preserveAspectRatio="xMidYMid meet" href="{logo}"/>')
        elif kind == "photo":
            parts.append(f'<image x="{x}" y="{y}" width="{w}" height="{h}" preserveAspectRatio="xMidYMid meet" '
                         f'href="{photo_uri(d["imageId"])}"/>')
    # Sample designation, deliberately outside the production drawing instructions.
    parts.append(f'<text x="{width - 34}" y="{height - 4}" text-anchor="end" font-family="Noto Sans" font-size="6" '
                 f'font-weight="bold" fill="#92520F">MUSTER · LAYOUTVORSCHAU · fiktive Daten</text></svg>')
    return "\n".join(parts)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--png", type=Path, help="also write one PNG per page into this folder")
    args = parser.parse_args()
    for source, target in SAMPLES.items():
        data = REPORTS / source
        if not data.is_file():
            print(f"{data} fehlt: zuerst die Layout-Tests ausführen")
            continue
        layout = json.loads(data.read_text())
        with tempfile.TemporaryDirectory() as temporary:
            files = []
            for index, page in enumerate(layout["pages"], start=1):
                svg = Path(temporary) / f"page-{index:02d}.svg"
                svg.write_text(page_svg(page, layout["width"], layout["height"]))
                files.append(str(svg))
                if args.png:
                    args.png.mkdir(parents=True, exist_ok=True)
                    subprocess.run(["rsvg-convert", "-d", "110", "-p", "110", "-o",
                                    str(args.png / f"{Path(target).stem}-{index}.png"), str(svg)], check=True)
            subprocess.run(["rsvg-convert", "-f", "pdf", "-o", str(OUTPUT / target), *files], check=True)
        print(f"{OUTPUT / target}: {len(layout['pages'])} Seiten")


if __name__ == "__main__":
    main()
