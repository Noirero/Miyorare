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


def write_fake_hiraukan(tmpdir: Path, ids) -> Path:
    """Creates a minimal Hiraukan checkout containing the given source ids."""
    models_dir = tmpdir / "lib" / "src" / "sources"
    models_dir.mkdir(parents=True)
    arms = "\n".join(f"        UnifiedSourceKind.k{i} => '{i}'," for i in ids)
    (models_dir / "unified_source_models.dart").write_text(
        "String get id => switch (this) {\n" + arms + "\n      };\n",
        encoding="utf-8",
    )
    return tmpdir


def mutate_pack(mutations):
    data = json.loads((ROOT / "pack.json").read_text(encoding="utf-8"))
    mutations(data)
    tmp = tempfile.TemporaryDirectory()
    path = Path(tmp.name) / "pack.json"
    path.write_text(json.dumps(data), encoding="utf-8")
    return tmp, path


class AudioPackValidationTest(unittest.TestCase):
    def test_repository_manifest_is_valid(self):
        module.validate(ROOT / "pack.json")

    def test_repository_manifest_contains_all_hiraukan_runtimes(self):
        data = json.loads((ROOT / "pack.json").read_text(encoding="utf-8"))
        extensions = {item["id"]: item for item in data["extensions"]}
        self.assertEqual(
            set(extensions),
            {
                "miyorare.audio.asmr_one",
                "miyorare.audio.hentai_asmr",
                "miyorare.audio.japanese_asmr",
                "miyorare.audio.asmr18",
                "miyorare.audio.ero_voice",
                "miyorare.audio.asmr_hentai_net",
            },
        )

        hentai = set(
            extensions["miyorare.audio.asmr_hentai_net"]["capabilities"]
        )
        self.assertIn("playback", hentai)
        self.assertIn("subtitles", hentai)
        self.assertNotIn("download", hentai)

        asmr18 = set(extensions["miyorare.audio.asmr18"]["capabilities"])
        self.assertIn("playback", asmr18)
        self.assertNotIn("download", asmr18)

        japanese = set(
            extensions["miyorare.audio.japanese_asmr"]["capabilities"]
        )
        self.assertIn("playback", japanese)
        self.assertIn("download", japanese)

    def test_rejects_non_audio_entry(self):
        tmp, path = mutate_pack(
            lambda data: data["extensions"][0].__setitem__("type", "manga")
        )
        with tmp:
            with self.assertRaises(SystemExit):
                module.validate(path)

    def test_rejects_external_code_delivery_in_v1(self):
        def mutate(data):
            data["extensions"][0]["delivery"]["kind"] = "download"

        tmp, path = mutate_pack(mutate)
        with tmp:
            with self.assertRaises(SystemExit):
                module.validate(path)

    def test_rejects_bad_version(self):
        def mutate(data):
            data["extensions"][0]["version"] = "1.0"

        tmp, path = mutate_pack(mutate)
        with tmp:
            with self.assertRaises(SystemExit):
                module.validate(path)

    def test_rejects_empty_name(self):
        def mutate(data):
            data["extensions"][0]["name"] = "  "

        tmp, path = mutate_pack(mutate)
        with tmp:
            with self.assertRaises(SystemExit):
                module.validate(path)

    def test_rejects_bad_languages(self):
        for bad in ([], ["xx!"], [""]):
            def mutate(data, bad=bad):
                data["extensions"][0]["languages"] = bad

            tmp, path = mutate_pack(mutate)
            with tmp:
                with self.assertRaises(SystemExit):
                    module.validate(path)

    def test_rejects_runtime_id_without_prefix(self):
        def mutate(data):
            data["extensions"][0]["id"] = "audio.asmr_one"
            data["extensions"][0]["delivery"]["runtimeId"] = "audio.asmr_one"

        tmp, path = mutate_pack(mutate)
        with tmp:
            with self.assertRaises(SystemExit):
                module.validate(path)

    def test_cross_check_passes_when_all_runtimes_bundled(self):
        data = json.loads((ROOT / "pack.json").read_text(encoding="utf-8"))
        ids = [
            item["id"].removeprefix("miyorare.audio.")
            for item in data["extensions"]
        ]
        with tempfile.TemporaryDirectory() as tmpdir:
            hiraukan_dir = write_fake_hiraukan(Path(tmpdir), ids)
            # Must not raise.
            module.validate(ROOT / "pack.json", hiraukan_dir)

    def test_cross_check_fails_for_missing_runtime(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            hiraukan_dir = write_fake_hiraukan(
                Path(tmpdir), ["asmr_one", "hentai_asmr", "ero_voice"]
            )
            with self.assertRaises(SystemExit) as ctx:
                module.validate(ROOT / "pack.json", hiraukan_dir)
            self.assertIn("japanese_asmr", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
