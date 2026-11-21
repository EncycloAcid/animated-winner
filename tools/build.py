#!/usr/bin/env python3
"""Assemble index.html from the map sources and every airport pack.

  src/shell.html               page markup, styles and the script prelude
  airports/<code>/basemap.js   OpenStreetMap layers (tools/build_basemap.py)
  airports/<code>/airport.js   that airport's map pack: gate numbers, concourses, labels
  src/map.js                   the map (airport-independent)

Usage: python3 tools/build.py   (run from anywhere)
"""
import glob, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
read = lambda p: open(os.path.join(ROOT, p), encoding='utf-8').read()

parts = [read('src/shell.html')]
for d in sorted(glob.glob(os.path.join(ROOT, 'airports', '*', ''))):
    code = os.path.basename(os.path.dirname(d))
    parts.append(f'/* ---- airport: {code} (map data © OpenStreetMap contributors, ODbL) ---- */\n')
    parts.append(read(f'airports/{code}/basemap.js'))
    parts.append(read(f'airports/{code}/airport.js'))
parts.append(read('src/map.js'))
open(os.path.join(ROOT, 'index.html'), 'w', encoding='utf-8').write(''.join(parts))
print('index.html', sum(len(p) for p in parts) // 1024, 'KB from', len(parts), 'parts')
