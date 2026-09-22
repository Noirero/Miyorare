import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODULE_PATH = ROOT / "tools" / "validate_pack.py"
spec = importlib.util.spec_from_file_location("validate_audio_pack", MODULE_PATH)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)


class AudioPackValidationTest(unittest.TestCase):
    def test_repository_manifest_is_valid(self):
        module.validate(ROOT / "pack.json")

    def test_rejects_non_audio_entry(self):
        data = json.loads((ROOT / "pack.json").read_text(encoding="utf-8"))
        data["extensions"][0]["type"] = "manga"
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "pack.json"
            path.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaises(SystemExit):
                module.validate(path)

    def test_rejects_external_code_delivery_in_v1(self):
        data = json.loads((ROOT / "pack.json").read_text(encoding="utf-8"))
        data["extensions"][0]["delivery"]["kind"] = "download"
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "pack.json"
            path.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaises(SystemExit):
                module.validate(path)


if __name__ == "__main__":
    unittest.main()
