#!/usr/bin/env python3
"""MCP-сервер ЦАТ: доступ ИИ-агента к расчётам трассировки тепловых сетей.

Говорит по протоколу MCP (JSON-RPC 2.0 через stdio) и переводит вызовы инструментов в обращения
к REST-интерфейсу сервиса. Зависимостей нет — только стандартная библиотека Python 3.8+, поэтому
сервер работает в закрытом контуре рядом с сервисом.

Переменные окружения:
  CATP_URL    адрес сервиса, по умолчанию http://localhost:8080
  CATP_TOKEN  токен доступа (нужен, если сервис запущен с обязательной проверкой токенов)

Запуск вручную для проверки:
  echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | CATP_URL=http://localhost:8080 python3 catp_mcp_server.py
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = os.environ.get("CATP_URL", "http://localhost:8080").rstrip("/")
TOKEN = os.environ.get("CATP_TOKEN")
TIMEOUT = float(os.environ.get("CATP_TIMEOUT", "1800"))
PROTOCOL = "2024-11-05"
VERSION = "1.0.0"


# ---------------------------------------------------------------- HTTP к сервису

def _request(method, path, body=None, content_type="application/json", raw_body=None):
    url = BASE + path
    data = raw_body if raw_body is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", content_type)
    if TOKEN:
        req.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            payload = r.read()
            ctype = r.headers.get("Content-Type", "")
            head = {k: v for k, v in r.headers.items() if k.lower().startswith("x-")}
            if "json" in ctype:
                return json.loads(payload.decode("utf-8")), head
            return payload.decode("utf-8", "replace"), head
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:800]
        raise RuntimeError(f"сервис ответил {e.code} на {method} {path}: {detail}")
    except urllib.error.URLError as e:
        raise RuntimeError(f"сервис недоступен по адресу {BASE}: {e.reason}. "
                           f"Проверьте CATP_URL и что сервис запущен")


def _multipart(path, file_path):
    """Отправка файла в multipart/form-data без внешних библиотек."""
    with open(file_path, "rb") as f:
        content = f.read()
    boundary = "----catp" + uuid.uuid4().hex
    name = os.path.basename(file_path)
    body = b"".join([
        f"--{boundary}\r\n".encode(),
        f'Content-Disposition: form-data; name="file"; filename="{name}"\r\n'.encode(),
        b"Content-Type: application/geo+json\r\n\r\n",
        content,
        f"\r\n--{boundary}--\r\n".encode(),
    ])
    return _request("POST", path, content_type=f"multipart/form-data; boundary={boundary}", raw_body=body)


# ---------------------------------------------------------------- работа с результатом

_last = {"result": None, "source": None}


def _summaries(fc):
    return [f["properties"] for f in fc.get("features", [])
            if f.get("properties", {}).get("object_type") == "variant_summary"]


def _result_or_fail(result_path):
    """Результат: из файла, если указан, иначе последний посчитанный в этой сессии."""
    if result_path:
        with open(result_path, "r", encoding="utf-8") as f:
            return json.load(f)
    if _last["result"] is None:
        raise RuntimeError("расчёта в этой сессии ещё не было — вызовите calculate "
                           "или передайте result_path к готовому выходному файлу")
    return _last["result"]


def _money(x):
    try:
        return f"{round(float(x)):,}".replace(",", " ") + " ₽"
    except (TypeError, ValueError):
        return "—"


def _variant_line(p):
    return (f"Вариант {p.get('variant_id')} (место {p.get('rank')}): {p.get('variant_name', '')} — "
            f"{p.get('variant_kind_title', '')}, длина {p.get('new_network_length')} м, "
            f"{_money(p.get('calculated_cost'))}, S={p.get('score')}, метод "
            f"{'граф видимости' if p.get('routing_method') == 'visibility' else 'растровая сетка'}, "
            f"поиск {p.get('routing_millis')} мс, инженерный расчёт {p.get('engineering_millis')} мс")


# ---------------------------------------------------------------- инструменты

def t_service_info(_):
    rules, _h = _request("GET", "/api/rules/routing")
    samples, _h2 = _request("GET", "/api/samples")
    lines = [f"Сервис ЦАТ доступен: {BASE}",
             f"Шаг растровой сетки: {rules.get('gridStepM')} м, предел поворота: {rules.get('maxTurnDeg')}°",
             "Образцы данных:"]
    for k, v in samples.items():
        lines.append(f"  {k}: {v.get('file')} — {'есть' if v.get('available') else 'нет'}")
    return "\n".join(lines)


def t_calculate(a):
    path = a["dataset_path"]
    if not os.path.isfile(path):
        raise RuntimeError(f"файла нет: {path}")
    q = {"methods": a.get("methods", "all")}
    if a.get("depth"):
        q["depth"] = "true"
    if a.get("variants"):
        q["variants"] = str(int(a["variants"]))
    size = os.path.getsize(path)
    if size > 200 * 1024 * 1024:
        job, _h = _multipart("/api/jobs?" + urllib.parse.urlencode(q), path)
        return (f"Файл {size / 1048576:.0f} МБ принят в очередь, задание {job['id']}. "
                f"Состояние — job_status, результат — get_variants после завершения.")
    fc, head = _multipart("/api/process?" + urllib.parse.urlencode(q), path)
    _last["result"] = fc
    _last["source"] = path
    sums = sorted(_summaries(fc), key=lambda p: p.get("rank", 99))
    out = [f"Расчёт выполнен за {head.get('X-Compute-Millis', '?')} мс, вариантов: {len(sums)}",
           f"Предупреждений в диагностике: {head.get('X-Diagnostics-Warnings', '0')}"]
    out += [_variant_line(p) for p in sums]
    for p in sums:
        for n in p.get("notes", []):
            out.append(f"  ! вариант {p.get('variant_id')}: {n}")
    if a.get("save_to"):
        with open(a["save_to"], "w", encoding="utf-8") as f:
            json.dump(fc, f, ensure_ascii=False)
        out.append(f"Выходной GeoJSON сохранён: {a['save_to']}")
    return "\n".join(out)


def t_job_status(a):
    st, _h = _request("GET", "/api/jobs/" + a["job_id"])
    if st.get("status") == "DONE":
        fc, _h2 = _request("GET", f"/api/jobs/{a['job_id']}/result")
        _last["result"] = fc
        _last["source"] = "задание " + a["job_id"]
    line = (f"Задание {st.get('id')}: {st.get('status')}, файл {st.get('original_filename')} "
            f"({(st.get('input_size') or 0) / 1048576:.1f} МБ)")
    if st.get("compute_millis"):
        line += f", расчёт {st['compute_millis'] / 1000:.1f} с, вариантов {st.get('variants_count')}"
    if st.get("error"):
        line += f", ошибка: {st['error']}"
    return line


def t_get_variants(a):
    fc = _result_or_fail(a.get("result_path"))
    sums = sorted(_summaries(fc), key=lambda p: p.get("rank", 99))
    if not sums:
        return "в файле нет вариантов расчёта"
    out = [_variant_line(p) for p in sums]
    for p in sums:
        if p.get("unconnected_oks_ids"):
            out.append(f"  вариант {p.get('variant_id')}: не подключено {p['unconnected_oks_ids']}")
        for n in p.get("notes", []):
            out.append(f"  ! вариант {p.get('variant_id')}: {n}")
    return "\n".join(out)


def t_compare_methods(a):
    fc = _result_or_fail(a.get("result_path"))
    sums = sorted(_summaries(fc), key=lambda p: p.get("rank", 99))
    by = {}
    for p in sums:
        m = p.get("routing_method", "grid")
        if m not in by or p.get("score", 9e9) < by[m].get("score", 9e9):
            by[m] = p
    if len(by) < 2:
        return ("в результате один метод поиска — посчитайте с methods=all, чтобы сравнить "
                "растровую сетку и граф видимости")
    ru = {"grid": "растровая сетка (A*)", "visibility": "граф видимости (Дейкстра)"}
    out = ["Сравнение методов на одних данных и одних правилах:"]
    for m, p in by.items():
        out.append(f"  {ru.get(m, m)}: {p.get('new_network_length')} м, {_money(p.get('calculated_cost'))}, "
                   f"S={p.get('score')}, поворотов на км {p.get('turns_per_km')}, "
                   f"медиана прямой {p.get('median_straight_m')} м, косых изломов {p.get('oblique_turns')}, "
                   f"поиск {p.get('routing_millis')} мс")
    best = min(by.values(), key=lambda p: p.get("score", 9e9))
    fast = min(by.values(), key=lambda p: p.get("routing_millis", 9e9))
    out.append(f"Лучший показатель S: {ru.get(best.get('routing_method'))} (S={best.get('score')}); "
               f"быстрее ищет: {ru.get(fast.get('routing_method'))} ({fast.get('routing_millis')} мс)")
    return "\n".join(out)


def t_get_segments(a):
    fc = _result_or_fail(a.get("result_path"))
    vid = str(a["variant_id"])
    rows = [f["properties"] for f in fc.get("features", [])
            if f.get("properties", {}).get("object_type") == "heat_network"
            and str(f["properties"].get("variant_id")) == vid]
    if not rows:
        return f"в варианте {vid} нет участков"
    out = [f"Ведомость участков варианта {vid} ({len(rows)} участков):"]
    for p in sorted(rows, key=lambda x: str(x.get("id"))):
        depth = ""
        if p.get("depth_start") is not None:
            depth = f", глубина {p['depth_start']}→{p['depth_end']} м"
        out.append(f"  {p.get('id')}: {p.get('start_node_id')} → {p.get('end_node_id')}, "
                   f"ДУ{p.get('diameter')}, {p.get('length')} м, расход {p.get('flow_tph')} т/ч, "
                   f"{'специальный проход' if p.get('laying_method') == 'special' else 'обычная прокладка'}"
                   f"{depth}, {_money(p.get('cost'))}")
    return "\n".join(out)


def t_get_estimate(a):
    fc = _result_or_fail(a.get("result_path"))
    vid = str(a["variant_id"])
    sums = [p for p in _summaries(fc) if str(p.get("variant_id")) == vid]
    if not sums:
        return f"варианта {vid} в результате нет"
    s = sums[0]
    segs = [f["properties"] for f in fc.get("features", [])
            if f.get("properties", {}).get("object_type") == "heat_network"
            and str(f["properties"].get("variant_id")) == vid]
    by_dn = {}
    for p in segs:
        d = by_dn.setdefault(p.get("diameter"), [0, 0.0, 0.0])
        d[0] += 1
        d[1] += float(p.get("length") or 0)
        d[2] += float(p.get("cost") or 0)
    out = [f"Смета варианта {vid}: итого {_money(s.get('calculated_cost'))}",
           f"  строительство участков и камер: {_money(s.get('construction_cost'))}",
           f"  из них камеры: {_money(s.get('chamber_construction_cost'))}",
           f"  врезки в существующие камеры: {s.get('existing_chamber_tie_in_count')} × "
           f"{_money(s.get('existing_chamber_tie_in_cost'))}",
           f"  штраф за неподключённые: {_money(s.get('unconnected_penalty'))}",
           "По диаметрам:"]
    for dn in sorted(by_dn):
        n, ln, cost = by_dn[dn]
        out.append(f"  ДУ{dn}: участков {n}, {ln:.1f} м, {_money(cost)}")
    return "\n".join(out)


def t_existing_impact(a):
    fc = _result_or_fail(a.get("result_path"))
    vid = str(a["variant_id"]) if a.get("variant_id") else None
    out = []
    for s in sorted(_summaries(fc), key=lambda p: p.get("rank", 99)):
        if vid and str(s.get("variant_id")) != vid:
            continue
        im = s.get("existing_network_impact") or []
        if not im:
            continue
        up = [i for i in im if i.get("needs_upsize")]
        out.append(f"Вариант {s.get('variant_id')}: затронуто участков существующей сети {len(im)}, "
                   f"требуют увеличения ДУ {len(up)}")
        for i in im:
            mark = " ← не хватает ДУ" if i.get("needs_upsize") else ""
            out.append(f"  участок {i.get('segment_id')}: ДУ{i.get('current_diameter')}, "
                       f"текущий расход {i.get('current_flow_tph')} т/ч"
                       f"{' (во входных данных не задан)' if not i.get('current_flow_known') else ''}, "
                       f"добавлено {i.get('added_flow_tph')} т/ч, итого {i.get('total_flow_tph')} т/ч, "
                       f"требуемый ДУ{i.get('required_diameter')}{mark}")
    return "\n".join(out) or "в результате нет данных о влиянии на существующую сеть"


def t_depth_crossings(a):
    fc = _result_or_fail(a.get("result_path"))
    out = []
    side = {"above": "сверху", "below": "снизу", "conflict": "пройти нельзя"}
    for s in sorted(_summaries(fc), key=lambda p: p.get("rank", 99)):
        cr = s.get("depth_crossings") or []
        if not cr:
            continue
        out.append(f"Вариант {s.get('variant_id')}: пересечений {len(cr)}")
        for c in cr:
            out.append(f"  {c.get('utility_type')} {c.get('utility_id')}: проход {side.get(c.get('position'))}, "
                       f"вертикальное расстояние {c.get('vertical_clearance_m')} м при норме "
                       f"{c.get('required_clearance_m')} м, глубина новой сети {c.get('new_top_depth_m')} м, "
                       f"участок {c.get('segment_id')} на {c.get('at_m')} м"
                       + (f" — {c['note']}" if c.get("note") else ""))
    return "\n".join(out) or ("пересечений по глубине в результате нет: посчитайте с depth=true "
                              "и на данных, где есть подземные коммуникации")


def t_diagnostics(a):
    if a.get("job_id"):
        d, _h = _request("GET", f"/api/jobs/{a['job_id']}/diagnostics")
        msgs = d.get("messages", [])
    else:
        raise RuntimeError("диагностика доступна по job_id: посчитайте через очередь (большой файл) "
                           "или укажите job_id уже выполненного задания")
    level = (a.get("level") or "").upper()
    rows = [m for m in msgs if not level or m.get("level") == level]
    out = [f"Сообщений: {len(rows)} из {len(msgs)}"]
    for m in rows[:int(a.get("limit", 60))]:
        out.append(f"  {m.get('level')} {m.get('code')}: {m.get('text')[:300]}")
    return "\n".join(out)


def t_get_rules(a):
    kind = a.get("kind", "restrictions")
    if kind not in ("reference", "restrictions", "routing"):
        raise RuntimeError("kind: reference, restrictions или routing")
    d, _h = _request("GET", "/api/rules/" + kind)
    return json.dumps(d, ensure_ascii=False, indent=1)


def t_events(a):
    q = {"size": str(int(a.get("limit", 30)))}
    if a.get("type"):
        q["type"] = a["type"]
    if a.get("level"):
        q["level"] = a["level"]
    d, _h = _request("GET", "/api/events?" + urllib.parse.urlencode(q))
    out = [f"Всего записей: {d.get('total')}"]
    for e in d.get("items", []):
        out.append(f"  {e.get('createdAt', '')[:19]} {e.get('level')} {e.get('type')}: {str(e.get('message'))[:200]}")
    return "\n".join(out)


TOOLS = [
    {"name": "service_info", "fn": t_service_info,
     "description": "Проверить, что сервис ЦАТ доступен, и показать действующие параметры трассировки "
                    "и список образцов данных.",
     "schema": {"type": "object", "properties": {}}},
    {"name": "calculate", "fn": t_calculate,
     "description": "Посчитать трассировку по входному GeoJSON. Файл до 200 МБ считается сразу, "
                    "больше — ставится в очередь (следите через job_status). Результат запоминается "
                    "для остальных инструментов.",
     "schema": {"type": "object", "required": ["dataset_path"], "properties": {
         "dataset_path": {"type": "string", "description": "путь к входному GeoJSON"},
         "methods": {"type": "string", "enum": ["grid", "visibility", "all"],
                     "description": "метод поиска: растровая сетка, граф видимости или оба (по умолчанию оба)"},
         "depth": {"type": "boolean", "description": "режим дополнительной задачи — с учётом глубины"},
         "variants": {"type": "integer", "description": "сколько вариантов вернуть"},
         "save_to": {"type": "string", "description": "куда сохранить выходной GeoJSON"}}}},
    {"name": "job_status", "fn": t_job_status,
     "description": "Состояние задания из очереди; по завершении подхватывает результат.",
     "schema": {"type": "object", "required": ["job_id"], "properties": {
         "job_id": {"type": "string"}}}},
    {"name": "get_variants", "fn": t_get_variants,
     "description": "Варианты последнего расчёта или из готового выходного файла: место, длина, "
                    "стоимость, показатель S, метод, время, пометки о ручной проработке.",
     "schema": {"type": "object", "properties": {
         "result_path": {"type": "string", "description": "путь к выходному GeoJSON (иначе последний расчёт)"}}}},
    {"name": "compare_methods", "fn": t_compare_methods,
     "description": "Сравнить методы поиска между собой: длина, стоимость, S, геометрия, время.",
     "schema": {"type": "object", "properties": {"result_path": {"type": "string"}}}},
    {"name": "get_segments", "fn": t_get_segments,
     "description": "Ведомость участков варианта: узлы, ДУ, длина, расход, способ прокладки, глубина, стоимость.",
     "schema": {"type": "object", "required": ["variant_id"], "properties": {
         "variant_id": {"type": "string"}, "result_path": {"type": "string"}}}},
    {"name": "get_estimate", "fn": t_get_estimate,
     "description": "Смета варианта: итог, разбор по статьям и по диаметрам.",
     "schema": {"type": "object", "required": ["variant_id"], "properties": {
         "variant_id": {"type": "string"}, "result_path": {"type": "string"}}}},
    {"name": "existing_network_impact", "fn": t_existing_impact,
     "description": "Влияние новых подключений на существующую сеть: добавленный расход по цепочке "
                    "к источнику и требуемые условные диаметры.",
     "schema": {"type": "object", "properties": {
         "variant_id": {"type": "string"}, "result_path": {"type": "string"}}}},
    {"name": "depth_crossings", "fn": t_depth_crossings,
     "description": "Места пересечений с подземными коммуникациями в режиме глубины: сторона "
                    "прохождения и вертикальные расстояния.",
     "schema": {"type": "object", "properties": {"result_path": {"type": "string"}}}},
    {"name": "diagnostics", "fn": t_diagnostics,
     "description": "Диагностика задания: что было не так с данными и как шёл расчёт.",
     "schema": {"type": "object", "required": ["job_id"], "properties": {
         "job_id": {"type": "string"},
         "level": {"type": "string", "enum": ["INFO", "WARNING", "ERROR"]},
         "limit": {"type": "integer"}}}},
    {"name": "get_rules", "fn": t_get_rules,
     "description": "Действующие правила расчёта: справочник диаметров и стоимости (reference), "
                    "ограничения по таблице 2 (restrictions), параметры трассировки (routing).",
     "schema": {"type": "object", "properties": {
         "kind": {"type": "string", "enum": ["reference", "restrictions", "routing"]}}}},
    {"name": "events", "fn": t_events,
     "description": "Журнал событий сервиса: кто и когда запускал расчёты, чем они закончились.",
     "schema": {"type": "object", "properties": {
         "limit": {"type": "integer"}, "type": {"type": "string"}, "level": {"type": "string"}}}},
]
BY_NAME = {t["name"]: t for t in TOOLS}


# ---------------------------------------------------------------- протокол MCP

def handle(req):
    method = req.get("method")
    rid = req.get("id")
    if method == "initialize":
        return {"jsonrpc": "2.0", "id": rid, "result": {
            "protocolVersion": PROTOCOL,
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "catp-heatnet", "version": VERSION}}}
    if method in ("notifications/initialized", "initialized"):
        return None
    if method == "ping":
        return {"jsonrpc": "2.0", "id": rid, "result": {}}
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": rid, "result": {"tools": [
            {"name": t["name"], "description": t["description"], "inputSchema": t["schema"]} for t in TOOLS]}}
    if method == "tools/call":
        params = req.get("params") or {}
        name = params.get("name")
        args = params.get("arguments") or {}
        tool = BY_NAME.get(name)
        if tool is None:
            return {"jsonrpc": "2.0", "id": rid,
                    "error": {"code": -32601, "message": f"нет инструмента {name}"}}
        try:
            text = tool["fn"](args)
            return {"jsonrpc": "2.0", "id": rid,
                    "result": {"content": [{"type": "text", "text": text}], "isError": False}}
        except Exception as e:                                  # ошибка инструмента — не падение сервера
            return {"jsonrpc": "2.0", "id": rid,
                    "result": {"content": [{"type": "text", "text": f"Не удалось: {e}"}], "isError": True}}
    if rid is None:
        return None
    return {"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": f"метод {method} не поддерживается"}}


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except json.JSONDecodeError:
            sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": None, "error": {
                "code": -32700, "message": "строка не разобрана как JSON"}}) + "\n")
            sys.stdout.flush()
            continue
        resp = handle(req)
        if resp is not None:
            sys.stdout.write(json.dumps(resp, ensure_ascii=False) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
