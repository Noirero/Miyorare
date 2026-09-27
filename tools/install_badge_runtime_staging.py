#!/usr/bin/env python3
import base64
import hashlib
import io
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BADGE_ROOT = ROOT / "app/src/main/badge-assets"
STAGING = ROOT / ".badge-runtime-staging"
ZIP_SHA256 = "6ebda7b8d327a7de2ce0967d25c5ccd29b197a1b296db7691a67c338afd79d0e"
TOTAL_BYTES = 773_682
EXPECTED = [
    ("badge_01_first_page_silver_base.webp", 53132, "b920662ebce5b4a3ec7937d4297fa3b8b12aea9de3ccd53a2e6a86e1b7b3eb5a"),
    ("badge_02_first_light_blue_base.webp", 51330, "c0de19b3361975e982ad82b93a9dee3eb179bce0320ffe56083dc9f32fd75785"),
    ("badge_03_cyan_orbit_base.webp", 55308, "868f9983a9f178e9faad8707633c3c5e5b574b9669639b1ed551dc0498675c1d"),
    ("badge_04_emerald_pulse_base.webp", 53998, "e264394fadff205cc4757a119db26a0bd037c8485718af41ab22fbea6a0720a6"),
    ("badge_05_arcane_scholar_base.webp", 62638, "d2527413a424054bb938bacc542a99eb42bb0d1414ad27bdc68d36025c18a17d"),
    ("badge_06_violet_halo_base.webp", 52294, "27158b91ed5470c80af232d7775037bc485a5e4e6bf8db359def4710aed7cec3"),
    ("badge_07_rose_nebula_base.webp", 58168, "214bd7971c1c7cc5c78c4acf00b9c627b0ec478360fc0cc6bfe889cc03d81fa9"),
    ("badge_08_crimson_ember_base.webp", 52174, "8ae59b23efddc19c58894b6c34c3c5eb88b7b1556c7ee5b032dd71bac1120497"),
    ("badge_09_amber_manuscript_base.webp", 47742, "319573d04b3663f1973d0ab3981120de594712dbc8719477bc7feb4b530ccc52"),
    ("badge_10_golden_manuscript_deluxe_base.webp", 57022, "f0f150cd0496376405cb60c9b93685477e14b2c2cb534d804c9ea5e727d1f729"),
    ("badge_11_eternal_library_prism_base.webp", 52340, "9a7e68d9dcbbca8a40b6e484c747d50b6fcf126f09cbf13405aeef05842c0713"),
    ("badge_12_celestial_infinity_base.webp", 58232, "639c62ebc41adf734056300c8906774146da167d93c8475f75797a03772dac1f"),
    ("badge_01_first_page_silver_thumb.webp", 9960, "e7b56fa60712d1bf324fe827f9b58c1949aff0f34a576d50fa658e6711ae2a33"),
    ("badge_02_first_light_blue_thumb.webp", 9766, "0e7911b0454c52cbb669d0e46756f65ba26a86ba8eaf8137c34f66d393ed75a3"),
    ("badge_03_cyan_orbit_thumb.webp", 10350, "5b10588a0ec64d793382084fd1d9c0ce70ad8014dc221c9c55366215214aa507"),
    ("badge_04_emerald_pulse_thumb.webp", 9344, "d4182aae2cc0110edb40b1d871b7846735c3b851173bfb3bfd842b1162c64844"),
    ("badge_05_arcane_scholar_thumb.webp", 11436, "3ffed5dbaf07a659922700957e9e605a6a31869d10a377f09d9255312916ec9e"),
    ("badge_06_violet_halo_thumb.webp", 9686, "6c7ec917a39da5935503db771a22d879b650b1b4c0c2201cfbbf6ac6e1684c7f"),
    ("badge_07_rose_nebula_thumb.webp", 10348, "3da336d205f2b7633789f844abc89105fcda1b235692e9d4fecac7eca45dfd93"),
    ("badge_08_crimson_ember_thumb.webp", 9874, "0157e44bf11f19c7916cb6deb2b6fe3cf2b29057cb8072d98252705b94cbb7b8"),
    ("badge_09_amber_manuscript_thumb.webp", 8836, "b33eaa7637ad75a69df5c2bbce70accd8b110c2461a3fe76af7a216468e3aee1"),
    ("badge_10_golden_manuscript_deluxe_thumb.webp", 9674, "48958577326a4797cd555f97c8740012c03c61ffdc781e52ba34a727f33aeb78"),
    ("badge_11_eternal_library_prism_thumb.webp", 9650, "84da9b98f9e07623ab441efd8772c2cef97359ef9c19ba453a5243b7a0d87e8d"),
    ("badge_12_celestial_infinity_thumb.webp", 10380, "4f44967cdb330cf48724a5be064214c380ca616fb0c817e5d32787358a3b65ce"),
]

def git_blob_sha(data: bytes) -> str:
    header = f"blob {len(data)}\0".encode()
    return hashlib.sha1(header + data).hexdigest()

def main():
    parts = sorted(STAGING.glob("runtime.b64.part*"))
    expected_part_names = [f"runtime.b64.part{i:02d}" for i in range(70)]
    assert [p.name for p in parts] == expected_part_names, "Staging parts are incomplete"
    encoded = "".join(p.read_text(encoding="ascii").strip() for p in parts)
    raw_zip = base64.b64decode(encoded, validate=True)
    assert hashlib.sha256(raw_zip).hexdigest() == ZIP_SHA256, "Runtime ZIP checksum mismatch"

    expected = {name: (size, digest) for name, size, digest in EXPECTED}
    with zipfile.ZipFile(io.BytesIO(raw_zip), "r") as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)) == 24, "ZIP must contain exactly 24 unique files"
        assert set(names) == set(expected), "ZIP resource mapping differs from production mapping"
        payload = {}
        for name, (size, digest) in expected.items():
            data = archive.read(name)
            assert len(data) == size, f"{name}: size mismatch"
            assert hashlib.sha256(data).hexdigest() == digest, f"{name}: checksum mismatch"
            payload[name] = data

    assert sum(len(data) for data in payload.values()) == TOTAL_BYTES

    source_dir = BADGE_ROOT / "v2-source"
    source_dir.mkdir(parents=True, exist_ok=True)
    for old in source_dir.glob("*.webp"):
        old.unlink()
    for name, _, _ in EXPECTED:
        (source_dir / name).write_bytes(payload[name])

    manifest_path = BADGE_ROOT / "v2-source-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["sourceZip"] = "miyorare_badge_v2_runtime_1mb.zip"
    manifest["sourceZipSha256"] = ZIP_SHA256
    manifest["assets"] = [
        {
            "name": name,
            "size": size,
            "sha256": digest,
            "git_blob_sha": git_blob_sha(payload[name]),
        }
        for name, size, digest in EXPECTED
    ]
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "sourceZipSha256": ZIP_SHA256,
        "assetCount": len(EXPECTED),
        "runtimeAssetBytes": TOTAL_BYTES,
    }, indent=2))

if __name__ == "__main__":
    main()
