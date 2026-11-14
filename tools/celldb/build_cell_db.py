#!/usr/bin/env python3
"""Сборка офлайн-базы сотовых вышек для приложения из нескольких источников.

Источники (порядок не важен, при совпадении соты берётся запись с более поздним `updated`):
- **OpenCelliD** — выгрузка страны `250.csv.gz` (opencellid.org/downloads.php, нужен бесплатный токен).
  Только соты, виденные за последние 18 месяцев. Лицензия **CC BY-SA 4.0**: база, собранная с ней,
  распространяется под той же лицензией, авторство «OpenCelliD» обязательно на экране приложения.
- **Mozilla Location Service** — финальная выгрузка (март 2024), CC0 1.0:
  https://web.archive.org/web/20240504021731/https://d2koia3g127518.cloudfront.net/final_export/MLS-full-cell-export-final.csv.gz
- Готовая база BKCL (`*.bin` / `*.bin.gz`) — например, прежняя `cells-moscow.bin.gz` (считается MLS 2024).

CSV OpenCelliD и MLS имеют одинаковые колонки: radio,mcc,net,area,cell,unit,lon,lat,range,samples,
changeable,created,updated,averageSignal (у OpenCelliD — без строки заголовка).

Использование:
  python3 build_cell_db.py app/src/main/assets/cells/cells-moscow.bin.gz 250.csv.gz [MLS.csv.gz | old.bin.gz ...]

Фильтр: MCC 250, рамка Москвы и Московской области (широта 54.2–57.0, долгота 35.1–40.3).

Формат (big-endian, затем gzip): b"BKCL", версия int32 = 1, число записей int32,
далее записи, отсортированные по ключу: ключ int64, широта·1e6 int32, долгота·1e6 int32, радиус соты int32 (м).
Ключ — см. CellKey в приложении: технология (2 бита) << 51 | MNC (7 бит) << 44 | идентификатор (44 бита):
GSM — LAC << 16 | CID, UMTS — LAC << 28 | UC-ID, LTE — ECI (28 бит), NR — NCI (36 бит).

**Записи базовых станций LTE** (с 0.8): идентификатор = SITE_BIT (бит 43) | eNB (ECI >> 8). Координаты —
медиана известных сот станции, радиус — двойной разброс, не меньше SITE_MIN_RANGE_M; станции, где больше половины
сот дальше SITE_MAX_SPREAD_M от медианы, не пишутся. Нужны, когда точной соты в базе нет,
а станция известна (полевые записи 04–07.11.2026: точных сот основного оператора 22 из 156, по станции — 82).
Старые версии приложения эти ключи не запрашивают, поэтому база обратно совместима.
"""
import csv, gzip, io, math, statistics, struct, sys
from collections import defaultdict

LAT = (54.2, 57.0)
LON = (35.1, 40.3)
TECH = {"GSM": 0, "UMTS": 1, "LTE": 2, "NR": 3}
SITE_BIT = 1 << 43
SITE_MIN_RANGE_M = 2000
SITE_MAX_SPREAD_M = 3000
SITE_MAX_CELL_RANGE_M = 10000
MLS_UPDATED = 1711497600  # 2024-03-27: финальная выгрузка MLS


def key(tech, mnc, lac, cid):
    if tech == 0:
        ident = (lac << 16) | (cid & 0xFFFF)
    elif tech == 1:
        ident = (lac << 28) | (cid & 0xFFFFFFF)
    elif tech == 2:
        ident = cid & 0xFFFFFFF
    else:
        ident = cid & 0xFFFFFFFFF
    return (tech << 51) | (mnc << 44) | (ident & ((1 << 44) - 1))


def site_key(mnc, eci):
    return (2 << 51) | (mnc << 44) | SITE_BIT | ((eci & 0xFFFFFFF) >> 8)


def open_text(path):
    return gzip.open(path, "rt", newline="") if path.endswith(".gz") else open(path, newline="")


def read_csv(path):
    """Строки (tech, mnc, lac, cid, lat, lon, range, samples, updated) из CSV OpenCelliD/MLS."""
    with open_text(path) as f:
        for row in csv.reader(f):
            if not row or row[0] == "radio":
                continue
            if row[1] != "250":
                continue
            tech = TECH.get(row[0])
            try:
                mnc, lac, cid = int(row[2]), int(row[3] or 0), int(row[4] or 0)
                lon, lat = float(row[6]), float(row[7])
                rng, samples = int(row[8] or 0), int(row[9] or 1)
                updated = int(row[12] or 0)
            except (ValueError, IndexError):
                continue
            if tech is None or mnc > 127 or lac > 0xFFFF or cid <= 0:
                continue
            yield tech, mnc, lac, cid, lat, lon, rng, samples, updated


def read_bkcl(path):
    """Записи готовой базы BKCL: (ключ, lat·1e6, lon·1e6, range). Ключи станций пропускаются."""
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rb") as f:
        data = f.read()
    assert data[:4] == b"BKCL", path
    _, n = struct.unpack(">ii", data[4:12])
    off = 12
    for _ in range(n):
        k, la, lo, rng = struct.unpack(">qiii", data[off:off + 20])
        off += 20
        if (k >> 51) == 2 and (k & SITE_BIT):
            continue
        yield k, la, lo, rng


def main(dst, sources):
    records = {}  # ключ -> (lat·1e6, lon·1e6, range, samples, updated)
    origin = defaultdict(int)

    def put(k, la, lo, rng, samples, updated, src):
        prev = records.get(k)
        if prev is None or (updated, samples) > (prev[4], prev[3]):
            records[k] = (la, lo, rng, samples, updated)
            origin[k] = src

    for src in sources:
        if src.endswith(".bin") or src.endswith(".bin.gz"):
            for k, la, lo, rng in read_bkcl(src):
                put(k, la, lo, rng, 1, MLS_UPDATED, src)
            continue
        for tech, mnc, lac, cid, lat, lon, rng, samples, updated in read_csv(src):
            if not (LAT[0] <= lat <= LAT[1] and LON[0] <= lon <= LON[1]):
                continue
            put(key(tech, mnc, lac, cid), round(lat * 1e6), round(lon * 1e6), rng, samples, updated or MLS_UPDATED, src)

    # Базовые станции LTE: среднее известных сот и радиус, покрывающий их разброс.
    sites = defaultdict(list)
    for k, (la, lo, rng, _, _) in records.items():
        if (k >> 51) == 2:
            mnc = (k >> 44) & 0x7F
            eci = k & 0xFFFFFFF
            sites[site_key(mnc, eci)].append((la / 1e6, lo / 1e6, rng))
    site_records = {}
    for sk, cells in sites.items():
        # Медиана устойчива к одиночной соте, «уехавшей» в краудсорсинге; дальние от неё соты не участвуют.
        lat = statistics.median(c[0] for c in cells)
        lon = statistics.median(c[1] for c in cells)
        kx = 111195 * math.cos(math.radians(lat))
        dist = [math.hypot((c[0] - lat) * 111195, (c[1] - lon) * kx) for c in cells]
        near = [d for d in dist if d <= SITE_MAX_SPREAD_M]
        if 2 * len(near) <= len(dist):
            continue  # соты станции разбросаны — станция только навредит
        if statistics.median(c[2] for c in cells) > SITE_MAX_CELL_RANGE_M:
            # Соты с радиусами в десятки километров — координаты ненадёжны (на полевых записях встречалась станция,
            # все соты которой отстояли на 16 км при радиусах 19–23 км). Отдельная сота с таким радиусом безвредна,
            # запись станции с радиусом 2 км — нет.
            continue
        rng = max(SITE_MIN_RANGE_M, int(2 * max(near)))
        site_records[sk] = (round(lat * 1e6), round(lon * 1e6), rng)

    out = io.BytesIO()
    allk = {k: (v[0], v[1], v[2]) for k, v in records.items()}
    allk.update(site_records)
    out.write(b"BKCL" + struct.pack(">ii", 1, len(allk)))
    for k in sorted(allk):
        la, lo, rng = allk[k]
        out.write(struct.pack(">qiii", k, la, lo, rng))
    with gzip.open(dst, "wb", compresslevel=9) as g:
        g.write(out.getvalue())
    by = defaultdict(int)
    for k in records:
        by[k >> 51] += 1
    src_count = defaultdict(int)
    for k in records:
        src_count[origin[k]] += 1
    print(f"сот: {len(records)} (GSM, UMTS, LTE, NR: {[by[i] for i in range(4)]}); станций LTE: {len(site_records)}")
    for s, c in src_count.items():
        print(f"  из {s}: {c}")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2:])
