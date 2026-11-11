#!/usr/bin/env python3
"""
Runs ytmusicapi (the version pinned in requirements.txt) over the recorded
YouTube Music answers and writes what it parses, field by field, to
<fixtures>/expected/. Harmony's Kotlin parser is tested against these files
(YtmConformanceTest), so a difference between the two shows up as a failing
test, and a new ytmusicapi release can be tried by re-running this script.

No network: ytmusicapi's request function is replaced by one that returns
the recorded pages in order.

Usage:
  python3 -I scripts/ytmusic/expected_outputs.py domain/online/src/test/resources/ytmusic          # write
  python3 -I scripts/ytmusic/expected_outputs.py domain/online/src/test/resources/ytmusic --check  # CI: fail on any difference
"""
import json
import os
import sys

import ytmusicapi
from ytmusicapi import YTMusic

PINNED = "1.12.3"

# fixture (relative to the fixtures root) -> (call, extra pages)
CASES = {
    "real/2026_11_search_songs_no_results_oasis.json": ("search", []),
    "real/2026_11_search_songs_no_results_subcarpati.json": ("search", []),
    "real/2026_11_search_videos_subcarpati.json": ("search", []),
    "derived/search_songs_atv.json": ("search", []),
    "real/2026_11_watch_radio_ZrOKjDZOtkA.json": ("watch", ["real/2026_11_watch_radio_ZrOKjDZOtkA.page1.json"]),
    "real/2026_11_watch_radio_DF_CKTwIg3I.json": ("watch", []),
    "derived/watch_radio_atv.json": ("watch", []),
}


def load(root, rel):
    with open(os.path.join(root, rel), encoding="utf-8") as f:
        return json.load(f)


def names(items):
    return [a.get("name") for a in (items or [])]


def search_fields(r):
    album = r.get("album") or None
    return {
        "videoId": r.get("videoId"),
        "title": r.get("title"),
        "artists": names(r.get("artists")),
        "album": album.get("name") if album else None,
        "albumId": album.get("id") if album else None,
        "duration_seconds": r.get("duration_seconds"),
        "videoType": r.get("videoType"),
        "isExplicit": r.get("isExplicit"),
        "isAvailable": r.get("isAvailable"),
        "year": r.get("year"),
    }


def watch_fields(t):
    album = t.get("album") or None
    return {
        "videoId": t.get("videoId"),
        "title": t.get("title"),
        "length": t.get("length"),
        "videoType": t.get("videoType"),
        "artists": names(t.get("artists")),
        "album": album.get("name") if album else None,
        "albumId": album.get("id") if album else None,
        "year": t.get("year"),
    }


def parse(root, rel, call, extra):
    pages = [load(root, rel)] + [load(root, p) for p in extra]
    yt = YTMusic()

    def fake(endpoint, body, additionalParams=""):
        return pages.pop(0) if pages else {}

    yt._send_request = fake
    if call == "search":
        return [search_fields(r) for r in yt.search("fixture", filter="songs", limit=100)]
    tracks = yt.get_watch_playlist("ZrOKjDZOtkA", radio=True, limit=1000)["tracks"]
    return [watch_fields(t) for t in tracks]


def main():
    root = sys.argv[1]
    check = "--check" in sys.argv[2:]
    if ytmusicapi.__version__ != PINNED:
        sys.exit(f"ytmusicapi {ytmusicapi.__version__} installed, {PINNED} expected (pip install -r scripts/ytmusic/requirements.txt)")
    out_dir = os.path.join(root, "expected")
    os.makedirs(out_dir, exist_ok=True)
    differences = []
    for rel, (call, extra) in CASES.items():
        document = {
            "ytmusicapi": PINNED,
            "fixture": rel,
            "call": call,
            "results": parse(root, rel, call, extra),
        }
        text = json.dumps(document, ensure_ascii=False, indent=1) + "\n"
        target = os.path.join(out_dir, os.path.basename(rel))
        if check:
            try:
                with open(target, encoding="utf-8") as f:
                    if f.read() != text:
                        differences.append(target)
            except FileNotFoundError:
                differences.append(target)
        else:
            with open(target, "w", encoding="utf-8") as f:
                f.write(text)
    if differences:
        sys.exit("ytmusicapi " + PINNED + " parses differently from the committed outputs:\n  " + "\n  ".join(differences))
    print(("checked " if check else "wrote ") + str(len(CASES)) + " expected outputs (ytmusicapi " + PINNED + ")")


if __name__ == "__main__":
    main()
