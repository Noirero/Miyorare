#!/usr/bin/env python3
import argparse
import base64
import io
import json
import zipfile
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw

BADGES = [
    "badge_01_first_page_silver",
    "badge_02_first_light_blue",
    "badge_03_cyan_orbit",
    "badge_04_emerald_pulse",
    "badge_05_arcane_scholar",
    "badge_06_violet_halo",
    "badge_07_rose_nebula",
    "badge_08_crimson_ember",
    "badge_09_amber_manuscript",
    "badge_10_golden_manuscript_deluxe",
    "badge_11_eternal_library_prism",
    "badge_12_celestial_infinity",
]

def load_zip(payload_path: Path):
    encoded = payload_path.read_text(encoding="utf-8")
    raw = base64.b64decode(encoded)
    return zipfile.ZipFile(io.BytesIO(raw), "r")

def checker(size: int) -> Image.Image:
    tile = max(16, size // 20)
    out = Image.new("RGBA", (size, size), (38, 42, 52, 255))
    draw = ImageDraw.Draw(out)
    for y in range(0, size, tile):
        for x in range(0, size, tile):
            if ((x // tile) + (y // tile)) % 2:
                draw.rectangle((x, y, x + tile - 1, y + tile - 1), fill=(53, 58, 70, 255))
    return out

def alpha_metrics(img: Image.Image):
    rgba = np.asarray(img.convert("RGBA"))
    alpha = rgba[:, :, 3]
    h, w = alpha.shape
    mask = (alpha > 8).astype(np.uint8)
    ys, xs = np.where(mask > 0)
    if len(xs) == 0:
        return {
            "size": [w, h], "empty": True, "bbox": None, "margins": None,
            "centerOffsetPx": None, "extentPx": None, "edgeAlphaFraction": 0.0,
            "componentCount": 0, "lineCandidates": [],
        }
    x0, x1 = int(xs.min()), int(xs.max())
    y0, y1 = int(ys.min()), int(ys.max())
    bbox_w, bbox_h = x1 - x0 + 1, y1 - y0 + 1
    cx = (x0 + x1) / 2.0
    cy = (y0 + y1) / 2.0
    center_offset = [round(cx - (w - 1) / 2.0, 2), round(cy - (h - 1) / 2.0, 2)]
    rim = max(2, min(w, h) // 100)
    edge = np.zeros_like(mask)
    edge[:rim, :] = 1
    edge[-rim:, :] = 1
    edge[:, :rim] = 1
    edge[:, -rim:] = 1
    edge_fraction = float((mask * edge).sum()) / float(max(1, edge.sum()))

    n, labels, stats, cents = cv2.connectedComponentsWithStats(mask, 8)
    lines = []
    comps = 0
    for i in range(1, n):
        x, y, cw, ch, area = [int(v) for v in stats[i]]
        if area < 6:
            continue
        comps += 1
        aspect = cw / max(1, ch)
        thin_horizontal = aspect >= 6.0 and ch <= max(10, int(h * 0.025))
        lower = y + ch / 2.0 >= h * 0.62
        if thin_horizontal and lower:
            lines.append({"x": x, "y": y, "w": cw, "h": ch, "area": area, "aspect": round(aspect, 2)})

    return {
        "size": [w, h],
        "empty": False,
        "bbox": [x0, y0, x1 + 1, y1 + 1],
        "margins": [x0, y0, w - (x1 + 1), h - (y1 + 1)],
        "centerOffsetPx": center_offset,
        "extentPx": [bbox_w, bbox_h],
        "edgeAlphaFraction": round(edge_fraction, 6),
        "alphaCoverage": round(float(mask.mean()), 6),
        "componentCount": comps,
        "lineCandidates": lines,
    }

def render_cell(img: Image.Image, cell: int) -> Image.Image:
    src = img.convert("RGBA")
    bg = checker(cell)
    bbox = src.getbbox()
    if bbox:
        obj = src.crop(bbox)
        target = int(cell * 0.83)
        obj.thumbnail((target, target), Image.Resampling.LANCZOS)
        x = (cell - obj.width) // 2
        y = (cell - obj.height) // 2
        bg.alpha_composite(obj, (x, y))
    return bg

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--payload", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    payload = Path(args.payload)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    report = {"payload": str(payload), "badges": []}
    cell = 360
    sheet = Image.new("RGBA", (cell * 4, cell * 3), (9, 13, 23, 255))
    draw = ImageDraw.Draw(sheet)

    with load_zip(payload) as zf:
        names = set(zf.namelist())
        for idx, stem in enumerate(BADGES, start=1):
            base_name = f"{stem}_base.webp"
            thumb_name = f"{stem}_thumb.webp"
            missing = [n for n in (base_name, thumb_name) if n not in names]
            if missing:
                raise SystemExit(f"Missing entries: {missing}")

            base = Image.open(io.BytesIO(zf.read(base_name))).convert("RGBA")
            thumb = Image.open(io.BytesIO(zf.read(thumb_name))).convert("RGBA")
            bm = alpha_metrics(base)
            tm = alpha_metrics(thumb)
            report["badges"].append({
                "index": idx,
                "stem": stem,
                "base": bm,
                "thumb": tm,
            })

            col = (idx - 1) % 4
            row = (idx - 1) // 4
            cell_img = render_cell(base, cell)
            sheet.alpha_composite(cell_img, (col * cell, row * cell))
            label_bg = (0, 0, 0, 155)
            draw.rectangle((col * cell + 8, row * cell + 8, col * cell + 78, row * cell + 38), fill=label_bg)
            draw.text((col * cell + 18, row * cell + 12), f"{idx:02d}", fill=(255, 255, 255, 255))

    blockers = []
    for item in report["badges"]:
        idx = item["index"]
        base = item["base"]
        if base["edgeAlphaFraction"] > 0.02:
            blockers.append({"index": idx, "reason": "alpha touches outer edge", "value": base["edgeAlphaFraction"]})
        margins = base["margins"] or [0, 0, 0, 0]
        if min(margins) < 8:
            blockers.append({"index": idx, "reason": "insufficient transparent padding", "margins": margins})
        if idx in (9, 10) and base["lineCandidates"]:
            blockers.append({"index": idx, "reason": "lower horizontal line candidate", "components": base["lineCandidates"]})
    report["blockers"] = blockers
    report["status"] = "BELUM_LOLOS" if blockers else "STATIC_AUDIT_NO_AUTOMATIC_BLOCKER"

    sheet.convert("RGB").save(out / "00-source-static-contact-sheet.jpg", quality=94, subsampling=0)
    (out / "source-metrics.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({"status": report["status"], "blockers": blockers}, indent=2))

if __name__ == "__main__":
    main()
