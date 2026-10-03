import importlib.util
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parent

def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

guard = load("guard", "release_build_config_guard.py")
evidence = load("evidence", "promotion_evidence.py")

class TestPreMainAudit(unittest.TestCase):
    def test_release_guard_selects_build_type_not_signing(self):
        guard.verify_release_config("""android {
signingConfigs { release { storeFile file('x') } }
buildTypes { release {
buildConfigField 'boolean', 'READER_JOURNEY_UNLOCK_ALL_REWARDS', 'false'
buildConfigField 'boolean', 'EXCLUSIVE_THEME_QA_ENABLED', 'false'
} } }""")

    def test_release_guard_rejects_unsafe_flag(self):
        with self.assertRaises(ValueError):
            guard.verify_release_config("""android { buildTypes { release {
buildConfigField 'boolean', 'READER_JOURNEY_UNLOCK_ALL_REWARDS', 'true'
buildConfigField 'boolean', 'EXCLUSIVE_THEME_QA_ENABLED', 'false'
} } }""")

    def test_promotion_requires_all_required_checks(self):
        evidence.validate_check_runs({"check_runs": [
            {"name": name, "conclusion": "success"} for name in evidence.REQUIRED
        ]})
        with self.assertRaises(ValueError):
            evidence.validate_check_runs({"check_runs": [
                {"name": "verify-identity", "conclusion": "success"},
                {"name": "Fast", "conclusion": "success"},
                {"name": "Deep", "conclusion": "skipped"},
            ]})

if __name__ == "__main__":
    unittest.main()
