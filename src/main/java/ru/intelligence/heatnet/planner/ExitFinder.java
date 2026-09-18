package ru.intelligence.heatnet.planner;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.operation.distance.IndexedFacetDistance;
import java.util.HashSet;
import java.util.Set;
import ru.intelligence.heatnet.geo.GeoUtil;
import ru.intelligence.heatnet.model.InputModel;
import ru.intelligence.heatnet.routing.ObstacleField;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Выход из ОКС: точка подключения лежит внутри (или на границе) полигона здания. Первый участок идёт
 * по нормали к ближайшей стороне полигона за пределы буфера (Q&A 16.09), направление квантуется к
 * стандартным 45°, чтобы дальнейшие повороты оставались стандартными.
 */
public class ExitFinder {

    public static class Exit {
        public InputModel.Restriction building;   // null — точка вне зданий
        public Coordinate exitPoint;               // точка за буфером, откуда начинается поиск
        public int direction;                      // 0..7, направление выхода (для A*)
        public List<Coordinate> corridor = new ArrayList<>(); // от точки подключения до exitPoint
        /** Коридор проходит через буфер соседнего здания (строгий выход невозможен). */
        public boolean relaxed;
        /** Свободного выхода нет вовсе. */
        public boolean enclosed;
    }

    private final ObstacleField field;
    private final double clearance;
    private final double step;

    public ExitFinder(ObstacleField field, double clearanceM, double gridStep) {
        this.field = field;
        this.clearance = clearanceM;
        this.step = gridStep;
    }

    public Exit find(InputModel.ConnectionPoint cp, List<InputModel.Restriction> buildings) {
        return findAll(cp, buildings).get(0);
    }

    /** Все допустимые выходы в порядке предпочтения: строгие (ближняя сторона раньше), затем через буферы соседей. */
    public List<Exit> findAll(InputModel.ConnectionPoint cp, List<InputModel.Restriction> buildings) {
        List<Exit> found = new ArrayList<>();
        Point p = cp.geom;
        Exit ex = new Exit();
        InputModel.Restriction host = null;
        for (InputModel.Restriction r : buildings) if (r.geom.covers(p)) { host = r; break; }
        if (host == null) {
            // точка вне зданий: если она внутри чьего-то буфера — тоже нужен выход
            for (InputModel.Restriction r : buildings) if (r.geom.distance(p) < clearance) { host = r; break; }
        }
        ex.building = host;
        Coordinate c = p.getCoordinate();
        if (host == null) {
            ex.exitPoint = c; ex.direction = -1; ex.corridor.add(c);
            found.add(ex);
            return found;
        }
        // кандидаты: нормали ко всем сторонам внешнего кольца, ближе — раньше
        List<double[]> cands = new ArrayList<>(); // [distToSide, bearingOut, bx, by]
        for (LinearRing ring : GeoUtil.exteriorRings(host.geom)) {
            Coordinate[] cs = ring.getCoordinates();
            for (int i = 0; i + 1 < cs.length; i++) {
                LineString side = GeoUtil.line(cs[i], cs[i + 1]);
                Coordinate[] near = DistanceOp.nearestPoints(side, p);
                Coordinate foot = near[0];
                double dist = foot.distance(c);
                double bearing = GeoUtil.bearingDeg(c, foot);
                if (dist < 1e-6) {
                    // точка на стороне: нормаль наружу
                    double sb = GeoUtil.bearingDeg(cs[i], cs[i + 1]);
                    bearing = (sb + 90) % 360;
                    Coordinate test = new Coordinate(c.x + Math.cos(Math.toRadians(bearing)), c.y + Math.sin(Math.toRadians(bearing)));
                    if (host.geom.covers(GeoUtil.point(test))) bearing = (bearing + 180) % 360;
                }
                cands.add(new double[]{dist, bearing, foot.x, foot.y});
            }
        }
        cands.sort(Comparator.comparingDouble(a -> a[0]));
        PreparedGeometry hostPrep = new PreparedGeometryFactory().create(host.geom);
        IndexedFacetDistance hostDist = new IndexedFacetDistance(host.geom);
        Set<Integer> triedDirs = new HashSet<>();
        // проход 1: строгий — коридор не задевает чужие буферы; проход 2: коридор может пройти через буфер
        // соседнего здания (но не через само здание) — с предупреждением в диагностике
        for (int pass = 0; pass < 2; pass++) {
            for (double[] cand : cands) {
                int base = (int) Math.round(cand[1] / 45.0) % 8;
                for (int delta : new int[]{0, 1, -1}) {
                    int dir = ((base + delta) % 8 + 8) % 8;
                    if (!triedDirs.add(pass * 8 + dir)) continue;
                    double db = dir * 45.0;
                    double dx = Math.cos(Math.toRadians(db)), dy = Math.sin(Math.toRadians(db));
                    double maxLen = cand[0] + clearance + 30 * step + host.geom.getEnvelopeInternal().maxExtent();
                    Coordinate exitPt = null;
                    boolean leftHost = false;
                    for (double t = step / 2; t <= maxLen; t += step / 2) {
                        Coordinate q = new Coordinate(c.x + dx * t, c.y + dy * t);
                        Point qp = GeoUtil.point(q);
                        boolean inside = hostPrep.covers(qp);
                        if (!leftHost && !inside) leftHost = true;
                        if (leftHost && inside) { exitPt = null; break; }   // луч снова вошёл в здание (невыпуклое)
                        if (!inside && hostDist.distance(qp) >= clearance - 1e-6 && !field.isBlocked(q, host.id)) { exitPt = q; break; }
                    }
                    if (exitPt == null) continue;
                    LineString ray = GeoUtil.line(c, exitPt);
                    if (pass == 0 && field.crossesBlocked(ray, host.id)) continue;
                    if (pass == 1 && field.crossesSource(ray, host.id)) continue;
                    boolean dup = false;
                    for (Exit f : found) if (f.direction == dir && f.exitPoint.distance(exitPt) < step) { dup = true; break; }
                    if (dup) continue;
                    Exit e = new Exit();
                    e.building = host;
                    e.exitPoint = exitPt;
                    e.direction = dir;
                    e.corridor.add(c);
                    e.corridor.add(exitPt);
                    e.relaxed = pass == 1;
                    found.add(e);
                }
            }
        }
        if (!found.isEmpty()) return found;
        // выхода нет: точка окружена; вернём ближайшую сторону, маршрут, скорее всего, не будет найден
        double[] cand = cands.isEmpty() ? new double[]{0, 0, c.x, c.y} : cands.get(0);
        int dir = (int) Math.round(cand[1] / 45.0) % 8;
        double db = dir * 45.0;
        Coordinate exitPt = new Coordinate(c.x + Math.cos(Math.toRadians(db)) * (cand[0] + clearance + step),
                c.y + Math.sin(Math.toRadians(db)) * (cand[0] + clearance + step));
        ex.exitPoint = exitPt; ex.direction = dir; ex.corridor.add(c); ex.corridor.add(exitPt);
        ex.building = host;
        ex.enclosed = true;
        found.add(ex);
        return found;
    }

    /** Полигон-коридор выхода (для разблокировки клеток растра вдоль луча). */
    public static Geometry corridorGeom(Exit ex, double width) {
        if (ex.corridor.size() < 2) return GeoUtil.point(ex.exitPoint).buffer(width);
        return GeoUtil.line(ex.corridor).buffer(width);
    }

    public static Envelope envelope(Exit ex) {
        Envelope e = new Envelope(ex.exitPoint);
        for (Coordinate c : ex.corridor) e.expandToInclude(c);
        return e;
    }
}
