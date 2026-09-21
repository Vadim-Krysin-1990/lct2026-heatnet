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
