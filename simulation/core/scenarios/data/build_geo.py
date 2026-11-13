#!/usr/bin/env python3
"""Builds the geography of a scenario from public data.

Sources (downloaded once into --cache, outside the repository):
- Population, households and the 65+ share: 2020 census, 500 m mesh (e-Stat 地域メッシュ統計 T001141).
- Municipal boundaries, place names, schools/community centres/offices, buildings and major roads:
  OpenStreetMap via the Overpass API.
- Elevation and distance to the sea: 地理院 DEM tiles (z13, about 20 m).

Each municipality's cells are scaled to its 2024-01-01 estimate (石川県推計) and by the New Year
factor (帰省客; an assumption — no headcount was published).

Outputs next to this script:
- <scenario>_households.csv  lat,lon,people,elderly,district,elev_m,coast_m
- <scenario>_geo.json        districts, facilities, road graph (contracted, with geometry)

Usage:
    uv run --with numpy --with scipy python3 build_geo.py noto
"""

import argparse
import csv
import io
import json
import math
import random
import re
import sys
import time
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

import numpy as np
from scipy.ndimage import distance_transform_edt

HERE = Path(__file__).parent
UA = "mosimo-simulation/0.1 (disaster information research; https://github.com/jphacks/sd_2607)"
OVERPASS = "https://overpass-api.de/api/interpreter"

MUNICIPALITIES = {"輪島市": "wajima", "珠洲市": "suzu", "能登町": "noto", "穴水町": "anamizu", "志賀町": "shika", "七尾市": "nanao"}
POP_2024 = {"wajima": 21903, "suzu": 11721, "noto": 14277, "anamizu": 7312, "shika": 17239, "nanao": 47198}
NEW_YEAR = 1.1

SCENARIOS = {
    # bbox: south, west, north, east
    "noto": {"bbox": (36.93, 136.64, 37.56, 137.38), "mesh": ["5536", "5537", "5636", "5637"], "zones": None},
    "shelter": {"bbox": (37.380, 137.180, 37.425, 137.265), "mesh": ["5637"], "zones": {"suzu"}},
}


def fetch(url, path, data=None, tries=10, check=None):
    if path.exists() and (path.stat().st_size > 0 or path.suffix == ".csv"):
        return path.read_bytes()
    wait = 15
    for i in range(tries):
        try:
            req = urllib.request.Request(url, data=data, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=240) as r:
                body = r.read()
            if body[:200].lstrip().startswith((b"<?xml", b"<!DOCTYPE", b"<html")) or b"Gateway Timeout" in body[:300]:
                raise RuntimeError("server busy")
            if check and not check(body):
                raise RuntimeError("unexpected response")
            path.write_bytes(body)
            return body
        except urllib.error.HTTPError as e:
            if e.code == 404:
                raise
            print(f"  retry {i + 1} {path.name}: {e}", file=sys.stderr)
        except Exception as e:  # noqa: BLE001
            print(f"  retry {i + 1} {path.name}: {e}", file=sys.stderr)
        time.sleep(wait)
        wait = min(wait * 2, 120)
    raise SystemExit(f"failed to download {url}")


def overpass(cache, name, query):
    # CSV answers may be empty (a tile of sea); JSON answers must parse.
    check = (lambda b: b.lstrip().startswith(b"{")) if "[out:json]" in query else None
    path = cache / name
    if path.exists():
        return path.read_bytes()
    body = fetch(OVERPASS, path, data=urllib.parse.urlencode({"data": query}).encode(), check=check)
    return body


# ---------------------------------------------------------------- census mesh

def mesh_bounds(code):
    p, u, q, v, r, w, m = int(code[0:2]), int(code[2:4]), int(code[4]), int(code[5]), int(code[6]), int(code[7]), int(code[8])
    lat = p / 1.5 + q * (5 / 60) + r * (30 / 3600)
    lon = u + 100 + v * (7.5 / 60) + w * (45 / 3600)
    dlat, dlon = 15 / 3600, 22.5 / 3600
    if m in (3, 4):
        lat += dlat
    if m in (2, 4):
        lon += dlon
    return lat, lon, dlat, dlon


def load_mesh(cache, first_codes):
    cells = []
    for c in first_codes:
        raw = fetch(f"https://www.e-stat.go.jp/gis/statmap-search/data?statsId=T001141&code={c}&downloadType=2", cache / f"mesh_{c}.zip")
        with zipfile.ZipFile(io.BytesIO(raw)) as z:
            text = z.read(next(n for n in z.namelist() if n.endswith(".txt"))).decode("shift_jis", errors="replace")
        rows = list(csv.reader(text.splitlines()))
        head = rows[0]
        i_pop, i_old, i_hh = head.index("T001141001"), head.index("T001141019"), head.index("T001141034")
        num = lambda s: int(s) if s.strip().isdigit() else 0  # "*" is suppressed and counted in a neighbour
        for row in rows[2:]:
            if len(row) <= i_hh or not row[0].isdigit() or num(row[i_pop]) <= 0:
                continue
            lat, lon, dlat, dlon = mesh_bounds(row[0])
            cells.append({"lat": lat, "lon": lon, "dlat": dlat, "dlon": dlon, "pop": num(row[i_pop]),
                          "old": num(row[i_old]), "hh": max(1, num(row[i_hh]))})
    return cells


# ---------------------------------------------------------------- polygons

def rings_of(rel):
    """Outer rings of an OSM multipolygon relation as lists of (lat, lon), joining way segments."""
    segs = [[(p["lat"], p["lon"]) for p in m["geometry"]] for m in rel["members"] if m["type"] == "way" and m.get("role") == "outer" and m.get("geometry")]
    rings = []
    while segs:
        ring = segs.pop(0)
        changed = True
        while changed and ring[0] != ring[-1]:
            changed = False
            for i, s in enumerate(segs):
                if s[0] == ring[-1]:
                    ring += s[1:]
                elif s[-1] == ring[-1]:
                    ring += s[::-1][1:]
                elif s[-1] == ring[0]:
                    ring = s + ring[1:]
                elif s[0] == ring[0]:
                    ring = s[::-1] + ring[1:]
                else:
                    continue
                segs.pop(i)
                changed = True
                break
        rings.append(np.array(ring))
    return rings


def inside(rings, lat, lon):
    hit = False
    for r in rings:
        y, x = r[:, 0], r[:, 1]
        y2, x2 = np.roll(y, -1), np.roll(x, -1)
        cross = ((y > lat) != (y2 > lat)) & (lon < (x2 - x) * (lat - y) / np.where(y2 - y == 0, 1e-12, y2 - y) + x)
        if np.count_nonzero(cross) % 2 == 1:
            hit = not hit
    return hit


# ---------------------------------------------------------------- elevation

Z = 13


def tile_xy(lat, lon, z=Z):
    n = 2 ** z
    x = (lon + 180) / 360 * n
    y = (1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi) / 2 * n
    return x, y


class Dem:
    """Elevation stitched from 地理院 DEM tiles; NaN is sea."""

    def __init__(self, cache, bbox):
        s, w, n, e = bbox
        x0, y0 = (int(v) for v in tile_xy(n, w))
        x1, y1 = (int(v) for v in tile_xy(s, e))
        self.x0, self.y0 = x0, y0
        self.grid = np.full(((y1 - y0 + 1) * 256, (x1 - x0 + 1) * 256), np.nan, dtype=np.float32)
        for ty in range(y0, y1 + 1):
            for tx in range(x0, x1 + 1):
                try:
                    body = fetch(f"https://cyberjapandata.gsi.go.jp/xyz/dem/{Z}/{tx}/{ty}.txt", cache / f"dem_{Z}_{tx}_{ty}.txt", tries=3)
                except Exception:  # noqa: BLE001  — all-sea tiles do not exist
                    continue
                arr = np.array([[np.nan if v == "e" else float(v) for v in line.split(",")] for line in body.decode().splitlines() if line], dtype=np.float32)
                oy, ox = (ty - y0) * 256, (tx - x0) * 256
                self.grid[oy : oy + arr.shape[0], ox : ox + arr.shape[1]] = arr
        self.coast = distance_transform_edt(~np.isnan(self.grid)).astype(np.float32)

    def at(self, lat, lon):
        x, y = tile_xy(lat, lon)
        px, py = int((x - self.x0) * 256), int((y - self.y0) * 256)
        if not (0 <= py < self.grid.shape[0] and 0 <= px < self.grid.shape[1]):
            return float("nan"), 0.0
        mpp = 156543.03 * math.cos(math.radians(lat)) / 2 ** Z
        return float(self.grid[py, px]), float(self.coast[py, px]) * mpp


# ---------------------------------------------------------------- roads

SPEED = {"motorway": 70, "trunk": 50, "primary": 45, "secondary": 40, "tertiary": 35}


def dist_m(a, b):
    return math.hypot((a[0] - b[0]) * 110574, (a[1] - b[1]) * 111320 * math.cos(math.radians(a[0])))


def simplify(pts, tol=25.0):
    if len(pts) < 3:
        return pts
    a, b = pts[0], pts[-1]
    best, idx = 0.0, 0
    for i in range(1, len(pts) - 1):
        p = pts[i]
        ab = dist_m(a, b)
        if ab == 0:
            d = dist_m(a, p)
        else:
            # perpendicular distance in a local flat frame
            ax, ay = 0.0, 0.0
            bx, by = (b[1] - a[1]) * 111320 * math.cos(math.radians(a[0])), (b[0] - a[0]) * 110574
            px, py = (p[1] - a[1]) * 111320 * math.cos(math.radians(a[0])), (p[0] - a[0]) * 110574
            d = abs((bx - ax) * (ay - py) - (ax - px) * (by - ay)) / ab
        if d > best:
            best, idx = d, i
    if best > tol:
        return simplify(pts[: idx + 1], tol)[:-1] + simplify(pts[idx:], tol)
    return [a, b]


def build_roads(ways, bbox):
    s, w, n, e = bbox
    deg = {}
    coords = {}
    for wy in ways:
        # Nodes are identified by position (≈1 m), which also joins ways that share a node.
        wy["nodes"] = ids = [(round(g["lat"], 5), round(g["lon"], 5)) for g in wy["geometry"]]
        for nid in ids:
            coords[nid] = nid
        for i, nid in enumerate(ids):
            deg[nid] = deg.get(nid, 0) + (1 if i in (0, len(ids) - 1) else 2)
            if i not in (0, len(ids) - 1) and ids.count(nid) > 1:
                deg[nid] += 1
    junction = {nid for nid, d in deg.items() if d != 2}
    edges = []
    for wy in ways:
        ids = wy["nodes"]
        tags = wy.get("tags", {})
        cls = tags.get("highway", "tertiary").replace("_link", "")
        name = tags.get("name") or (f"国道{tags['ref']}号" if tags.get("ref") and cls in ("trunk", "primary") else tags.get("ref", ""))
        start = 0
        for i in range(1, len(ids)):
            if ids[i] in junction or i == len(ids) - 1:
                seg = [coords[x] for x in ids[start : i + 1]]
                length = sum(dist_m(seg[k], seg[k + 1]) for k in range(len(seg) - 1))
                if length > 0:
                    edges.append({"a": ids[start], "b": ids[i], "name": name, "ref": tags.get("ref", ""), "cls": cls,
                                  "len": round(length), "geom": simplify(seg)})
                start = i
    # Largest connected component only.
    adj = {}
    for k, ed in enumerate(edges):
        adj.setdefault(ed["a"], []).append(k)
        adj.setdefault(ed["b"], []).append(k)
    seen, best = set(), set()
    for start in adj:
        if start in seen:
            continue
        comp, stack = set(), [start]
        while stack:
            x = stack.pop()
            if x in comp:
                continue
            comp.add(x)
            for k in adj[x]:
                stack.extend((edges[k]["a"], edges[k]["b"]))
        seen |= comp
        if len(comp) > len(best):
            best = comp
    edges = [ed for ed in edges if ed["a"] in best]
    used = sorted({ed["a"] for ed in edges} | {ed["b"] for ed in edges})
    index = {nid: i for i, nid in enumerate(used)}
    nodes = [[round(coords[nid][0], 6), round(coords[nid][1], 6)] for nid in used]
    out = []
    for ed in edges:
        out.append({"a": index[ed["a"]], "b": index[ed["b"]], "name": ed["name"], "ref": ed["ref"], "cls": ed["cls"],
                    "speed": SPEED.get(ed["cls"], 35), "len": ed["len"],
                    "geom": [[round(p[0], 5), round(p[1], 5)] for p in ed["geom"]]})
    print(f"roads: {len(nodes)} nodes, {len(out)} edges", file=sys.stderr)
    return nodes, out


# ---------------------------------------------------------------- assembly

def district_key(name):
    m = re.match(r"^(.+?町)", name)
    return m.group(1) if m else name


def build(scenario, cache):
    cfg = SCENARIOS[scenario]
    bbox = cfg["bbox"]
    s, w, n, e = bbox
    rng = random.Random(20240101)
    big = SCENARIOS["noto"]["bbox"]
    admin = json.loads(overpass(cache, "admin.json",
        '[out:json][timeout:180];area["name"="石川県"]["admin_level"="4"]->.a;rel(area.a)["admin_level"="7"]'
        '["name"~"^(輪島市|珠洲市|能登町|穴水町|志賀町|七尾市)$"];out geom;'))
    polys = {MUNICIPALITIES[r["tags"]["name"]]: rings_of(r) for r in admin["elements"]}
    zone_of = lambda lat, lon: next((z for z, rings in polys.items() if inside(rings, lat, lon)), None)
    places = []
    for line in overpass(cache, "place.tsv",
            f'[out:csv(name,place,::lat,::lon;true;"\\t")][timeout:120];node["place"~"town|village|hamlet|suburb|quarter|neighbourhood|locality"]{big};out;').decode().splitlines()[1:]:
        p = line.split("\t")
        if len(p) == 4 and p[0]:
            places.append((p[0], float(p[2]), float(p[3])))
    facilities = []
    for line in overpass(cache, "fac.tsv",
            f'[out:csv(name,amenity,::lat,::lon;true;"\\t")][timeout:120];(nwr["amenity"~"school|townhall|fire_station|community_centre|hospital|police"]{big};);out center;').decode().splitlines()[1:]:
        p = line.split("\t")
        # A margin keeps offices just outside a small area (the city hall for the shelter scenario).
        if len(p) == 4 and p[0] and s - 0.03 <= float(p[2]) <= n + 0.03 and w - 0.03 <= float(p[3]) <= e + 0.03:
            facilities.append({"name": p[0], "amenity": p[1], "lat": float(p[2]), "lon": float(p[3])})
    for f in facilities:
        f["zone"] = zone_of(f["lat"], f["lon"])
    facilities = [f for f in facilities if f["zone"]]

    # Census cells → municipality (polygon) → district (nearest place group in the same municipality).
    cells = []
    for c in load_mesh(cache, cfg["mesh"]):
        clat, clon = c["lat"] + c["dlat"] / 2, c["lon"] + c["dlon"] / 2
        if not (s <= clat <= n and w <= clon <= e):
            continue
        z = zone_of(clat, clon)
        if not z or (cfg["zones"] and z not in cfg["zones"]):
            continue
        c["zone"], c["clat"], c["clon"] = z, clat, clon
        cells.append(c)
    zone_places = {}
    for name, plat, plon in places:
        z = zone_of(plat, plon)
        if z:
            zone_places.setdefault(z, []).append((district_key(name), plat, plon))
    for c in cells:
        cand = zone_places.get(c["zone"], [])
        best = min(cand, key=lambda p: (p[1] - c["clat"]) ** 2 + ((p[2] - c["clon"]) * 0.8) ** 2) if cand else (c["zone"], c["clat"], c["clon"])
        c["district"] = f"{c['zone']}:{best[0]}"
    by_zone = {}
    for c in cells:
        by_zone[c["zone"]] = by_zone.get(c["zone"], 0) + c["pop"]
    # 2020 census municipal totals, to scale partial areas consistently.
    census_2020 = {"wajima": 24608, "suzu": 12929, "noto": 15687, "anamizu": 7890, "shika": 18630, "nanao": 50300}
    for c in cells:
        c["scale"] = POP_2024[c["zone"]] / census_2020[c["zone"]] * NEW_YEAR
    print({z: (v, census_2020[z]) for z, v in by_zone.items()}, file=sys.stderr)

    # Buildings, in tiles.
    bld = []
    step = 0.1
    lat = s
    while lat < n - 1e-9:
        lon = w
        while lon < e - 1e-9:
            box = (round(lat, 3), round(lon, 3), round(min(lat + step, n), 3), round(min(lon + step, e), 3))
            # Skip tiles without people (sea, other municipalities).
            if not any(box[0] <= c["clat"] <= box[2] and box[1] <= c["clon"] <= box[3] for c in cells):
                lon += step
                continue
            body = overpass(cache, f"bld_{box[0]}_{box[1]}_{box[2]}_{box[3]}.csv",
                            f'[out:csv(::lat,::lon;false)][timeout:170];way["building"]{box};out center;')
            for line in body.decode().splitlines():
                p = line.split("\t")
                if len(p) == 2:
                    try:
                        bld.append((float(p[0]), float(p[1])))
                    except ValueError:
                        pass
            lon += step
        lat += step
    bld = np.array(bld) if bld else np.zeros((0, 2))
    print(f"buildings: {len(bld)}", file=sys.stderr)
    dem = Dem(cache, bbox)

    households = []
    for c in cells:
        sel = (bld[:, 0] >= c["lat"]) & (bld[:, 0] < c["lat"] + c["dlat"]) & (bld[:, 1] >= c["lon"]) & (bld[:, 1] < c["lon"] + c["dlon"]) if len(bld) else np.zeros(0, bool)
        here = bld[sel] if len(bld) else bld
        people = round(c["pop"] * c["scale"])
        size = max(1.0, c["pop"] / c["hh"])
        old = c["old"] / c["pop"]
        while people > 0:
            p = max(1, min(people, round(rng.gauss(size, 0.9))))
            people -= p
            elev = float("nan")
            for _ in range(20):
                if len(here):
                    hl, hn = here[rng.randrange(len(here))]
                    hl += rng.uniform(-0.00003, 0.00003)
                    hn += rng.uniform(-0.00004, 0.00004)
                else:
                    hl, hn = c["lat"] + rng.random() * c["dlat"], c["lon"] + rng.random() * c["dlon"]
                elev, coast = dem.at(hl, hn)
                if not math.isnan(elev):
                    break
            if math.isnan(elev):
                elev, coast = 1.0, 0.0
            households.append((round(hl, 6), round(hn, 6), p, round(old, 3), c["district"], round(elev, 1), int(coast)))

    # Districts: population-weighted centres.
    dist = {}
    for h in households:
        d = dist.setdefault(h[4], {"pop": 0, "lat": 0.0, "lon": 0.0, "old": 0.0, "low": 0})
        d["pop"] += h[2]
        d["lat"] += h[0] * h[2]
        d["lon"] += h[1] * h[2]
        d["old"] += h[3] * h[2]
    districts = []
    for key, d in sorted(dist.items()):
        zone, name = key.split(":", 1)
        members = [h for h in households if h[4] == key]
        clat, clon = d["lat"] / d["pop"], d["lon"] / d["pop"]
        r = max(150, max(dist_m((clat, clon), (h[0], h[1])) for h in members))
        districts.append({"id": key, "name": name, "zone": zone, "lat": round(clat, 6), "lon": round(clon, 6),
                          "pop": d["pop"], "elderly": round(d["old"] / d["pop"], 3), "radius_m": round(min(r, 4000))})

    ways = json.loads(overpass(cache, "roads.json",
        f'[out:json][timeout:180];way["highway"~"^(trunk|primary|secondary|tertiary|trunk_link|primary_link|secondary_link|tertiary_link|motorway|motorway_link)$"]{big};out geom tags;'))["elements"]
    ways = [wy for wy in ways if any(s - 0.01 <= g["lat"] <= n + 0.01 and w - 0.01 <= g["lon"] <= e + 0.01 for g in wy["geometry"])]
    nodes, edges = build_roads(ways, bbox)

    # Hills near each municipality's largest district where a distant tower could be reached.
    hills = []
    for z in sorted({d["zone"] for d in districts}):
        for d in sorted([d for d in districts if d["zone"] == z], key=lambda d: -d["pop"])[:2]:
            best = None
            for _ in range(400):
                hl = d["lat"] + rng.uniform(-0.025, 0.025)
                hn = d["lon"] + rng.uniform(-0.03, 0.03)
                el, _ = dem.at(hl, hn)
                if not math.isnan(el) and (best is None or el > best[2]):
                    best = (hl, hn, el)
            if best:
                hills.append({"zone": z, "near": d["name"], "lat": round(best[0], 5), "lon": round(best[1], 5), "elev_m": round(best[2])})

    geo = {"bbox": bbox, "districts": districts, "facilities": facilities, "nodes": nodes, "edges": edges, "hills": hills,
           "places": [{"name": p[0], "lat": p[1], "lon": p[2]} for p in places if s <= p[1] <= n and w <= p[2] <= e]}
    (HERE / f"{scenario}_geo.json").write_text(json.dumps(geo, ensure_ascii=False, separators=(",", ":")))
    with (HERE / f"{scenario}_households.csv").open("w", newline="") as f:
        wr = csv.writer(f)
        wr.writerow(["lat", "lon", "people", "elderly", "district", "elev_m", "coast_m"])
        wr.writerows(households)
    total = sum(h[2] for h in households)
    print(f"{scenario}: {len(districts)} districts, {len(households)} households, {total} people, {len(facilities)} facilities", file=sys.stderr)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("scenario", choices=list(SCENARIOS))
    ap.add_argument("--cache", type=Path, default=Path.home() / ".cache" / "mosimo-sim")
    a = ap.parse_args()
    a.cache.mkdir(parents=True, exist_ok=True)
    build(a.scenario, a.cache)


if __name__ == "__main__":
    main()
