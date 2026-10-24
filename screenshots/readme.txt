Screenshots for the website
===========================

Drop the images here (.png, .jpg, .gif or .webp), then run, from website/:

    py tools/update-screenshots.py

It rewrites data/screenshots.js with one entry per file, in file-name order,
using the file name as the caption ("Turrican_2.png" -> "Turrican 2"). Edit the
captions in data/screenshots.js afterwards if you want, but note that running
the script again overwrites them.

A static site cannot list a folder by itself, which is why the list exists.
