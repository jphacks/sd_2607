"""Generate the JMA area Geohash cover table (importer/jma-xml/areas.json) used by the JMA XML importer.

Input: JMA "予報区等GISデータ" shapefiles (https://www.data.jma.go.jp/developer/gis.html), extracted from
  - 20260226_AreaInformationCity_weather_GIS.zip -> 気象警報等/市町村等（気象警報等）.shp
    (市町村等（気象警報等）, the areas after the late-May 2026 reorganisation; codes as in
    Warning[@type="気象警報・注意報（市町村等）"]/Item/Area/Code, 7 digits)
  - 20240520_AreaForecastLocalE_GIS.zip -> 地震情報／細分区域.shp
    (地震情報／細分区域; codes as in VXSE53 Body/Intensity/Observation/Pref/Area/Code, 3 digits)
Pass the zip basenames as labels so that "source" in the output records the edition.

Output: one-line JSON {"source": {...}, "warningCity": {code: [geohash, ...]}, "quakeArea": {code: [geohash, ...]}}.
Each code's cells cover its polygons buffered by BUFFER_DEG in full: the cover is never trimmed, only coarsened
(complete sibling sets merge into their parent, and an area that needs too many cells is re-covered at a coarser
precision). Records without a code (Northern Territories, unassigned reclaimed land, etc.) are skipped; records
sharing a code are unioned. Cells are sorted ascending and contain no duplicates or parent/child pairs.

Usage: uv run --with shapely --with pyshp python -I area_cover.py \
         <市町村等（気象警報等）.shp> <weather zip label> <地震情報／細分区域.shp> <quake zip label> <out.json>
Requires shapely and pyshp. The output is deterministic for the same input files (and GEOS version).
Takes about 15 minutes, mostly buffering the full-resolution polygons.
"""
import json
import sys

import shapefile
from shapely import make_valid
from shapely.geometry import box, shape
from shapely.ops import unary_union
from shapely.prepared import prep

BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
BUFFER_DEG = 0.01  # ~1 km: absorbs coastline simplification and small islets near the coast
# (table, key field, precisions tried in order, max cells per area)
TABLES = {
    "warningCity": ("regioncode", (5, 4, 3), 32),
    "quakeArea": ("code", (4, 3), 64),
}


def bounds(code):
    lat, lon, even = [-90.0, 90.0], [-180.0, 180.0], True
    for c in code:
        v = BASE32.index(c)
        for bit in range(4, -1, -1):
            r = lon if even else lat
            mid = (r[0] + r[1]) / 2
            if v >> bit & 1:
                r[0] = mid
            else:
                r[1] = mid
            even = not even
    return box(lon[0], lat[0], lon[1], lat[1])


def cover(geom, precision):
    """Cells of [precision] intersecting geom, found by descending from the cells that intersect."""
    pgeom, out, frontier = prep(geom), [], list(BASE32)
    while frontier:
        nxt = []
        for code in frontier:
            if not pgeom.intersects(bounds(code)):
                continue
            if len(code) == precision:
                out.append(code)
            else:
                nxt.extend(code + c for c in BASE32)
        frontier = nxt
    return out


def merge(cells):
    """Replace every complete set of 32 siblings by their parent, repeatedly."""
    cells = set(cells)
    while True:
        parents = {}
        for c in cells:
            if len(c) > 1:
                parents.setdefault(c[:-1], set()).add(c)
        full = [p for p, kids in parents.items() if len(kids) == 32]
        if not full:
            return cells
        for p in full:
            cells -= parents[p]
            cells.add(p)


def load(shp, key):
    """{code: buffered geometry} for every coded record; records sharing a code are unioned."""
    reader = shapefile.Reader(shp, encoding="utf-8")
    fields = [f[0] for f in reader.fields[1:]]
    parts = {}
    for rec, shp_ in zip(reader.iterRecords(), reader.iterShapes()):
        code = dict(zip(fields, rec))[key].strip()
        if code:
            parts.setdefault(code, []).append(make_valid(shape(shp_.__geo_interface__)))
    return {code: unary_union(gs).buffer(BUFFER_DEG) for code, gs in parts.items()}


def build(shp, key, precisions, max_cells):
    table = {}
    for code, geom in sorted(load(shp, key).items()):
        for precision in precisions:
            cells = merge(cover(geom, precision))
            if len(cells) <= max_cells:
                break
        table[code] = sorted(cells)
    return table


def main(weather_shp, weather_label, quake_shp, quake_label, out):
    result = {"source": {"warningCity": weather_label, "quakeArea": quake_label}}
    for name, shp in (("warningCity", weather_shp), ("quakeArea", quake_shp)):
        result[name] = build(shp, *TABLES[name])
    with open(out, "w") as f:
        json.dump(result, f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")


if __name__ == "__main__":
    main(*sys.argv[1:6])
