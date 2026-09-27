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

BASE_CANVAS = 1024
BASE_EXTENT = 900
THUMB_CANVAS = 384

def decode_payload(path: Path):
    raw = base64.b64decode(path.read_text(encoding="utf-8"))
    with zipfile.ZipFile(io.BytesIO(raw), "r") as zf:
        return {name: zf.read(name) for name in zf.namelist() if not name.endswith("/")}

def encode_payload(entries, output: Path):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, date_time=(2026, 9, 27, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            zf.writestr(info, entries[name])
    output.write_text(base64.encodebytes(buffer.getvalue()).decode("ascii"), encoding="ascii")

def nonzero_bbox(alpha, threshold=8):
    ys, xs = np.where(alpha > threshold)
    if xs.size == 0:
        return None
    return int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1

def remove_background_matte(rgba, badge_index):
    out = rgba.copy()
    alpha = out[:, :, 3]
    bbox = nonzero_bbox(alpha)
    if bbox is None:
        return out, {"removedMattePixels": 0}
    x0, y0, x1, y1 = bbox
    rgb = out[:, :, :3].astype(np.float32)
    lum = 0.2126 * rgb[:, :, 0] + 0.7152 * rgb[:, :, 1] + 0.0722 * rgb[:, :, 2]

    # Poster/card matte is dark and connected to the crop perimeter. Protect the
    # authored internal backplate/material and dark edge shading around bright ornament;
    # only exterior crop matte is eligible for removal.
    yy, xx = np.ogrid[:alpha.shape[0], :alpha.shape[1]]
    cx = (x0 + x1 - 1) / 2.0
    cy = (y0 + y1 - 1) / 2.0
    rx = max(1.0, (x1 - x0) * 0.34)
    ry = max(1.0, (y1 - y0) * 0.34)
    protected_inner = (((xx - cx) / rx) ** 2 + ((yy - cy) / ry) ** 2) <= 1.0

    rgb_max = rgb.max(axis=2)
    rgb_min = rgb.min(axis=2)
    chroma = rgb_max - rgb_min
    authored_bright = (lum >= 132) | ((rgb_max >= 145) & (chroma >= 38))
    distance_to_bright = cv2.distanceTransform((~authored_bright).astype(np.uint8), cv2.DIST_L2, 3)
    protected_edge_shadow = distance_to_bright <= 10.0

    removable_zone = ~(protected_inner | protected_edge_shadow)
    candidate = ((alpha > 8) & (lum < 108) & removable_zone).astype(np.uint8)
    sub = candidate[y0:y1, x0:x1]
    n, labels, stats, _ = cv2.connectedComponentsWithStats(sub, 8)
    remove = np.zeros_like(candidate, dtype=np.uint8)
    h, w = sub.shape
    for i in range(1, n):
        sx, sy, sw, sh, area = [int(v) for v in stats[i]]
        touches = sx <= 1 or sy <= 1 or sx + sw >= w - 1 or sy + sh >= h - 1
        if touches and area >= 24:
            remove[y0:y1, x0:x1][labels == i] = 1

    # Grow only through still-dark neighbouring matte, never through bright rim light.
    grown = remove.copy()
    dark2 = ((alpha > 8) & (lum < 138) & removable_zone).astype(np.uint8)
    kernel = np.ones((3, 3), np.uint8)
    for _ in range(2):
        fringe = cv2.dilate(grown, kernel, iterations=1)
        grown = ((grown > 0) | ((fringe > 0) & (dark2 > 0))).astype(np.uint8)

    exterior_threshold = 100 if badge_index <= 4 else 120
    if badge_index in (7, 9, 10, 11, 12):
        exterior_threshold = 128
    direct_exterior = ((alpha > 8) & (lum < exterior_threshold) & removable_zone).astype(np.uint8)
    grown = ((grown > 0) | (direct_exterior > 0)).astype(np.uint8)
    removed = int((grown > 0).sum())
    out[grown > 0, 3] = 0
    out[grown > 0, :3] = 0
    return out, {"removedMattePixels": removed}

def hard_trim_poster_baseline(rgba, badge_index):
    if badge_index not in (9, 10):
        return rgba, None
    out = rgba.copy()
    alpha = out[:, :, 3]
    mask = alpha > 5
    h, w = mask.shape
    counts = mask.sum(axis=1)
    candidates = np.where((np.arange(h) >= int(h * 0.72)) & (counts >= int(w * 0.40)))[0]
    if candidates.size == 0:
        return out, None
    cut = max(0, int(candidates[0]) - 3)
    out[cut:, :, 3] = 0
    out[cut:, :, :3] = 0
    return out, cut

def remove_crop_lines_and_trash(rgba, badge_index):
    out = rgba.copy()
    alpha = out[:, :, 3]
    mask = (alpha > 8).astype(np.uint8)
    h, w = mask.shape

    # 09/10 poster baselines contain very faint antialias pixels that can fall below
    # ordinary component thresholds. Extract only long straight horizontal runs in
    # the lower zone; curved laurel/diamond ornament is not long enough to survive
    # this morphology and therefore remains intact.
    if badge_index in (9, 10):
        low_mask = (alpha > 0).astype(np.uint8)
        low_mask[:int(h * 0.72), :] = 0
        kernel_w = max(64, int(w * 0.18))
        straight = cv2.morphologyEx(
            low_mask,
            cv2.MORPH_OPEN,
            np.ones((1, kernel_w), np.uint8),
        )
        straight = cv2.dilate(straight, np.ones((7, 3), np.uint8), iterations=1)
        out[straight > 0, 3] = 0
        out[straight > 0, :3] = 0
        alpha = out[:, :, 3]
        mask = (alpha > 8).astype(np.uint8)
    n, labels, stats, cents = cv2.connectedComponentsWithStats(mask, 8)
    removed = []
    for i in range(1, n):
        x, y, cw, ch, area = [int(v) for v in stats[i]]
        cx, cy = [float(v) for v in cents[i]]
        horizontal_crop = (
            cw >= int(w * 0.18)
            and ch <= max(8, int(h * 0.018))
            and cy >= h * 0.72
            and cw / max(1, ch) >= 12.0
        )
        vertical_crop = (
            ch >= int(h * 0.32)
            and cw <= max(8, int(w * 0.018))
            and (cx <= w * 0.10 or cx >= w * 0.90)
            and ch / max(1, cw) >= 12.0
        )
        tiny_edge_trash = area <= 3 and (x <= 2 or y <= 2 or x + cw >= w - 2 or y + ch >= h - 2)
        if horizontal_crop:
            pad_x = 3
            pad_y = 3
            xa = max(0, x - pad_x)
            xb = min(w, x + cw + pad_x)
            ya = max(0, y - pad_y)
            yb = min(h, y + ch + pad_y)
            out[ya:yb, xa:xb, 3] = 0
            out[ya:yb, xa:xb, :3] = 0
            removed.append({"x":x,"y":y,"w":cw,"h":ch,"area":area,"kind":"horizontal"})
        elif vertical_crop or tiny_edge_trash:
            out[labels == i, 3] = 0
            out[labels == i, :3] = 0
            removed.append({"x":x,"y":y,"w":cw,"h":ch,"area":area,"kind":"edge"})
    return out, removed

def normalize(rgba):
    alpha = rgba[:, :, 3]
    bbox = nonzero_bbox(alpha)
    if bbox is None:
        raise ValueError("empty badge after cleanup")
    x0, y0, x1, y1 = bbox
    crop = Image.fromarray(rgba, "RGBA").crop((x0, y0, x1, y1))
    scale = BASE_EXTENT / max(crop.width, crop.height)
    nw = max(1, round(crop.width * scale))
    nh = max(1, round(crop.height * scale))
    crop = crop.resize((nw, nh), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (BASE_CANVAS, BASE_CANVAS), (0,0,0,0))
    x = (BASE_CANVAS - nw) // 2
    y = (BASE_CANVAS - nh) // 2
    canvas.alpha_composite(crop, (x, y))
    return canvas

def to_webp_bytes(img, quality):
    b = io.BytesIO()
    img.save(b, "WEBP", quality=quality, method=6, lossless=False)
    return b.getvalue()

def line_blockers(img):
    a = np.asarray(img.convert("RGBA"))[:, :, 3]
    mask = (a > 12).astype(np.uint8)
    h, w = mask.shape
    n, labels, stats, cents = cv2.connectedComponentsWithStats(mask, 8)
    result=[]
    for i in range(1,n):
        x,y,cw,ch,area=[int(v) for v in stats[i]]
        cx,cy=[float(v) for v in cents[i]]
        if cw >= int(w*0.18) and ch <= max(8,int(h*0.018)) and cy >= h*0.72 and cw/max(1,ch)>=12:
            result.append({"x":x,"y":y,"w":cw,"h":ch,"area":area})
    return result

def alpha_metrics(img):
    a=np.asarray(img.convert("RGBA"))[:,:,3]
    bbox=nonzero_bbox(a)
    if bbox is None:
        return {"bbox":None}
    x0,y0,x1,y1=bbox
    return {
        "bbox":[x0,y0,x1,y1],
        "margins":[x0,y0,img.width-x1,img.height-y1],
        "extent":[x1-x0,y1-y0],
        "centerOffset":[round((x0+x1-1)/2-(img.width-1)/2,2),round((y0+y1-1)/2-(img.height-1)/2,2)],
        "coverage":round(float((a>8).mean()),6),
        "lineBlockers":line_blockers(img),
    }

def checker(size):
    tile=max(14,size//18)
    im=Image.new("RGBA",(size,size),(38,42,52,255))
    d=ImageDraw.Draw(im)
    for y in range(0,size,tile):
        for x in range(0,size,tile):
            if ((x//tile)+(y//tile))%2:
                d.rectangle((x,y,x+tile-1,y+tile-1),fill=(54,59,71,255))
    return im

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--source-payload",required=True)
    ap.add_argument("--output-payload",required=True)
    ap.add_argument("--audit-out",required=True)
    args=ap.parse_args()

    entries=decode_payload(Path(args.source_payload))
    audit=Path(args.audit_out)
    audit.mkdir(parents=True,exist_ok=True)
    report={"baseCanvas":BASE_CANVAS,"baseExtent":BASE_EXTENT,"thumbCanvas":THUMB_CANVAS,"badges":[]}
    cleaned={}

    cell=360
    sheet=Image.new("RGBA",(cell*4,cell*3),(9,13,23,255))
    draw=ImageDraw.Draw(sheet)

    for idx, stem in enumerate(BADGES, start=1):
        base_name=f"{stem}_base.webp"
        thumb_name=f"{stem}_thumb.webp"
        src=Image.open(io.BytesIO(entries[base_name])).convert("RGBA")
        rgba=np.asarray(src).copy()
        rgba, matte_info=remove_background_matte(rgba, idx)
        hard_cut=None
        rgba, removed_lines=remove_crop_lines_and_trash(rgba, idx)
        base=normalize(rgba)
        thumb=base.resize((THUMB_CANVAS,THUMB_CANVAS),Image.Resampling.LANCZOS)

        bm=alpha_metrics(base)
        tm=alpha_metrics(thumb)
        blockers=[]
        if bm["lineBlockers"]:
            blockers.append("horizontal crop line remains")
        if min(bm["margins"]) < 45:
            blockers.append(f"base padding too small: {bm['margins']}")
        if max(abs(v) for v in bm["centerOffset"]) > 2.0:
            blockers.append(f"base not centered: {bm['centerOffset']}")
        if abs(max(bm["extent"])-BASE_EXTENT) > 4:
            blockers.append(f"optical extent drift: {bm['extent']}")

        report["badges"].append({
            "index":idx,"stem":stem,
            **matte_info,
            "hardBaselineCutY":hard_cut,
            "removedCropComponents":removed_lines,
            "base":bm,"thumb":tm,"blockers":blockers,
        })
        if blockers:
            raise SystemExit(f"Badge {idx:02d} cleanup QA failed: {blockers}")

        cleaned[base_name]=to_webp_bytes(base,95)
        cleaned[thumb_name]=to_webp_bytes(thumb,92)

        col=(idx-1)%4; row=(idx-1)//4
        bg=checker(cell)
        preview=base.copy()
        preview.thumbnail((int(cell*.90),int(cell*.90)),Image.Resampling.LANCZOS)
        bg.alpha_composite(preview,((cell-preview.width)//2,(cell-preview.height)//2))
        sheet.alpha_composite(bg,(col*cell,row*cell))
        draw.rectangle((col*cell+8,row*cell+8,col*cell+78,row*cell+38),fill=(0,0,0,160))
        draw.text((col*cell+18,row*cell+12),f"{idx:02d}",fill=(255,255,255,255))

    for name, data in cleaned.items():
        entries[name]=data

    out_payload=Path(args.output_payload)
    encode_payload(entries,out_payload)

    clean_path=audit/"01-clean-static-contact-sheet.jpg"
    sheet.convert("RGB").save(clean_path,quality=90,subsampling=1)
    (audit/"01-clean-static-contact-sheet.preview64").write_text(
        base64.b64encode(clean_path.read_bytes()).decode("ascii"),encoding="ascii"
    )
    report["status"]="STATIC_ASSET_CLEAN_QA_PASS"
    (audit/"clean-metrics.json").write_text(json.dumps(report,indent=2),encoding="utf-8")
    print(json.dumps({
        "status":report["status"],
        "payloadBytes":out_payload.stat().st_size,
        "removedMattePixels":[x["removedMattePixels"] for x in report["badges"]],
    },indent=2))

if __name__=="__main__":
    main()
