# Clearway Tower

An air traffic control game played on real airport maps: Cleveland Hopkins (CLE), Hartsfield–Jackson Atlanta (ATL) and Dallas/Fort Worth (DFW). It's one self-contained web page, `index.html`, that opens in any browser.

## How to play

1. **Arrivals** fly in and hold until you pick a runway for them. Watch their fuel.
2. When a plane is off the runway, **tap a free gate number** on the map (free gates light up), or send it to the nearest free gate.
3. After boarding, **push it back** to a runway. It taxis out and waits short of the runway.
4. **Clear it for takeoff**, but only when the runway is empty.

- A landing plane that finds its runway in use goes around (−50 points) and burns more fuel.
- A plane that runs out of fuel diverts, and clearing a takeoff onto a busy runway is a runway incursion. Each is a strike, and three strikes end your shift.
- Landings score 50, reaching the gate 100, and each departure 100, plus 50 if it wasn't kept waiting.
- Traffic picks up the longer your shift lasts. Use the panel to see who's waiting on you, most urgent first, and the **1× / 2×** and pause buttons to set the pace.
- Tap the airport name to switch airports. Each airport keeps its own best score.

Planes taxi on the airport's real taxiways and runways from OpenStreetMap, park at the real gates, and use the aircraft silhouette from the map.

## The map

The map is traced from [OpenStreetMap](https://www.openstreetmap.org/copyright) and is © OpenStreetMap contributors, available under the Open Database License. This is a game, not an official airport map or real procedures.

## Project layout

| Path | What it is |
|---|---|
| `src/shell.html` | Page markup, styles and the script prelude |
| `src/map.js` | The map: base map, gate numbers, zoom/pan/rotate, airport picker (works for any airport) |
| `src/sim.js` | The tower game: ground network, runways, aircraft, commands, scoring |
| `airports/<code>/airport.js` | One airport's pack: bounds, concourses, gate numbers, parking stands, labels |
| `airports/<code>/basemap.js` | That airport's OpenStreetMap layers, including runways and taxiways |
| `tools/build.py` | Builds `index.html` from all of the above |
| `tools/build_basemap.py` | Regenerates an airport's base map from OpenStreetMap |

Edit the files in `src/` and `airports/`, then run `python3 tools/build.py`. Don't edit `index.html` by hand; it's generated.
