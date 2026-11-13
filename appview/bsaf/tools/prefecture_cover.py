"""Generate the prefecture Geohash cover table used by dev.jisaku.mosimo.public.bsaf.hazard.

Input: Natural Earth 1:10m Admin 1 (public domain), the version given by --ne-version.
Output: JSON {target: [geohash, ...]} whose union covers every prefecture polygon buffered by
BUFFER_DEG, plus the remote islands listed in EXTRA_POINTS (absent at 1:10m).

Usage: python -I prefecture_cover.py <ne_10m_admin_1_states_provinces.shp> <out.json>
Requires shapely and pyshp. The output is deterministic for the same input file.
"""
import json
import sys

import shapefile
from shapely.geometry import MultiPolygon, Point, box, shape
from shapely.ops import unary_union

BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
BUFFER_DEG = 0.05  # ~5 km: absorbs 1:10m simplification and small islets near the coast
MAX_CELLS = 64
TARGETS = {
    "01": "jp-hokkaido", "02": "jp-aomori", "03": "jp-iwate", "04": "jp-miyagi", "05": "jp-akita", "06": "jp-yamagata",
    "07": "jp-fukushima", "08": "jp-ibaraki", "09": "jp-tochigi", "10": "jp-gunma", "11": "jp-saitama", "12": "jp-chiba",
    "13": "jp-tokyo", "14": "jp-kanagawa", "15": "jp-niigata", "16": "jp-toyama", "17": "jp-ishikawa", "18": "jp-fukui",
    "19": "jp-yamanashi", "20": "jp-nagano", "21": "jp-gifu", "22": "jp-shizuoka", "23": "jp-aichi", "24": "jp-mie",
    "25": "jp-shiga", "26": "jp-kyoto", "27": "jp-osaka", "28": "jp-hyogo", "29": "jp-nara", "30": "jp-wakayama",
    "31": "jp-tottori", "32": "jp-shimane", "33": "jp-okayama", "34": "jp-hiroshima", "35": "jp-yamaguchi", "36": "jp-tokushima",
    "37": "jp-kagawa", "38": "jp-ehime", "39": "jp-kochi", "40": "jp-fukuoka", "41": "jp-saga", "42": "jp-nagasaki",
    "43": "jp-kumamoto", "44": "jp-oita", "45": "jp-miyazaki", "46": "jp-kagoshima", "47": "jp-okinawa",
}
# Inhabited or administered islands that 1:10m omits: (prefecture code, lat, lon).
EXTRA_POINTS = [
    ("13", 20.4253, 136.0814),   # Okinotorishima
    ("13", 24.2867, 153.9806),   # Minamitorishima
]


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
    out, frontier = [], list(BASE32)
    while frontier:
        nxt = []
        for code in frontier:
            if not bounds(code).intersects(geom):
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


def main(shp, out):
    reader = shapefile.Reader(shp)
    fields = [f[0] for f in reader.fields[1:]]
    geoms = {}
    for rec, shp_ in zip(reader.iterRecords(), reader.iterShapes()):
        d = dict(zip(fields, rec))
        if d["adm0_a3"] == "JPN" and d["iso_3166_2"].startswith("JP-"):
            geoms[d["iso_3166_2"][3:]] = shape(shp_.__geo_interface__)
    assert sorted(geoms) == sorted(TARGETS), sorted(geoms)
    # Natural Earth 5.1.1 files the Amami Islands (Kagoshima) under Okinawa; move every JP-47 part north of 27N, east of
    # 128.3E (Yoron and beyond) to JP-46. Okinawa's own northern islands (Iheya, Izena) lie west of 128E.
    amami = [g for g in geoms["47"].geoms if g.centroid.y > 27.0 and g.centroid.x > 128.3]
    assert amami, "Amami parts not found; re-check the input version"
    geoms["47"] = MultiPolygon([g for g in geoms["47"].geoms if g not in amami])
    geoms["46"] = unary_union([geoms["46"], *amami])
    table = {}
    for code, target in sorted(TARGETS.items()):
        parts = [geoms[code].buffer(BUFFER_DEG)]
        parts += [Point(lon, lat).buffer(BUFFER_DEG) for c, lat, lon in EXTRA_POINTS if c == code]
        geom = unary_union(parts)
        for precision in (4, 3, 2):
            cells = merge(cover(geom, precision))
            if len(cells) <= MAX_CELLS:
                break
        table[target] = sorted(cells, key=lambda c: (len(c), c))
    with open(out, "w") as f:
        json.dump(table, f, indent=1)
        f.write("\n")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
