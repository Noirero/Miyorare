#!/usr/bin/env python3
"""Best-effort first-party source icon metadata extraction.

This intentionally stays conservative: it never downloads images or website pages during a source
pack build. If an upstream parser declares a favicon URL, that URL wins. Otherwise, when a stable
site domain can be inferred from the parser source, Miyorare records the conventional /favicon.ico
URL. Sources that cannot be identified safely simply get no icon metadata.
"""

from __future__ import annotations

import re
from urllib.parse import urlparse


ANNOTATION_RE = re.compile(r"@MangaSourceParser\s*\((.*?)\)", re.DOTALL)
FAVICON_RE = re.compile(r'Favicon\s*\(\s*"((?:\\.|[^"\\])*)"', re.DOTALL)
DOMAIN_PATTERNS = (
    re.compile(r'\b(?:domain|host)\s*(?::[^=\n]+)?=\s*"([^"\s/]+)"', re.IGNORECASE),
    re.compile(r'ConfigKey\.Domain\s*\(\s*"([^"\s/]+)"', re.IGNORECASE),
)
QUOTED_HOST_RE = re.compile(r'"([A-Za-z0-9][A-Za-z0-9.-]*\.[A-Za-z]{2,63})"')


def _valid_http_url(value: str) -> bool:
    try:
        parsed = urlparse(value)
    except ValueError:
        return False
    return parsed.scheme in {"http", "https"} and bool(parsed.hostname)


def _valid_domain(value: str) -> bool:
    value = value.strip().lower().rstrip(".")
    if not value or "/" in value or " " in value or "." not in value:
        return False
    labels = value.split(".")
    if any(not label or len(label) > 63 for label in labels):
        return False
    return all(re.fullmatch(r"[a-z0-9-]+", label) and not label.startswith("-") and not label.endswith("-") for label in labels)


def _domain_from_content(content: str) -> str | None:
    without_annotations = ANNOTATION_RE.sub("", content)
    for pattern in DOMAIN_PATTERNS:
        match = pattern.search(without_annotations)
        if match:
            candidate = match.group(1).strip().lower()
            if _valid_domain(candidate):
                return candidate

    # Compact parser subclasses often pass the site host directly to a base parser constructor.
    # Only accept a single unambiguous hostname literal after annotation strings are removed.
    candidates = []
    for match in QUOTED_HOST_RE.finditer(without_annotations):
        candidate = match.group(1).strip().lower()
        if _valid_domain(candidate) and candidate not in candidates:
            candidates.append(candidate)
    return candidates[0] if len(candidates) == 1 else None


def extract_source_icon_urls(content: str, source_names: list[str]) -> dict[str, str]:
    """Return sourceName -> icon URL for a source file when the mapping is unambiguous."""
    if len(source_names) != 1:
        return {}
    source_name = source_names[0]
    domain = _domain_from_content(content)

    for match in FAVICON_RE.finditer(content):
        raw = bytes(match.group(1), "utf-8").decode("unicode_escape").strip()
        candidate = raw
        if domain:
            candidate = candidate.replace("${domain}", domain).replace("$domain", domain)
        # Do not attempt to evaluate arbitrary Kotlin interpolation. A missing icon is preferable
        # to recording a misleading or unstable URL.
        if "$" in candidate or "{" in candidate or "}" in candidate:
            continue
        if _valid_http_url(candidate):
            return {source_name: candidate}

    if domain:
        return {source_name: f"https://{domain}/favicon.ico"}
    return {}
