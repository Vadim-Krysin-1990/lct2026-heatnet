package ru.intelligence.heatnet.planner;

import org.locationtech.jts.geom.Envelope;
import ru.intelligence.heatnet.config.ReferenceRules;
import ru.intelligence.heatnet.config.RestrictionRules;
import ru.intelligence.heatnet.geo.GeoUtil;
import ru.intelligence.heatnet.model.Diagnostics;
import ru.intelligence.heatnet.model.InputModel;
import ru.intelligence.heatnet.model.Variant;
import ru.intelligence.heatnet.routing.ObstacleField;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Проверка готовой трассы на фактические отступы (ТП табл. 2). Поиск идёт по зонам, построенным для
 * расчётного ДУ, а окончательный диаметр участка известен только после гидравлики, поэтому результат
 * перепроверяется по фактическому диаметру каждого участка.
 *
 * Обязательный ввод в своё здание нарушением не считается: точка подключения задана внутри полигона
 * ОКС, и приходящий в неё участок по определению касается этого здания (Q&A 16.09).
 */
public class ClearanceValidator {

    public static class Violation {
        public String segmentId;
        public String obstacleId;
        public String obstacleType;
        public int diameter;
        public double actualM;
        public double requiredM;
    }

    private final InputModel model;
    private final ReferenceRules ref;
    private final RestrictionRules rules;
    private final ObstacleField field;
    /** Точка подключения → id полигона ОКС, в котором она находится. */
    private final Map<String, String> ownBuilding = new HashMap<>();

    public ClearanceValidator(InputModel model, ReferenceRules ref, RestrictionRules rules, ObstacleField field) {
        this.model = model;
        this.ref = ref;
        this.rules = rules;
        this.field = field;
        List<InputModel.Restriction> polygons = new ArrayList<>(model.oksPolygons);
        for (InputModel.Restriction r : model.restrictions) {
            if (r.type != null && r.type.startsWith("oks")) polygons.add(r);
        }
        for (InputModel.ConnectionPoint p : model.points) {
            String best = null;
            double bestDist = Double.MAX_VALUE;
            for (InputModel.Restriction r : polygons) {
                double d = r.geom.distance(p.geom);
                if (d < bestDist) { bestDist = d; best = r.id; }
                if (d == 0) break;
            }
            if (best != null && bestDist < 25) ownBuilding.put(p.id, best);
        }
    }

    /**
     * Самопроверка геометрии: поворотов круче 90° внутри участка быть не должно (ТП §2.1).
     * Срезка углов выполняется при построении, но у самой границы буфера фаска может не пройти —
     * тогда сервис обязан сказать об этом сам, а не оставлять это на эксперта.
     */
    private void checkTurns(Variant v, Diagnostics diag) {
        List<String> bad = new ArrayList<>();
        double worst = 0;
        for (Variant.NewSegment s : v.segments) {
            if (s.geom == null) continue;
            org.locationtech.jts.geom.Coordinate[] c = s.geom.getCoordinates();
            for (int i = 1; i + 1 < c.length; i++) {
                if (c[i - 1].distance(c[i]) < 0.01 || c[i].distance(c[i + 1]) < 0.01) continue;
                double t = ru.intelligence.heatnet.routing.PathSimplifier.turnDeg(c[i - 1], c[i], c[i + 1]);
                if (t > 90.4) {
                    bad.add(s.id + " (" + GeoUtil.round(t, 1) + "°, " + point(c[i]) + ")");
                    worst = Math.max(worst, t);
                }
            }
        }
        if (bad.isEmpty()) {
            diag.info("TURN_LIMIT_OK", "Вариант " + v.variantId
                    + ": поворотов круче 90° внутри участков нет (ТП §2.1)", null);
        } else {
            v.ruleViolations += bad.size();
            v.violationNotes.add("поворот круче 90° в " + bad.size() + " вершинах (худший " + GeoUtil.round(worst, 1) + "°)");
            diag.warn("TURN_LIMIT", "Вариант " + v.variantId + ": поворот круче 90° остался в "
                    + bad.size() + " вершинах (худший " + GeoUtil.round(worst, 1) + "°): "
                    + String.join(", ", bad.subList(0, Math.min(5, bad.size())))
                    + (bad.size() > 5 ? ", …" : "") + " — срезка фаской не прошла по свободному месту, "
                    + "требуется ручная проработка", null);
        }
    }

    /**
     * Новые участки не должны пересекаться вне узлов (ТЗ 2.2: сеть — дерево).
     * Проверяется после сборки дерева: спрямление пути одной точки может пересечь трассу другой.
     */
    private void checkSelfCrossings(Variant v, Diagnostics diag) {
        List<Variant.NewSegment> segs = new ArrayList<>();
        for (Variant.NewSegment s : v.segments) if (s.geom != null) segs.add(s);
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < segs.size(); i++) {
            Variant.NewSegment a = segs.get(i);
            for (int j = i + 1; j < segs.size(); j++) {
                Variant.NewSegment b = segs.get(j);
                if (!a.geom.getEnvelopeInternal().intersects(b.geom.getEnvelopeInternal())) continue;
                org.locationtech.jts.geom.Geometry inter = a.geom.intersection(b.geom);
                if (inter.isEmpty()) continue;
                boolean shareNode = a.startNodeId.equals(b.startNodeId) || a.startNodeId.equals(b.endNodeId)
                        || a.endNodeId.equals(b.startNodeId) || a.endNodeId.equals(b.endNodeId);
                if (inter.getLength() > 0.5) {
                    bad.add(a.id + " и " + b.id + " (наложение " + GeoUtil.round(inter.getLength(), 1) + " м)");
                    continue;
                }
                if (shareNode) continue;   // законный стык в общем узле
                for (org.locationtech.jts.geom.Coordinate c : inter.getCoordinates()) {
                    if (isEnd(a, c) || isEnd(b, c)) continue;   // касание концом линии — тоже узел
                    bad.add(a.id + " и " + b.id);
                    break;
                }
            }
        }
        if (bad.isEmpty()) {
            diag.info("TREE_OK", "Вариант " + v.variantId + ": новые участки не пересекаются вне узлов (ТЗ 2.2)", null);
        } else {
            v.ruleViolations += bad.size();
            v.violationNotes.add("пересечение новых участков вне узлов: " + bad.size());
            diag.warn("TREE_CROSS", "Вариант " + v.variantId + ": новые участки пересекаются вне узлов в "
                    + bad.size() + " местах: " + String.join(", ", bad.subList(0, Math.min(5, bad.size())))
                    + (bad.size() > 5 ? ", …" : ""), null);
        }
    }

    /** Координата в расчётной проекции — короткой записью, чтобы можно было найти место на карте. */
    private static String point(org.locationtech.jts.geom.Coordinate c) {
        return GeoUtil.round(c.x, 1) + " " + GeoUtil.round(c.y, 1);
    }

    private static boolean isEnd(Variant.NewSegment s, org.locationtech.jts.geom.Coordinate c) {
        org.locationtech.jts.geom.Coordinate[] cs = s.geom.getCoordinates();
        return cs[0].distance(c) < 0.05 || cs[cs.length - 1].distance(c) < 0.05;
    }

    /** @return найденные нарушения; сводка попадает в диагностику. */
    public List<Violation> check(Variant v, Diagnostics diag) {
        List<Violation> found = new ArrayList<>();
        for (Variant.NewSegment s : v.segments) {
            if (s.geom == null) continue;
            double half = ref.spec(s.diameter).widthM / 2.0;
            Envelope env = new Envelope(s.geom.getEnvelopeInternal());
            env.expandBy(rules.defaultClearanceM + half + 12);
            String skipId = ownBuilding.get(s.startNodeId);
            if (skipId == null) skipId = ownBuilding.get(s.endNodeId);
            for (ObstacleField.Obstacle o : field.query(env)) {
                if (!o.isForbidden()) continue;                  // спецпроходы нормируются углом, а не отступом
                if (o.id.equals(skipId)) continue;               // ввод в своё здание
                RestrictionRules.Rule rule = rules.resolve(o.type);
                double required = rule.clearance(s.diameter, rules.defaultClearanceM) + half;
                double actual = o.source.distance(s.geom);
                if (actual + 0.05 < required) {
                    Violation vio = new Violation();
                    vio.segmentId = s.id; vio.obstacleId = o.id; vio.obstacleType = o.type;
                    vio.diameter = s.diameter; vio.actualM = actual; vio.requiredM = required;
                    found.add(vio);
                }
            }
        }
        checkTurns(v, diag);
        checkSelfCrossings(v, diag);
        if (!found.isEmpty()) {
            v.ruleViolations += found.size();
            v.violationNotes.add("отступ меньше нормы в " + found.size() + " парах «участок — объект»");
        }
        if (found.isEmpty()) {
            diag.info("CLEARANCE_OK", "Вариант " + v.variantId
                    + ": отступы от препятствий выдержаны по фактическим диаметрам участков", null);
            return found;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(5, found.size()); i++) {
            Violation vio = found.get(i);
            if (i > 0) sb.append("; ");
            sb.append("участок ").append(vio.segmentId).append(" (ДУ").append(vio.diameter).append(") — ")
                    .append(vio.obstacleType).append(' ').append(vio.obstacleId).append(": ")
                    .append(GeoUtil.round(vio.actualM, 2)).append(" м при норме ").append(GeoUtil.round(vio.requiredM, 2));
        }
        diag.warn("CLEARANCE_TIGHT", "Вариант " + v.variantId + ": отступ меньше нормативного в "
                + found.size() + " парах «участок — объект» (проверка по фактическому ДУ после подбора диаметров): "
                + sb + (found.size() > 5 ? "; …" : ""), null);
        return found;
    }
}
