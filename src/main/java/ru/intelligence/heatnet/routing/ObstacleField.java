package ru.intelligence.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import ru.intelligence.heatnet.config.ReferenceRules;
import ru.intelligence.heatnet.config.RestrictionRules;
import ru.intelligence.heatnet.config.RoutingRules;
import ru.intelligence.heatnet.geo.GeoUtil;
import ru.intelligence.heatnet.model.InputModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Поле препятствий. Для каждого ограничения по правилу табл. 5.1:
 *  - forbidden: запретная зона = геометрия + отступ (ТП: расстояние от внешней границы полигона);
 *  - special (полигон: дорога, трамвай, ж/д): зона спецучастка = полигон + margin; внутри зоны движение
 *    только прямое и под углом ≥ min_angle к оси объекта; запретной зоны нет — прохождение рядом
 *    обеспечивается тем, что margin ≥ отступа;
 *  - special (линия: газопровод, кабель, существующая теплосеть): линия режется на прямые отрезки, каждый —
 *    своя зона (ось ± (margin + половина габарита)); внутри — только прямо и под углом ≥ line_zone_min_angle
 *    к отрезку, чтобы исключить прокладку вдоль объекта ближе отступа; если отступ шире зоны — кольцо
 *    между ними заблокировано.
 * Отступ от ОКС берётся по верхней оценке ДУ новой сети (clearanceDn).
 */
public class ObstacleField {

    public static class Obstacle {
        public String id;
        public String type;
        public RestrictionRules.Rule rule;
        public PreparedGeometry blocked;       // может быть null
        public Geometry blockedGeom;
        public Geometry specialZoneGeom;       // null для forbidden
        public double kSpecial = 1.0;
        public Double axisBearing;
        public Double minAngleDeg;
        public Envelope envelope;
        public Geometry source;
        public boolean isForbidden() { return specialZoneGeom == null; }
    }

    private final List<Obstacle> obstacles = new ArrayList<>();
    private final STRtree index = new STRtree();
    private final int clearanceDn;
    private final double halfWidthNew;

    public ObstacleField(InputModel model, RestrictionRules rules, ReferenceRules ref, RoutingRules routing, int clearanceDn) {
        this.clearanceDn = clearanceDn;
        this.halfWidthNew = ref.spec(clearanceDn).widthM / 2.0;
        PreparedGeometryFactory pf = new PreparedGeometryFactory();
        for (InputModel.Restriction r : model.restrictions) {
            RestrictionRules.Rule rule = rules.resolve(r.type);
            double clearance = rule.clearance(clearanceDn, rules.defaultClearanceM);
            if (!rule.isSpecial()) {
                Obstacle o = new Obstacle();
                o.id = r.id; o.type = r.type; o.rule = rule; o.source = r.geom;
                Geometry b = r.geom.buffer(Math.max(0, clearance));
                o.blockedGeom = b; o.blocked = pf.create(b);
                o.envelope = b.getEnvelopeInternal();
                add(o);
            } else if (!rule.isLine()) {
                Obstacle o = new Obstacle();
                o.id = r.id; o.type = r.type; o.rule = rule; o.source = r.geom;
                o.specialZoneGeom = r.geom.buffer(Math.max(rule.zoneMarginM, clearance));
                o.kSpecial = rule.kSpecial;
                if (rule.minCrossingAngleDeg != null) {
                    double[] axis = GeoUtil.polygonAxis(r.geom);
                    if (axis[1] >= 1.5) { o.axisBearing = axis[0]; o.minAngleDeg = rule.minCrossingAngleDeg; }
                }
                o.envelope = o.specialZoneGeom.getEnvelopeInternal();
                add(o);
            } else {
                double halfUtility = utilityHalfWidth(ref, r.type);
                double totalClearance = clearance + halfUtility + halfWidthNew;
                double zoneHalf = rule.zoneMarginM + halfUtility;
                addLineObstacle(r.id, r.type, rule, r.geom, zoneHalf, totalClearance, rule.kSpecial,
                        rule.minCrossingAngleDeg != null ? rule.minCrossingAngleDeg : routing.lineZoneMinAngleDeg, pf);
            }
        }
        // существующая тепловая сеть: независимое пересечение без врезки — спецпроход (ТП табл. 5.1)
        RestrictionRules.Rule hn = rules.resolve("heat_network");
        for (InputModel.ExistingSegment s : model.segments) {
            double halfExisting = ref.spec(s.diameter).widthM / 2.0;
            double totalClearance = hn.clearance(clearanceDn, rules.defaultClearanceM) + halfExisting + halfWidthNew;
            double zoneHalf = hn.zoneMarginM + halfExisting;
            if (!hn.isSpecial()) {
                Obstacle o = new Obstacle();
                o.id = s.id; o.type = "heat_network"; o.rule = hn; o.source = s.geom;
                o.blockedGeom = s.geom.buffer(totalClearance); o.blocked = pf.create(o.blockedGeom);
                o.envelope = o.blockedGeom.getEnvelopeInternal();
                add(o);
            } else {
                addLineObstacle(s.id, "heat_network", hn, s.geom, zoneHalf, totalClearance, hn.kSpecial,
                        hn.minCrossingAngleDeg != null ? hn.minCrossingAngleDeg : routing.lineZoneMinAngleDeg, pf);
            }
        }
        index.build();
    }

    private void addLineObstacle(String id, String type, RestrictionRules.Rule rule, Geometry geom, double zoneHalf,
                                 double totalClearance, double k, Double minAngle, PreparedGeometryFactory pf) {
        for (int gi = 0; gi < geom.getNumGeometries(); gi++) {
            Geometry part = geom.getGeometryN(gi);
            Coordinate[] cs = part.getCoordinates();
            if (!(part instanceof LineString)) {
                // полигон, объявленный линейным правилом: ось не строим, считаем как полигональный спецпроход
                Obstacle o = new Obstacle();
                o.id = id; o.type = type; o.rule = rule; o.source = part;
                o.specialZoneGeom = part.buffer(zoneHalf); o.kSpecial = k;
                o.envelope = o.specialZoneGeom.getEnvelopeInternal();
                add(o);
                continue;
            }
            // слить почти коллинеарные соседние отрезки
            List<Coordinate> pts = new ArrayList<>();
            pts.add(cs[0]);
            for (int i = 1; i < cs.length; i++) {
                if (pts.size() >= 2) {
                    Coordinate a = pts.get(pts.size() - 2), b = pts.get(pts.size() - 1);
                    if (GeoUtil.acuteAngleDeg(GeoUtil.bearingDeg(a, b), GeoUtil.bearingDeg(b, cs[i])) < 8) { pts.set(pts.size() - 1, cs[i]); continue; }
                }
                pts.add(cs[i]);
            }
            for (int i = 0; i + 1 < pts.size(); i++) {
                LineString seg = GeoUtil.line(pts.get(i), pts.get(i + 1));
                if (seg.getLength() < 1e-6) continue;
                Obstacle o = new Obstacle();
                o.id = id; o.type = type; o.rule = rule; o.source = seg;
                o.specialZoneGeom = seg.buffer(zoneHalf);
                o.kSpecial = k;
                o.axisBearing = GeoUtil.bearingDeg(pts.get(i), pts.get(i + 1));
                o.minAngleDeg = minAngle;
                Envelope env = o.specialZoneGeom.getEnvelopeInternal();
                if (totalClearance > zoneHalf + 1e-9) {
                    o.blockedGeom = seg.buffer(totalClearance).difference(o.specialZoneGeom);
                    o.blocked = pf.create(o.blockedGeom);
                    env = o.blockedGeom.getEnvelopeInternal();
                }
                o.envelope = env;
                add(o);
            }
        }
    }

    private void add(Obstacle o) {
        obstacles.add(o);
        index.insert(o.envelope, o);
    }

    private static double utilityHalfWidth(ReferenceRules ref, String type) {
        ReferenceRules.Utility u = ref.existingUtilities.get(type);
        return u != null && u.widthM != null ? u.widthM / 2.0 : 0;
    }

    @SuppressWarnings("unchecked")
    public List<Obstacle> query(Envelope env) {
        return (List<Obstacle>) index.query(env);
    }

    public List<Obstacle> all() { return obstacles; }
    public int clearanceDn() { return clearanceDn; }
    public double halfWidthNew() { return halfWidthNew; }

    /** Заблокирована ли точка запретной зоной (без учёта спецзон). */
    public boolean isBlocked(Coordinate c, String ignoreId) {
        Point p = GeoUtil.point(c);
        for (Obstacle o : query(new Envelope(c))) {
            if (o.id.equals(ignoreId) || o.blocked == null) continue;
            if (o.blocked.intersects(p)) return true;
        }
        return false;
    }

    /** Пересекает ли отрезок саму геометрию запрещённого объекта (здание, водоём), без учёта отступа. */
    public boolean crossesSource(LineString ray, String ignoreId) {
        for (Obstacle o : query(ray.getEnvelopeInternal())) {
            if (o.id.equals(ignoreId) || !o.isForbidden()) continue;
            if (o.source.intersects(ray)) return true;
        }
        return false;
    }

    /** Пересекает ли отрезок запретную зону какого-либо препятствия (кроме ignoreId). */
    public boolean crossesBlocked(LineString ray, String ignoreId) {
        for (Obstacle o : query(ray.getEnvelopeInternal())) {
            if (o.id.equals(ignoreId) || o.blocked == null) continue;
            if (o.blocked.intersects(ray)) return true;
        }
        return false;
    }
}
