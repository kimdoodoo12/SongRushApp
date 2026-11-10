"""Draws red callout boxes on copies of the measurement captures (cycle 105).

The captures in perf/results/shots/ are evidence and stay untouched; this tool
writes annotated copies for docs/performance.md. The spec (JSON) maps each
source image to an optional crop and a list of boxes:

    {"images": [{"source": "perf/results/shots/x.png",
                 "crop": {"x": 0, "y": 0, "w": 100, "h": 100},
                 "boxes": [{"x": 1, "y": 2, "w": 3, "h": 4,
                            "label": "text", "label_at": "right"}]}]}

Box and crop coordinates are pixels of the source image; boxes are moved by
the crop. "label" may hold "\\n" for a second line, or be empty for a box
without a pill. "label_at" puts the pill outside the box: "right" (default),
"left", "top-right", "top-left", "bottom-right" or "bottom-left"; the pill is
kept inside the image.

    python -I perf/annotate.py --spec perf/annotations.json --out docs/perf/annotated

Writes <out>/<source stem>-annotated.png for each image and prints each path
with its size. Needs Pillow and the Malgun Gothic font (Windows).
"""

import argparse
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

RED = (229, 57, 53)
WHITE = (255, 255, 255)
LINE = 4
RADIUS = 8
FONT_SIZE = 20
PAD_X = 12
PAD_Y = 6
LINE_GAP = 4
GAP = 8
FONTS = ("C:/Windows/Fonts/malgunbd.ttf", "C:/Windows/Fonts/malgun.ttf")
ANCHORS = ("right", "left", "top-right", "top-left", "bottom-right", "bottom-left")


def load_font():
    for path in FONTS:
        if Path(path).is_file():
            return ImageFont.truetype(path, FONT_SIZE)
    raise SystemExit(f"no Korean font found: {', '.join(FONTS)}")


def pill_size(draw, font, lines):
    widths, heights = [], []
    for line in lines:
        left, top, right, bottom = draw.textbbox((0, 0), line, font=font)
        widths.append(right - left)
        heights.append(bottom - top)
    line_h = max(FONT_SIZE, max(heights))
    w = max(widths) + 2 * PAD_X
    h = len(lines) * line_h + (len(lines) - 1) * LINE_GAP + 2 * PAD_Y
    return w, h, line_h


def place(anchor, box, size, image_size):
    x0, y0, x1, y1 = box
    w, h = size
    if anchor == "right":
        x, y = x1 + GAP, (y0 + y1 - h) // 2
    elif anchor == "left":
        x, y = x0 - GAP - w, (y0 + y1 - h) // 2
    elif anchor == "top-right":
        x, y = x1 - w, y0 - GAP - h
    elif anchor == "top-left":
        x, y = x0, y0 - GAP - h
    elif anchor == "bottom-right":
        x, y = x1 - w, y1 + GAP
    else:
        x, y = x0, y1 + GAP
    x = min(max(x, 0), image_size[0] - w)
    y = min(max(y, 0), image_size[1] - h)
    return x, y


def annotate(entry, font, out_dir):
    source = Path(entry["source"])
    image = Image.open(source).convert("RGB")
    ox, oy = 0, 0
    crop = entry.get("crop")
    if crop:
        ox, oy = crop["x"], crop["y"]
        image = image.crop((ox, oy, ox + crop["w"], oy + crop["h"]))
    draw = ImageDraw.Draw(image)
    for spec in entry.get("boxes", []):
        anchor = spec.get("label_at", "right")
        if anchor not in ANCHORS:
            raise SystemExit(f"{source}: unknown label_at {anchor!r}")
        box = (spec["x"] - ox, spec["y"] - oy,
               spec["x"] - ox + spec["w"], spec["y"] - oy + spec["h"])
        draw.rounded_rectangle(box, radius=RADIUS, outline=RED, width=LINE)
        label = spec.get("label", "")
        if not label:
            continue
        lines = label.split("\n")
        w, h, line_h = pill_size(draw, font, lines)
        x, y = place(anchor, box, (w, h), image.size)
        draw.rounded_rectangle((x, y, x + w, y + h), radius=min(h // 2, 16), fill=RED)
        for i, line in enumerate(lines):
            ty = y + PAD_Y + i * (line_h + LINE_GAP) + line_h // 2
            draw.text((x + PAD_X, ty), line, font=font, fill=WHITE, anchor="lm")
    out = out_dir / f"{source.stem}-annotated.png"
    image.save(out, optimize=True)
    return out, image.size


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--spec", required=True, help="annotations JSON file")
    parser.add_argument("--out", required=True, help="output folder")
    args = parser.parse_args(argv)
    sys.stdout.reconfigure(encoding="utf-8")
    spec = json.loads(Path(args.spec).read_text(encoding="utf-8"))
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    font = load_font()
    for entry in spec["images"]:
        out, size = annotate(entry, font, out_dir)
        print(f"{out.as_posix()} {size[0]}x{size[1]} {out.stat().st_size} B")
    return 0


if __name__ == "__main__":
    sys.exit(main())
