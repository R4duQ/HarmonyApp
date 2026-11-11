#!/usr/bin/env python3
"""
Builds the *derived* YouTube Music fixtures from the *real* ones.

Why: the real answers were captured anonymously on 2026-11-11 from a network
where YouTube Music showed no licensed catalogue (the songs filter answered
"No results" and every radio track was a user upload). They prove the shape
of the answers and how Harmony rejects non-songs, but contain no song
(MUSIC_VIDEO_TYPE_ATV) at all. The derived fixtures keep the real renderer
structure and change only the values a song carries: its videoType, its
byline runs (artist, album, year), duration, an explicit badge, a greyed-out
item, a song/video wrapper and an unplayable entry. Every change is below,
in code, so a reviewer can see exactly what is not real.

A derived fixture confirms only how Harmony (and ytmusicapi) read that
structure. Whether the live catalogue answers this way for a given song must
be checked on the phone (docs/YTMUSIC-RECOMMENDATIONS.md, "Still to check").

Usage: python3 -I scripts/ytmusic/derive_fixtures.py domain/online/src/test/resources/ytmusic
"""
import copy
import json
import os
import sys

ATV = "MUSIC_VIDEO_TYPE_ATV"
OMV = "MUSIC_VIDEO_TYPE_OMV"

# videoId, title, artists, album (name, browseId) or None, duration, explicit, greyed
SEARCH_SONGS = [
    ("aaaaaaaaa01", "Pe Drum", ["Subcarpați"], ("Underground Folclor", "MPREb_derived01"), "3:42", False, False),
    ("aaaaaaaaa02", "Pe Drum (Live)", ["Subcarpați"], ("Live la Arenele Romane", "MPREb_derived02"), "4:15", False, False),
    ("aaaaaaaaa03", "Pe Drum", ["Subcarpati", "Surorile Osoianu"], ("Pe Drum (Single)", "MPREb_derived03"), "3:43", False, False),
    ("aaaaaaaaa04", "Wonderwall", ["Oasis"], ("(What's The Story) Morning Glory? (Remastered)", "MPREb_derived04"), "4:19", False, False),
    ("aaaaaaaaa05", "Drumul spre pace", ["SUBCARPAŢI"], ("Satele noastre", "MPREb_derived05"), "3:58", True, False),
    ("aaaaaaaaa06", "Pe Drum (Sped Up)", ["Subcarpați"], None, "2:51", False, True),
]

# videoId, title, artists, album or None, year, length, type, explicit, wrapped, unplayable
WATCH = [
    ("bbbbbbbbb01", "Pe Drum", ["Subcarpați"], ("Underground Folclor", "MPREb_derived01"), "2016", "3:42", ATV, False, False, False),
    ("bbbbbbbbb02", "Scrisoare din trecut", ["Subcarpați"], ("Aripi de lumină", "MPREb_derived06"), "2018", "4:01", ATV, False, False, False),
    ("bbbbbbbbb03", "da-i volum!", ["raku"], ("da-i volum!", "MPREb_derived07"), "2021", "2:58", ATV, True, False, False),
    ("bbbbbbbbb04", "Flori Ilegale", ["Guess Who"], ("Tot ce am", "MPREb_derived08"), "2019", "3:35", ATV, False, True, False),
    ("bbbbbbbbb05", "Azi", ["Deliric", "Silent Strike", "EMAA"], None, None, "3:20", OMV, False, False, False),
    ("bbbbbbbbb06", "Folclor Nemuritor", ["Subcarpați"], ("Folclor Nemuritor", "MPREb_derived09"), "2022", "4:44", ATV, False, False, True),
    ("bbbbbbbbb07", "Povești despre noroc și ghinion", ["Chimie"], ("Chimie", "MPREb_derived10"), "2017", "3:12", ATV, False, False, False),
]


def run(text, browse_id=None, page_type=None):
    r = {"text": text}
    if browse_id:
        r["navigationEndpoint"] = {"browseEndpoint": {"browseId": browse_id}}
        if page_type:
            r["navigationEndpoint"]["browseEndpoint"]["browseEndpointContextSupportedConfigs"] = {
                "browseEndpointContextMusicConfig": {"pageType": page_type}}
    return r


def dot():
    return {"text": " • "}


def explicit_badge():
    return [{"musicInlineBadgeRenderer": {"icon": {"iconType": "MUSIC_EXPLICIT_BADGE"},
                                          "accessibilityData": {"accessibilityData": {"label": "Explicit"}}}}]


def derive_search(real):
    out = copy.deepcopy(real)
    shelf = out["contents"]["tabbedSearchResultsRenderer"]["tabs"][0]["tabRenderer"]["content"]["sectionListRenderer"]["contents"][0]["musicShelfRenderer"]
    shelf["title"] = {"runs": [{"text": "Songs"}]}
    template = shelf["contents"][0]["musicResponsiveListItemRenderer"]
    items = []
    for vid, title, artists, album, duration, explicit, greyed in SEARCH_SONGS:
        it = copy.deepcopy(template)
        play = it["overlay"]["musicItemThumbnailOverlayRenderer"]["content"]["musicPlayButtonRenderer"]["playNavigationEndpoint"]["watchEndpoint"]
        play["videoId"] = vid
        play["watchEndpointMusicSupportedConfigs"]["watchEndpointMusicConfig"]["musicVideoType"] = ATV
        col0 = it["flexColumns"][0]["musicResponsiveListItemFlexColumnRenderer"]["text"]["runs"][0]
        col0["text"] = title
        col0["navigationEndpoint"]["watchEndpoint"]["videoId"] = vid
        col0["navigationEndpoint"]["watchEndpoint"]["watchEndpointMusicSupportedConfigs"]["watchEndpointMusicConfig"]["musicVideoType"] = ATV
        runs = []
        for i, a in enumerate(artists):
            if i:
                runs.append({"text": ", "})
            runs.append(run(a, "UCderivedArtist%02d" % i, "MUSIC_PAGE_TYPE_ARTIST"))
        if album:
            runs += [dot(), run(album[0], album[1], "MUSIC_PAGE_TYPE_ALBUM")]
        runs += [dot(), {"text": duration}]
        it["flexColumns"][1]["musicResponsiveListItemFlexColumnRenderer"]["text"]["runs"] = runs
        del it["flexColumns"][2:]
        if explicit:
            it["badges"] = explicit_badge()
        else:
            it.pop("badges", None)
        if greyed:
            it["musicItemRendererDisplayPolicy"] = "MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT"
        else:
            it.pop("musicItemRendererDisplayPolicy", None)
        items.append({"musicResponsiveListItemRenderer": it})
    shelf["contents"] = items
    return out


def derive_watch(real):
    out = copy.deepcopy(real)
    panel = out["contents"]["singleColumnMusicWatchNextResultsRenderer"]["tabbedRenderer"]["watchNextTabbedResultsRenderer"]["tabs"][0]["tabRenderer"]["content"]["musicQueueRenderer"]["content"]["playlistPanelRenderer"]
    template = panel["contents"][0]["playlistPanelVideoRenderer"]
    items = []
    for vid, title, artists, album, year, length, vtype, explicit, wrapped, unplayable in WATCH:
        it = copy.deepcopy(template)
        it["videoId"] = vid
        it["title"] = {"runs": [{"text": title}]}
        it["lengthText"] = {"runs": [{"text": length}]}
        we = it["navigationEndpoint"]["watchEndpoint"]
        we["videoId"] = vid
        we["watchEndpointMusicSupportedConfigs"]["watchEndpointMusicConfig"]["musicVideoType"] = vtype
        runs = []
        for i, a in enumerate(artists):
            if i:
                runs.append({"text": " & "})
            runs.append(run(a, "UCderivedArtist%02d" % i, "MUSIC_PAGE_TYPE_ARTIST"))
        if album:
            runs += [dot(), run(album[0], album[1], "MUSIC_PAGE_TYPE_ALBUM")]
        if year:
            runs += [dot(), {"text": year}]
        it["longBylineText"] = {"runs": runs}
        if explicit:
            it["badges"] = explicit_badge()
        else:
            it.pop("badges", None)
        if unplayable:
            it["unplayableText"] = {"runs": [{"text": "Song is unavailable"}]}
        entry = {"playlistPanelVideoRenderer": it}
        if wrapped:
            video = copy.deepcopy(it)
            video["videoId"] = vid[:-2] + "vv"
            video["navigationEndpoint"]["watchEndpoint"]["videoId"] = video["videoId"]
            video["navigationEndpoint"]["watchEndpoint"]["watchEndpointMusicSupportedConfigs"]["watchEndpointMusicConfig"]["musicVideoType"] = OMV
            video["title"] = {"runs": [{"text": title + " (Official Video)"}]}
            entry = {"playlistPanelVideoWrapperRenderer": {
                "primaryRenderer": {"playlistPanelVideoRenderer": it},
                "counterpart": [{"counterpartRenderer": {"playlistPanelVideoRenderer": video}}]}}
        items.append(entry)
    panel["contents"] = items
    return out


def main():
    root = sys.argv[1]
    real = os.path.join(root, "real")
    derived = os.path.join(root, "derived")
    os.makedirs(derived, exist_ok=True)
    with open(os.path.join(real, "2026_11_search_videos_subcarpati.json"), encoding="utf-8") as f:
        search = derive_search(json.load(f))
    with open(os.path.join(real, "2026_11_watch_radio_DF_CKTwIg3I.json"), encoding="utf-8") as f:
        watch = derive_watch(json.load(f))
    for name, data in [("search_songs_atv.json", search), ("watch_radio_atv.json", watch)]:
        with open(os.path.join(derived, name), "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, separators=(",", ":"))
            f.write("\n")


if __name__ == "__main__":
    main()
