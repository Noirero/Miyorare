#!/usr/bin/env python3
"""Opt-in bounded EN→ID gtx HTTP diagnostics; no production behavior change."""
import hashlib
import json
import os
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

EN_P04 = (
    "“It wasn't Lina who opened the gate,” Samuel said. “It was Lena. "
    "They are two different people.” Ava wrote both names in her notebook, "
    "careful not to change the spelling. She could not tell whether Samuel "
    "was frightened of the person who had come through the gate or of the "
    "person who had stayed outside. The garden was quiet, and the kettle "
    "in the kitchen had begun to whistle."
)
CASES = [
    {
        "id": "sentence",
        "short": "He knew their absence mattered more than any sentence that remained, but he could not prove it.",
        "context": (
            "He carried the blue notebook in his coat, the one she had sworn was lost. "
            "Three pages had been torn out, not by him. He knew their absence "
            "mattered more than any sentence that remained, but he could not prove it."
        ),
    },
    {
        "id": "idiom",
        "short": "“Don't count your chickens,” her father said.",
        "context": (
            "By dawn the little bookshop had sold exactly one book. Nora called it a "
            "victory, although the rent was due on Friday and the ceiling had started "
            "leaking again. “Don't count your chickens,” her father said. "
            "She smiled at the idiom but did not promise to be cautious."
        ),
    },
    {
        "id": "dialogue_characterization",
        "short": "“You're barking up the wrong tree,” he said.",
        "context": (
            "The clerk put down his book. “You're barking up the wrong tree,” "
            "he said. The expression seemed borrowed from a language he didn't "
            "quite speak. Mara wondered whether he had chosen it to sound casual "
            "or to warn her off."
        ),
    },
]

def sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()

def parse_like_kotlin_engine(raw):
    decoded = json.loads(raw)
    if not isinstance(decoded, list) or not decoded or not isinstance(decoded[0], list):
        raise ValueError("Missing Google translation segments")
    fragments = [s[0] if isinstance(s, list) and s and isinstance(s[0], str)
                 else "" for s in decoded[0]]
    return fragments, "".join(fragments)

def self_test():
    assert sha(EN_P04) == "6832c5017083ec243299e4bb39db497cc97a5fe0841645737a5d4066aa2388a7"
    for sample in CASES:
        assert sample["short"] in sample["context"], sample["id"]
    for segments, expected in [
        ([[ "Lena.", "Lena." ], [ " Mereka", " They" ]], "Lena. Mereka"),
        ([[ "Lena.", "Lena." ], [ "Mereka", " They" ]], "Lena.Mereka"),
    ]:
        fragments, output = parse_like_kotlin_engine(json.dumps([segments]))
        assert output == expected and len(fragments) == 2

def fetch_gtx(text):
    query = urlencode({"client": "gtx", "sl": "en", "tl": "id", "dt": "t", "q": text})
    request = Request("https://translate.googleapis.com/translate_a/single?" + query,
                      headers={"User-Agent": "Mozilla/5.0 (Android) Miyorare"},
                      method="GET")
    start = time.monotonic()
    try:
        with urlopen(request, timeout=20) as response:
            body = response.read(200000).decode("utf-8")
            status = response.status
    except HTTPError as exc:
        return {"status": exc.code, "durationMs": round((time.monotonic()-start)*1000),
                "failure": "HTTP_ERROR", "rawHttpBody": exc.read(200000).decode("utf-8", "replace")}
    except (URLError, TimeoutError, OSError) as exc:
        return {"status": None, "durationMs": round((time.monotonic()-start)*1000),
                "failure": type(exc).__name__}
    result = {"status": status, "durationMs": round((time.monotonic()-start)*1000),
              "rawHttpBody": body, "rawHttpSha256": sha(body)}
    try:
        fragments, joined = parse_like_kotlin_engine(body)
        result["translatedFragments"] = fragments
        result["engineStyleJoined"] = joined
        result["fragmentSeams"] = [
            {"leftTail": fragments[i][-35:], "rightHead": fragments[i+1][:35]}
            for i in range(len(fragments)-1)
        ]
    except (ValueError, IndexError, TypeError) as exc:
        result["failure"] = "PARSE_" + type(exc).__name__
    return result

def main():
    self_test()
    if os.environ.get("MIYORARE_EN_ID_HTTP_DIAGNOSTIC_LIVE") != "1":
        print("EN HTTP diagnostic offline self-test PASS, live disabled")
        return
    plan = [("en-p04", "full", 1, EN_P04)]
    for sample in CASES:
        for repeat, arms in [(1, ("short", "context")), (2, ("context", "short"))]:
            for arm in arms:
                plan.append((sample["id"], arm, repeat, sample[arm]))
    plan.append(("en-p04", "full", 2, EN_P04))
    assert len(plan) == 14
    output = {"schema": "miyorare.en-id.http-targeted.v1", "scope": "NONPRODUCTION",
              "provider": "google-gtx", "sourceLanguage": "en", "targetLanguage": "id",
              "expectedRequestCount": 14, "baselineHistoricalEnP04Sha256": sha(EN_P04),
              "results": []}
    path = Path("app/build/translation-en-targeted-diagnostics/en-id-http-targeted.json")
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        for i, (case, arm, repeat, source) in enumerate(plan):
            if i: time.sleep(.75)
            response = fetch_gtx(source)
            output["results"].append({"sequence": i+1, "case": case, "arm": arm,
                                      "repeat": repeat, "sourceSha256": sha(source),
                                      "source": source, **response})
            print("EN diagnostic", i+1, case, arm, "HTTP", response.get("status"),
                  "failure", response.get("failure", "none"), flush=True)
            if response.get("failure") or response.get("status") != 200:
                break
    finally:
        output["status"] = ("COMPLETE" if len(output["results"]) == 14
                            and all(item.get("status") == 200 and not item.get("failure")
                                    for item in output["results"])
                            else "INCOMPLETE_NOT_COMPARABLE")
        path.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
        print("EN HTTP artifact written", output["status"], flush=True)
    if output["status"] != "COMPLETE":
        raise RuntimeError("EN HTTP diagnostic incomplete: inspect artifact, do not extrapolate")

if __name__ == "__main__":
    main()
