#!/usr/bin/env python3
"""Rebuild the Base64 ZIP from unmodified V2 WebPs; poster is test-only."""
import argparse
import base64
import hashlib
import io
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/badge-assets'


def regenerate(check=False):
    manifest = json.loads((ROOT / 'v2-source-manifest.json').read_text())
    files = sorted((ROOT / 'v2-source').glob('*.webp'))
    assert len(files) == 24
    expected = {entry['name']: entry for entry in manifest['assets']}
    assert {p.name for p in files} == set(expected)
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w', compression=zipfile.ZIP_STORED) as archive:
        for path in files + [ROOT / 'reference-only/exclusive_badge_golden_reference_sheet.webp']:
            data = path.read_bytes()
            digest = hashlib.sha256(data).hexdigest()
            if path.name in expected:
                assert digest == expected[path.name]['sha256'], path.name
            else:
                assert digest == manifest['goldenReferenceSha256']
            info = zipfile.ZipInfo(path.name, (2026, 9, 27, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    payload = base64.encodebytes(stream.getvalue())
    target = ROOT / 'exclusive_badge_material_payload.b64'
    if check:
        assert target.read_bytes() == payload, 'Payload is stale or contains non-V2 production assets'
    else:
        target.write_bytes(payload)
    print(json.dumps({'assetCount': len(files), 'payloadSha256': hashlib.sha256(payload).hexdigest(),
                      'zipSha256': hashlib.sha256(stream.getvalue()).hexdigest()}, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    regenerate(parser.parse_args().check)
