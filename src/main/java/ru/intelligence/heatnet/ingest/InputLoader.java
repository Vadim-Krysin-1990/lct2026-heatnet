package ru.intelligence.heatnet.ingest;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.operation.linemerge.LineMerger;
import ru.intelligence.heatnet.config.RulesService;
import ru.intelligence.heatnet.geo.CrsTransformer;
import ru.intelligence.heatnet.model.Diagnostics;
import ru.intelligence.heatnet.model.InputModel;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Преобразует Feature входного GeoJSON в доменную модель в расчётной проекции с диагностикой:
 * обязательные атрибуты (ТП §2.2), типы геометрии (ТП табл. 2.1), валидность (Q&A: невалидная
 * геометрия — диагностическая ошибка, не падение).
 */
public class InputLoader {
    private final CrsTransformer crs;
    private final RulesService rules;

    public InputLoader(RulesService rules) {
        this.rules = rules;
        this.crs = new CrsTransformer(rules.reference().crs.calcProj4);
    }

    public CrsTransformer crs() { return crs; }

    public InputModel load(InputStream in) throws IOException {
        InputModel m = new InputModel();
        Diagnostics d = m.diagnostics;
        Set<String> ids = new HashSet<>();
        int[] missingFlow = {0};
        GeoJsonStreamReader reader = new GeoJsonStreamReader();
        long n = reader.read(in, (index, props, wgs, gtype) -> {
            String type = str(props.get("object_type"));
            String id = str(props.get("id"));
            if (id == null) {
                id = "feature_" + index;
                d.warn("MISSING_ID", "У объекта №" + index + " нет id, присвоен " + id, id);
            }
            if (!ids.add(id)) d.warn("DUPLICATE_ID", "Повторяющийся id " + id + " (тип " + type + ")", id);
            if (type == null) {
                d.error("MISSING_OBJECT_TYPE", "У объекта " + id + " нет object_type — пропущен", id);
                return;
            }
            if (wgs == null) {
                if (!"variant_summary".equals(type)) d.error("MISSING_GEOMETRY", "У объекта " + id + " (" + type + ") нет геометрии — пропущен", id);
                return;
            }
            if (!wgs.isValid()) {
                d.error("INVALID_GEOMETRY", "Невалидная геометрия у " + id + " (" + type + "): "
                        + new org.locationtech.jts.operation.valid.IsValidOp(wgs).getValidationError(), id);
                return;
            }
            Geometry g = crs.toCalc(wgs);
            switch (type) {
                case "source": {
                    InputModel.Source s = new InputModel.Source();
                    s.id = id; s.name = str(props.get("name"));
                    s.geom = asPoint(g, id, type, d);
                    if (s.geom != null) m.sources.add(s);
                    break;
                }
                case "heat_network": {
                    InputModel.ExistingSegment s = new InputModel.ExistingSegment();
                    s.id = id;
                    Integer dia = intOrNull(props.get("diameter"));
                    if (dia == null) { d.error("MISSING_DIAMETER", "У участка " + id + " нет diameter — пропущен", id); return; }
                    s.diameter = dia;
                    s.flowTph = dblOrNull(props.get("flow_tph"));
                    if (s.flowTph == null) missingFlow[0]++;
                    s.upstreamObjectId = str(props.get("upstream_object_id"));
                    s.geom = asLine(g, id, type, d);
                    if (s.geom == null) return;
                    s.length = s.geom.getLength();
                    m.segments.add(s);
                    break;
                }
                case "heat_chamber": {
                    InputModel.ExistingChamber c = new InputModel.ExistingChamber();
                    c.id = id;
                    c.diameter = intOrNull(props.get("diameter"));
                    c.upstreamObjectId = str(props.get("upstream_object_id"));
                    c.geom = asPoint(g, id, type, d);
                    if (c.geom != null) m.chambers.add(c);
                    break;
                }
                case "oks_connection_point": {
                    InputModel.ConnectionPoint p = new InputModel.ConnectionPoint();
                    p.id = id;
                    p.oksId = str(props.get("oks_id"));
                    Double flow = dblOrNull(props.get("flow_tph"));
                    if (flow == null) { d.error("MISSING_FLOW", "У точки подключения " + id + " нет flow_tph — пропущена", id); return; }
                    p.flowTph = flow;
                    p.heatLoad = dblOrNull(props.get("heat_load"));
                    p.geom = asPoint(g, id, type, d);
                    if (p.geom != null) m.points.add(p);
                    break;
                }
                case "oks_future":
                case "oks_existing": {
                    InputModel.Restriction r = new InputModel.Restriction();
                    r.id = id; r.type = type; r.geom = g; r.props.putAll(props);
                    m.oksPolygons.add(r);
                    // здание — такое же ограничение, как restriction/oks
                    InputModel.Restriction asRestriction = new InputModel.Restriction();
                    asRestriction.id = id; asRestriction.type = "oks"; asRestriction.geom = g; asRestriction.props.putAll(props);
                    m.restrictions.add(asRestriction);
                    break;
                }
                case "restriction": {
                    InputModel.Restriction r = new InputModel.Restriction();
                    r.id = id;
                    r.type = str(props.get("restriction_type"));
                    if (r.type == null) {
                        d.warn("MISSING_RESTRICTION_TYPE", "У ограничения " + id + " нет restriction_type — применено правило по умолчанию", id);
                        r.type = "unknown";
                    } else if (!rules.restrictions().isKnown(r.type)) {
                        d.warn("UNKNOWN_RESTRICTION_TYPE", "Тип ограничения '" + r.type + "' (" + id + ") не описан в правилах — применено правило по умолчанию: "
                                + rules.restrictions().defaultRule, id);
                    }
                    r.geom = g; r.props.putAll(props);
                    m.restrictions.add(r);
                    break;
                }
                default:
                    d.warn("UNKNOWN_OBJECT_TYPE", "Неизвестный object_type '" + type + "' у " + id + " — пропущен", id);
            }
        });
        m.totalFeatures = (int) n;
        if (missingFlow[0] > 0) d.warn("MISSING_FLOW", "У " + missingFlow[0] + " из " + m.segments.size() + " участков heat_network нет flow_tph — текущий расход принят 0 т/ч (реконструкция считается только по добавленному расходу)", null);

        if (m.sources.isEmpty()) d.error("NO_SOURCE", "Во входных данных нет объекта source", null);
        if (m.sources.size() > 1) d.warn("MANY_SOURCES", "Источников больше одного (" + m.sources.size() + "); базовая модель ТЗ 2.2 предполагает один", null);
        if (m.segments.isEmpty()) d.error("NO_NETWORK", "Во входных данных нет участков heat_network", null);
        if (m.points.isEmpty()) d.error("NO_POINTS", "Во входных данных нет точек подключения oks_connection_point", null);

        d.stats.put("features", n);
        d.stats.put("sources", m.sources.size());
        d.stats.put("heat_network", m.segments.size());
        d.stats.put("heat_chamber", m.chambers.size());
        d.stats.put("oks_connection_point", m.points.size());
        d.stats.put("restriction", m.restrictions.size());
        d.stats.put("total_new_flow_tph", m.totalNewFlow());
        return m;
    }

    private static Point asPoint(Geometry g, String id, String type, Diagnostics d) {
        if (g instanceof Point) return (Point) g;
        if (g.getNumGeometries() == 1 && g.getGeometryN(0) instanceof Point) return (Point) g.getGeometryN(0);
        d.error("BAD_GEOMETRY_TYPE", "Объект " + id + " (" + type + ") должен быть Point, получен " + g.getGeometryType() + " — пропущен", id);
        return null;
    }

    private static LineString asLine(Geometry g, String id, String type, Diagnostics d) {
        if (g instanceof LineString) return (LineString) g;
        if (g instanceof MultiLineString) {
            LineMerger lm = new LineMerger();
            lm.add(g);
            var merged = lm.getMergedLineStrings();
            if (merged.size() == 1) {
                d.warn("MULTILINE_MERGED", "Объект " + id + " (" + type + ") был MultiLineString, объединён в LineString", id);
                return (LineString) merged.iterator().next();
            }
        }
        d.error("BAD_GEOMETRY_TYPE", "Объект " + id + " (" + type + ") должен быть LineString, получен " + g.getGeometryType() + " — пропущен", id);
        return null;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    private static Integer intOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return (int) Math.round(Double.parseDouble(String.valueOf(o).replace(',', '.'))); } catch (NumberFormatException e) { return null; }
    }

    private static Double dblOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try { return Double.parseDouble(String.valueOf(o).replace(',', '.')); } catch (NumberFormatException e) { return null; }
    }
}
