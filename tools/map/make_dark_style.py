#!/usr/bin/env python3
"""Генерирует тёмный OLED-стиль карты из светлого: app/src/main/assets/map/style-ru.json → style-ru-dark.json.

Палитра — из docs/brand/logo.jpg: чёрный фон, тёмно-синие слои, оранжевые магистрали, циановая вода.
Запуск: python3 tools/map/make_dark_style.py
"""
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "app/src/main/assets/map/style-ru.json"
DST = ROOT / "app/src/main/assets/map/style-ru-dark.json"

TEXT, TEXT_STRONG, HALO = "#A9B8CC", "#E6EDF7", "#000000"


def fill_color(lid):
    if lid in ("background", "earth"):
        return "#000000"
    if lid.startswith("water"):
        return "#00202F"
    if lid == "buildings":
        return "#141E30"
    if "park" in lid or "green" in lid:
        return "#04140E"
    if lid.startswith("roads") or "runway" in lid:
        return "#1A2638"
    return "#070E1A"


def line_color(lid):
    if lid.startswith("water"):
        return "#0B4058"
    if "casing" in lid:
        return "#000000"
    if "rail" in lid:
        return "#375988"
    if lid.startswith("boundaries"):
        return "#4A6A9A"
    if "tunnels" in lid or "runway" in lid or "taxiway" in lid:
        return "#1A2638"
    if "highway" in lid:
        return "#B04A10"
    if "major" in lid:
        return "#3A5478"
    return "#22324A"


def text_color(lid):
    if lid.startswith("water"):
        return "#3FB9E0"
    if lid in ("places_locality", "places_country"):
        return TEXT_STRONG
    return TEXT


def main():
    style = json.loads(SRC.read_text(encoding="utf-8"))
    for layer in style["layers"]:
        lid, kind, paint = layer["id"], layer["type"], layer.setdefault("paint", {})
        if kind == "background":
            paint["background-color"] = fill_color(lid)
        elif kind == "fill":
            paint["fill-color"] = fill_color(lid)
        elif kind == "line":
            paint["line-color"] = line_color(lid)
        elif kind == "symbol":
            # Цвета POI светлого стиля плохо читаются на чёрном — общий цвет подписей.
            paint["text-color"] = text_color(lid)
            paint["text-halo-color"] = HALO
            paint.setdefault("text-halo-width", 1)
    style["sprite"] = style["sprite"].replace("sprites/light", "sprites/dark")
    style["name"] = style.get("name", "BK-PHENIX") + " (OLED)"
    DST.write_text(json.dumps(style, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
