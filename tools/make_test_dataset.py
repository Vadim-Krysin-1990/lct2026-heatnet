"""Проверочный набор заданного объёма на основе конкурсного.

Отличается от конкурсного двумя вещами, чтобы на нём было видно, что сервис считает данные,
а не выдаёт заранее известный ответ:
  * подключается другое множество точек присоединения (по умолчанию каждая вторая) — меняются
    длина, диаметры и стоимость;
  * пространственные ограничения тиражируются по сетке плиток со сдвигом, пока файл не наберёт
    заданный объём, — так проверяется потоковый разбор и отсечение объектов вне области расчёта.

Источник, существующая сеть и камеры берутся один раз: подключаться нужно к реальной сети.

Использование: make_test_dataset.py <in.geojson> <out.geojson> <целевой размер, МБ> [шаг точек]
"""
import json, sys

src, dst, target_mb = sys.argv[1], sys.argv[2], float(sys.argv[3])
step = int(sys.argv[4]) if len(sys.argv) > 4 else 2
target = int(target_mb * 1024 * 1024)

d = json.load(open(src, encoding="utf-8"))
feats = d["features"]


def walk(c, fn):
    if isinstance(c[0], (int, float)):
        return fn(c)
    return [walk(x, fn) for x in c]


lons, lats = [], []
for f in feats:
    walk(f["geometry"]["coordinates"], lambda c: (lons.append(c[0]), lats.append(c[1])))
dx = (max(lons) - min(lons)) * 1.1
dy = (max(lats) - min(lats)) * 1.1

restr = [f for f in feats if f["properties"]["object_type"] == "restriction"]
points = [f for f in feats if f["properties"]["object_type"] == "oks_connection_point"]
others = [f for f in feats if f["properties"]["object_type"] not in ("restriction", "oks_connection_point")]
kept = points[::step]

# плитки кольцами от центра: (0,0) — исходная сцена, остальные уходят в стороны, без повторов
def tiles():
    yield 0, 0
    r = 1
    while True:
        ring = set()
        for i in range(-r, r + 1):
            ring.add((i, -r)); ring.add((i, r)); ring.add((-r, i)); ring.add((r, i))
        for t in sorted(ring):
            yield t
        r += 1


count = 0
with open(dst, "w", encoding="utf-8") as out:
    out.write('{"type":"FeatureCollection","name":"heatnet_test_set",'
              '"crs":{"type":"name","properties":{"name":"urn:ogc:def:crs:OGC:1.3:CRS84"}},"features":[\n')
    first = True
    for f in others + kept:
        out.write(("" if first else ",\n") + json.dumps(f, ensure_ascii=False))
        first = False
        count += 1
    for i, j in tiles():
        if out.tell() >= target:
            break
        for f in restr:
            g = dict(f["geometry"])
            g["coordinates"] = walk(f["geometry"]["coordinates"],
                                    lambda c: [round(c[0] + i * dx, 8), round(c[1] + j * dy, 8)] + c[2:])
            p = dict(f["properties"])
            if (i, j) != (0, 0):
                p["id"] = f"{f['properties']['id']}_{i}_{j}"
            out.write(",\n" + json.dumps({"type": "Feature", "properties": p, "geometry": g}, ensure_ascii=False))
            count += 1
    out.write("\n]}\n")

import os
print(f"объектов {count}, точек присоединения {len(kept)} из {len(points)}, "
      f"размер {os.path.getsize(dst) / 1048576:.1f} МБ")
