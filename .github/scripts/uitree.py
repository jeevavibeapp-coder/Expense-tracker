#!/usr/bin/env python3
"""The parts of native-smoke.sh that are easier to get right in Python.

The smoke test sees the screen through `uiautomator dump`, not through a test
APK, so it exercises exactly the build a person would install. This reads
those dumps, turns screenshots into images small enough for the job log, and
pulls crashes out of logcat.

    uitree.py texts   DUMP PKG               what is on screen, in layout order
    uitree.py has     DUMP PKG REGEX         exit 0 if any node's text/desc matches
    uitree.py center  DUMP PKG REGEX [ATTR]  "x y" of the first match (ATTR text|desc|any)
    uitree.py field   DUMP PKG REGEX INDEX   "x y" of a text field found by its label,
                                             else the INDEX-th text field on screen
    uitree.py thumb   PNG NAME               small JPEG, base64, between SCREENSHOT markers
    uitree.py crashes PKG LOGFILE...         print crash/ANR evidence; exit 0 if any

An empty PKG means every window, which is how a system dialog in front of the
app gets seen at all.
"""
import base64
import io
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")

# The orchestrator decodes these out of the job log, where anything much
# bigger makes the log itself the problem.
THUMB_WIDTH = 320
MAX_BASE64 = 60_000


def nodes(path, pkg):
    """Document order is layout order, so "first match" means the topmost."""
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError):
        return []
    return [n for n in root.iter("node") if not pkg or n.get("package") == pkg]


def center(node):
    m = BOUNDS.fullmatch(node.get("bounds", ""))
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    if x2 <= x1 or y2 <= y1:
        return None
    return (x1 + x2) // 2, (y1 + y2) // 2


def words(node, attr="any"):
    text, desc = node.get("text") or "", node.get("content-desc") or ""
    return {"text": [text], "desc": [desc]}.get(attr, [text, desc])


def texts(path, pkg):
    shown = nodes(path, pkg)
    if pkg and not shown:
        print(f"    (nothing from {pkg} on screen; every window follows)")
        shown = nodes(path, "")
    for n in shown:
        text, desc = n.get("text") or "", n.get("content-desc") or ""
        if not text and not desc:
            continue
        kind = (n.get("class") or "?").rsplit(".", 1)[-1]
        parts = [f'text="{text}"'] if text else []
        if desc:
            parts.append(f'desc="{desc}"')
        line = " ".join(parts).replace("\n", "\\n")
        print(f"    {kind:<12} {line}")
    return 0


def has(path, pkg, pattern):
    rx = re.compile(pattern)
    return 0 if any(rx.search(w) for n in nodes(path, pkg) for w in words(n) if w) else 1


def center_of(path, pkg, pattern, attr="any"):
    rx = re.compile(pattern)
    for n in nodes(path, pkg):
        if any(rx.search(w) for w in words(n, attr) if w):
            c = center(n)
            if c:
                print(c[0], c[1])
                return 0
    return 1


def field(path, pkg, pattern, index):
    """Compose reports a text field's label as text inside the field, or not
    at all once there is a value, so the position on screen is the fallback."""
    rx = re.compile(pattern)
    fields = [n for n in nodes(path, pkg) if (n.get("class") or "").endswith("EditText")]
    for f in fields:
        inside = [w for d in f.iter("node") for w in words(d)] + [f.get("hint") or ""]
        if any(rx.search(w) for w in inside if w):
            c = center(f)
            if c:
                print(c[0], c[1])
                return 0
    if 0 <= index < len(fields) and center(fields[index]):
        print(*center(fields[index]))
        return 0
    return 1


def jpeg(png):
    """Smallest acceptable copy first tried at a readable quality."""
    try:
        from PIL import Image
    except ImportError:
        Image = None
    if Image is not None:
        try:
            img = Image.open(png).convert("RGB")
        except Exception as e:  # an empty or truncated screencap
            return None, f"unreadable screenshot: {e}"
        lanczos = getattr(Image, "Resampling", Image).LANCZOS
        data = None
        for width, quality in ((THUMB_WIDTH, 70), (THUMB_WIDTH, 55), (THUMB_WIDTH, 40), (240, 40)):
            height = max(1, round(img.height * width / img.width))
            buf = io.BytesIO()
            img.resize((width, height), lanczos).save(buf, "JPEG", quality=quality, optimize=True)
            data = buf.getvalue()
            if len(base64.b64encode(data)) <= MAX_BASE64:
                break
        return data, None
    tool = shutil.which("magick") or shutil.which("convert")
    if tool:
        for quality in (70, 50, 35):
            r = subprocess.run([tool, png, "-resize", f"{THUMB_WIDTH}x", "-quality", str(quality),
                                "jpg:-"], capture_output=True)
            if r.returncode != 0 or not r.stdout:
                return None, "ImageMagick could not read the screenshot"
            if len(base64.b64encode(r.stdout)) <= MAX_BASE64:
                break
        return r.stdout, None
    return None, "neither Pillow nor ImageMagick is installed"


def thumb(png, name):
    data, why = jpeg(png)
    if data is None:
        print(f"SCREENSHOT-UNAVAILABLE {name}: {why}")
        return 0
    print(f"SCREENSHOT-BEGIN {name}")
    sys.stdout.write(base64.encodebytes(data).decode("ascii"))
    print(f"SCREENSHOT-END {name}")
    return 0


def crashes(pkg, *logs):
    """Java crashes, native crashes and ANRs that name the package. Each
    block is printed once even when two log files both carry it."""
    found, seen = [], set()
    for log in logs:
        try:
            lines = open(log, encoding="utf-8", errors="replace").read().splitlines()
        except OSError:
            continue
        i = 0
        while i < len(lines):
            line = lines[i]
            if "FATAL EXCEPTION" in line and "AndroidRuntime" in line:
                block = [line]
                j = i + 1
                while j < len(lines) and "AndroidRuntime" in lines[j]:
                    block.append(lines[j])
                    j += 1
                if any(f"Process: {pkg}" in b for b in block):
                    found.append(block)
                i = j
                continue
            if f"ANR in {pkg}" in line:
                block = [line] + [l for l in lines[i + 1:i + 12] if "ActivityManager" in l]
                found.append(block)
            elif "Fatal signal" in line and pkg in line:
                found.append([line])
            i += 1
    printed = 0
    for block in found:
        # Keyed on the message text, not the timestamp column, so one crash
        # read from two buffers is reported once.
        key = tuple(re.sub(r"^.*?: ", "", b) for b in block[:4])
        if key in seen:
            continue
        seen.add(key)
        printed += 1
        print("    ----")
        for b in block[:80]:
            print("    " + b)
    return 0 if printed else 1


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    cmd, args = argv[1], argv[2:]
    if cmd == "texts":
        return texts(args[0], args[1])
    if cmd == "has":
        return has(args[0], args[1], args[2])
    if cmd == "center":
        return center_of(args[0], args[1], args[2], args[3] if len(args) > 3 else "any")
    if cmd == "field":
        return field(args[0], args[1], args[2], int(args[3]))
    if cmd == "thumb":
        return thumb(args[0], args[1])
    if cmd == "crashes":
        return crashes(args[0], *args[1:])
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
