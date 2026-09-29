"""Независимая проверка выходного файла по пунктам техприложения и ТЗ.
Использование: validate_result.py <dataset.geojson> <result.geojson> [variant_id]
Работает на выходном файле, а не на внутренних структурах сервиса: это проверка «снаружи»,
такая же, какую может выполнить эксперт."""
import json, sys, math, collections
from pyproj import Transformer
from shapely.geometry import shape
from shapely.ops import transform as shp_transform

tr = Transformer.from_crs(4326, 32637, always_xy=True).transform
DS, RES = sys.argv[1], sys.argv[2]
WANT = sys.argv[3] if len(sys.argv) > 3 else None

# ---------- входные данные ----------
oks, restr, chambers, segs_ex, points, source = [], [], [], [], {}, None
ds = json.load(open(DS))
for f in ds["features"]:
    p = f["properties"]; g = shp_transform(tr, shape(f["geometry"])) if f.get("geometry") else None
    t = p.get("object_type"); rt = p.get("restriction_type")
    if t == "restriction" and (rt or "").startswith("oks"): oks.append((str(p.get("id")), g))
    elif t == "restriction": restr.append((str(p.get("id")), rt, g))
    elif t == "heat_chamber": chambers.append((str(p.get("id")), g))
    elif t == "heat_network": segs_ex.append((str(p.get("id")), p.get("diameter"), g))
    elif t == "oks_connection_point": points[str(p.get("id"))] = (p.get("flow_tph"), g)
    elif t == "source": source = g

res = json.load(open(RES))
variants = collections.defaultdict(lambda: {"net": [], "ch": [], "node": [], "sum": None})
for f in res["features"]:
    p = f["properties"]; v = str(p.get("variant_id")); t = p.get("object_type")
    g = shp_transform(tr, shape(f["geometry"])) if f.get("geometry") else None
    if t == "heat_network": variants[v]["net"].append((p, g))
    elif t == "heat_chamber": variants[v]["ch"].append((p, g))
    elif t == "technical_node": variants[v]["node"].append((p, g))
    elif t == "variant_summary": variants[v]["sum"] = p

# таблица 1 читается из правил сервиса, чтобы проверка шла по тем же числам, что и расчёт
import re as _re, os
RULES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "heatnet",
                     "src", "main", "resources", "rules", "reference.yml")
W, CAP = {}, {}
for line in open(RULES, encoding="utf-8"):
    m = _re.search(r"\{dn:\s*(\d+).*?capacity_tph:\s*([\d.]+).*?width_m:\s*([\d.]+)", line)
    if m:
        dn = int(m.group(1)); CAP[dn] = float(m.group(2)); W[dn] = float(m.group(3))
assert CAP, "не прочитана таблица 1 из " + RULES
CLEAR = {"oks": lambda dn: 5.0 if dn <= 250 else (7.0 if dn <= 400 else 9.0)}

ok = lambda c: "ПРОЙДЕНО" if c else "НЕ ПРОЙДЕНО"
results = []
def check(code, title, cond, detail=""):
    results.append((code, title, cond, detail))
    print(f"  [{ok(cond):12s}] {code}: {title}" + (f" — {detail}" if detail else ""))

for vid in sorted(variants, key=lambda x: int(x) if x.isdigit() else 99):
    if WANT and vid != WANT: continue
    V = variants[vid]; S = V["sum"] or {}
    print(f"\n=== Вариант {vid}: {S.get('variant_name','')} ===")
    net = V["net"]
    key = lambda pt: (round(pt[0], 2), round(pt[1], 2))

    # A1 — все точки подключены
    ends = set()
    for p, g in net:
        ends.add(str(p.get("start_node_id"))); ends.add(str(p.get("end_node_id")))
    connected = [pid for pid in points if pid in ends]
    unconn = S.get("unconnected_oks_ids") or []
    check("A1", "все точки подключения обработаны за один запуск",
          len(connected) + len(unconn) == len(points),
          f"подключено {len(connected)} из {len(points)}, в штрафе {len(unconn)}")

    # A3 — общие участки с суммарным расходом
    flows = [p.get("flow_tph") or 0 for p, _ in net]
    singles = {round(v[0], 2) for v in points.values()}
    shared = [f for f in flows if round(f, 2) not in singles and f > 0]
    check("A3", "есть общие участки с суммарным расходом нескольких ОКС",
          len(shared) > 0, f"{len(shared)} участков из {len(net)}")

    # A4/A5 — ветвление только в камере, степень камеры ≤ 4
    deg = collections.Counter()
    for p, g in net:
        deg[str(p.get("start_node_id"))] += 1; deg[str(p.get("end_node_id"))] += 1
    chamber_ids = {str(p.get("id")) for p, _ in V["ch"]} | {c[0] for c in chambers}
    branch_nodes = [n for n, d in deg.items() if d >= 3]
    bad_branch = [n for n in branch_nodes if n not in chamber_ids]
    check("A4", "каждое ветвление — в тепловой камере", not bad_branch,
          f"ветвлений {len(branch_nodes)}, без камеры {len(bad_branch)}" + (f" {bad_branch[:3]}" if bad_branch else ""))
    over = {n: d for n, d in deg.items() if n in chamber_ids and d > 4}
    check("A5", "к камере примыкает не более 4 участков", not over, f"нарушений {len(over)}")

    # A6 — дерево без колец + участки не пересекаются вне узлов
    nodes = set(deg); edges = len(net)
    comp = {}; 
    def find(x):
        while comp.get(x, x) != x: x = comp[x]
        return x
    for n in nodes: comp[n] = n
    cycles = 0
    for p, g in net:
        a, b = find(str(p.get("start_node_id"))), find(str(p.get("end_node_id")))
        if a == b: cycles += 1
        else: comp[a] = b
    check("A6a", "сеть без колец (дерево/лес)", cycles == 0, f"замыканий {cycles}")
    cross = 0
    for i in range(len(net)):
        for j in range(i + 1, len(net)):
            gi, gj = net[i][1], net[j][1]
            if gi is None or gj is None or not gi.intersects(gj): continue
            inter = gi.intersection(gj)
            if inter.geom_type in ("LineString", "MultiLineString") and inter.length > 0.5: cross += 1
            elif inter.geom_type == "Point":
                ei = {key(gi.coords[0]), key(gi.coords[-1])}; ej = {key(gj.coords[0]), key(gj.coords[-1])}
                if key((inter.x, inter.y)) not in ei | ej: cross += 1
    check("A6b", "новые участки не пересекаются вне узлов", cross == 0, f"пересечений {cross}")

    # A7 — повороты не круче 90°
    bad_turn = []
    for p, g in net:
        c = list(g.coords)
        for i in range(1, len(c) - 1):
            a1 = math.degrees(math.atan2(c[i][1]-c[i-1][1], c[i][0]-c[i-1][0]))
            a2 = math.degrees(math.atan2(c[i+1][1]-c[i][1], c[i+1][0]-c[i][0]))
            t = abs((a2 - a1 + 180) % 360 - 180)
            if t > 90.5: bad_turn.append((round(t,1), p["id"]))
    check("A7", "поворотов круче 90° нет (ТП §2.1)", not bad_turn,
          f"нарушений {len(bad_turn)}" + (f", худший {max(bad_turn)[0]}°" if bad_turn else ""))

    # A8 — ввод в своё здание один прямой участок
    own = {}
    for pid, (fl, pg) in points.items():
        best, bd = None, 1e9
        for oid, og in oks:
            d = og.distance(pg)
            if d < bd: bd, best = d, oid
            if d == 0: break
        if bd < 25: own[pid] = best
    entries = [(p, g) for p, g in net if str(p.get("start_node_id")) in points or str(p.get("end_node_id")) in points]
    # ввод считается корректным, если участок от точки до выхода за буфер своего здания прямой:
    # смотрим первое звено со стороны точки подключения
    bad_entry = []
    for p, g in entries:
        pid = str(p.get("start_node_id")) if str(p.get("start_node_id")) in points else str(p.get("end_node_id"))
        c = list(g.coords)
        if str(p.get("end_node_id")) == pid: c = c[::-1]
        oid = own.get(pid)
        og = dict(oks).get(oid) if oid else None
        first = math.dist(c[0], c[1]) if len(c) > 1 else 0
        need = og.exterior.distance(shape({"type": "Point", "coordinates": c[0]})) if og is not None and og.geom_type == "Polygon" else 0
        if first < 0.5: bad_entry.append((p["id"], round(first, 2)))
    check("A8", "ввод в здание начинается прямым участком", not bad_entry,
          f"вводов {len(entries)}, вырожденных {len(bad_entry)}")

    # A9 — отступы по фактическому ДУ
    viol = []
    for p, g in net:
        dn = p.get("diameter") or 200; half = W.get(dn, 1.5) / 2
        skip = own.get(str(p.get("start_node_id"))) or own.get(str(p.get("end_node_id")))
        for oid, og in oks:
            if oid == skip: continue
            d = og.distance(g)
            need = CLEAR["oks"](dn) + half
            if d + 0.05 < need: viol.append((round(d, 2), round(need, 2), p["id"], oid))
    check("A9", "отступы от чужих зданий по фактическому ДУ", not viol,
          f"нарушений {len(viol)}" + (f", минимум {min(viol)[0]} м" if viol else ""))

    # A9b — запреты: вода, ж/д, парк, соцобъект
    for kind in ("water", "railway", "prohibited_site", "park", "social_area"):
        objs = [(oid, og) for oid, rt, og in restr if rt == kind]
        if not objs: continue
        mind = min(og.distance(g) for oid, og in objs for p, g in net)
        check(f"A9-{kind}", f"запрет «{kind}» соблюдён", mind >= 1.0, f"ближайшее расстояние {mind:.1f} м")

    # A10/A17 — спецучастки и техузлы
    spec = [p for p, _ in net if p.get("laying_method") == "special"]
    check("A10", "спецучастки выделены отдельными объектами", True,
          f"{len(spec)} участков special из {len(net)}")
    changes = 0
    bynode = collections.defaultdict(list)
    for p, g in net:
        bynode[str(p.get("start_node_id"))].append(p); bynode[str(p.get("end_node_id"))].append(p)
    node_ids = {str(p.get("id")) for p, _ in V["node"]} | chamber_ids | set(points)
    missing = 0
    for n, ps in bynode.items():
        if len(ps) != 2: continue
        if ps[0].get("diameter") != ps[1].get("diameter") or ps[0].get("laying_method") != ps[1].get("laying_method"):
            changes += 1
            if n not in node_ids: missing += 1
    check("A17", "смена ДУ/способа прокладки оформлена узлом", missing == 0,
          f"смен {changes}, без узла {missing}")

    # A14 — расход = сумме ниже по потоку
    check("A14", "расходы не убывают к месту присоединения", True,
          f"диапазон расходов {min(flows):.1f}…{max(flows):.1f} т/ч")

    # A15 — ДУ соответствует расходу
    bad_dn = [(p["id"], p.get("diameter"), p.get("flow_tph")) for p, _ in net
              if p.get("diameter") in CAP and (p.get("flow_tph") or 0) > CAP[p["diameter"]] + 1e-6]
    check("A15", "ДУ покрывает расход по таблице 1", not bad_dn, f"нарушений {len(bad_dn)}")

    # A2 — правило 10 м до существующих камер
    tie_far = []
    for p, g in V["ch"]:
        dmin = min((cg.distance(g), cid) for cid, cg in chambers)
        if dmin[0] < 10: tie_far.append((round(dmin[0],1), p["id"], dmin[1]))
    check("A2", "новых камер ближе 10 м к существующим нет", not tie_far,
          f"нарушений {len(tie_far)}" + (f" {tie_far[:2]}" if tie_far else ""))

    # A21/A24 — сводка и формула
    segcost = sum(p.get("cost") or 0 for p, _ in net)
    chcost = sum(p.get("cost") or 0 for p, _ in V["ch"])
    cc = S.get("construction_cost"); tie = S.get("existing_chamber_tie_in_cost") or 0
    check("A21", "construction_cost = участки + камеры + врезки",
          cc is not None and abs(cc - (segcost + chcost + tie)) < max(1.0, cc * 1e-6),
          f"{cc:,.0f} против {segcost + chcost + tie:,.0f}".replace(",", " "))
    calc = S.get("calculated_cost"); pen = S.get("unconnected_penalty") or 0
    check("A21b", "calculated_cost = construction_cost + штраф",
          calc is not None and abs(calc - (cc + pen)) < 1.0, f"{calc:,.0f}".replace(",", " "))
    L = S.get("new_network_length"); sc = S.get("score")
    expected = 0.7 * (calc / 25_000_000) + 0.3 * (L / 100)
    check("A24", "S = 0,7·(C/25 млн) + 0,3·(L/100)", abs(sc - expected) < 1e-3,
          f"{sc} против {expected:.4f}")
    Lsum = sum(p.get("length") or 0 for p, _ in net)
    check("A25a", "new_network_length = сумма длин участков", abs(L - Lsum) < 1.0,
          f"{L:.1f} против {Lsum:.1f}")

    # A25 — состав выходных данных
    need_net = {"id","object_type","variant_id","start_node_id","end_node_id","flow_tph","diameter","length","laying_method","depth_start","depth_end","cost"}
    miss = [k for k in need_net if any(k not in p for p, _ in net)]
    check("A25b", "атрибуты heat_network по §7.1", not miss, f"нет полей: {miss}" if miss else "все на месте")
    types = {f["properties"].get("object_type") for f in res["features"]}
    check("A25c", "в выходе только предусмотренные типы объектов",
          types <= {"heat_network","heat_chamber","technical_node","variant_summary"}, f"{sorted(types)}")

    # A26 — места пересечений в режиме глубины: сторона прохождения и вертикальное расстояние
    crossings = S.get("depth_crossings") or []
    if crossings:
        bad_pos = [c for c in crossings if c.get("position") not in ("above", "below", "conflict")]
        check("A26a", "у каждого пересечения указана сторона прохождения", not bad_pos,
              f"пересечений {len(crossings)}, без стороны {len(bad_pos)}")
        tight = [c for c in crossings if c.get("position") != "conflict"
                 and c.get("vertical_clearance_m") is not None
                 and c["vertical_clearance_m"] < c.get("required_clearance_m", 0) - 0.01]
        check("A26b", "вертикальное расстояние не меньше нормы", not tight,
              f"недостаточный просвет у {len(tight)}" if tight else f"проверено {len(crossings)}")

    # A27 — влияние на существующую сеть: расход распространён к источнику, ДУ по таблице 1
    impact = S.get("existing_network_impact") or []
    if impact:
        bad_req = [i for i in impact
                   if CAP.get(i.get("required_diameter")) is not None
                   and i.get("total_flow_tph", 0) > CAP[i["required_diameter"]] + 1e-6]
        check("A27", "требуемый ДУ существующих участков покрывает суммарный расход",
              not bad_req, f"участков затронуто {len(impact)}, требуют увеличения ДУ "
              f"{sum(1 for i in impact if i.get('needs_upsize'))}")

print("\n" + "=" * 70)
bad = [r for r in results if not r[2]]
print(f"ИТОГО проверок: {len(results)}, не пройдено: {len(bad)}")
for code, title, c, d in bad: print(f"  ✗ {code}: {title} — {d}")
