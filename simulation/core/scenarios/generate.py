#!/usr/bin/env python3
"""Generates the built-in scenarios (shelter.json, kawauchi.json, noto.json).

Numbers come from published records; each block cites its source. Values marked [推定] are
interpolations or assumptions, and coordinates are approximate (±1–3 km for Noto settlements).
Run: python3 generate.py  (writes the JSON files next to this script)
"""

import json
import sys
import math
import random
from pathlib import Path

HERE = Path(__file__).parent

# ---------------------------------------------------------------------------
# Shared sources
# ---------------------------------------------------------------------------

SRC = {
    "naikaku": ("内閣府 令和6年能登半島地震に係る被害状況等について（各報）", "https://www.bousai.go.jp/updates/r60101notojishin/"),
    "ishikawa": ("石川県 令和6年能登半島地震による被害等の状況について（各報）", "https://www.pref.ishikawa.lg.jp/saigai/202401jishin-taioutop.html"),
    "kensho": ("石川県 令和6年能登半島地震 検証報告書（2025）", "https://www.pref.ishikawa.lg.jp/bousai/bousai_g/kenshou.html"),
    "hakusho": ("総務省 情報通信白書 令和6年版（能登半島地震と通信）", "https://www.soumu.go.jp/johotsusintokei/whitepaper/r06.html"),
    "soumu": ("総務省 能登半島地震における通信インフラの被害・復旧（有識者会議資料）", "https://www.soumu.go.jp/main_sosiki/kenkyu/saigai_tsushin/"),
    "jma": ("気象庁 令和6年能登半島地震 津波警報の伝達に関する調査", "https://www.jma.go.jp/jma/kishou/know/tsunami/"),
    "nict": ("NICT X上の救助要請投稿の分析（発災24時間、1,091件中104件が偽）", "https://www.nict.go.jp/"),
    "kddi": ("KDDI 能登半島地震における通信の復旧（Starlink・船上基地局）", "https://newsroom.kddi.com/"),
    "docomo": ("NTTドコモ・KDDI 船上基地局「きずな」運用（1/6〜1/11 町野、1/13〜1/18 大沢）", "https://www.docomo.ne.jp/info/news_release/"),
    "sb": ("ソフトバンク 有線給電ドローン基地局（輪島市門前町、1/6〜）", "https://www.softbank.jp/corp/news/"),
    "au_sd": ("KDDI au Starlink Direct（SMS 2025-04-10〜、データ 2025-08-28〜）", "https://www.au.com/mobile/area/starlink-direct/"),
    "hosei": ("内閣府 避難所運営実態調査（珠洲市宝立小中学校 ほか）", "https://www.bousai.go.jp/taisaku/hinanjo/"),
    "fdma": ("消防庁 令和6年能登半島地震 消防の活動", "https://www.fdma.go.jp/disaster/info/"),
    "mlit": ("国土交通省 能登半島地震 道路啓開（くしの歯）", "https://www.mlit.go.jp/road/"),
    "tohoku2011": ("東北大学 東日本大震災 東北大学の記録（安否確認 3/30完了ほか）", "https://www.tohoku.ac.jp/japanese/disaster/"),
    "mic2011": ("総務省 東日本大震災における通信の被災・規制状況（音声規制 ドコモ90%・au95%・SB70%）", "https://www.soumu.go.jp/menu_seisaku/ictseisaku/net_anzen/hijyo/"),
    "sendai_survey": ("仙台市民アンケート（停電平均4.3日、当日キャリアメール52%）", "https://www.city.sendai.jp/"),
    "darmstadt": ("Darmstadt field test of smartphone ad hoc networks (125 phones, median link 30 m)", "https://www.informatik.tu-darmstadt.de/"),
    "census": ("総務省統計局 令和2年国勢調査 地域メッシュ統計（500mメッシュ、e-Stat）", "https://www.e-stat.go.jp/gis/statmap-search?type=1"),
    "osm": ("OpenStreetMap contributors（建物・道路・地名・施設、ODbL）", "https://www.openstreetmap.org/copyright"),
    "gsi": ("国土地理院 地理院タイル 標高タイル（DEM）・淡色地図", "https://maps.gsi.go.jp/development/ichiran.html"),
    "kobe": ("阪神・淡路大震災 救出時間と生存率（1日目約8割→3日目約2割）", "https://www.bousai.go.jp/kyoiku/kyokun/hanshin_awaji/"),
}


def sources(*keys):
    return [{"label": SRC[k][0], "url": SRC[k][1]} for k in keys]


def curve(table, hours=(0, 1, 3, 6, 12, 24, 48, 72, 120, 168, 240, 336, 504, 720), invert=False):
    vals = [1 - v if invert else v for v in table]
    return [[h, round(v, 3)] for h, v in zip(hours, vals)]


CARRIERS = [
    # Shares in Hokuriku are not published; ドコモ0.45/KDDI0.30/SB0.20/楽天0.05 is an assumption.
    # Outage factors follow each carrier's worst area-outage ratio (ドコモ70%, KDDI54%, SB45%).
    {"id": "docomo", "name": "ドコモ", "share": 0.45, "outage_factor": 1.15},
    {"id": "au", "name": "au", "share": 0.30, "outage_factor": 1.0, "direct_to_cell": True},
    {"id": "softbank", "name": "ソフトバンク", "share": 0.20, "outage_factor": 0.85},
    {"id": "rakuten", "name": "楽天", "share": 0.05, "roams_on": "au", "outage_factor": 1.0},
]

# ---------------------------------------------------------------------------
# Noto: per-municipality availability tables (研究メモ §8, all [推定] interpolations
# anchored on 内閣府 total stopped-tower counts and per-municipality clearance dates).
# Columns: 0h 1h 3h 6h 12h 24h 48h 72h 5d 7d 10d 14d 21d 30d
# ---------------------------------------------------------------------------

CELL_UP = {  # SMS/data physical availability
    "wajima": [0.50, 0.48, 0.45, 0.42, 0.35, 0.25, 0.15, 0.15, 0.25, 0.35, 0.45, 0.60, 0.80, 0.90],
    "suzu": [0.45, 0.42, 0.40, 0.37, 0.30, 0.20, 0.10, 0.10, 0.20, 0.30, 0.40, 0.55, 0.80, 0.90],
    "noto": [0.60, 0.58, 0.55, 0.52, 0.45, 0.35, 0.25, 0.25, 0.40, 0.55, 0.80, 0.90, 0.97, 0.99],
    "anamizu": [0.65, 0.63, 0.60, 0.57, 0.50, 0.40, 0.30, 0.30, 0.45, 0.60, 0.85, 0.92, 0.98, 0.99],
    "shika": [0.75, 0.73, 0.70, 0.68, 0.62, 0.55, 0.50, 0.60, 0.90, 0.97, 0.99, 1.00, 1.00, 1.00],
    "nanao": [0.88, 0.88, 0.86, 0.84, 0.80, 0.75, 0.70, 0.75, 0.85, 0.90, 0.93, 0.97, 1.00, 1.00],
}
FIXED_UP = {
    "wajima": [0.10, 0.10, 0.10, 0.10, 0.10, 0.08, 0.05, 0.05, 0.05, 0.08, 0.12, 0.15, 0.25, 0.35],
    "suzu": [0.03, 0.03, 0.03, 0.03, 0.03, 0.03, 0.02, 0.02, 0.03, 0.05, 0.08, 0.12, 0.20, 0.30],
    "noto": [0.20, 0.20, 0.20, 0.20, 0.20, 0.18, 0.15, 0.15, 0.25, 0.40, 0.55, 0.60, 0.70, 0.75],
    "anamizu": [0.10, 0.10, 0.10, 0.10, 0.10, 0.10, 0.10, 0.15, 0.30, 0.45, 0.60, 0.70, 0.80, 0.85],
    "shika": [0.30, 0.30, 0.30, 0.30, 0.30, 0.30, 0.30, 0.60, 0.80, 0.85, 0.90, 0.92, 0.95, 0.97],
    "nanao": [0.70, 0.70, 0.70, 0.70, 0.75, 0.75, 0.80, 0.85, 0.88, 0.90, 0.92, 0.94, 0.96, 0.98],
}
POWER_UP = {
    "wajima": [0.20, 0.20, 0.20, 0.20, 0.25, 0.30, 0.25, 0.25, 0.25, 0.40, 0.55, 0.62, 0.78, 0.88],
    "suzu": [0.03, 0.03, 0.03, 0.03, 0.03, 0.05, 0.05, 0.05, 0.08, 0.15, 0.30, 0.55, 0.70, 0.80],
    "noto": [0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.25, 0.55, 0.80, 0.80, 0.82, 0.97, 0.98],
    "anamizu": [0.05, 0.05, 0.05, 0.05, 0.05, 0.05, 0.05, 0.25, 0.45, 0.80, 0.84, 0.95, 0.98, 0.99],
    "shika": [0.85, 0.85, 0.85, 0.88, 0.90, 0.92, 0.95, 0.97, 0.99, 0.99, 0.99, 1.00, 1.00, 1.00],
    "nanao": [0.80, 0.80, 0.82, 0.84, 0.85, 0.85, 0.85, 0.99, 0.99, 1.00, 1.00, 1.00, 1.00, 1.00],
}
# Water outage: 輪島・珠洲 nearly all households until March; others restored Feb–May.
WATER_OUT = {
    "wajima": [[0, 0.95], [720, 0.9]],
    "suzu": [[0, 0.98], [720, 0.95]],
    "noto": [[0, 0.9], [336, 0.7], [720, 0.5]],
    "anamizu": [[0, 0.95], [336, 0.8], [720, 0.6]],
    "shika": [[0, 0.8], [168, 0.5], [720, 0.2]],
    "nanao": [[0, 0.8], [168, 0.6], [720, 0.4]],
}
# 防災行政無線: 珠洲 lost outdoor speakers to the tsunami and a mountain relay stopped.
MUSEN_OK = {"wajima": 0.6, "suzu": 0.35, "noto": 0.6, "anamizu": 0.6, "shika": 0.75, "nanao": 0.8}
ZONE_NAMES = {"wajima": "輪島市", "suzu": "珠洲市", "noto": "能登町", "anamizu": "穴水町", "shika": "志賀町", "nanao": "七尾市"}


def zone(zid):
    return {
        "id": zid,
        "name": ZONE_NAMES[zid],
        "power_out": curve(POWER_UP[zid], invert=True),
        "cell_out": curve(CELL_UP[zid], invert=True),
        "fixed_out": curve(FIXED_UP[zid], invert=True),
        "water_out": WATER_OUT[zid],
        "musen_ok": MUSEN_OK[zid],
    }


# Voice passes the restriction less often in the first hours; data a little less in the first 3 h.
# The actual restriction ratio on 1/1 was not published (研究メモ §4) — [推定], tune by sensitivity.
NOTO_CONGESTION = {
    "voice_success": [[0, 0.5], [1, 0.55], [3, 0.75], [6, 0.95], [12, 1.0]],
    "data_success": [[0, 0.85], [3, 0.85], [3.1, 1.0]],
    "fire_lines_per_h": 30,
}

DATA = HERE / "data"

# Per municipality [推定 from records]:
# collapse  = share of households whose house collapsed (全壊 6,168棟; concentrated in 輪島・珠洲).
# shelter   = peak share in shelters (Ishikawa reports: 輪島 12,834 / 珠洲 7,122 / 能登町 5,505 / 穴水 3,991 / 志賀 2,650 / 七尾 ~3,700).
# tsunami   = run-up height reaching houses (m; 輪島・門前 uplifted 1.5–4 m so nothing flooded), height people flee from,
#             and first arrival (h; 珠洲 within minutes, 七尾港 16:37, 志賀 ~1.5 h).
ZONE_PARAMS = {
    "wajima": {"collapse": 0.25, "shelter": 0.59, "height": 0.0, "flee": 8.0, "arrival": None},
    "suzu": {"collapse": 0.33, "shelter": 0.61, "height": 4.0, "flee": 10.0, "arrival": 0.05},
    "noto": {"collapse": 0.07, "shelter": 0.39, "height": 4.0, "flee": 10.0, "arrival": 0.15},
    "anamizu": {"collapse": 0.10, "shelter": 0.55, "height": 1.5, "flee": 6.0, "arrival": 0.4},
    "shika": {"collapse": 0.04, "shelter": 0.15, "height": 2.5, "flee": 8.0, "arrival": 1.5},
    "nanao": {"collapse": 0.02, "shelter": 0.08, "height": 1.5, "flee": 5.0, "arrival": 0.45},
}
# Districts with the heaviest reported damage.
HARD_HIT = ("河井町", "鳳至町", "輪島崎町", "門前町", "町野町", "宝立町", "正院町", "蛸島町", "飯田町", "上戸町", "三崎町", "大谷町")
CAR_SHARE = 0.25  # out-of-shelter evacuees (car, barns) [推定]
CENSUS_NOTE = "人口は国勢調査2020の500mメッシュを、市町ごとに2024-01-01推計人口へ合わせ、帰省客として1.1倍（推定）。住居はOpenStreetMapの建物に置き、標高と海岸からの距離は地理院DEMから。"


def load_geo(name):
    path = DATA / f"{name}_geo.json"
    if not path.exists():
        raise SystemExit(f"{path} is missing: run `mise run geo` first")
    return json.loads(path.read_text())


def node_index(geo):
    pts = geo["nodes"]

    def nearest(lat, lon):
        return min(range(len(pts)), key=lambda i: (pts[i][0] - lat) ** 2 + ((pts[i][1] - lon) * 0.8) ** 2)

    return nearest


def facility(geo, pattern, amenity=None):
    import re as _re
    for f in geo["facilities"]:
        if _re.search(pattern, f["name"]) and (amenity is None or f["amenity"] == amenity):
            return f
    return None


def place(geo, name):
    for p in geo["places"]:
        if p["name"] == name or p["name"].startswith(name):
            return p
    return None


def shelter_capacity(name, amenity):
    if amenity == "school":
        if "高等学校" in name or "高校" in name:
            return 600
        if "小中" in name or "義務教育" in name:
            return 450
        if "中学" in name:
            return 400
        return 350
    return 150


def build_sites(geo, zones, rng, extra_shelter_km=2.5):
    sites = []
    known_peaks = {"宝立小中学校": (287, 900), "プルート": (465, 463), "大谷小中学校": (300, 300), "日置小学校": (150, 50)}
    for k, f in enumerate(geo["facilities"]):
        z = f["zone"]
        if z not in zones:
            continue
        name, am = f["name"], f["amenity"]
        if am == "school" and ("大学" in name or "支援学校" in name or "分校" in name):
            continue
        if am in ("school", "community_centre"):
            designated = am == "school" or any(x in name for x in ("公民館", "センター", "会館", "体育館", "プルート"))
            cap = shelter_capacity(name, am) if designated else 60
            peak = None
            for key, (c, p) in known_peaks.items():
                if key in name:
                    cap, peak = c, p
            starlink = round(rng.uniform(150, 380)) if z in ("wajima", "suzu", "noto", "anamizu") else round(rng.uniform(200, 420))
            sites.append({
                "id": f"S{k}", "name": name, "kind": "shelter", "zone": z, "pos": [f["lat"], f["lon"]],
                "radius_m": 50 if am == "school" else 25, "capacity": cap, "designated": designated,
                **({} if designated else {"known_to_hq": False}),
                "stock_water_l": cap * 1.0 if designated else 0, "stock_food": cap * 1.0 if designated else 0,
                "power_from_h": round(rng.uniform(24, 160)), "starlink_from_h": starlink if designated else round(rng.uniform(300, 500)),
                **({"peak_observed": peak} if peak else {}),
            })
        elif am == "fire_station" and ("消防署" in name or "消防本部" in name):
            sites.append({"id": f"F{k}", "name": name, "kind": "fire", "zone": z, "pos": [f["lat"], f["lon"]], "radius_m": 30})
    # Resident-run shelters (自主避難所: 集会所, barns, greenhouses) where no shelter is near; unknown to the HQ at first.
    shelters = [s for s in sites if s["kind"] == "shelter"]
    for d in geo["districts"]:
        if d["zone"] not in zones or d["pop"] < 40:
            continue
        near = [s for s in shelters if s["zone"] == d["zone"] and dist_km((d["lat"], d["lon"]), s["pos"]) < extra_shelter_km]
        if not near:
            sites.append({
                "id": f"J_{d['id']}", "name": f"{d['name']} 集会所（自主避難所）", "kind": "shelter", "zone": d["zone"],
                "pos": [d["lat"] + 0.0015, d["lon"] + 0.0015], "radius_m": 25, "capacity": max(40, int(d["pop"] * 0.4)),
                "designated": False, "known_to_hq": False, "stock_water_l": 0, "stock_food": 0,
                "starlink_from_h": round(rng.uniform(300, 500)), "note": "住民が開いた避難所。本部は当初把握していない",
            })
    return sites


def dist_km(a, b):
    return math.hypot((a[0] - b[0]) * 110.6, (a[1] - b[1]) * 88.0)


# Road cuts by road name and area, following MLIT's くしの歯 opening: ordinary cars reached the
# 輪島・珠洲・能登町 offices on 1/2 (≈24–30 h), large vehicles on 1/4 (≈66 h), ~80% of trunk roads by
# 1/9 and ~90% by 1/15; the outer-coast R249 and 西保 stayed cut for weeks. [推定 windows]
# (name regex, (lat_min, lat_max, lon_min, lon_max), until_h, foot_ok, cause)
CUT_RULES = [
    (r"のと里山海道", (37.06, 37.40, 136.0, 138.0), 66, True, "盛土崩落"),
    (r"能越", (37.10, 37.40, 136.0, 138.0), 72, True, "路面損傷"),
    (r"249", (37.36, 37.60, 137.03, 137.10), 450, False, "トンネル崩落・大規模崩落"),
    (r"249", (37.36, 37.60, 136.93, 137.03), 360, True, "土砂崩れ・隆起"),
    (r"249", (37.39, 37.60, 137.10, 137.20), 300, True, "土砂崩れ"),
    (r"", (37.36, 37.60, 136.60, 136.85), 430, False, "大規模崩落（西保）"),
    (r"249", (37.28, 37.36, 136.70, 136.88), 100, True, "土砂崩れ"),
    (r"249", (37.14, 37.28, 136.60, 136.80), 96, True, "土砂崩れ"),
    (r"249", (37.20, 37.36, 136.93, 137.10), 40, True, "路面損傷"),
    (r"249", (37.28, 37.36, 137.10, 137.20), 60, True, "津波・路面損傷"),
    (r"249", (37.33, 37.44, 137.20, 137.27), 90, True, "津波・家屋倒壊"),
    (r"珠洲道路", (37.20, 37.50, 136.90, 137.30), 50, True, "路面損傷"),
    (r"七尾輪島線", (37.25, 37.40, 136.85, 136.95), 28, True, "土砂崩れ"),
    (r"能登島.*大橋|ツインブリッジ", (37.05, 37.15, 136.90, 137.10), 200, True, "橋梁損傷"),
]
# Landslides on other rural roads, per municipality: (probability, until_h range, foot_ok probability) [推定;
# tuned so that isolated settlements peak near the recorded 24 areas / 3,345 people].
RANDOM_CUTS = {"wajima": (0.25, (48, 400), 0.7), "suzu": (0.25, (48, 400), 0.7), "noto": (0.12, (24, 200), 0.8),
               "anamizu": (0.12, (24, 200), 0.8), "shika": (0.06, (24, 120), 0.9), "nanao": (0.03, (12, 72), 0.9)}


def build_roads(geo, zone_at, rng, rules=CUT_RULES, random_cuts=RANDOM_CUTS):
    import re as _re
    nodes = [{"id": f"n{i}", "pos": p} for i, p in enumerate(geo["nodes"])]
    roads = []
    for e in geo["edges"]:
        a, b = geo["nodes"][e["a"]], geo["nodes"][e["b"]]
        mid = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
        label = f"{e['name']} {e['ref']}"
        r = {"a": f"n{e['a']}", "b": f"n{e['b']}", "name": e["name"] or e["ref"], "speed_kmh": e["speed"], "length_m": e["len"], "geom": e["geom"]}
        cut = None
        for pat, (la0, la1, lo0, lo1), until, foot, cause in rules:
            if la0 <= mid[0] <= la1 and lo0 <= mid[1] <= lo1 and (_re.search(pat, label) if pat else e["cls"] in ("secondary", "tertiary")):
                cut = (until, foot, cause)
                break
        if cut is None and e["cls"] in ("secondary", "tertiary") and random_cuts:
            z = zone_at(*mid)
            p, (lo, hi), foot_p = random_cuts.get(z, (0, (0, 0), 1))
            if rng.random() < p * min(1.0, e["len"] / 1500):
                cut = (round(rng.uniform(lo, hi)), rng.random() < foot_p, "土砂崩れ")
        if cut:
            r["cut"] = {"until_h": cut[0], "foot_ok": cut[1], "cause": cut[2]}
        roads.append(r)
    return nodes, roads


def zone_lookup(geo):
    ds = geo["districts"]

    def zone_at(lat, lon):
        return min(ds, key=lambda d: (d["lat"] - lat) ** 2 + ((d["lon"] - lon) * 0.8) ** 2)["zone"]

    return zone_at


def settlements_from(geo, zones, nearest_node):
    out = []
    for d in geo["districts"]:
        z = d["zone"]
        if z not in zones:
            continue
        zp = ZONE_PARAMS[z]
        collapse = zp["collapse"] * (1.5 if d["name"].startswith(HARD_HIT) else 0.8)
        out.append({
            "id": d["id"], "name": d["name"], "zone": z, "pos": [d["lat"], d["lon"]], "pop": d["pop"], "radius_m": d["radius_m"],
            "elderly": d["elderly"], "collapse": round(min(0.6, collapse), 3),
            "evac_ratio": round(min(0.92, zp["shelter"] / (1 - CAR_SHARE)), 3), "car_ratio": CAR_SHARE,
            "tsunami_height_m": zp["height"], "tsunami_flee_m": zp["flee"],
            **({"tsunami_arrival_h": zp["arrival"]} if zp["arrival"] is not None else {}),
            "node": f"n{nearest_node(d['lat'], d['lon'])}",
        })
    return out


NOTO_BEHAVIOR = {
    "evac_delay_h": 0.8, "self_evac_tsunami": 0.6, "warned_evac_tsunami": 0.85,
    "talk_per_h": 2.0, "talk_items": 2, "trips_per_day": 0.6, "signal_trip_share": 0.25,
    # ≈17,000 left shelters by 1/17 (≈3%/day).
    "leave_per_day": 0.03,
    "phone_by_age": [0.5, 0.97, 0.75], "family_outside": 0.85, "phone_tries_per_h": 2.0,
    "drain_per_h": 0.025, "mesh_drain_per_h": 0.01, "charge_per_h": 0.4, "car_charge": 0.4,
    "sns_post": 0.25, "broadcast_per_h": 0.6, "radio_without_power": 0.25, "board_read_h": 4,
    "report_in_person": 0.5, "household_size": 2.3, "staff_ratio": 0.012,
    # 区長・民生委員 checked on neighbours door to door in the first day and a half.
    "leaders_per_100": 1.0, "rounds_h": 36,
}

TSUNAMI_BROADCASTS = [
    {"t_h": 0.033, "kind": "tsunami_warning", "text": "津波警報（能登・加賀）", "channels": ["tv", "radio", "musen", "cell_broadcast", "internet"], "expires_h": 0.2},
    {"t_h": 0.2, "kind": "tsunami_warning", "text": "大津波警報（石川県能登）直ちに高台へ", "channels": ["tv", "radio", "musen", "cell_broadcast", "internet"], "expires_h": 4.33},
    {"t_h": 4.33, "kind": "tsunami_update", "text": "津波警報に切り替え（能登）", "channels": ["tv", "radio", "internet"], "expires_h": 9.08},
    {"t_h": 9.08, "kind": "tsunami_update", "text": "津波注意報に切り替え", "channels": ["tv", "radio", "internet"], "expires_h": 17.83},
]

SURVIVAL = [[0, 1.0], [24, 0.74], [48, 0.45], [72, 0.25], [96, 0.1], [120, 0.06], [168, 0.02]]


def offshore(p, dlat):
    return [round(p["lat"] + dlat, 4), round(p["lon"], 4)]


def noto():
    rng = random.Random(7)
    geo = load_geo("noto")
    zones = set(ZONE_NAMES)
    nearest = node_index(geo)
    zone_at = zone_lookup(geo)
    sites = build_sites(geo, zones, rng)
    hq_names = {"wajima": "輪島市役所", "suzu": "珠洲市役所", "noto": "能登町役場", "anamizu": "穴水町役場", "shika": "志賀町役場", "nanao": "七尾市役所"}
    # Satellite phone from (h), fixed internet from (h), Starlink/portable link from (h) at each HQ [推定 from records].
    hq_links = {"wajima": (3, None, 40), "suzu": (12, None, 80), "noto": (3, None, 48), "anamizu": (12, None, 60), "shika": (3, 72, None), "nanao": (1, 0, None)}
    hq_pos = {}
    for z, name in hq_names.items():
        f = facility(geo, name)
        if f is None:
            raise SystemExit(f"{name} not found in OSM facilities")
        sat, fixed, sl = hq_links[z]
        hq_pos[z] = (f["lat"], f["lon"])
        sites.append({"id": f"HQ_{z}", "name": name, "kind": "hq", "zone": z, "pos": [f["lat"], f["lon"]], "radius_m": 50,
                      "satphone_from_h": sat, "fixed_from_h": fixed, "starlink_from_h": sl, "power_from_h": 0})
    sites.append({"id": "AIRPORT", "name": "のと里山空港（救助・物資拠点）", "kind": "depot", "zone": "wajima",
                  "pos": [37.2931, 136.9622], "radius_m": 200, "power_from_h": 0})
    mobile = []
    for i, h in enumerate(geo["hills"]):
        sites.append({"id": f"SIG{i}", "name": f"{h['near']}付近の高台（標高{h['elev_m']}m）", "kind": "signal_spot", "zone": h["zone"],
                      "pos": [h["lat"], h["lon"]], "radius_m": 80})
        mobile.append({"name": f"高台で拾える遠くの局（{h['near']}）", "pos": [h["lat"], h["lon"]], "from_h": 0, "radius_m": 300,
                       "carriers": ["docomo", "au", "softbank"]})
    machino, osawa = place(geo, "町野町"), place(geo, "大沢町")
    monzen, houryu = place(geo, "門前町"), place(geo, "宝立町")
    mobile += [
        {"name": "可搬型基地局(au 輪島市役所)", "pos": list(hq_pos["wajima"]), "from_h": 36, "radius_m": 1500, "carriers": ["au", "rakuten"], "satellite": True},
        {"name": "車載型基地局(SB 穴水町役場)", "pos": list(hq_pos["anamizu"]), "from_h": 46, "radius_m": 1500, "carriers": ["softbank"], "satellite": True},
        {"name": "可搬型基地局(楽天 珠洲市役所)", "pos": list(hq_pos["suzu"]), "from_h": 76, "radius_m": 1200, "carriers": ["rakuten", "au"], "satellite": True},
        {"name": "有線給電ドローン(SB 門前)", "pos": [monzen["lat"], monzen["lon"]], "from_h": 110, "radius_m": 4000, "carriers": ["softbank"], "satellite": True},
        {"name": "車載型基地局(docomo 宝立)", "pos": [houryu["lat"], houryu["lon"]], "from_h": 100, "radius_m": 2000, "carriers": ["docomo"], "satellite": True},
    ]
    nodes, roads = build_roads(geo, zone_at, rng)
    south = min(range(len(geo["nodes"])), key=lambda i: geo["nodes"][i][0])
    return {
        "id": "noto",
        "name": "能登半島全体",
        "description": "令和6年能登半島地震（2024-01-01 16:10, M7.6）。奥能登6市町の約13.1万人を、国勢調査の500mメッシュとOpenStreetMapの建物に置き、実際の道路網・基地局の停波・停電・避難・孤立の記録に合わせて再現する。発災直後から携帯がおおむね復旧する1月下旬（21日間）まで。",
        "start": "2024-01-01T16:10:00+09:00",
        "duration_h": 504,
        "dt_schedule": [[0, 1], [6, 2], [72, 5]],
        "origin": [37.25, 136.98],
        "view": {"center": [37.27, 136.98], "zoom": 10},
        "defaults": {"population_scale": 1.0, "adoption": 0.3},
        "carriers": CARRIERS,
        "zones": [zone(z) for z in ZONE_NAMES],
        "households": "noto_households.csv",
        "settlements": settlements_from(geo, zones, nearest),
        "sites": sites,
        "road_nodes": nodes,
        "roads": roads,
        "exits": [{"id": "kanazawa", "name": "金沢方面", "node": f"n{south}",
                   # 県庁→のと三井IC was 6.5 h right after the quake, 4 h on day 3 (MLIT).
                   "travel_h": [[0, 4.0], [72, 2.5], [168, 1.5]], "internet": True}],
        "towers": {"radius_m": 3500, "people_per_tower": 1200, "battery_h": [3, 24]},
        "congestion": NOTO_CONGESTION,
        "broadcasts": TSUNAMI_BROADCASTS + [
            {"t_h": 40, "kind": "official", "text": "自衛隊・緊急消防援助隊が各市町で救助活動中", "channels": ["tv", "radio", "internet"], "expires_h": 200},
            {"t_h": 72, "kind": "official", "text": "給水所・物資配布所を開設（各市町）", "channels": ["tv", "radio", "musen", "internet"], "expires_h": 500},
        ],
        "ships": [
            {"name": "船上基地局「きずな」(町野沖)", "pos": offshore(machino, 0.03), "from_h": 114, "until_h": 240, "radius_m": 4000, "carriers": ["docomo", "au", "rakuten"], "capacity": 150},
            {"name": "船上基地局「きずな」(大沢沖)", "pos": offshore(osawa, 0.02), "from_h": 282, "until_h": 410, "radius_m": 4000, "carriers": ["docomo", "au", "rakuten"], "capacity": 150},
        ],
        "mobile_bs": mobile,
        # 1 terminal: 40–220 Mbps down, 128 devices. Cell capacity is not published [推定].
        "starlink": {"terminal_mbps": 100, "cell_capacity_mbps": 1500, "user_kbps": 400, "wifi_radius_m": 70},
        # Beam ≈3–4 Mbps shared by a large area; SMS sent within 30 s–2 min after the 2025-07 upgrade [推定].
        "starlink_direct": {"beam_msgs_per_min": 300, "visibility": 0.85, "indoor_factor": 0.1, "device_share": 0.6, "attempts_per_h": 2},
        "rescue": {
            "base": "AIRPORT",
            # 緊援隊 546 teams/2,035 people dispatched; SDF ~1,000 on 1/2 and up to 14,000 later.
            # A "team" here works one site at a time [推定 calibration to ~550 professional rescues].
            "teams": [[0, 8], [6, 14], [24, 50], [48, 110], [72, 180], [168, 220]],
            "local_teams": 8,
            "duration_h": [1.5, 6],
            "search_find_per_h": 0.12,
            "search_from_h": 30,
            "survival": SURVIVAL,
            # 阪神: about 77% of those trapped were freed by family and neighbours.
            "light_share": 0.75,
            "neighbor_rescue_h": 2.5,
            "trapped_per_person_collapsed": 0.10,
            "helicopters": [[0, 0], [20, 4], [48, 12], [96, 20]],
        },
        "supply": {
            "depot": "AIRPORT",
            # Shelter demand ≈34,000 × 3 L/day ≈ 4,250 L/h at the peak; push supply ramps up from 1/2 [推定].
            "water_l_per_h": [[0, 0], [16, 800], [48, 3000], [96, 6000], [168, 5000], [336, 4000]],
            "food_per_h": [[0, 0], [16, 800], [48, 3000], [96, 6000], [168, 5000], [336, 4000]],
            "trucks": [[0, 0], [16, 10], [48, 40], [96, 80], [168, 100]],
            "truck_water_l": 2000, "truck_food": 2000,
            "water_l_per_person_day": 3, "food_per_person_day": 3, "surplus_days": 4, "plan_every_h": 3,
            "liaisons_per_zone": 3, "liaison_from_h": 18,
        },
        "behavior": NOTO_BEHAVIOR,
        "hazards": {
            "fires": [{"name": "輪島朝市通り", "pos": [37.3924, 136.9035], "from_h": 0.3}],
            "fake_sns_rate": 0.095,
        },
        "sources": sources("naikaku", "ishikawa", "kensho", "hakusho", "soumu", "jma", "nict", "kddi", "docomo", "sb", "au_sd", "hosei", "fdma", "mlit", "kobe", "census", "osm", "gsi"),
        "notes": [
            CENSUS_NOTE,
            "地区は OpenStreetMap の地名（町・字）ごとにまとめ、避難所は学校・公民館・集会所の実在地点。住民が開いた自主避難所は、近くに避難所がない地区に置く。",
            "基地局は市町ごとの停波率の推移（内閣府の停波局数と市町別の支障解消日から補間）に合わせて止まる。",
            "1/1夜の音声の発信規制率は公表されておらず、通話の成功率は推定値。",
            "道路は OpenStreetMap の幹線（国道・県道）。寸断は路線名と区間で国交省「くしの歯」の到達日に合わせ、それ以外の山間の県道には市町ごとの確率で土砂崩れを置いた（推定）。",
            "津波は家ごとの標高と海岸からの距離で判定。輪島・門前は隆起で浸水しなかったため、逃げはするが浸水は数えない。",
            "Starlink Direct は2024年1月には存在しない。反実仮想として、2025年以降の能力（SMS、オプションでデータ）を置ける。",
            "要救助者は全壊家屋の居住者の一部として推定し、専門機関による救助約550人（警察114・消防435）に近づけている。",
            "SNSの救助要請の約1割が偽（NICTの分析）として、偽の救助要請も発生させる。",
        ],
    }


# ---------------------------------------------------------------------------
# Single shelter: 珠洲市立宝立小中学校 (capacity 287; 800–900 evacuees on the night of 1/1;
# run by about 30 residents; tsunami 2.7–4.3 m nearby; Starlink arrived in 珠洲 1/7–1/11).
# ---------------------------------------------------------------------------

def shelter():
    rng = random.Random(11)
    geo = load_geo("shelter")
    nearest = node_index(geo)
    zone_at = zone_lookup(geo)
    school = facility(geo, "宝立小中学校")
    hq = facility(geo, "珠洲市役所")
    fire = facility(geo, "珠洲消防署")
    sites = [
        {"id": "HOURYU", "name": school["name"], "kind": "shelter", "zone": "suzu", "pos": [school["lat"], school["lon"]],
         "radius_m": 45, "floor": [70, 45], "capacity": 287, "peak_observed": 900, "designated": True,
         "stock_water_l": 300, "stock_food": 300, "power_from_h": 60, "starlink_from_h": 150, "satphone_from_h": 120,
         "note": "収容287人に800〜900人が避難。住民約30人が運営"},
        {"id": "HQ_S", "name": "珠洲市役所（災害対策本部）", "kind": "hq", "zone": "suzu", "pos": [hq["lat"], hq["lon"]],
         "radius_m": 50, "satphone_from_h": 12, "starlink_from_h": 80, "power_from_h": 0},
        {"id": "F_S", "name": fire["name"], "kind": "fire", "zone": "suzu", "pos": [fire["lat"], fire["lon"]], "radius_m": 30},
        {"id": "DEPOT", "name": "珠洲市 物資拠点", "kind": "depot", "zone": "suzu", "pos": [hq["lat"] + 0.001, hq["lon"] - 0.002], "radius_m": 40, "power_from_h": 0},
    ]
    others = [s for s in build_sites(geo, {"suzu"}, rng, extra_shelter_km=1.5)
              if s["kind"] == "shelter" and "宝立小中" not in s["name"] and dist_km(s["pos"], (school["lat"], school["lon"])) < 3.5]
    sites += others
    for i, h in enumerate(geo["hills"][:1]):
        sites.append({"id": f"SIG{i}", "name": f"高台（標高{h['elev_m']}m、電波が届く場所）", "kind": "signal_spot", "zone": "suzu", "pos": [h["lat"], h["lon"]], "radius_m": 60})
    settlements = settlements_from(geo, {"suzu"}, nearest)
    for st in settlements:
        # 宝立町 residents go to the school first; neighbours to whatever is nearest.
        if st["name"].startswith("宝立町"):
            st["shelters"] = ["HOURYU"]
    nodes, roads = build_roads(geo, zone_at, rng, random_cuts={"suzu": (0.25, (24, 200), 0.8)})
    west = min(range(len(geo["nodes"])), key=lambda i: geo["nodes"][i][1])
    hill = geo["hills"][0] if geo["hills"] else {"lat": school["lat"] + 0.01, "lon": school["lon"] - 0.02}
    return {
        "id": "shelter",
        "name": "避難所一つ（珠洲市 宝立小中学校）",
        "description": "収容287人の宝立小中学校に、津波と家屋倒壊から800〜900人が避難した。住民が運営し、携帯は止まり、Starlinkが届くのは1/7以降。周辺の住民一人ひとりを実在の建物に置き、10日間を再現する。",
        "start": "2024-01-01T16:10:00+09:00",
        "duration_h": 240,
        "dt_schedule": [[0, 0.5], [6, 1], [72, 2]],
        "origin": [school["lat"], school["lon"]],
        "view": {"center": [school["lat"] + 0.004, school["lon"] + 0.003], "zoom": 15, "max_zoom": 20},
        "defaults": {"population_scale": 1.0, "adoption": 0.3},
        "carriers": CARRIERS,
        "zones": [zone("suzu")],
        "households": "shelter_households.csv",
        "settlements": settlements,
        "sites": sites,
        "road_nodes": nodes,
        "roads": roads,
        "exits": [{"id": "kanazawa", "name": "金沢方面（珠洲道路経由）", "node": f"n{west}", "travel_h": [[0, 6], [72, 4], [168, 3]], "internet": True}],
        "towers": {"radius_m": 3000, "people_per_tower": 800},
        # The area is about a fifth of 珠洲市, so it gets that share of the 119 lines.
        "congestion": {**NOTO_CONGESTION, "fire_lines_per_h": 6},
        "broadcasts": TSUNAMI_BROADCASTS + [
            {"t_h": 72, "kind": "official", "text": "珠洲市: 給水所を開設", "channels": ["tv", "radio", "internet"], "expires_h": 240},
        ],
        "mobile_bs": [
            {"name": "車載型基地局(docomo 宝立)", "pos": [school["lat"], school["lon"]], "from_h": 100, "radius_m": 2000, "carriers": ["docomo"], "satellite": True},
            {"name": "高台で拾える遠くの局", "pos": [hill["lat"], hill["lon"]], "from_h": 0, "radius_m": 250, "carriers": ["docomo", "au"]},
        ],
        "starlink": {"terminal_mbps": 100, "cell_capacity_mbps": 1500, "user_kbps": 400, "wifi_radius_m": 70},
        "starlink_direct": {"beam_msgs_per_min": 300, "visibility": 0.85, "indoor_factor": 0.1, "device_share": 0.6, "attempts_per_h": 2},
        "rescue": {
            "base": "F_S",
            "teams": [[0, 2], [24, 4], [48, 8], [96, 10]],
            "local_teams": 2,
            "duration_h": [1.5, 6],
            "search_find_per_h": 0.15,
            "search_from_h": 30,
            "survival": SURVIVAL,
            "light_share": 0.75,
            "neighbor_rescue_h": 2.0,
            "trapped_per_person_collapsed": 0.10,
        },
        "supply": {
            "depot": "DEPOT",
            "water_l_per_h": [[0, 0], [20, 80], [48, 300], [96, 500], [168, 400]],
            "food_per_h": [[0, 0], [20, 80], [48, 300], [96, 500], [168, 400]],
            "trucks": [[0, 0], [20, 1], [48, 2], [96, 3]],
            "truck_water_l": 1000, "truck_food": 1000,
            "water_l_per_person_day": 3, "food_per_person_day": 3, "surplus_days": 4, "plan_every_h": 3,
            "liaisons_per_zone": 1, "liaison_from_h": 30,
        },
        "behavior": {**NOTO_BEHAVIOR, "evac_delay_h": 0.3, "self_evac_tsunami": 0.65, "warned_evac_tsunami": 0.9,
                     "talk_per_h": 3.0, "signal_trip_share": 0.3, "board_read_h": 3, "staff_ratio": 0.035},
        "hazards": {"fake_sns_rate": 0.095},
        "sources": sources("hosei", "naikaku", "kensho", "hakusho", "kddi", "jma", "nict", "au_sd", "kobe", "census", "osm", "gsi"),
        "notes": [
            CENSUS_NOTE,
            "宝立小中学校は収容287人に対して元日の夜に800〜900人。住民約30人が運営し、名簿と聞き取りで72時間以内の安否確認を目指した（内閣府 避難所運営実態調査）。",
            "珠洲市の携帯は停波率9割前後が続き、避難所へのStarlinkの搬入は1/7〜1/11。",
            "避難所の外（自宅・車中泊）の人も地図に含め、物資や情報を取りに来る動きを再現する。",
        ],
    }


# ---------------------------------------------------------------------------
# 川内キャンパス, 2011-03-11 14:46 (spring break; the 後期 exam was the next day and cancelled).
# ---------------------------------------------------------------------------

def kawauchi():
    b = [
        # id, name, lat, lon, occupancy, student_share, radius
        ("LA", "講義棟A棟", 38.2598, 140.8513, 60, 0.85, 35),
        ("LB", "講義棟B棟", 38.2602, 140.8513, 80, 0.85, 35),
        ("LC", "講義棟C棟", 38.2605, 140.8513, 60, 0.85, 35),
        ("LIB", "附属図書館本館", 38.2584, 140.8519, 260, 0.85, 45),
        ("HAGI", "川内萩ホール", 38.2588, 140.8541, 60, 0.4, 40),
        ("KOSEI", "川内厚生会館（生協食堂）", 38.2600, 140.8529, 120, 0.8, 30),
        ("GYM", "川内体育館・サブアリーナ（部活動）", 38.2612, 140.8527, 150, 0.95, 40),
        ("BUN", "文学研究科棟", 38.2576, 140.8516, 160, 0.55, 40),
        ("HO", "法学研究科棟", 38.2579, 140.8525, 110, 0.5, 35),
        ("KEI", "経済学研究科棟", 38.2570, 140.8518, 120, 0.5, 35),
        ("KYO", "教育学研究科棟", 38.2567, 140.8532, 90, 0.5, 35),
        ("RESEARCH", "東北アジア研究センター・研究棟", 38.2595, 140.8500, 90, 0.3, 35),
        ("CLUB", "課外活動共用施設（サークル棟）", 38.2617, 140.8510, 110, 1.0, 30),
    ]
    sites = []
    for sid, name, lat, lon, occ, ss, r in b:
        sites.append({"id": sid, "name": name, "kind": "building", "zone": "sendai", "pos": [lat, lon], "radius_m": r,
                      "occupancy": occ, "student_share": ss,
                      # Campus backbone went down 2 h 50 min after the quake and came back on 3/13 14:10.
                      "fixed_from_h": 47.4})
    sites += [
        {"id": "PLAZA_N", "name": "川内北キャンパス 中央広場（一時集合場所）", "kind": "open", "zone": "sendai", "pos": [38.2601, 140.8522], "radius_m": 50},
        {"id": "PLAZA_S", "name": "萩ホール前広場（一時集合場所）", "kind": "open", "zone": "sendai", "pos": [38.2586, 140.8530], "radius_m": 45},
        {"id": "GYM_SHELTER", "name": "川内体育館（学生・帰宅困難者の一時滞在、推定）", "kind": "shelter", "zone": "sendai", "pos": [38.2613, 140.8530],
         "radius_m": 35, "floor": [45, 35], "capacity": 400, "designated": False, "known_to_hq": False, "stock_water_l": 200, "stock_food": 200,
         "power_from_h": 50, "fixed_from_h": 47.4, "note": "市の指定避難所としての記録は確認できていない"},
        {"id": "KATAHIRA_ES", "name": "仙台市立片平丁小学校（指定避難所）", "kind": "shelter", "zone": "sendai", "pos": [38.2552, 140.8655],
         "radius_m": 40, "floor": [40, 30], "capacity": 600, "designated": True, "stock_water_l": 1500, "stock_food": 1500, "power_from_h": 60},
        {"id": "HQ_UNIV", "name": "東北大学 災害対策本部（片平）", "kind": "hq", "zone": "sendai", "pos": [38.2541, 140.8690], "radius_m": 50,
         "satphone_from_h": 1, "fixed_from_h": 47.4, "power_from_h": 0},
    ]
    nodes = [
        {"id": "campus", "pos": [38.2595, 140.8520]}, {"id": "ohashi", "pos": [38.2588, 140.8600]},
        {"id": "yodo", "pos": [38.2640, 140.8580]}, {"id": "city", "pos": [38.2600, 140.8760]},
        {"id": "katahira", "pos": [38.2545, 140.8670]}, {"id": "west", "pos": [38.2570, 140.8420]},
    ]
    roads = [
        {"a": "campus", "b": "ohashi", "name": "大橋", "speed_kmh": 20}, {"a": "ohashi", "b": "city", "name": "青葉通・広瀬通", "speed_kmh": 20},
        {"a": "ohashi", "b": "katahira", "name": "市道", "speed_kmh": 20}, {"a": "campus", "b": "yodo", "name": "澱橋", "speed_kmh": 20},
        {"a": "yodo", "b": "city", "name": "市道", "speed_kmh": 20}, {"a": "campus", "b": "west", "name": "川内・亀岡方面", "speed_kmh": 20},
    ]
    sendai = {
        "id": "sendai", "name": "仙台市青葉区",
        # Citywide blackout; average outage 4.3 days in the residents' survey [推定 curve].
        "power_out": [[0, 0.98], [24, 0.95], [48, 0.7], [72, 0.4], [96, 0.2], [120, 0.1], [168, 0.03]],
        # Batteries lasted about 3 h; ドコモ peaked 3/12 17:00. Central Sendai recovered from 3/13 [推定 curve].
        "cell_out": [[0, 0.1], [3, 0.3], [6, 0.5], [12, 0.65], [26, 0.7], [48, 0.5], [72, 0.3], [96, 0.15], [144, 0.05], [240, 0.02]],
        "fixed_out": [[0, 0.3], [3, 0.9], [48, 0.8], [96, 0.4], [168, 0.2]],
        "water_out": [[0, 0.8], [168, 0.4]],
        # 同報系防災行政無線 covers 54.3% of 宮城's municipalities; Sendai relies on other channels.
        "musen_ok": 0.3,
    }
    return {
        "id": "kawauchi",
        "name": "川内キャンパス全体（東日本大震災）",
        "description": "2011-03-11 14:46、春休み中の東北大学川内キャンパス。全学教育の講義棟・図書館・部活動の学生と教職員が、停電と発信規制の中で安否を伝える。とんペディア（学生の約9割が利用）にもしもプロトコルが入っていた場合を比べる。大学の安否確認が完了したのは3/30。",
        "start": "2011-03-11T14:46:00+09:00",
        "duration_h": 168,
        "dt_schedule": [[0, 0.5], [6, 1], [48, 2]],
        "origin": [38.2595, 140.8530],
        "view": {"center": [38.2590, 140.8560], "zoom": 16, "max_zoom": 20},
        "defaults": {"population_scale": 1.0, "adoption": 0.2, "adoption_students": 0.9, "mesh_range_m": 25},
        "carriers": [
            {"id": "docomo", "name": "ドコモ", "share": 0.5, "outage_factor": 1.0},
            {"id": "au", "name": "au", "share": 0.25, "outage_factor": 1.0, "direct_to_cell": True},
            {"id": "softbank", "name": "ソフトバンク", "share": 0.25, "outage_factor": 1.1},
        ],
        "zones": [sendai],
        "settlements": [],
        "sites": sites,
        "road_nodes": nodes,
        "roads": roads,
        "exits": [
            {"id": "home_city", "name": "市内の自宅・下宿", "node": "city", "travel_h": [[0, 0.5]], "internet": False},
            {"id": "home_west", "name": "八幡・亀岡方面の下宿", "node": "west", "travel_h": [[0, 0.4]], "internet": False},
        ],
        "towers": {"radius_m": 800, "people_per_tower": 300, "extra": [[38.2620, 140.8600], [38.2560, 140.8480], [38.2560, 140.8650]]},
        "congestion": {
            # Voice restrictions up to 90% (ドコモ), 95% (au), 70% (SB); packets ≤30% (ドコモ).
            "voice_success": [[0, 0.08], [3, 0.12], [12, 0.25], [24, 0.4], [48, 0.7], [72, 0.9], [96, 1.0]],
            # Carrier mail got through for 52% of Sendai residents on the day; mail was delayed for hours.
            "data_success": [[0, 0.2], [6, 0.25], [24, 0.45], [48, 0.8], [72, 1.0]],
            "fire_lines_per_h": 20,
        },
        "broadcasts": [
            {"t_h": 0.05, "kind": "tsunami_warning", "text": "大津波警報（宮城県）", "channels": ["tv", "radio", "musen", "cell_broadcast", "internet"], "expires_h": 48},
            {"t_h": 1.0, "kind": "official", "text": "地下鉄・JR運転見合わせ、帰宅困難者は一時滞在を", "channels": ["tv", "radio", "internet"], "expires_h": 72},
            {"t_h": 6, "kind": "official", "text": "仙台市: 指定避難所を開設", "channels": ["radio", "internet"], "expires_h": 168},
        ],
        "mobile_bs": [
            {"name": "移動基地局車(ドコモ 片平)", "pos": [38.2545, 140.8670], "from_h": 60, "radius_m": 1200, "carriers": ["docomo"]},
            {"name": "移動基地局車(au 市中心部)", "pos": [38.2600, 140.8760], "from_h": 54, "radius_m": 1500, "carriers": ["au"]},
        ],
        "starlink": {"terminal_mbps": 100, "cell_capacity_mbps": 1500, "user_kbps": 400, "wifi_radius_m": 70},
        "starlink_direct": {"beam_msgs_per_min": 300, "visibility": 0.85, "indoor_factor": 0.1, "device_share": 0.6, "attempts_per_h": 2},
        "rescue": {"teams": [[0, 2]], "local_teams": 0, "duration_h": [1, 3], "search_find_per_h": 0.1, "search_from_h": 6,
                   "survival": [[0, 1.0], [24, 0.74], [72, 0.25]], "light_share": 0.8, "neighbor_rescue_h": 1, "trapped_per_person_collapsed": 0},
        "supply": {"water_l_per_h": [[0, 0]], "food_per_h": [[0, 0]], "trucks": [[0, 0]], "truck_water_l": 1000, "truck_food": 1000,
                   "water_l_per_person_day": 3, "food_per_person_day": 3, "surplus_days": 4, "plan_every_h": 6,
                   # University staff going round buildings and shelters to collect 安否.
                   "liaisons_per_zone": 3, "liaison_from_h": 1},
        "behavior": {
            "evac_delay_h": 0.1, "self_evac_tsunami": 0, "warned_evac_tsunami": 0,
            "talk_per_h": 4.0, "talk_items": 2, "trips_per_day": 0.5, "signal_trip_share": 0.1, "leave_per_day": 0.15,
            "phone_by_age": [0.9, 0.98, 0.9], "family_outside": 0.9, "phone_tries_per_h": 1.0,
            "drain_per_h": 0.03, "mesh_drain_per_h": 0.01, "charge_per_h": 0.4, "car_charge": 0.1,
            "sns_post": 0.15, "broadcast_per_h": 0.4, "radio_without_power": 0.3, "board_read_h": 3,
            "report_in_person": 0.5, "household_size": 1.0, "staff_ratio": 0.02,
            # Students answer the faculty's emails and calls; 安否確認 finished on 3/30 (about 20 days).
            "report_safety_to_hq": 0.02,
        },
        "hazards": {"fake_sns_rate": 0.0},
        "sources": sources("tohoku2011", "mic2011", "sendai_survey", "darmstadt"),
        "notes": [
            "春休み中のため在校者は約1,400人と推定（学期中の平日は全学教育の学生だけで約4,900人。人口倍率で再現できる）。",
            "SINET は発災28分後、学内基幹網は2時間50分後に停止し、3/13 14:10に復旧。",
            "大学の安否確認は約20日かかり3/30に完了（文学研究科は3/22時点で93%）。",
            "音声の発信規制は最大でドコモ90%・au95%・ソフトバンク70%、パケットはドコモ30%。",
            "2015年の訓練では「スマホdeリレー」で川内→片平に1時間以内に675件のテキストを中継した。",
            "川内体育館が避難所として使われたかは確認できておらず、推定として置いている。",
        ],
    }


def main():
    builders = {"shelter": shelter, "kawauchi": kawauchi, "noto": noto}
    names = sys.argv[1:] or list(builders)
    for name in names:
        data = builders[name]()
        (HERE / f"{name}.json").write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n")
        print(f"wrote {name}.json")


if __name__ == "__main__":
    main()
