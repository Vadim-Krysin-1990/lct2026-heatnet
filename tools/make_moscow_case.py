"""Синтетический проверочный набор для другого района Москвы + эталонная трасса.

ВАЖНО: это не выгрузка из рабочих систем. Геометрия построена генератором, чтобы проверить сервис
на данных, не похожих на конкурсный набор: другой район, другой азимут застройки, другой состав
ограничений (дорога, трамвайные пути, железная дорога, река, парк) и другая конфигурация сети.
Эталонная трасса — то, как проложил бы инженер «по-старому»: вдоль проездов, прямыми углами;
она нужна только для сравнения геометрии и в расчёт не входит (object_type reference_route).

Район: Северное Бутово, между Куликовской улицей и Феодосийской — прямоугольная сетка кварталов
с азимутом около 12°, железная дорога курского направления с востока, река Битца с юга.

Использование: make_moscow_case.py <out_dataset.geojson> [out_reference.geojson]
"""
import json, math, sys

OUT = sys.argv[1]
REF = sys.argv[2] if len(sys.argv) > 2 else None

# центр района и азимут застройки
LON0, LAT0 = 37.5658, 55.5721
AZ = math.radians(12.0)
M_LAT = 1 / 111320.0
M_LON = 1 / (111320.0 * math.cos(math.radians(LAT0)))


def pt(x, y):
    """Локальные метры (x — вдоль застройки, y — поперёк) в WGS 84."""
    ex = x * math.cos(AZ) - y * math.sin(AZ)
    ey = x * math.sin(AZ) + y * math.cos(AZ)
    return [round(LON0 + ex * M_LON, 8), round(LAT0 + ey * M_LAT, 8)]


def rect(cx, cy, w, h):
    return [[pt(cx - w / 2, cy - h / 2), pt(cx + w / 2, cy - h / 2),
             pt(cx + w / 2, cy + h / 2), pt(cx - w / 2, cy + h / 2), pt(cx - w / 2, cy - h / 2)]]


feats = []


def add(props, geom):
    feats.append({"type": "Feature", "properties": props, "geometry": geom})


# --- существующая тепловая сеть: магистраль с запада на восток и отвод на север
src = pt(-520, -170)
net_nodes = [pt(-520, -170), pt(-330, -170), pt(-140, -170), pt(60, -170), pt(250, -170)]
add({"id": "S1", "object_type": "source", "name": "РТС «Южное Бутово» (условный источник)"},
    {"type": "Point", "coordinates": src})
dn_trunk = [600, 600, 500, 500]
for i in range(4):
    add({"id": f"HN{i + 1}", "object_type": "heat_network", "diameter": dn_trunk[i],
         "flow_tph": 1450 - i * 120,
         "upstream_object_id": "S1" if i == 0 else f"HN{i}"},
        {"type": "LineString", "coordinates": [net_nodes[i], net_nodes[i + 1]]})
# отвод на север от третьей камеры
branch = [pt(-140, -170), pt(-140, -40), pt(-140, 90)]
for i in range(2):
    add({"id": f"HN{5 + i}", "object_type": "heat_network", "diameter": 300, "flow_tph": 260 - i * 60,
         "upstream_object_id": "HN2" if i == 0 else f"HN{4 + i}"},
        {"type": "LineString", "coordinates": [branch[i], branch[i + 1]]})
# существующие камеры в узлах
for i, c in enumerate([net_nodes[1], net_nodes[2], net_nodes[3], branch[1], branch[2]], start=1):
    add({"id": f"CH{i}", "object_type": "heat_chamber", "diameter": 500 if i <= 3 else 300,
         "upstream_object_id": "S1"},
        {"type": "Point", "coordinates": c})

# --- застройка: два ряда существующих домов и семь перспективных ОКС
existing = [(-430, 60, 70, 24), (-300, 60, 84, 24), (-170, 60, 70, 24), (-40, 60, 96, 24),
            (110, 60, 70, 24), (240, 60, 84, 24),
            (-430, 200, 60, 30), (-260, 200, 60, 30), (-90, 200, 60, 30), (80, 200, 60, 30),
            (-350, -60, 48, 48), (-60, -60, 48, 48), (200, -60, 48, 48)]
for i, (cx, cy, w, h) in enumerate(existing, start=1):
    add({"id": f"B{i}", "object_type": "restriction", "restriction_type": "oks_existing",
         "name": f"существующий дом {i}"},
        {"type": "Polygon", "coordinates": rect(cx, cy, w, h)})

# перспективные ОКС: полигон + точка подключения на границе, обращённой к сети
new_oks = [  # cx, cy, w, h, нагрузка Гкал/ч
    (-400, 330, 72, 28, 4.6),
    (-230, 330, 90, 28, 6.1),
    (-60, 330, 72, 28, 3.9),
    (110, 330, 66, 28, 2.8),
    (280, 200, 60, 34, 5.2),
    (300, 60, 70, 26, 3.4),
    (-500, 200, 56, 34, 2.1),
]
for i, (cx, cy, w, h, load) in enumerate(new_oks, start=1):
    add({"id": f"N{i}", "object_type": "restriction", "restriction_type": "oks_future",
         "name": f"перспективный ОКС {i}"},
        {"type": "Polygon", "coordinates": rect(cx, cy, w, h)})
    flow = round(load * 1000 / (70 - 40) / 1.163 / 10, 2) * 10   # Гкал/ч → т/ч при ΔT 30 °C
    add({"id": f"P{i}", "object_type": "oks_connection_point", "oks_id": f"N{i}",
         "heat_load": load, "flow_tph": round(load * 1000 / (70 - 40) / 1.163, 2)},
        {"type": "Point", "coordinates": pt(cx, cy - h / 2 - 1)})

# --- прочие ограничения
add({"id": "R1", "object_type": "restriction", "restriction_type": "road",
     "name": "Куликовская улица (условно)"},
    {"type": "Polygon", "coordinates": rect(-60, 140, 1100, 22)})
add({"id": "R2", "object_type": "restriction", "restriction_type": "road",
     "name": "внутриквартальный проезд"},
    {"type": "Polygon", "coordinates": rect(-60, -110, 1100, 14)})
add({"id": "T1", "object_type": "restriction", "restriction_type": "tram_tracks",
     "name": "трамвайные пути (условно)"},
    {"type": "Polygon", "coordinates": rect(170, 140, 12, 500)})
add({"id": "Z1", "object_type": "restriction", "restriction_type": "railway",
     "name": "железная дорога курского направления (условно)"},
    {"type": "Polygon", "coordinates": rect(430, 60, 40, 900)})
add({"id": "W1", "object_type": "restriction", "restriction_type": "water",
     "name": "река Битца (условно)"},
    {"type": "Polygon", "coordinates": rect(-60, -300, 1200, 60)})
add({"id": "G1", "object_type": "restriction", "restriction_type": "park",
     "name": "Бутовский парк (условно)"},
    {"type": "Polygon", "coordinates": rect(-430, -20, 150, 90)})
add({"id": "SO1", "object_type": "restriction", "restriction_type": "social_area",
     "name": "территория школы (условно)"},
    {"type": "Polygon", "coordinates": rect(20, 200, 120, 70)})

with open(OUT, "w", encoding="utf-8") as f:
    json.dump({"type": "FeatureCollection", "name": "moscow_butovo_test_case",
               "description": "Синтетический проверочный набор (Северное Бутово, условная геометрия). "
                              "Не является выгрузкой из рабочих систем.",
               "crs": {"type": "name", "properties": {"name": "urn:ogc:def:crs:OGC:1.3:CRS84"}},
               "features": feats}, f, ensure_ascii=False)

# --- эталонная трасса: как проложил бы инженер — вдоль проездов, прямыми углами
if REF:
    ref = []
    # ствол от камеры CH2 на север через проезд и вдоль улицы, затем ответвления к каждому ОКС
    trunk = [pt(-140, -170), pt(-140, -40), pt(-140, 90), pt(-140, 265), pt(110, 265)]
    ref.append({"type": "Feature",
                "properties": {"id": "REF_trunk", "object_type": "reference_route",
                               "note": "эталон: ствол вдоль проезда и улицы, повороты 90°"},
                "geometry": {"type": "LineString", "coordinates": trunk}})
    taps = {"P1": [pt(-400, 265), pt(-400, 315)], "P2": [pt(-230, 265), pt(-230, 315)],
            "P3": [pt(-60, 265), pt(-60, 315)], "P4": [pt(110, 265), pt(110, 315)]}
    ref.append({"type": "Feature",
                "properties": {"id": "REF_trunk_west", "object_type": "reference_route",
                               "note": "эталон: западная часть ствола"},
                "geometry": {"type": "LineString", "coordinates": [pt(-140, 265), pt(-400, 265)]}})
    for pid, coords in taps.items():
        ref.append({"type": "Feature",
                    "properties": {"id": "REF_" + pid, "object_type": "reference_route",
                                   "note": f"эталон: ввод к точке {pid} по нормали к стене"},
                    "geometry": {"type": "LineString", "coordinates": coords}})
    with open(REF, "w", encoding="utf-8") as f:
        json.dump({"type": "FeatureCollection", "name": "moscow_butovo_reference_route",
                   "description": "Эталонная трасса для сравнения геометрии. Построена вручную "
                                  "по практике проектирования, в расчёт сервиса не входит.",
                   "crs": {"type": "name", "properties": {"name": "urn:ogc:def:crs:OGC:1.3:CRS84"}},
                   "features": ref}, f, ensure_ascii=False)

import os
print(f"набор: {OUT}, объектов {len(feats)}, {os.path.getsize(OUT) / 1024:.0f} КБ")
if REF:
    print(f"эталон: {REF}, линий {len(ref)}")
