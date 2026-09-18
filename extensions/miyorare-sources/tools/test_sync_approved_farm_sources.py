#!/usr/bin/env python3
from __future__ import annotations

import copy
import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "sync_approved_farm_sources",
    ROOT / "sync_approved_farm_sources.py",
)
assert SPEC and SPEC.loader
syncer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(syncer)

UMA = "a" * 40
GEK = "b" * 40


def packs() -> dict:
    return {
        "schema": 1,
        "upstream": {"name": "UMA", "commit": "1" * 40},
        "additionalUpstreams": {
            "gekkoushi": {"name": "Gekkoushi", "commit": "2" * 40},
        },
        "packs": {
            "id": {
                "language": "id",
                "includeAllFrom": ["gekkoushi"],
                "sources": ["Existing.kt"],
            },
            "en": {
                "language": "en",
                "includeAllFrom": ["gekkoushi"],
                "sources": ["ExistingEn.kt"],
            },
        },
    }


def source(
    canonical_id: str,
    language: str,
    providers: list[str],
    identities: dict,
    state: str = "ACTIVE",
) -> dict:
    return {
        "canonicalId": canonical_id,
        "language": language,
        "providers": providers,
        "upstreamIdentities": identities,
        "compatibilityEnrollment": {
            "state": state,
            "parserCoverageRequired": state == "ACTIVE",
        },
    }


def registry(rows: list[dict]) -> dict:
    return {
        "schemaVersion": 1,
        "providerBaselines": {"uma": UMA, "gekkoushi": GEK, "keiyoushi": "c" * 40},
        "sources": rows,
    }


class FarmPackMembershipSyncTest(unittest.TestCase):
    def test_adds_language_local_uma_sources_and_nested_nsfw(self) -> None:
        reg = registry([
            source(
                "miyorare:inventory-id:ALPHA",
                "id",
                ["uma"],
                {"uma": {"file": "src/main/kotlin/tsuki/site/id/Alpha.kt"}},
            ),
            source(
                "miyorare:inventory-id:NSFW",
                "id",
                ["uma"],
                {"uma": {"file": "src/main/kotlin/tsuki/site/id/nsfw/Nsfw.kt"}},
            ),
            source(
                "miyorare:inventory-en:BETA",
                "en",
                ["uma"],
                {"uma": {"file": "src/main/kotlin/tsuki/site/en/Beta.kt"}},
            ),
        ])
        updated, report = syncer.sync(reg, packs())
        self.assertEqual(
            updated["packs"]["id"]["sources"],
            ["Alpha.kt", "Existing.kt", "nsfw/Nsfw.kt"],
        )
        self.assertEqual(
            updated["packs"]["en"]["sources"],
            ["Beta.kt", "ExistingEn.kt"],
        )
        self.assertEqual(report["addedUmaSources"]["id"], ["Alpha.kt", "nsfw/Nsfw.kt"])
        self.assertEqual(report["addedUmaSources"]["en"], ["Beta.kt"])

    def test_pending_source_is_not_added(self) -> None:
        reg = registry([
            source(
                "miyorare:inventory-id:PENDING",
                "id",
                ["uma"],
                {"uma": {"file": "src/main/kotlin/tsuki/site/id/Pending.kt"}},
                state="PENDING",
            )
        ])
        updated, report = syncer.sync(reg, packs())
        self.assertEqual(updated["packs"]["id"]["sources"], ["Existing.kt"])
        self.assertEqual(report["activeSourceCount"], 0)

    def test_gekkoushi_is_covered_without_explicit_membership(self) -> None:
        reg = registry([
            source(
                "miyorare:inventory-en:GEK",
                "en",
                ["gekkoushi"],
                {"gekkoushi": {"file": "src/main/kotlin/tsuki/site/en/Gek.kt"}},
            )
        ])
        updated, report = syncer.sync(reg, packs())
        self.assertEqual(updated["packs"]["en"]["sources"], ["ExistingEn.kt"])
        self.assertEqual(
            report["gekkoushiCoveredCanonicalIds"],
            ["miyorare:inventory-en:GEK"],
        )

    def test_keiyoushi_only_is_validated_but_not_packable(self) -> None:
        reg = registry([
            source(
                "miyorare:inventory-en:KEY",
                "en",
                ["keiyoushi"],
                {"keiyoushi": {"module": "src/en/key"}},
            )
        ])
        updated, report = syncer.sync(reg, packs())
        self.assertEqual(updated["packs"]["en"]["sources"], ["ExistingEn.kt"])
        self.assertEqual(
            report["validatedNotPackable"]["keiyoushiOnlyCanonicalIds"],
            ["miyorare:inventory-en:KEY"],
        )

    def test_uma_site_all_is_not_forced_into_language_pack(self) -> None:
        reg = registry([
            source(
                "miyorare:inventory-id:WEBTOONS",
                "id",
                ["uma"],
                {"uma": {"file": "src/main/kotlin/tsuki/site/all/Webtoons.kt"}},
            )
        ])
        updated, report = syncer.sync(reg, packs())
        self.assertEqual(updated["packs"]["id"]["sources"], ["Existing.kt"])
        blocked = report["validatedNotPackable"]["umaOutsideLanguagePackRoot"]
        self.assertEqual(blocked[0]["canonicalId"], "miyorare:inventory-id:WEBTOONS")

    def test_sync_is_idempotent_and_advances_exact_provider_pins(self) -> None:
        reg = registry([
            source(
                "miyorare:inventory-en:BETA",
                "en",
                ["uma", "gekkoushi"],
                {
                    "uma": {"file": "src/main/kotlin/tsuki/site/en/Beta.kt"},
                    "gekkoushi": {"file": "src/main/kotlin/tsuki/site/en/Beta.kt"},
                },
            )
        ])
        first, report1 = syncer.sync(reg, packs())
        self.assertTrue(report1["changed"])
        self.assertEqual(first["upstream"]["commit"], UMA)
        self.assertEqual(first["additionalUpstreams"]["gekkoushi"]["commit"], GEK)

        second_input = copy.deepcopy(first)
        second, report2 = syncer.sync(reg, second_input)
        self.assertEqual(second, first)
        self.assertFalse(report2["changed"])
        self.assertEqual(report2["addedUmaSources"]["en"], [])

    def test_missing_gekkoushi_include_all_fails_closed(self) -> None:
        value = packs()
        value["packs"]["id"]["includeAllFrom"] = []
        with self.assertRaisesRegex(ValueError, "retain includeAllFrom gekkoushi"):
            syncer.sync(registry([]), value)


if __name__ == "__main__":
    unittest.main()
