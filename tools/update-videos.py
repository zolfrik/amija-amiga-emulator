"""Rewrites data/videos.js from the AmiJa YouTube playlist.

Run from anywhere:  py tools/update-videos.py [playlist-id]

It reads the public playlist page (no API key needed) and keeps the playlist
order. If YouTube changes its page layout and nothing is found, the existing
data/videos.js is left untouched.
"""
import json
import pathlib
import re
import sys
import urllib.request

SITE = pathlib.Path(__file__).resolve().parent.parent
PLAYLIST = sys.argv[1] if len(sys.argv) > 1 else "PLdkiRBBNki2g"

req = urllib.request.Request(
    f"https://www.youtube.com/playlist?list={PLAYLIST}",
    headers={
        "Accept-Language": "en",
        "Cookie": "SOCS=CAI; CONSENT=YES+",
        "User-Agent": "Mozilla/5.0",
    },
)
html = urllib.request.urlopen(req, timeout=30).read().decode("utf-8")
m = re.search(r"var ytInitialData = (\{.*?\});</script>", html)
if not m:
    sys.exit("playlist data not found in the page; data/videos.js left as it is")
data = json.loads(m.group(1))

videos, seen = [], set()


def walk(node):
    if isinstance(node, dict):
        if "playlistVideoRenderer" in node:
            r = node["playlistVideoRenderer"]
            add(r.get("videoId"), "".join(x["text"] for x in r["title"]["runs"]),
                r.get("lengthText", {}).get("simpleText", ""))
        if "lockupViewModel" in node:
            l = node["lockupViewModel"]
            md = l.get("metadata", {}).get("lockupMetadataViewModel", {})
            dur = re.findall(r'"text": "(\d+:\d\d(?::\d\d)?)"', json.dumps(l))
            add(l.get("contentId"), md.get("title", {}).get("content", ""), dur[0] if dur else "")
        for v in node.values():
            walk(v)
    elif isinstance(node, list):
        for v in node:
            walk(v)


def add(vid, title, duration):
    if vid and vid not in seen and title:
        seen.add(vid)
        videos.append({"id": vid, "title": title, "duration": duration})


walk(data)
if not videos:
    sys.exit("no video found; data/videos.js left as it is")

body = "\n".join("  " + json.dumps(v, ensure_ascii=False) + "," for v in videos)
(SITE / "data" / "videos.js").write_text(
    "/*\n"
    " * Videos shown in the \"Video\" window, in playlist order.\n"
    " * Regenerate from the YouTube playlist with:  py tools/update-videos.py\n"
    " */\n"
    f"window.AMIJA_PLAYLIST = {json.dumps(PLAYLIST)};\n"
    "window.AMIJA_VIDEOS = [\n" + body + "\n];\n",
    encoding="utf-8",
    newline="\n",
)
print(f"{len(videos)} video(s) written to data/videos.js")
