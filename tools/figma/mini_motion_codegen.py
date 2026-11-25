#!/usr/bin/env python3
"""
Turns the mini player's Figma motion (get_motion_context output, saved under
design/figma/mini-player/) into MiniMotionData.kt: every keyframe, value and
easing exactly as Figma exports them, nothing re-timed or approximated.

    python3 tools/figma/mini_motion_codegen.py

Figma file rrilnVhuT79ENxaSaCUBnU, frames 3:6 (skip), 3:10 (skip completed),
3:14 (replay), 3:18 (replay completed), 10:2 (automatic next: prepare, flip &
release) and 52:3 (07A: previous track becomes replay).

Reads both of Figma's export dialects: the older one (--motion-translate-x,
--motion-scale-x, ...) and the current one (--translateX-transform,
--scale-transform, translate: x y, --rotate-transform / --rotateX-transform
in rad).
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
        "3:468": "inArtist", "3:472": "inRail", "3:473": "inProgress", "3:474": "restartMark", "3:469": "pause",
    }),
    "REPLAY_COMPLETED": ("motion_3_18.json", "3:18 · 05 Mini player — Replay completed", {
        "3:363": "replayButton", "3:370": "capsule",
    }),
    "AUTO_NEXT": ("motion_10_2.json", "10:2 · 06 Mini player — Automatic next · Prepare & release", {
        "10:95": "outline", "10:187": "trail", "10:280": "bridge", "10:482": "skipButton", "10:669": "skipIcon",
        "10:682": "skipTriangle", "10:689": "skipBar", "10:11110": "ring", "10:11138": "preparing",
        "10:11142": "core", "25:2": "flip", "10:1269": "capsule", "10:2103": "progress",
        "10:2677": "incoming", "10:3896": "inProgress",
    }),
    "PREVIOUS_TO_REPLAY": ("motion_52_3.json", "52:3 · 07A Mini player — Previous Track → Replay", {
        "52:5": "leftButton", "52:57": "previousGlyph", "52:6": "replayIcon", "52:20": "progress",
    }),
}

# Keyframe-name token -> (declared CSS property, Prop(s)).
PROPS = {
    "--motion-translate-x": "TranslateX", "--motion-translate-y": "TranslateY",
    "--motion-scale-x": "ScaleX", "--motion-scale-y": "ScaleY", "--motion-rotate": "Rotate",
    "translateX": "TranslateX", "translateY": "TranslateY", "scaleX": "ScaleX", "scaleY": "ScaleY",
    "scale": "Scale", "rotate": "Rotate", "rotateX": "RotateX", "translate": "Translate",
    "width": "Width", "height": "Height", "border-radius": "Radius", "opacity": "Opacity",
    "background-color": "Background", "fill": "Fill", "stroke": "Stroke", "filter-effect-0": "Blur",
    "box-shadow": "Shadow",
}
COLOR_PROPS = {"Background", "Fill", "Stroke"}
# What the current dialect declares inside a keyframe for each token.
DECLARED = {
    "translateX": "--translateX-transform", "translateY": "--translateY-transform",
    "scaleX": "--scaleX-transform", "scaleY": "--scaleY-transform", "scale": "--scale-transform",
    "rotate": "--rotate-transform", "rotateX": "--rotateX-transform", "translate": "translate",
    "filter-effect-0": "filter",
}


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
    if t == "linear":
        return "null"
    # CSS's named curves, as the spec defines them.
    named = {"ease": "0.25, 0.1, 0.25, 1", "ease-in": "0.42, 0, 1, 1", "ease-out": "0, 0, 0.58, 1", "ease-in-out": "0.42, 0, 0.58, 1"}
    if t in named:
        t = f"cubic-bezier({named[t]})"
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
    funcs = []
    for name, (file, frame, roles) in TIMELINES.items():
        data = json.loads((SRC / file).read_text())
        cohort = data["timelineCohorts"][0]
        duration = cohort["durationMs"]
        tracks = []
        for node in data["nodes"]:
            role = roles[node["nodeId"]]
            css = node["codeSnippets"]["css"]
            origin = re.search(r"--motion-origin-x: ([\d.]+);", css) or re.search(r"transform-origin: ([\d.]+)% ", css)
            for kname, body in blocks(css):
                token = re.match(r"kf_\d+_\d+_(.+)_0$", kname).group(1)
                prop = PROPS[token]
                declared = DECLARED.get(token, token)
                times, values, eases, shadow_blur, shadow_alpha, second = [], [], [], [], [], []
                for pct, decl in frames(body):
                    times.append(pct / 100.0 * duration)
                    eases.append(easing_of(decl))
                    raw = re.search(r"(?<![\w-])" + re.escape(declared) + r": ([^;]+);", decl).group(1).strip()
                    if prop in COLOR_PROPS:
                        values.append(color(raw))
                    elif prop == "Blur":
                        values.append(kt_float(re.search(r"blur\(([\d.]+)px\)", raw).group(1)))
                    elif prop == "Shadow":
                        nums = re.match(r"0px 0px ([\d.]+)px 0px rgba\(166, 255, 204, ([\d.]+)\)", raw)
                        shadow_blur.append(kt_float(nums.group(1)))
                        shadow_alpha.append(kt_float(nums.group(2)))
                    elif prop == "Translate":
                        x, y = raw.split()
                        values.append(kt_float(x.replace("px", "")))
                        second.append(kt_float(y.replace("px", "")))
                    else:
                        values.append(kt_float(re.sub(r"(px|rad)$", "", raw)))
                t = ", ".join(kt_float(x) for x in times)
                e = ", ".join(eases)
                def number(p, vs):
                    tracks.append(f'        Key("{role}", Prop.{p}) to NumberTrack(floatArrayOf({t}), floatArrayOf({", ".join(vs)}), arrayOf({e})),')
                if prop == "Shadow":
                    number("ShadowBlur", shadow_blur)
                    number("ShadowAlpha", shadow_alpha)
                elif prop == "Translate":
                    number("TranslateX", values)
                    number("TranslateY", second)
                elif prop == "Scale":
                    number("ScaleX", values)
                    number("ScaleY", values)
                elif prop in COLOR_PROPS:
                    tracks.append(f'        Key("{role}", Prop.{prop}) to ColorTrack(floatArrayOf({t}), longArrayOf({", ".join(values)}), arrayOf({e})),')
                else:
                    number(prop, values)
            if origin and origin.group(1) != "50":
                tracks.append(f'        // {role}: transform origin x = {origin.group(1)}%')
        # One function per track: the JVM caps a method (and a class initializer) at 64 KB.
        calls = []
        for i, line in enumerate(x for x in tracks if not x.strip().startswith("//")):
            fn = f"{name.lower()}{i}"
            body = line.strip().rstrip(",")
            funcs.append(f"    private fun {fn}(): Pair<Key, Track> = {body}\n")
            calls.append(f"{fn}()")
        notes = "".join(f"    {x.strip()}\n" for x in tracks if x.strip().startswith("//"))
        out.append(f"    /** Figma {frame}, {duration} ms. */\n{notes}    val {name}: Timeline by lazy {{\n        Timeline(\n            durationMs = {duration}f,\n            tracks = mapOf(\n" +
                   "".join(f"                {c},\n" for c in calls) +
                   "            ),\n        )\n    }\n")
    header = '''package com.harmony.feature.player.gesture

import androidx.compose.animation.core.CubicBezierEasing

// GENERATED by tools/figma/mini_motion_codegen.py from the Figma motion export
// (design/figma/mini-player/*.json, file rrilnVhuT79ENxaSaCUBnU). Do not edit:
// change the animation in Figma, export it again and re-run the script.
@Suppress("LargeClass", "MaxLineLength")
internal object MiniMotionData {
'''
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(header + "\n".join(out) + "\n" + "".join(funcs) + "}\n")
    print("wrote", OUT, sum(1 for l in OUT.read_text().splitlines() if " to " in l), "tracks")


if __name__ == "__main__":
    generate()
