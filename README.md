# AmiJa website

Static site in the style of the Workbench 1.3 desktop. No build step: open
`index.html` through any web server (GitHub Pages included). Opened straight
from disk (`file://`) it works too, except the floppy sound falls back to plain
`<audio>` elements and the release list may be refused by the browser.

| Path | Contents |
|---|---|
| `index.html` | the page, plus the window contents as `<template>`s (About = the project README) |
| `css/workbench.css` | the Workbench look |
| `js/art.js` | pixel art: icons, gadgets, mouse pointers |
| `js/workbench.js` | desktop, windows, floppy sound, viewers, release list |
| `data/videos.js` | the YouTube playlist - regenerate with `py tools/update-videos.py` |
| `data/screenshots.js` | the screenshots - drop files in `screenshots/`, then `py tools/update-screenshots.py` |
| `sounds/` | three of AmiJa's own synthesised drive sounds (`floppysounds/`, GPL-3.0-or-later), at a third of their level |
| `fonts/` | Topaz (Kickstart 1.x) from rewtnull/amigafonts, GPL with font exception - see `fonts/LICENSE.txt` |

The Download window reads the releases from the GitHub API: GitHub pages refuse
to be shown inside an `<iframe>`.

Try it locally:

    py -m http.server 8000      # from this folder, then open http://localhost:8000
After changing a .css/.js/.wav, raise the `?v=` number in `index.html` (and the sound paths in `js/workbench.js`) so browsers drop their cached copy.
