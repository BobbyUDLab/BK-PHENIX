#!/usr/bin/env python3
"""Сборка офлайн-базы сотовых вышек для приложения из выгрузки Mozilla Location Service.

Источник: финальная выгрузка MLS (март 2024), CC0 1.0 — Mozilla отказалась от авторских и смежных прав
на MLS Cell Network Export Data, поэтому файл можно включать в APK; авторство указываем как жест вежливости.
Скачать без аккаунта (копия Internet Archive):
  https://web.archive.org/web/20240504021731/https://d2koia3g127518.cloudfront.net/final_export/MLS-full-cell-export-final.csv.gz

Использование:
  python3 build_cell_db.py MLS-full-cell-export-final.csv.gz app/src/main/assets/cells/cells-moscow.bin.gz

Фильтр: MCC 250 (Россия), рамка Москвы и Московской области (широта 54.2–57.0, долгота 35.1–40.3).

Формат (big-endian, затем gzip): b"BKCL", версия int32 = 1, число записей int32,
далее записи, отсортированные по ключу: ключ int64, широта·1e6 int32, долгота·1e6 int32, радиус соты int32 (м).
Ключ — см. CellKey в приложении: технология (2 бита) << 51 | MNC (7 бит) << 44 | идентификатор (44 бита):
GSM — LAC << 16 | CID, UMTS — LAC << 28 | UC-ID, LTE — ECI (28 бит), NR — NCI (36 бит).
"""
import csv, gzip, io, struct, sys

LAT = (54.2, 57.0)
LON = (35.1, 40.3)
TECH = {"GSM": 0, "UMTS": 1, "LTE": 2, "NR": 3}


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


def main(src, dst):
    records = {}
    with gzip.open(src, "rt", newline="") as f:
        for row in csv.DictReader(f):
            if row["mcc"] != "250":
                continue
            lat, lon = float(row["lat"]), float(row["lon"])
            if not (LAT[0] <= lat <= LAT[1] and LON[0] <= lon <= LON[1]):
                continue
            tech = TECH.get(row["radio"])
            mnc, lac, cid = int(row["net"]), int(row["area"] or 0), int(row["cell"] or 0)
            if tech is None or mnc > 127 or lac > 0xFFFF or cid <= 0:
                continue
            k = key(tech, mnc, lac, cid)
            samples = int(row["samples"] or 1)
            rng = int(row["range"] or 0)
            prev = records.get(k)
            if prev is None or samples > prev[3]:
                records[k] = (round(lat * 1e6), round(lon * 1e6), rng, samples)
    out = io.BytesIO()
    out.write(b"BKCL" + struct.pack(">ii", 1, len(records)))
    for k in sorted(records):
        lat, lon, rng, _ = records[k]
        out.write(struct.pack(">qiii", k, lat, lon, rng))
    with gzip.open(dst, "wb", compresslevel=9) as g:
        g.write(out.getvalue())
    by = {}
    for k in records:
        by[k >> 51] = by.get(k >> 51, 0) + 1
    print(f"записей: {len(records)}; по технологиям (GSM, UMTS, LTE, NR): {[by.get(i, 0) for i in range(4)]}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
