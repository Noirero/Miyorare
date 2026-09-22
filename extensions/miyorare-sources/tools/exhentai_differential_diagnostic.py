#!/usr/bin/env python3
"""Differential diagnostic for the E-Hentai / ExHentai search pipeline.

Runs multiple cursor pages and records the first stage where gallery IDs diverge.
Authentication cookie VALUES are read only from environment variables and are
never printed or written to the report.

Environment variables:
  EH_COOKIE_IPB_MEMBER_ID
  EH_COOKIE_IPB_PASS_HASH
  EH_COOKIE_IGNEOUS
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from dataclasses import asdict, dataclass
from html.parser import HTMLParser
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlencode, urlparse
from urllib.request import Request, urlopen

AUTH_ENV = {
    "ipb_member_id": "EH_COOKIE_IPB_MEMBER_ID",
    "ipb_pass_hash": "EH_COOKIE_IPB_PASS_HASH",
    "igneous": "EH_COOKIE_IGNEOUS",
}
DEFAULT_QUERIES = [
    'language:"english$"',
    'language:"japanese$"',
    'language:"chinese$"',
]
GALLERY_RE = re.compile(r"/g/(\d+)/[^/?#]+/?", re.I)


@dataclass
class Row:
    td_count: int
    gallery_id: int | None


class SearchHtmlParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.table_depth = 0
        self.tbody_depth = 0
        self.current_row: Row | None = None
        self.rows: list[Row] = []
        self.next_href: str | None = None

    @staticmethod
    def attrs_dict(attrs):
        return {k: v for k, v in attrs}

    def handle_starttag(self, tag, attrs):
        a = self.attrs_dict(attrs)
        classes = set((a.get("class") or "").split())
        if tag == "table" and "itg" in classes:
            self.table_depth += 1
            return
        if not self.table_depth:
            if a.get("id") == "unext" and a.get("href"):
                self.next_href = a["href"]
            return
        if tag == "tbody":
            self.tbody_depth += 1
        elif tag == "tr" and self.tbody_depth and self.current_row is None:
            self.current_row = Row(td_count=0, gallery_id=None)
        elif tag == "td" and self.current_row is not None:
            self.current_row.td_count += 1
        elif tag == "a" and self.current_row is not None:
            href = a.get("href") or ""
            match = GALLERY_RE.search(href)
            if match and self.current_row.gallery_id is None:
                self.current_row.gallery_id = int(match.group(1))
        if a.get("id") == "unext" and a.get("href"):
            self.next_href = a["href"]

    def handle_endtag(self, tag):
        if tag == "tr" and self.current_row is not None:
            self.rows.append(self.current_row)
            self.current_row = None
        elif tag == "tbody" and self.tbody_depth:
            self.tbody_depth -= 1
        elif tag == "table" and self.table_depth:
            self.table_depth -= 1

    def close(self):
        super().close()
        if self.current_row is not None:
            self.rows.append(self.current_row)
            self.current_row = None


def cookie_values() -> dict[str, str]:
    return {
        name: os.environ.get(env, "").strip()
        for name, env in AUTH_ENV.items()
        if os.environ.get(env, "").strip()
    }


def cookie_header(values: dict[str, str], domain: str) -> str:
    names = ["nw", "sl"]
    pairs = ["nw=1", "sl=dm_2"]
    for name in ("ipb_member_id", "ipb_pass_hash"):
        if values.get(name):
            names.append(name)
            pairs.append(f"{name}={values[name]}")
    if domain == "exhentai.org" and values.get("igneous"):
        names.append("igneous")
        pairs.append(f"igneous={values['igneous']}")
    return "; ".join(pairs)


def choose_domain(site: str, values: dict[str, str]) -> str:
    if site == "ehentai":
        return "e-hentai.org"
    if site == "exhentai":
        return "exhentai.org"
    complete = all(values.get(name) for name in AUTH_ENV)
    return "exhentai.org" if complete else "e-hentai.org"


def next_cursor_from_href(href: str | None) -> int:
    if not href:
        return 0
    try:
        return int(parse_qs(urlparse(href).query).get("next", ["0"])[0])
    except (TypeError, ValueError):
        return 0


def stable_unique(values: list[int]) -> list[int]:
    seen = set()
    out = []
    for value in values:
        if value not in seen:
            seen.add(value)
            out.append(value)
    return out


def classify(record: dict) -> str:
    raw = record["raw_gallery_ids"]
    parsed = record["parser_gallery_ids"]
    repo = record["repository_gallery_ids"]
    dedupe = record["dedupe_gallery_ids"]
    ui = record["ui_gallery_ids"]
    if record["http_status"] != 200 or not raw:
        return "1_raw_response_request_auth_filter_domain"
    if raw != parsed:
        return "2_parser"
    if parsed != repo or repo != dedupe:
        return "4_repository_or_dedupe"
    if dedupe != ui:
        return "5_ui"
    return "ok"


def fetch_page(domain: str, query: str, cursor: int, f_cats: str | None, f_sh: bool,
               user_agent: str, values: dict[str, str], page_index: int) -> dict:
    params = {
        "next": str(cursor),
        "f_apply": "Apply Filter",
        "f_search": query,
        "advsearch": "1",
        # Exact default control state from the #188 request path that was proven on-device.
        "f_doujinshi": "1",
        "f_manga": "1",
        "f_artistcg": "1",
        "f_gamecg": "1",
        "f_western": "1",
        "f_non-h": "1",
        "f_imageset": "1",
        "f_cosplay": "1",
        "f_asianporn": "1",
        "f_misc": "1",
        "f_sname": "on",
        "f_stags": "on",
        # Keep Miyorare's request-scoped account filter bypass.
        "f_sfl": "on",
        "f_sfu": "on",
        "f_sft": "on",
    }
    if f_cats:
        params["f_cats"] = f_cats
    if f_sh:
        params["f_sh"] = "on"
    requested_url = f"https://{domain}/?{urlencode(params)}"
    headers = {
        "User-Agent": user_agent,
        "Cookie": cookie_header(values, domain),
    }
    status = 0
    final_url = requested_url
    html = ""
    try:
        req = Request(requested_url, headers=headers)
        with urlopen(req, timeout=30) as response:
            status = response.status
            final_url = response.geturl()
            html = response.read().decode("utf-8", errors="replace")
    except HTTPError as exc:
        status = exc.code
        final_url = exc.geturl()
        html = exc.read().decode("utf-8", errors="replace")
    except URLError as exc:
        return {
            "page": page_index,
            "offset": page_index * 25,
            "requested_url": requested_url,
            "actual_domain": domain,
            "authenticated": False,
            "auth_cookie_names": sorted(values),
            "request_params": params,
            "http_status": 0,
            "network_error": str(exc.reason),
            "raw_row_count": 0,
            "raw_gallery_ids": [],
            "parser_count": 0,
            "parser_gallery_ids": [],
            "next_cursor": 0,
            "repository_count": 0,
            "repository_gallery_ids": [],
            "dedupe_count": 0,
            "dedupe_gallery_ids": [],
            "ui_count": 0,
            "ui_gallery_ids": [],
            "classification": "1_raw_response_request_auth_filter_domain",
        }

    parser = SearchHtmlParser()
    parser.feed(html)
    parser.close()

    raw_ids = [row.gallery_id for row in parser.rows if row.gallery_id is not None]
    parsed_ids = [
        row.gallery_id for row in parser.rows
        if row.td_count == 2 and row.gallery_id is not None
    ]
    repository_ids = list(parsed_ids)
    dedupe_ids = stable_unique(repository_ids)
    ui_ids = list(dedupe_ids)
    final_domain = urlparse(final_url).hostname or domain
    available_cookie_names = sorted(values)
    authenticated = (
        final_domain == "exhentai.org"
        and all(name in values for name in AUTH_ENV)
        and status == 200
    )
    record = {
        "page": page_index,
        "offset": page_index * 25,
        "requested_url": requested_url,
        "actual_domain": final_domain,
        "authenticated": authenticated,
        "auth_cookie_names": available_cookie_names,
        "request_params": params,
        "http_status": status,
        "raw_row_count": len(parser.rows),
        "raw_gallery_ids": raw_ids,
        "parser_count": len(parsed_ids),
        "parser_gallery_ids": parsed_ids,
        "next_cursor": next_cursor_from_href(parser.next_href),
        "repository_count": len(repository_ids),
        "repository_gallery_ids": repository_ids,
        "dedupe_count": len(dedupe_ids),
        "dedupe_gallery_ids": dedupe_ids,
        "ui_count": len(ui_ids),
        "ui_gallery_ids": ui_ids,
    }
    record["classification"] = classify(record)
    return record


def run_query(args, query: str, values: dict[str, str]) -> dict:
    domain = choose_domain(args.site, values)
    records = []
    cursor = 0
    seen_cursors = set()
    for page in range(args.pages):
        record = fetch_page(
            domain=domain,
            query=query,
            cursor=cursor,
            f_cats=args.f_cats,
            f_sh=args.f_sh,
            user_agent=args.user_agent,
            values=values,
            page_index=page,
        )
        records.append(record)
        next_cursor = int(record.get("next_cursor") or 0)
        if record["http_status"] != 200 or next_cursor <= 0 or next_cursor in seen_cursors:
            break
        seen_cursors.add(next_cursor)
        cursor = next_cursor
    first_loss = next((r for r in records if r["classification"] != "ok"), None)
    return {
        "query": query,
        "requested_pages": args.pages,
        "actual_pages": len(records),
        "first_loss": first_loss["classification"] if first_loss else None,
        "records": records,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--site", choices=("auto", "ehentai", "exhentai"), default="auto")
    ap.add_argument("--query", action="append", dest="queries",
                    help="Repeat for multiple queries. Defaults to three broad language searches.")
    ap.add_argument("--pages", type=int, default=10)
    ap.add_argument("--f-cats")
    ap.add_argument("--f-sh", action="store_true")
    ap.add_argument("--user-agent", default="Mozilla/5.0 Miyorare-ExHentai-Diagnostic/1.0")
    ap.add_argument("--output", type=str)
    args = ap.parse_args()
    if not 5 <= args.pages <= 10:
        ap.error("--pages must be between 5 and 10 for the differential diagnostic")

    values = cookie_values()
    report = {
        "cookie_values_logged": False,
        "available_auth_cookie_names": sorted(values),
        "queries": [],
    }
    for query in args.queries or DEFAULT_QUERIES:
        report["queries"].append(run_query(args, query, values))

    payload = json.dumps(report, indent=2, ensure_ascii=False)
    if args.output:
        Path = __import__("pathlib").Path
        Path(args.output).write_text(payload + "\n", encoding="utf-8")
    print(payload)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
