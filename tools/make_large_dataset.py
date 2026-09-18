"""Генератор большого входного файла для проверки потоковой обработки (ТЗ 3.2, до 3 ГБ).
Тиражирует ограничения (здания) конкурсного набора по сетке N×N со сдвигом; сеть, источник и точки подключения
берутся один раз (в центральной плитке). Использование: make_large_dataset.py <in.geojson> <out.geojson> <N>"""
import json, sys
src, dst, n = sys.argv[1], sys.argv[2], int(sys.argv[3])
d = json.load(open(src))
feats = d["features"]
xs = [c[0] for f in feats for c in _iter(f["geometry"]["coordinates"])] if False else None
def walk(c, fn):
    if isinstance(c[0], (int, float)): return fn(c)
    return [walk(x, fn) for x in c]
lons, lats = [], []
for f in feats:
    walk(f["geometry"]["coordinates"], lambda c: (lons.append(c[0]), lats.append(c[1])))
dx = (max(lons) - min(lons)) * 1.1; dy = (max(lats) - min(lats)) * 1.1
restr = [f for f in feats if f["properties"]["object_type"] == "restriction"]
others = [f for f in feats if f["properties"]["object_type"] != "restriction"]
count = 0
with open(dst, "w") as out:
    out.write('{"type":"FeatureCollection","name":"large_synthetic","crs":{"type":"name","properties":{"name":"urn:ogc:def:crs:OGC:1.3:CRS84"}},"features":[\n')
    first = True
    for f in others:
        out.write(("" if first else ",\n") + json.dumps(f, ensure_ascii=False)); first = False; count += 1
    for i in range(n):
        for j in range(n):
            ox, oy = (i - n // 2) * dx, (j - n // 2) * dy
            for f in restr:
                g = dict(f["geometry"]); g["coordinates"] = walk(f["geometry"]["coordinates"], lambda c: [round(c[0] + ox, 8), round(c[1] + oy, 8)] + c[2:])
                p = dict(f["properties"]); p["id"] = f"{f['properties']['id']}_{i}_{j}"
                out.write(",\n" + json.dumps({"type": "Feature", "properties": p, "geometry": g}, ensure_ascii=False)); count += 1
    out.write("\n]}\n")
print("features", count)
