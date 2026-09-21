#!/usr/bin/env python3
"""Regression coverage for ExHentai cursor recovery semantics.

This test models the exact failure fixed in the generated Gekkoushi parser:
page > 0 used to require an in-memory cursor populated by earlier requests.
After parser recreation/eviction that table is empty, so upstream returned [].
The fixed parser rebuilds missing cursors by following the website's own next
cursor chain and keeps independent state per effective request signature.
"""

from pathlib import Path
import unittest


class FakeCursorSite:
    def __init__(self, cursors):
        self.cursors = list(cursors)
        self.calls = []

    def fetch_next(self, cursor):
        self.calls.append(cursor)
        if cursor == 0:
            index = 0
        else:
            try:
                index = self.cursors.index(cursor) + 1
            except ValueError:
                return 0
        return self.cursors[index] if index < len(self.cursors) else 0


def old_cursor_for(page, cache):
    if page <= 0:
        return 0
    return cache.get(page, 0)


def recovered_cursor_for(page, cache, fetch_next):
    if page <= 0:
        return 0
    if cache.get(page, 0) > 0:
        return cache[page]

    nearest = max(
        ((cached_page, cursor) for cached_page, cursor in cache.items()
         if 1 <= cached_page < page and cursor > 0),
        default=(0, 0),
        key=lambda item: item[0],
    )
    cursor_page, cursor = nearest
    while cursor_page < page:
        next_cursor = fetch_next(cursor)
        if next_cursor <= 0 or next_cursor == cursor:
            return 0
        cursor_page += 1
        cursor = next_cursor
        cache[cursor_page] = cursor
    return cursor


class ExhentaiCursorRecoveryTest(unittest.TestCase):
    def test_reproduces_old_parser_recreation_bug(self):
        cache = {}
        self.assertEqual(0, old_cursor_for(4, cache))

    def test_recreated_parser_recovers_requested_page(self):
        site = FakeCursorSite([900, 800, 700, 600, 500, 400, 300, 200, 100])
        cache = {}
        self.assertEqual(600, recovered_cursor_for(4, cache, site.fetch_next))
        self.assertEqual([0, 900, 800, 700], site.calls)
        self.assertEqual({1: 900, 2: 800, 3: 700, 4: 600}, cache)

    def test_non_sequential_page_request_is_supported(self):
        site = FakeCursorSite([900, 800, 700, 600, 500, 400, 300, 200, 100])
        cache = {2: 800}
        self.assertEqual(400, recovered_cursor_for(6, cache, site.fetch_next))
        self.assertEqual([800, 700, 600, 500], site.calls)

    def test_end_of_results_does_not_wrap_or_invent_cursor(self):
        site = FakeCursorSite([900, 800])
        cache = {}
        self.assertEqual(0, recovered_cursor_for(5, cache, site.fetch_next))
        self.assertEqual({1: 900, 2: 800}, cache)

    def test_generated_patch_has_exact_request_key_and_recovery_contract(self):
        source = Path(__file__).with_name("prepare_global_gekkoushi_shard.py").read_text(encoding="utf-8")
        self.assertIn("val key = paginationKey(filter)", source)
        self.assertIn("ensurePageCursor(page, filter, key)", source)
        self.assertIn("append(domain)", source)
        self.assertIn("append(filter.toSearchQuery().orEmpty())", source)
        self.assertIn("append(filter.miyorareFilterSignature())", source)
        self.assertIn("append(filter.types.toFCats())", source)
        self.assertIn("append(config[suspiciousContentKey])", source)
        self.assertIn('?.toLongOrNull() ?: 0', source)
        self.assertIn('"__miyorare_exhentai__:"', source)
        self.assertIn('if (tag.key.startsWith("__miyorare_exhentai__:")', source)
        self.assertIn('url.addQueryParameter("f_apply", "Apply Filter")', source)
        self.assertIn('"f_doujinshi"', source)
        self.assertIn('"f_misc"', source)
        self.assertIn('"f_sname"', source)
        self.assertIn('"f_stags"', source)
        self.assertIn('url.addQueryParameter("f_sfl", "on")', source)
        self.assertIn('url.addQueryParameter("f_sfu", "on")', source)
        self.assertIn('url.addQueryParameter("f_sft", "on")', source)


if __name__ == "__main__":
    unittest.main()
