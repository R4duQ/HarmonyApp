#!/usr/bin/env python3
"""
Turns the mini player's Figma motion (get_motion_context output, saved under
design/figma/mini-player/) into MiniMotionData.kt: every keyframe, value and
easing exactly as Figma exports them, nothing re-timed or approximated.

    python3 tools/figma/mini_motion_codegen.py

Figma file rrilnVhuT79ENxaSaCUBnU, frames 3:6 (skip), 3:10 (skip completed),
3:14 (replay), 3:18 (replay completed).
"""
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "design/figma/mini-player"
OUT = ROOT / "feature/player/src/main/kotlin/com/harmony/feature/player/gesture/MiniMotionData.kt"

TIMELINES = {
    # name: (json, Figma frame, roles by node id)
    "SKIP": ("motion_3_6.json", "3:6 · 02 Mini player — Swipe right in progress", {
        "3:298": "outline", "3:301": "trail", "3:303": "bridge", "3:307": "skipButton", "3:308": "skipIcon",
        "3:309": "skipTriangle", "3:310": "skipBar", "3:311": "capsule", "3:312": "art", "3:313": "title",
        "3:314": "artist", "3:315": "rail", "3:406": "incoming", "3:407": "inArt", "3:408": "inTitle",
        "3:409": "inArtist", "3:413": "inRail", "3:414": "inProgress", "3:410": "pause",
    }),
    "SKIP_COMPLETED": ("motion_3_10.json", "3:10 · 03 Mini player — Skip completed", {
        "3:323": "skipButton", "3:324": "skipIcon", "3:325": "skipTriangle", "3:326": "skipBar",
        "3:327": "incoming", "3:328": "inArt", "3:329": "inTitle", "3:330": "inArtist", "3:331": "pause",
        "3:334": "inRail", "3:335": "inProgress",
    }),
    "REPLAY": ("motion_3_14.json", "3:14 · 04 Mini player — Swipe left in progress", {
        "3:341": "outline", "3:344": "trail", "3:346": "bridge", "3:347": "replayButton", "3:348": "replayIcon",
        "3:349": "replayVector", "3:354": "capsule", "3:355": "art", "3:356": "title", "3:357": "artist",
        "3:358": "rail", "3:359": "progress", "3:465": "incoming", "3:466": "inArt", "3:467": "inTitle",
        "3:468": "inArtist", "3:472": "inRail", "3:469": "pause",
    }),
    "REPLAY_COMPLETED": ("motion_3_18.json", "3:18 · 05 Mini player — Replay completed", {
        "3:363": "replayButton", "3:370": "capsule",
    }),
}

PROPS = {
    "--motion-translate-x": "TranslateX", "--motion-translate-y": "TranslateY",
    "--motion-scale-x": "ScaleX", "--motion-scale-y": "ScaleY", "--motion-rotate": "Rotate",
    "width": "Width", "height": "Height", "border-radius": "Radius", "opacity": "Opacity",
    "background-color": "Background", "fill": "Fill", "stroke": "Stroke", "filter-effect-0": "Blur",
    "box-shadow": "Shadow",
}
COLOR_PROPS = {"Background", "Fill", "Stroke"}


def blocks(css):
    """Each @keyframes name and its body."""
    for m in re.finditer(r"@keyframes (\S+) \{", css):
        depth, i = 1, m.end()
        while depth:
            depth += {"{": 1, "}": -1}.get(css[i], 0)
            i += 1
        yield m.group(1), css[m.end():i - 1]


def frames(body):
    for m in re.finditer(r"([\d.]+)% \{([^}]*)\}", body):
        yield float(m.group(1)), m.group(2)


def easing_of(decl):
    m = re.search(r"animation-timing-function: ([^;]+);", decl)
    if not m:
        return "null"
    t = m.group(1).strip()
    if t == "step-end":
        return "STEP_END"
    b = re.match(r"cubic-bezier\(([^)]*)\)", t)
    a, b2, c, d = [float(x) for x in b.group(1).split(",")]
    return f"CubicBezierEasing({a}f, {b2}f, {c}f, {d}f)"


def color(v):
    v = v.strip().lstrip("#")
    return f"0xFF{v.upper()}"


def kt_float(x):
    s = repr(round(float(x), 6))
    return s + "f"


def generate():
    out = []
    for name, (file, frame, roles) in TIMELINES.items():
        data = json.loads((SRC / file).read_text())
        cohort = data["timelineCohorts"][0]
        duration = cohort["durationMs"]
        tracks = []
        for node in data["nodes"]:
            role = roles[node["nodeId"]]
            css = node["codeSnippets"]["css"]
            origin = re.search(r"--motion-origin-x: ([\d.]+);", css)
            for kname, body in blocks(css):
                prop_css = next(p for p in sorted(PROPS, key=len, reverse=True) if f"_{p}_0" in kname)
                prop = PROPS[prop_css]
                times, values, eases, shadow_blur, shadow_alpha = [], [], [], [], []
                for pct, decl in frames(body):
                    times.append(pct / 100.0 * duration)
                    eases.append(easing_of(decl))
                    declared = "filter" if prop_css == "filter-effect-0" else prop_css
                    raw = re.search(r"(?<![\w-])" + re.escape(declared) + r": ([^;]+);", decl).group(1).strip()
                    if prop in COLOR_PROPS:
                        values.append(color(raw))
                    elif prop == "Blur":
                        values.append(kt_float(re.search(r"blur\(([\d.]+)px\)", raw).group(1)))
                    elif prop == "Shadow":
                        nums = re.match(r"0px 0px ([\d.]+)px 0px rgba\(166, 255, 204, ([\d.]+)\)", raw)
                        shadow_blur.append(kt_float(nums.group(1)))
                        shadow_alpha.append(kt_float(nums.group(2)))
                    else:
                        values.append(kt_float(raw.replace("px", "")))
                t = ", ".join(kt_float(x) for x in times)
                e = ", ".join(eases)
                if prop == "Shadow":
                    tracks.append(f'        Key("{role}", Prop.ShadowBlur) to NumberTrack(floatArrayOf({t}), floatArrayOf({", ".join(shadow_blur)}), arrayOf({e})),')
                    tracks.append(f'        Key("{role}", Prop.ShadowAlpha) to NumberTrack(floatArrayOf({t}), floatArrayOf({", ".join(shadow_alpha)}), arrayOf({e})),')
                elif prop in COLOR_PROPS:
                    tracks.append(f'        Key("{role}", Prop.{prop}) to ColorTrack(floatArrayOf({t}), longArrayOf({", ".join(values)}), arrayOf({e})),')
                else:
                    tracks.append(f'        Key("{role}", Prop.{prop}) to NumberTrack(floatArrayOf({t}), floatArrayOf({", ".join(values)}), arrayOf({e})),')
            if origin and origin.group(1) != "50":
                tracks.append(f'        // {role}: transform origin x = {origin.group(1)}%')
        out.append(f"    /** Figma {frame}, {duration} ms. */\n    val {name} = Timeline(\n        durationMs = {duration}f,\n        tracks = mapOf(\n" +
                   "\n".join("    " + x if not x.strip().startswith("//") else "    " + x for x in tracks) +
                   "\n        ),\n    )\n")
    header = '''package com.harmony.feature.player.gesture

import androidx.compose.animation.core.CubicBezierEasing

// GENERATED by tools/figma/mini_motion_codegen.py from the Figma motion export
// (design/figma/mini-player/*.json, file rrilnVhuT79ENxaSaCUBnU). Do not edit:
// change the animation in Figma, export it again and re-run the script.
@Suppress("LargeClass", "MaxLineLength")
internal object MiniMotionData {
'''
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(header + "\n".join(out) + "}\n")
    print("wrote", OUT, sum(1 for l in OUT.read_text().splitlines() if " to " in l), "tracks")


if __name__ == "__main__":
    generate()
