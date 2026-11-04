#!/usr/bin/env python3
"""Сборка файла рельефа BK-PHENIX (BKDM v1) для Москвы и области.

Источники (оба без аккаунта):
- Copernicus DEM GLO-90 (тайлы COG с s3://copernicus-dem-90m, AWS Open Data). Высоты —
  ортометрические относительно геоида EGM2008. © DLR e.V. 2010-2014 and © Airbus Defence and Space
  GmbH 2014-2018 provided under COPERNICUS by the European Union and ESA; all rights reserved.
  Лицензия Copernicus DEM разрешает бесплатное распространение с этой атрибуцией.
- Модель геоида EGM2008 (сетка 2.5′ NGA, us_nga_egm08_25.tif из cdn.proj.org; общественное достояние).

Формат (big-endian, затем gzip):
  "BKDM", int версия=1,
  рельеф:  double юг, double запад, double шаг по широте °, double шаг по долготе °, int строк, int столбцов,
           short[строк*столбцов] высота над геоидом в дм (с юга на север, с запада на восток; -32768 — нет данных),
  геоид:   double юг, double запад, double шаг °, int строк, int столбцов, short[...] N в см (h = H + N).

Использование: build_dem.py КАТАЛОГ_С_TIF ВЫХОД.bin.gz [--bbox Ю,З,С,В]
"""
import glob, gzip, os, struct, sys
import numpy as np
import rasterio
from rasterio.merge import merge
from rasterio.enums import Resampling

BBOX = (54.2, 35.1, 57.0, 40.3)
DLAT, DLON = 1 / 1200, 1 / 800   # 3″ × 4.5″ ≈ 93 × 80 м — родная сетка GLO-90 на этих широтах
GEOID_STEP = 1 / 24              # 2.5′
NODATA = -32768


def build(src, dst, bbox):
    s, w, n, e = bbox
    tiles = sorted(glob.glob(os.path.join(src, "Copernicus_DSM_COG_*_DEM.tif")))
    if not tiles:
        sys.exit("нет тайлов Copernicus в " + src)
    ds = [rasterio.open(t) for t in tiles]
    dem, _ = merge(ds, bounds=(w, s, e, n), res=(DLON, DLAT), nodata=np.nan, resampling=Resampling.bilinear)
    dem = dem[0][::-1]  # merge даёт север→юг; храним юг→север
    rows, cols = dem.shape
    dm = np.where(np.isfinite(dem), np.round(dem * 10), NODATA).astype(">i2")

    with rasterio.open(os.path.join(src, "egm08_25.tif")) as g:
        gs, gw = s - GEOID_STEP, w - GEOID_STEP
        grows = int(np.ceil((n - s) / GEOID_STEP)) + 3
        gcols = int(np.ceil((e - w) / GEOID_STEP)) + 3
        lats = gs + np.arange(grows) * GEOID_STEP
        lons = gw + np.arange(gcols) * GEOID_STEP
        pts = [(lo, la) for la in lats for lo in lons]
        und = np.array([v[0] for v in g.sample(pts)]).reshape(grows, gcols)
    cm = np.round(und * 100).astype(">i2")

    with gzip.open(dst, "wb", compresslevel=9) as f:
        f.write(b"BKDM" + struct.pack(">i", 1))
        f.write(struct.pack(">ddddii", s, w, DLAT, DLON, rows, cols))
        f.write(dm.tobytes())
        f.write(struct.pack(">dddii", gs, gw, GEOID_STEP, grows, gcols))
        f.write(cm.tobytes())
    valid = dem[np.isfinite(dem)]
    print(f"рельеф {rows}×{cols}, высоты {valid.min():.0f}…{valid.max():.0f} м, нет данных {np.isnan(dem).sum()}; "
          f"геоид {grows}×{gcols}, N {und.min():.2f}…{und.max():.2f} м")


if __name__ == "__main__":
    args = sys.argv[1:]
    bbox = BBOX
    if "--bbox" in args:
        i = args.index("--bbox")
        bbox = tuple(float(x) for x in args[i + 1].split(","))
        del args[i:i + 2]
    if len(args) != 2:
        sys.exit(__doc__)
    build(args[0], args[1], bbox)
