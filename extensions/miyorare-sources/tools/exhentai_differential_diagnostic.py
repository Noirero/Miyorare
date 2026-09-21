#!/usr/bin/env python3
from __future__ import annotations
import argparse, html.parser, json, os, re, time, urllib.error, urllib.parse, urllib.request
from dataclasses import dataclass, field

GALLERY_RE = re.compile(r"/g/(\d+)/[0-9A-Za-z]+/?")
AUTH_COOKIE_NAMES = ("ipb_member_id", "ipb_pass_hash", "igneous")
PAGE_SIZE = 25

@dataclass
class Row:
    td_count: int = 0
    gallery_ids: list[int] = field(default_factory=list)

class GalleryTableParser(html.parser.HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.in_itg = False
        self.in_tbody = False
        self.current_row = None
        self.rows = []
        self.next_href = None
        self.stack = []
        self.itg_depth = None
        self.all_gallery_ids = []
        self.table_itg_count = 0
        self.div_itg_count = 0

    def handle_starttag(self, tag, attrs):
        a = {k: (v or "") for k, v in attrs}
        parent = self.stack[-1] if self.stack else None
        self.stack.append(tag)
        classes = set(a.get("class", "").split())
        if "itg" in classes:
            if tag == "table": self.table_itg_count += 1
            if tag == "div": self.div_itg_count += 1
        if tag == "table" and not self.in_itg and "itg" in classes:
            self.in_itg, self.itg_depth = True, len(self.stack)
        elif self.in_itg and tag == "tbody":
            self.in_tbody = True
        elif self.in_itg and tag == "tr" and self.current_row is None:
            # E-Hentai's raw markup may omit tbody; Jsoup inserts it in the parsed DOM used by Kotlin.
            self.current_row = Row()
        elif self.current_row is not None and tag == "td" and parent == "tr":
            self.current_row.td_count += 1
        if tag == "a":
            href = a.get("href", "")
            matches = list(GALLERY_RE.finditer(href))
            for match in matches:
                gid = int(match.group(1))
                if gid not in self.all_gallery_ids:
                    self.all_gallery_ids.append(gid)
                if self.current_row is not None and gid not in self.current_row.gallery_ids:
                    self.current_row.gallery_ids.append(gid)
            if a.get("id") == "unext":
                self.next_href = href

    def handle_endtag(self, tag):
        if self.current_row is not None and tag == "tr":
            self.rows.append(self.current_row)
            self.current_row = None
        if self.in_itg and tag == "tbody":
            self.in_tbody = False
        if self.in_itg and tag == "table" and self.itg_depth == len(self.stack):
            self.in_itg, self.itg_depth = False, None
        if tag in self.stack:
            while self.stack:
                if self.stack.pop() == tag:
                    break

def unique(values):
    seen, out = set(), []
    for value in values:
        if value not in seen:
            seen.add(value); out.append(value)
    return out

def cookie_config():
    auth = {name: os.getenv(env, "").strip() for name, env in (
        ("ipb_member_id", "EXHENTAI_IPB_MEMBER_ID"),
        ("ipb_pass_hash", "EXHENTAI_IPB_PASS_HASH"),
        ("igneous", "EXHENTAI_IGNEOUS"),
    )}
    authenticated = all(auth[name] for name in AUTH_COOKIE_NAMES)
    cookies = {"nw": "1", "sl": "dm_2"}
    if authenticated:
        cookies.update(auth)
    return ("exhentai.org" if authenticated else "e-hentai.org"), cookies, sorted(cookies), authenticated

def parse_page(body):
    p = GalleryTableParser(); p.feed(body)
    gallery_rows = [row for row in p.rows if row.gallery_ids]
    raw_ids = list(p.all_gallery_ids)
    accepted = [row for row in gallery_rows if row.td_count == 2]
    parser_ids = unique(gid for row in accepted for gid in row.gallery_ids)
    next_cursor = None
    if p.next_href:
        try:
            raw = urllib.parse.parse_qs(urllib.parse.urlsplit(p.next_href).query).get("next", [None])[0]
            next_cursor = int(raw) if raw is not None else None
        except (TypeError, ValueError):
            pass
    return {
        "raw_gallery_row_count": len(raw_ids),
        "raw_gallery_ids": raw_ids,
        "table_gallery_row_count": len(gallery_rows),
        "html_layout": {"table_itg_count": p.table_itg_count, "div_itg_count": p.div_itg_count},
        "parser_gallery_row_count": len(accepted),
        "parser_gallery_ids": parser_ids,
        "rejected_gallery_rows": [{"td_count": r.td_count, "gallery_ids": r.gallery_ids} for r in gallery_rows if r.td_count != 2],
        "next_cursor": next_cursor,
    }

def request_page(domain, cookies, query, next_cursor, f_cats, f_sh, timeout, update_dm=False):
    params = {"next": str(next_cursor), "f_search": query, "advsearch": "1"}
    if update_dm: params["inline_set"] = "dm_e"
    if f_cats is not None: params["f_cats"] = f_cats
    if f_sh: params["f_sh"] = "on"
    url = "https://" + domain + "/?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={
        "User-Agent": "Mozilla/5.0 (Android) Miyorare-ExHentai-Diagnostic/1.1",
        "Cookie": "; ".join(f"{k}={v}" for k, v in cookies.items()),
    })
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            return res.status, res.geturl(), res.read().decode("utf-8", "replace"), params
    except urllib.error.HTTPError as exc:
        return exc.code, exc.geturl(), exc.read().decode("utf-8", "replace"), params

def resolve_page(offset, known_pages):
    if offset == 0: return 0
    if offset in known_pages: return known_pages[offset]
    page, tail = divmod(offset, PAGE_SIZE)
    return page + (1 if tail else 0)

def run_query(query, pages, reset_after, timeout, sleep_seconds, f_cats, f_sh, locale_filter):
    domain, cookies, cookie_names, authenticated = cookie_config()
    next_cursor, ui_ids, ui_seen = 0, [], set()
    reports, cursors, paginator_pages = [], {0: 0}, {}
    offset = 0
    for raw_page in range(pages):
        parser_page = resolve_page(offset, paginator_pages)
        status, final_url, body, params = request_page(domain, cookies, query, next_cursor, f_cats, f_sh, timeout)
        parsed = parse_page(body)
        request_attempts = [{"update_dm": False, "params": dict(params), "status": status}]
        if parsed["html_layout"]["table_itg_count"] == 0:
            status, final_url, body, params = request_page(domain, cookies, query, next_cursor, f_cats, f_sh, timeout, update_dm=True)
            parsed = parse_page(body)
            request_attempts.append({"update_dm": True, "params": dict(params), "status": status})
        repository_ids = list(parsed["parser_gallery_ids"])
        before = set(ui_ids)
        for gid in repository_ids:
            if gid not in ui_seen:
                ui_seen.add(gid); ui_ids.append(gid)
        paginator_pages[offset + len(repository_ids)] = parser_page + 1 if repository_ids else parser_page
        reports.append({
            "raw_chain_page": raw_page,
            "requested_parser_page": parser_page,
            "requested_offset": offset,
            "requested_domain": domain,
            "actual_domain": urllib.parse.urlsplit(final_url).hostname,
            "authenticated": authenticated,
            "cookie_names": cookie_names,
            "request_params": {
                "f_search": params.get("f_search"), "f_cats": params.get("f_cats"),
                "advsearch": params.get("advsearch"), "f_sh": params.get("f_sh"),
                "next": params.get("next"), "inline_set": params.get("inline_set"),
                "locale_filter": locale_filter,
            },
            "http_status": status,
            "request_attempts": request_attempts,
            **parsed,
            "repository_count": len(repository_ids),
            "repository_gallery_ids": repository_ids,
            "dedupe_filter_count": len([x for x in ui_ids if x not in before]),
            "dedupe_filter_gallery_ids_appended": [x for x in ui_ids if x not in before],
            "ui_count_sent_this_page": len([x for x in ui_ids if x not in before]),
            "ui_total_count": len(ui_ids),
            "ui_gallery_ids": list(ui_ids),
            "next_ui_offset": len(ui_ids),
        })
        following = parsed["next_cursor"]
        if following is None: break
        next_cursor, cursors[raw_page + 1], offset = following, following, len(ui_ids)
        if sleep_seconds: time.sleep(sleep_seconds)

    probe_page = min(max(reset_after + 1, 1), max(len(reports) - 1, 1))
    probe = next((x for x in reports if x["raw_chain_page"] == probe_page), None)
    state_loss = {
        "probe_page": probe_page,
        "probe_offset": probe["requested_offset"] if probe else probe_page * PAGE_SIZE,
        "website_cursor_known_from_raw_chain": cursors.get(probe_page),
        "website_raw_count": probe["raw_gallery_row_count"] if probe else None,
        "website_parser_count_if_http_is_sent": probe["parser_gallery_row_count"] if probe else None,
        "legacy_nextPages_after_parser_recreation": {},
        "legacy_cursor_lookup_result": 0,
        "legacy_parser_result_count": 0,
        "legacy_parser_would_send_http_request": False,
        "first_loss_stage": "pagination/cursor before HTTP" if probe and probe["raw_gallery_row_count"] > 0 else None,
        "classification": "3. pagination/cursor" if probe and probe["raw_gallery_row_count"] > 0 else "inconclusive",
    }
    nonseq_page = min(5, max(len(reports) - 1, 1))
    nonseq = next((x for x in reports if x["raw_chain_page"] == nonseq_page), None)
    return {
        "query": query, "domain": domain, "authenticated": authenticated, "cookie_names": cookie_names,
        "filters": {"f_cats": f_cats, "f_sh": f_sh, "locale_filter": locale_filter},
        "pages_requested": pages, "pages_observed": len(reports), "final_ui_total": len(ui_ids),
        "pages": reports, "state_loss_probe": state_loss,
        "non_sequential_probe": {
            "requested_page": nonseq_page,
            "website_cursor_known_from_raw_chain": cursors.get(nonseq_page),
            "website_raw_count": nonseq["raw_gallery_row_count"] if nonseq else None,
            "fresh_parser_cursor_lookup_result": 0, "fresh_parser_result_count": 0,
            "fresh_parser_would_send_http_request": False,
            "classification": "3. pagination/cursor" if nonseq and nonseq["raw_gallery_row_count"] > 0 else "inconclusive",
        },
    }

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--query", action="append", dest="queries")
    ap.add_argument("--pages", type=int, default=8)
    ap.add_argument("--reset-after", type=int, default=3)
    ap.add_argument("--timeout", type=int, default=30)
    ap.add_argument("--sleep", type=float, default=0.5)
    ap.add_argument("--f-cats"); ap.add_argument("--f-sh", action="store_true")
    ap.add_argument("--locale-filter"); ap.add_argument("--output")
    args = ap.parse_args()
    if not 5 <= args.pages <= 10: ap.error("--pages must be between 5 and 10")
    report = {
        "schema": 2,
        "cookie_value_policy": "Cookie values are used only for requests and are never recorded.",
        "current_parser_model": {
            "source": "Gekkoushi ExHentaiParser", "page_size": PAGE_SIZE,
            "cursor_state": "nextPages[filter.hashCode()][page] (parser-instance memory only)",
            "repository_mapping": "1:1 and order preserving",
            "ui_dedupe": "distinctBy gallery-derived manga id",
        },
        "queries": [],
    }
    for query in args.queries or ["touhou", "original", "fate"]:
        try:
            report["queries"].append(run_query(query, args.pages, args.reset_after, args.timeout, args.sleep, args.f_cats, args.f_sh, args.locale_filter))
        except Exception as exc:
            report["queries"].append({"query": query, "error": f"{type(exc).__name__}: {exc}"})
    payload = json.dumps(report, ensure_ascii=False, indent=2)
    print(payload)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as fh: fh.write(payload + "\n")
    return 0 if any(q.get("pages") for q in report["queries"]) else 2

if __name__ == "__main__":
    raise SystemExit(main())
