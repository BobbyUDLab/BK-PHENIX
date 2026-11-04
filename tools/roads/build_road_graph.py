#!/usr/bin/env python3
"""Сборка дорожного графа для привязки к дорогам (map matching) из OSM-вырезки.

Данные: © участники OpenStreetMap, ODbL 1.0. Граф — производная база данных, поэтому распространяется
под ODbL с указанием «© OpenStreetMap contributors» (показывается в приложении).
Вырезка без аккаунта: https://download.geofabrik.de/russia/central-fed-district-latest.osm.pbf

Использование (нужен pyosmium: pip install osmium):
  python3 build_road_graph.py central-fed-district-latest.osm.pbf BK-PHENIX-roads-moscow.bin.gz

Рамка по умолчанию: Москва и ближнее Подмосковье до ~60 км от МКАД (55.20–56.25° с. ш., 36.80–38.50° в. д.):
граф всей области (4,9 млн узлов) занимает ~200 МБ кучи и не помещается в память телефона.
Другая рамка: --bbox ЮГ,ЗАПАД,СЕВЕР,ВОСТОК (например, 54.2,35.1,57.0,40.3 — вся область).
Классы: бит 0 — проезжая для авто, бит 1 — для пешехода (тропы, тротуары, улицы).
Геометрия упрощается (Дуглас — Пёкер, 2 м), развилки и концы сохраняются.

Формат (big-endian, затем gzip): b"BKRG", версия int32 = 1, число узлов int32, число отрезков int32,
узлы: широта·1e6 int32, долгота·1e6 int32; отрезки: узел A int32, узел B int32, флаги int8.
"""
import gzip, io, math, struct, sys
import osmium

LAT = (55.20, 56.25)
LON = (36.80, 38.50)
CAR = {"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street",
       "service", "road", "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"}
FOOT = {"footway", "path", "pedestrian", "steps", "cycleway", "track", "bridleway", "living_street", "residential",
        "unclassified", "tertiary", "secondary", "primary", "service", "road", "tertiary_link", "secondary_link",
        "primary_link", "corridor"}
TOLERANCE_M = 2.0


def flags_of(tags):
    hw = tags.get("highway")
    if hw is None or tags.get("area") == "yes":
        return 0
    f = 0
    car_ok = hw in CAR and tags.get("access") not in ("no", "private") and tags.get("motor_vehicle") != "no"
    if hw == "service" and tags.get("service") in ("parking_aisle", "drive-through"):
        car_ok = False
    if car_ok:
        f |= 1
    foot_tag = tags.get("foot")
    if (hw in FOOT and foot_tag != "no" and tags.get("access") not in ("no", "private")) or foot_tag in ("yes", "designated"):
        f |= 2
    return f


def inside(lat, lon):
    return LAT[0] <= lat <= LAT[1] and LON[0] <= lon <= LON[1]


class Counter(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.uses = {}

    def way(self, w):
        if flags_of(w.tags):
            refs = [n.ref for n in w.nodes]
            for i, r in enumerate(refs):
                inc = 2 if i == 0 or i == len(refs) - 1 else 1
                self.uses[r] = self.uses.get(r, 0) + inc


def simplify(pts, keep):
    """Дуглас — Пёкер по индексам; keep — обязательные точки (развилки)."""
    out = {0, len(pts) - 1} | keep

    def rec(a, b):
        if b <= a + 1:
            return
        la, lo = pts[a][1], pts[a][2]
        k = math.cos(math.radians(la)) * 111320.0
        ax, ay = 0.0, 0.0
        bx, by = (pts[b][2] - lo) * k, (pts[b][1] - la) * 111320.0
        L = math.hypot(bx, by) or 1e-9
        best, bi = -1.0, -1
        for i in range(a + 1, b):
            px, py = (pts[i][2] - lo) * k, (pts[i][1] - la) * 111320.0
            d = abs((bx - ax) * (ay - py) - (ax - px) * (by - ay)) / L
            if d > best:
                best, bi = d, i
        if best > TOLERANCE_M:
            out.add(bi)
            rec(a, bi)
            rec(bi, b)

    idx = sorted(out)
    for a, b in zip(idx, idx[1:]):
        rec(a, b)
    return sorted(out)


class Builder(osmium.SimpleHandler):
    def __init__(self, uses):
        super().__init__()
        self.uses = uses
        self.index = {}
        self.lat = []
        self.lon = []
        self.segs = {}

    def node_id(self, ref, lat, lon):
        i = self.index.get(ref)
        if i is None:
            i = len(self.lat)
            self.index[ref] = i
            self.lat.append(round(lat * 1e6))
            self.lon.append(round(lon * 1e6))
        return i

    def way(self, w):
        f = flags_of(w.tags)
        if not f:
            return
        try:
            pts = [(n.ref, n.location.lat, n.location.lon) for n in w.nodes]
        except osmium.InvalidLocationError:
            return
        if len(pts) < 2 or not any(inside(p[1], p[2]) for p in pts):
            return
        keep = {i for i, p in enumerate(pts) if self.uses.get(p[0], 0) > 1}
        idx = simplify(pts, keep)
        for a, b in zip(idx, idx[1:]):
            pa, pb = pts[a], pts[b]
            if not (inside(pa[1], pa[2]) or inside(pb[1], pb[2])) or pa[0] == pb[0]:
                continue
            na, nb = self.node_id(*pa), self.node_id(*pb)
            key = (min(na, nb), max(na, nb))
            self.segs[key] = self.segs.get(key, 0) | f


def main(src, dst):
    c = Counter()
    c.apply_file(src)
    b = Builder(c.uses)
    del c
    b.apply_file(src, locations=True, idx="sparse_file_array," + dst + ".nodes.idx")
    out = io.BytesIO()
    out.write(b"BKRG" + struct.pack(">iii", 1, len(b.lat), len(b.segs)))
    for la, lo in zip(b.lat, b.lon):
        out.write(struct.pack(">ii", la, lo))
    for (x, y), f in b.segs.items():
        out.write(struct.pack(">iib", x, y, f))
    with gzip.open(dst, "wb", compresslevel=9) as g:
        g.write(out.getvalue())
    car = sum(1 for f in b.segs.values() if f & 1)
    foot = sum(1 for f in b.segs.values() if f & 2)
    print(f"узлов: {len(b.lat)}; отрезков: {len(b.segs)} (авто {car}, пешком {foot})")


if __name__ == "__main__":
    args = sys.argv[1:]
    if "--bbox" in args:
        i = args.index("--bbox")
        s, w, n, e = (float(x) for x in args[i + 1].split(","))
        LAT, LON = (s, n), (w, e)
        del args[i:i + 2]
    if len(args) != 2:
        sys.exit("использование: build_road_graph.py [--bbox Ю,З,С,В] вход.osm.pbf выход.bin.gz")
    main(args[0], args[1])
