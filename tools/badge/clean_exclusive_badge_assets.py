#!/usr/bin/env python3
from __future__ import annotations
import argparse, base64, io, json, zipfile
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw

BASE_CANVAS = 1024
THUMB_CANVAS = 384
TARGET_EXTENT = 860
CLEAN_TIERS = {5, 7, 8, 9, 10, 11, 12}
DARK_BG_THRESHOLD = 200

BADGE_PREFIXES = {
    1: 'badge_01_first_page_silver',
    2: 'badge_02_first_light_blue',
    3: 'badge_03_cyan_orbit',
    4: 'badge_04_emerald_pulse',
    5: 'badge_05_arcane_scholar',
    6: 'badge_06_violet_halo',
    7: 'badge_07_rose_nebula',
    8: 'badge_08_crimson_ember',
    9: 'badge_09_amber_manuscript',
    10: 'badge_10_golden_manuscript_deluxe',
    11: 'badge_11_eternal_library_prism',
    12: 'badge_12_celestial_infinity',
}


def rgba_from_bytes(data: bytes) -> np.ndarray:
    return np.array(Image.open(io.BytesIO(data)).convert('RGBA'))


def webp_bytes(arr: np.ndarray, *, quality: int = 95) -> bytes:
    out = io.BytesIO()
    Image.fromarray(arr, 'RGBA').save(out, 'WEBP', quality=quality, method=4, exact=True)
    return out.getvalue()


def connected_external_dark_mask(arr: np.ndarray, threshold: int = DARK_BG_THRESHOLD) -> np.ndarray:
    rgb = arr[:, :, :3]
    alpha = arr[:, :, 3]
    hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV)
    value = hsv[:, :, 2]
    object_mask = alpha > 8

    # Historical extraction retained an opaque rectangular/matte crop behind some tiers.
    # Remove only dark regions connected to the transparent exterior, preserving enclosed
    # dark material that genuinely belongs to the badge.
    candidate = ((value < threshold) & object_mask).astype(np.uint8) * 255
    exterior = (~object_mask).astype(np.uint8) * 255
    exterior_adjacent = cv2.dilate(exterior, np.ones((3, 3), np.uint8)) > 0
    starts = (candidate > 0) & exterior_adjacent

    count, labels, _stats, _centroids = cv2.connectedComponentsWithStats(candidate, 8)
    external_dark = np.zeros_like(candidate)
    for label in np.unique(labels[starts]):
        if label != 0:
            external_dark[labels == label] = 255

    keep = (object_mask & (external_dark == 0)).astype(np.uint8) * 255

    # Recover a narrow ring of legitimate shaded/antialiased edge pixels without restoring
    # the large matte field.
    keep = cv2.dilate(keep, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5)))
    keep[alpha <= 8] = 0
    return keep


def remove_long_thin_artifacts(out: np.ndarray) -> np.ndarray:
    alpha = out[:, :, 3]
    binary = (alpha > 8).astype(np.uint8)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(binary, 8)
    for label in range(1, count):
        _x, _y, w, h, _area = [int(v) for v in stats[label]]
        if h <= 5 and w >= 40 and w / max(1, h) >= 12.0:
            alpha[labels == label] = 0
    out[:, :, 3] = alpha
    out[alpha == 0, :3] = 0
    return out


def clean_tier(arr: np.ndarray, tier: int) -> np.ndarray:
    if tier not in CLEAN_TIERS:
        return arr.copy()

    original_alpha = arr[:, :, 3].copy()
    keep = connected_external_dark_mask(arr)

    # Remove only genuinely tiny isolated debris. Intended sparkle components are larger.
    count, labels, stats, _ = cv2.connectedComponentsWithStats((keep > 0).astype(np.uint8), 8)
    for label in range(1, count):
        area = int(stats[label, cv2.CC_STAT_AREA])
        if area <= 3:
            keep[labels == label] = 0

    # Soft alpha transition; preserve original authored alpha inside the cutout.
    soft = cv2.GaussianBlur(keep.astype(np.float32) / 255.0, (0, 0), 0.65)
    soft = np.clip(soft * 1.30, 0.0, 1.0)
    new_alpha = (original_alpha.astype(np.float32) * soft).astype(np.uint8)

    out = arr.copy()
    out[:, :, 3] = new_alpha
    out[new_alpha == 0, :3] = 0
    return remove_long_thin_artifacts(out)


def significant_bbox(alpha: np.ndarray) -> tuple[int, int, int, int]:
    binary = (alpha > 12).astype(np.uint8)
    count, labels, stats, _ = cv2.connectedComponentsWithStats(binary, 8)
    significant = np.zeros_like(binary)
    for label in range(1, count):
        area = int(stats[label, cv2.CC_STAT_AREA])
        if area >= 8:
            significant[labels == label] = 1

    ys, xs = np.where(significant > 0)
    if len(xs) == 0:
        ys, xs = np.where(alpha > 0)
    if len(xs) == 0:
        raise RuntimeError('empty badge after cleanup')

    x0, x1, y0, y1 = int(xs.min()), int(xs.max()), int(ys.min()), int(ys.max())
    return x0, y0, x1 + 1, y1 + 1


def normalize_canvas(arr: np.ndarray, size: int = BASE_CANVAS, target_extent: int = TARGET_EXTENT) -> np.ndarray:
    x0, y0, x1, y1 = significant_bbox(arr[:, :, 3])
    pad = 4
    x0 = max(0, x0 - pad)
    y0 = max(0, y0 - pad)
    x1 = min(arr.shape[1], x1 + pad)
    y1 = min(arr.shape[0], y1 + pad)

    crop = Image.fromarray(arr[y0:y1, x0:x1], 'RGBA')
    cw, ch = crop.size
    scale = target_extent / float(max(cw, ch))
    nw = max(1, int(round(cw * scale)))
    nh = max(1, int(round(ch * scale)))
    crop = crop.resize((nw, nh), Image.Resampling.LANCZOS)

    canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(crop, ((size - nw) // 2, (size - nh) // 2))
    return np.array(canvas)


def _runs(mask: np.ndarray) -> list[tuple[int, int]]:
    runs: list[tuple[int, int]] = []
    start = None
    for x, value in enumerate(mask.tolist()):
        if value and start is None:
            start = x
        elif not value and start is not None:
            runs.append((start, x - 1))
            start = None
    if start is not None:
        runs.append((start, len(mask) - 1))
    return runs


def repair_flat_top_point(base: np.ndarray, tier: int) -> np.ndarray:
    # The poster crop truncates the top crystal/star point on tiers 11 and 12.
    # Reconstruct only the missing tip from the badge's own adjacent facet pixels.
    heights = {11: 90, 12: 55}
    if tier not in heights:
        return base

    out = base.copy()
    alpha = out[:, :, 3]
    ys, xs = np.where(alpha > 8)
    if len(xs) == 0:
        return out

    y0 = int(ys.min())
    overlap = 8
    seam = y0 + overlap
    if seam >= out.shape[0]:
        return out

    runs = _runs(alpha[seam] > 8)
    if not runs:
        return out

    center_x = out.shape[1] // 2
    x_left, x_right = min(
        runs,
        key=lambda r: 0 if r[0] <= center_x <= r[1]
        else min(abs(center_x - r[0]), abs(center_x - r[1])),
    )

    height = min(heights[tier], seam, out.shape[0] - seam)
    patch = out[seam:seam + height, x_left:x_right + 1].copy()
    if patch.size == 0:
        return out

    extension = np.flipud(patch)
    h, w = extension.shape[:2]
    yy = np.arange(h, dtype=np.float32)[:, None]
    xx = np.arange(w, dtype=np.float32)[None, :]
    fraction = (yy + 1.0) / float(h)
    half_width = (w / 2.0) * fraction
    center = (w - 1) / 2.0
    side_distance = np.minimum(xx - (center - half_width), (center + half_width) - xx)
    triangle_alpha = np.clip(side_distance + 1.2, 0.0, 1.0)

    extension[:, :, 3] = (
        extension[:, :, 3].astype(np.float32) * triangle_alpha
    ).astype(np.uint8)
    extension[extension[:, :, 3] == 0, :3] = 0

    canvas = Image.fromarray(out, 'RGBA')
    canvas.alpha_composite(Image.fromarray(extension, 'RGBA'), (x_left, seam - h))
    return np.array(canvas)


def scrub_post_normalization(base: np.ndarray, tier: int) -> np.ndarray:
    out = base.copy()
    alpha = out[:, :, 3]
    alpha[alpha <= 3] = 0

    # Badge 09/10 had poster-separator line remnants below the real ornament. Remove only
    # isolated long/thin components in the lower portion, never whole rows of badge art.
    if tier in {9, 10}:
        binary = (alpha > 0).astype(np.uint8)
        count, labels, stats, _ = cv2.connectedComponentsWithStats(binary, 8)
        for label in range(1, count):
            _x, y, w, h, _area = [int(v) for v in stats[label]]
            if (
                y > int(out.shape[0] * 0.70)
                and h <= 6
                and w >= 20
                and w / max(1, h) >= 8.0
            ):
                alpha[labels == label] = 0

    out[:, :, 3] = alpha
    out[alpha == 0, :3] = 0
    return out


def make_thumb(base: np.ndarray) -> np.ndarray:
    return np.array(
        Image.fromarray(base, 'RGBA').resize(
            (THUMB_CANVAS, THUMB_CANVAS),
            Image.Resampling.LANCZOS,
        )
    )


def metrics(arr: np.ndarray) -> dict:
    alpha = arr[:, :, 3]
    ys, xs = np.where(alpha > 8)
    if not len(xs):
        return {'empty': True}

    x0, x1, y0, y1 = int(xs.min()), int(xs.max()), int(ys.min()), int(ys.max())
    bw, bh = x1 - x0 + 1, y1 - y0 + 1
    top = float(np.count_nonzero(alpha[y0, x0:x1 + 1] > 8) / bw)
    bottom = float(np.count_nonzero(alpha[y1, x0:x1 + 1] > 8) / bw)
    left = float(np.count_nonzero(alpha[y0:y1 + 1, x0] > 8) / bh)
    right = float(np.count_nonzero(alpha[y0:y1 + 1, x1] > 8) / bh)

    binary = (alpha > 8).astype(np.uint8)
    count, _labels, stats, _ = cv2.connectedComponentsWithStats(binary, 8)
    long_thin = []
    for label in range(1, count):
        x, y, w, h, area = [int(v) for v in stats[label]]
        if w >= 160 and h <= 5 and w / max(1, h) >= 20:
            long_thin.append({'x': x, 'y': y, 'w': w, 'h': h, 'area': area})

    return {
        'bbox': [x0, y0, x1, y1],
        'bboxSize': [bw, bh],
        'alphaFraction': float(np.mean(alpha > 8)),
        'boundaryOccupancy': {
            'top': top,
            'bottom': bottom,
            'left': left,
            'right': right,
        },
        'longThinComponents': long_thin,
    }


def checker_contact(images: dict[int, np.ndarray], out_path: Path) -> None:
    cell = 300
    label_h = 28
    sheet = Image.new('RGB', (cell * 4, (cell + label_h) * 3), (8, 10, 16))
    draw = ImageDraw.Draw(sheet)

    yy, xx = np.indices((cell, cell))
    checker_cells = ((xx // 24 + yy // 24) % 2) == 0
    checker_np = np.zeros((cell, cell, 4), dtype=np.uint8)
    checker_np[:, :, 3] = 255
    checker_np[checker_cells, :3] = (42, 44, 50)
    checker_np[~checker_cells, :3] = (27, 29, 35)

    for tier in range(1, 13):
        arr = images[tier]
        img = Image.fromarray(arr, 'RGBA').resize(
            (cell, cell),
            Image.Resampling.LANCZOS,
        )
        checker = Image.fromarray(checker_np.copy(), 'RGBA')
        checker.alpha_composite(img)

        col = (tier - 1) % 4
        row = (tier - 1) // 4
        x = col * cell
        y = row * (cell + label_h)
        sheet.paste(checker.convert('RGB'), (x, y + label_h))
        draw.text((x + 5, y + 5), f'{tier:02d}', fill='white')

    sheet.save(out_path, 'PNG')


def process_entries(entries: dict[str, bytes], audit_dir: Path | None = None) -> dict[str, bytes]:
    output = dict(entries)
    normalized: dict[int, np.ndarray] = {}
    report = {
        'baseCanvas': BASE_CANVAS,
        'thumbCanvas': THUMB_CANVAS,
        'targetExtent': TARGET_EXTENT,
        'tiers': {},
    }

    for tier, prefix in BADGE_PREFIXES.items():
        base_name = prefix + '_base.webp'
        if base_name not in entries:
            raise RuntimeError(f'missing {base_name}')

        source = rgba_from_bytes(entries[base_name])
        cleaned = clean_tier(source, tier)
        base = normalize_canvas(cleaned)
        base = repair_flat_top_point(base, tier)
        base = scrub_post_normalization(base, tier)
        thumb = make_thumb(base)

        output[base_name] = webp_bytes(base, quality=95)
        output[prefix + '_thumb.webp'] = webp_bytes(thumb, quality=94)
        normalized[tier] = base
        report['tiers'][f'{tier:02d}'] = metrics(base)

    # Hard blockers from the badge cleanup checklist.
    for tier in (9, 10):
        m = report['tiers'][f'{tier:02d}']
        if m['longThinComponents']:
            raise RuntimeError(
                f'tier {tier:02d} still has crop-line components: '
                f'{m["longThinComponents"]}'
            )

    for tier in CLEAN_TIERS:
        occupancy = report['tiers'][f'{tier:02d}']['boundaryOccupancy']
        if max(occupancy.values()) > 0.55:
            raise RuntimeError(
                f'tier {tier:02d} still has rectangular boundary signature: '
                f'{occupancy}'
            )

    if audit_dir:
        audit_dir.mkdir(parents=True, exist_ok=True)
        (audit_dir / 'badge-cleanup-report.json').write_text(
            json.dumps(report, indent=2),
            encoding='utf-8',
        )
        checker_contact(normalized, audit_dir / 'badge-cleanup-checker.png')
        for tier, arr in normalized.items():
            Image.fromarray(arr, 'RGBA').save(audit_dir / f'{tier:02d}-base.png')

    return output


def read_payload(path: Path) -> dict[str, bytes]:
    raw = base64.b64decode(path.read_text(encoding='utf-8'))
    entries = {}
    with zipfile.ZipFile(io.BytesIO(raw), 'r') as zf:
        for name in zf.namelist():
            if not name.endswith('/'):
                entries[name] = zf.read(name)
    return entries


def write_payload(path: Path, entries: dict[str, bytes]) -> None:
    raw = io.BytesIO()
    with zipfile.ZipFile(
        raw,
        'w',
        compression=zipfile.ZIP_DEFLATED,
        compresslevel=9,
    ) as zf:
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            zf.writestr(info, entries[name])

    path.write_text(
        base64.encodebytes(raw.getvalue()).decode('ascii'),
        encoding='utf-8',
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--payload', type=Path, required=True)
    parser.add_argument('--audit-dir', type=Path)
    args = parser.parse_args()

    entries = read_payload(args.payload)
    output = process_entries(entries, args.audit_dir)
    write_payload(args.payload, output)


if __name__ == '__main__':
    main()
