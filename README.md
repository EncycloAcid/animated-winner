# Clearway Map

Just the airport map: an interactive, self-contained web page (`index.html`) that opens in any browser.

- **Airports:** Cleveland Hopkins (CLE), Hartsfield–Jackson Atlanta (ATL) and Dallas/Fort Worth (DFW). Tap the airport name to switch.
- **Gate numbers:** every gate, coloured by concourse. Where numbers would overlap they shrink to dots and come back as you zoom in. Tap a gate to zoom in on it.
- **Concourses:** the badge buttons (or the concourse menu at larger airports) zoom to a concourse.
- **Floors:** the **U / L** switch flips between the upper and lower levels.
- **Moving around:** drag, flick, scroll, pinch or double-tap to zoom, twist with two fingers to turn the map (the compass puts north back up), and the frame button shows the whole airport.

The map is traced from [OpenStreetMap](https://www.openstreetmap.org/copyright) and is © OpenStreetMap contributors, available under the Open Database License. Gate assignments change, so always check your boarding pass. This is not an official airport map.

## Project layout

| Path | What it is |
|---|---|
| `src/shell.html` | Page markup, styles and the script prelude |
| `src/map.js` | The map (works for any airport) |
| `airports/<code>/airport.js` | One airport's map pack: bounds, concourses, gate numbers and positions, labels, floors |
| `airports/<code>/basemap.js` | That airport's OpenStreetMap layers |
| `tools/build.py` | Builds `index.html` from all of the above |
| `tools/build_basemap.py` | Regenerates an airport's base map from OpenStreetMap |

Edit the files in `src/` and `airports/`, then run `python3 tools/build.py`. Don't edit `index.html` by hand; it's generated.
