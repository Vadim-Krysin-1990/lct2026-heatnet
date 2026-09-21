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
 *
 * Буферы и подготовленные геометрии строятся лениво — только для препятствий, попавших в окно расчёта
 * (наборы масштаба города содержат сотни тысяч полигонов, заранее буферизовать их нельзя по памяти).
 */
public class ObstacleField {

    public static class Obstacle {
        public String id;
        public String type;
        public RestrictionRules.Rule rule;
        public Geometry source;
        public Envelope envelope;
        public double kSpecial = 1.0;
        public Double axisBearing;
        public Double minAngleDeg;
        double clearance;            // ширина запретной зоны (для forbidden) или отступа (для линейных special)
        double zoneHalf;             // полуширина спецзоны (special)
        boolean special;
        boolean line;
        private PreparedGeometry blocked;
        private Geometry blockedGeom;
        private Geometry specialZoneGeom;
        private boolean built;

        public boolean isForbidden() { return !special; }

        private synchronized void build() {
            if (built) return;
            PreparedGeometryFactory pf = new PreparedGeometryFactory();
            if (!special) {
                blockedGeom = source.buffer(Math.max(0, clearance));
                blocked = pf.create(blockedGeom);
            } else if (!line) {
                specialZoneGeom = source.buffer(zoneHalf);
            } else {
                specialZoneGeom = source.buffer(zoneHalf);
                if (clearance > zoneHalf + 1e-9) {
                    blockedGeom = source.buffer(clearance).difference(specialZoneGeom);
                    blocked = pf.create(blockedGeom);
                }
            }
            built = true;
        }

        /** Запретная зона (может быть null у спецпроходов). */
        public PreparedGeometry getBlocked() { build(); return blocked; }
        public Geometry getBlockedGeom() { build(); return blockedGeom; }
        /** Зона спецучастка; null для forbidden. */
        public Geometry getSpecialZoneGeom() { build(); return specialZoneGeom; }
    }

    private final List<Obstacle> obstacles = new ArrayList<>();
    private final STRtree index = new STRtree();
    private final int clearanceDn;
    private final double halfWidthNew;

    public ObstacleField(InputModel model, RestrictionRules rules, ReferenceRules ref, RoutingRules routing, int clearanceDn) {
        this.clearanceDn = clearanceDn;
        this.halfWidthNew = ref.spec(clearanceDn).widthM / 2.0;
        for (InputModel.Restriction r : model.restrictions) {
            RestrictionRules.Rule rule = rules.resolve(r.type);
            double clearance = rule.clearance(clearanceDn, rules.defaultClearanceM);
            if (!rule.isSpecial()) {
                Obstacle o = base(r.id, r.type, rule, r.geom);
                // ТП §3.1: расстояние измеряется от границы полигона до внешней границы расчётного
                // габарита новой сети, поэтому к отступу добавляется половина расчётной ширины пары труб
                o.clearance = Math.max(0, clearance) + halfWidthNew;
                o.envelope = expanded(r.geom, o.clearance);
                add(o);
            } else if (!rule.isLine()) {
                Obstacle o = base(r.id, r.type, rule, r.geom);
                o.special = true;
                o.zoneHalf = Math.max(rule.zoneMarginM, clearance + halfWidthNew);
                o.kSpecial = rule.kSpecial;
                if (rule.minCrossingAngleDeg != null) {
                    double[] axis = GeoUtil.polygonAxis(r.geom);
                    if (axis[1] >= 1.5) { o.axisBearing = axis[0]; o.minAngleDeg = rule.minCrossingAngleDeg; }
                }
                o.envelope = expanded(r.geom, o.zoneHalf);
                add(o);
            } else {
                double halfUtility = utilityHalfWidth(ref, r.type);
                addLineObstacle(r.id, r.type, rule, r.geom, rule.zoneMarginM + halfUtility, clearance + halfUtility + halfWidthNew, rule.kSpecial,
                        rule.minCrossingAngleDeg != null ? rule.minCrossingAngleDeg : routing.lineZoneMinAngleDeg);
            }
        }
        // существующая тепловая сеть: независимое пересечение без врезки — спецпроход (ТП табл. 5.1)
        RestrictionRules.Rule hn = rules.resolve("heat_network");
        for (InputModel.ExistingSegment s : model.segments) {
            double halfExisting = ref.spec(s.diameter).widthM / 2.0;
            double totalClearance = hn.clearance(clearanceDn, rules.defaultClearanceM) + halfExisting + halfWidthNew;
            if (!hn.isSpecial()) {
                Obstacle o = base(s.id, "heat_network", hn, s.geom);
                o.clearance = totalClearance;
                o.envelope = expanded(s.geom, totalClearance);
                add(o);
            } else {
                addLineObstacle(s.id, "heat_network", hn, s.geom, hn.zoneMarginM + halfExisting, totalClearance, hn.kSpecial,
                        hn.minCrossingAngleDeg != null ? hn.minCrossingAngleDeg : routing.lineZoneMinAngleDeg);
            }
        }
        index.build();
    }

    private static Obstacle base(String id, String type, RestrictionRules.Rule rule, Geometry source) {
        Obstacle o = new Obstacle();
        o.id = id; o.type = type; o.rule = rule; o.source = source;
        return o;
    }

    private static Envelope expanded(Geometry g, double by) {
        Envelope e = new Envelope(g.getEnvelopeInternal());
        e.expandBy(by);
        return e;
    }

    private void addLineObstacle(String id, String type, RestrictionRules.Rule rule, Geometry geom, double zoneHalf,
                                 double totalClearance, double k, Double minAngle) {
        for (int gi = 0; gi < geom.getNumGeometries(); gi++) {
            Geometry part = geom.getGeometryN(gi);
            if (!(part instanceof LineString)) {
                // полигон, объявленный линейным правилом: ось не строим, считаем как полигональный спецпроход
                Obstacle o = base(id, type, rule, part);
                o.special = true; o.zoneHalf = zoneHalf; o.kSpecial = k;
                o.envelope = expanded(part, zoneHalf);
                add(o);
                continue;
            }
            Coordinate[] cs = part.getCoordinates();
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
                Obstacle o = base(id, type, rule, seg);
                o.special = true; o.line = true;
                o.zoneHalf = zoneHalf; o.clearance = totalClearance; o.kSpecial = k;
                o.axisBearing = GeoUtil.bearingDeg(pts.get(i), pts.get(i + 1));
                o.minAngleDeg = minAngle;
                o.envelope = expanded(seg, Math.max(zoneHalf, totalClearance));
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
            if (o.id.equals(ignoreId)) continue;
            PreparedGeometry b = o.getBlocked();
            if (b != null && b.intersects(p)) return true;
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
            if (o.id.equals(ignoreId)) continue;
            PreparedGeometry b = o.getBlocked();
            if (b != null && b.intersects(ray)) return true;
        }
        return false;
    }
}
