package ru.intelligence.heatnet.network;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.strtree.STRtree;
import ru.intelligence.heatnet.geo.GeoUtil;
import ru.intelligence.heatnet.model.Diagnostics;
import ru.intelligence.heatnet.model.InputModel;
import ru.intelligence.heatnet.model.InputModel.ExistingChamber;
import ru.intelligence.heatnet.model.InputModel.ExistingSegment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Топология существующей тепловой сети: ориентация участков к источнику, цепочка upstream,
 * степени камер, поиск ближайших участков.
 *
 * Если во входных данных есть upstream_object_id (ТП §2.2), используется он; иначе цепочка
 * восстанавливается по геометрии: концы участков совпадают в пределах допуска, BFS от источника.
 */
public class NetworkTopology {

    /** Ориентированный участок: sourceEnd — какой конец (0 = начало LineString, 1 = конец) ближе к источнику. */
    public static class Oriented {
        public ExistingSegment seg;
        public int sourceEnd = -1;         // -1 = не определён
        public String upstreamId;          // следующий объект к источнику (участок, камера или источник)
        public int depth = Integer.MAX_VALUE;
    }

    private final InputModel model;
    private final double tol;
    private final Map<String, Oriented> oriented = new HashMap<>();
    private final Map<String, ExistingChamber> chamberById = new HashMap<>();
    private final Map<String, ExistingSegment> segmentById = new HashMap<>();
    private final Map<Long, List<ExistingSegment>> nodeSegments = new HashMap<>();
    private final Map<Long, ExistingChamber> nodeChamber = new HashMap<>();
    private final Map<String, Long> chamberNode = new HashMap<>();
    private final STRtree segmentIndex = new STRtree();
    private long sourceNode;

    public NetworkTopology(InputModel model, double snapToleranceM) {
        this.model = model;
        this.tol = Math.max(1e-3, snapToleranceM);
        build();
    }

    private long key(Coordinate c) {
        long kx = Math.round(c.x / tol);
        long ky = Math.round(c.y / tol);
        return (kx << 32) ^ (ky & 0xffffffffL);
    }

    private void build() {
        Diagnostics d = model.diagnostics;
        for (ExistingSegment s : model.segments) {
            segmentById.put(s.id, s);
            Oriented o = new Oriented();
            o.seg = s;
            oriented.put(s.id, o);
            segmentIndex.insert(s.geom.getEnvelopeInternal(), s);
            Coordinate[] cs = s.geom.getCoordinates();
            nodeSegments.computeIfAbsent(key(cs[0]), k -> new ArrayList<>()).add(s);
            nodeSegments.computeIfAbsent(key(cs[cs.length - 1]), k -> new ArrayList<>()).add(s);
        }
        for (ExistingChamber c : model.chambers) {
            chamberById.put(c.id, c);
            long k = key(c.geom.getCoordinate());
            if (!nodeSegments.containsKey(k)) {
                // камера не на стыке — ищем ближайший конец в пределах 3·tol
                long best = 0; double bd = Double.MAX_VALUE;
                for (Map.Entry<Long, List<ExistingSegment>> e : nodeSegments.entrySet()) {
                    for (ExistingSegment s : e.getValue()) {
                        Coordinate[] cs = s.geom.getCoordinates();
                        for (Coordinate end : new Coordinate[]{cs[0], cs[cs.length - 1]}) {
                            double dist = end.distance(c.geom.getCoordinate());
                            if (dist < bd) { bd = dist; best = e.getKey(); }
                        }
                    }
                }
                if (bd <= 3 * tol) k = best;
                else d.warn("CHAMBER_OFF_NETWORK", "Камера " + c.id + " не лежит на стыке участков (ближайший конец " + GeoUtil.round(bd, 2) + " м)", c.id);
            }
            nodeChamber.put(k, c);
            chamberNode.put(c.id, k);
        }
        if (model.sources.isEmpty()) return;
        sourceNode = key(model.sources.get(0).geom.getCoordinate());
        if (!nodeSegments.containsKey(sourceNode)) {
            // источник не на конце участка — ближайший конец
            long best = 0; double bd = Double.MAX_VALUE;
            Coordinate sc = model.sources.get(0).geom.getCoordinate();
            for (Map.Entry<Long, List<ExistingSegment>> e : nodeSegments.entrySet()) {
                for (ExistingSegment s : e.getValue()) {
                    Coordinate[] cs = s.geom.getCoordinates();
                    for (Coordinate end : new Coordinate[]{cs[0], cs[cs.length - 1]}) {
                        double dist = end.distance(sc);
                        if (dist < bd) { bd = dist; best = e.getKey(); }
                    }
                }
            }
            d.warn("SOURCE_OFF_NETWORK", "Источник не лежит на конце участка; привязан к ближайшему концу (" + GeoUtil.round(bd, 2) + " м)", model.sources.get(0).id);
            sourceNode = best;
        }

        boolean hasUpstreamAttr = model.segments.stream().anyMatch(s -> s.upstreamObjectId != null);
        if (hasUpstreamAttr) orientByAttribute();
        else d.info("TOPOLOGY_FROM_GEOMETRY", "upstream_object_id отсутствует — цепочка к источнику восстановлена по геометрии (допуск " + tol + " м)", null);
        orientByGeometry();

        int unreachable = 0;
        for (Oriented o : oriented.values()) if (o.sourceEnd < 0) { unreachable++; d.warn("SEGMENT_UNREACHABLE", "Участок " + o.seg.id + " не связан с источником по геометрии", o.seg.id); }
        d.stats.put("segments_unreachable", unreachable);
        for (ExistingChamber c : model.chambers) {
            c.diameter = c.diameter != null ? c.diameter : maxAdjacentDiameter(c);
        }
        if (model.chambers.stream().anyMatch(c -> c.diameter == null || c.diameter == 0)) {
            d.warn("CHAMBER_DIAMETER_DERIVED", "У камер нет diameter — принят максимальный ДУ примыкающих участков", null);
        }
    }

    private void orientByAttribute() {
        for (Oriented o : oriented.values()) {
            o.upstreamId = o.seg.upstreamObjectId;
            if (o.upstreamId == null) continue;
            Coordinate[] cs = o.seg.geom.getCoordinates();
            Coordinate target = null;
            if (chamberById.containsKey(o.upstreamId)) target = chamberById.get(o.upstreamId).geom.getCoordinate();
            else if (segmentById.containsKey(o.upstreamId)) {
                // общий конец с upstream-участком
                ExistingSegment up = segmentById.get(o.upstreamId);
                Coordinate[] uc = up.geom.getCoordinates();
                double d0 = Math.min(cs[0].distance(uc[0]), cs[0].distance(uc[uc.length - 1]));
                double d1 = Math.min(cs[cs.length - 1].distance(uc[0]), cs[cs.length - 1].distance(uc[uc.length - 1]));
                o.sourceEnd = d0 <= d1 ? 0 : 1;
                continue;
            } else if (!model.sources.isEmpty() && model.sources.get(0).id.equals(o.upstreamId)) target = model.sources.get(0).geom.getCoordinate();
            if (target != null) o.sourceEnd = cs[0].distance(target) <= cs[cs.length - 1].distance(target) ? 0 : 1;
        }
    }

    /** BFS от источника по совпадающим концам; дополняет/проверяет ориентацию. */
    private void orientByGeometry() {
        Map<Long, Integer> dist = new HashMap<>();
        Deque<Long> q = new ArrayDeque<>();
        dist.put(sourceNode, 0);
        q.add(sourceNode);
        Set<String> visited = new HashSet<>();
        while (!q.isEmpty()) {
            long n = q.poll();
            int dn = dist.get(n);
            for (ExistingSegment s : nodeSegments.getOrDefault(n, List.of())) {
                if (!visited.add(s.id)) continue;
                Oriented o = oriented.get(s.id);
                Coordinate[] cs = s.geom.getCoordinates();
                long k0 = key(cs[0]), k1 = key(cs[cs.length - 1]);
                int se = (k0 == n) ? 0 : 1;
                if (o.sourceEnd < 0) o.sourceEnd = se;
                else if (o.sourceEnd != se) model.diagnostics.warn("UPSTREAM_MISMATCH", "upstream_object_id участка " + s.id + " противоречит геометрии; принята геометрия", s.id);
                o.sourceEnd = se;
                o.depth = dn + 1;
                long far = se == 0 ? k1 : k0;
                if (o.upstreamId == null) {
                    ExistingChamber ch = nodeChamber.get(n);
                    if (n == sourceNode && !model.sources.isEmpty()) o.upstreamId = model.sources.get(0).id;
                    else if (ch != null) o.upstreamId = ch.id;
                    else {
                        for (ExistingSegment other : nodeSegments.get(n)) if (!other.id.equals(s.id) && visited.contains(other.id)) { o.upstreamId = other.id; break; }
                    }
                }
                if (!dist.containsKey(far)) { dist.put(far, dn + 1); q.add(far); }
            }
        }
    }

    // ---- запросы ----

    public Oriented oriented(String segmentId) { return oriented.get(segmentId); }
    public ExistingSegment segment(String id) { return segmentById.get(id); }
    public ExistingChamber chamber(String id) { return chamberById.get(id); }
    public List<ExistingSegment> segments() { return model.segments; }
    public List<ExistingChamber> chambers() { return model.chambers; }
    public STRtree segmentIndex() { return segmentIndex; }

    /** Число существующих участков, примыкающих к камере. */
    public int chamberDegree(ExistingChamber c) {
        Long k = chamberNode.get(c.id);
        return k == null ? 0 : nodeSegments.getOrDefault(k, List.of()).size();
    }

    public List<ExistingSegment> chamberSegments(ExistingChamber c) {
        Long k = chamberNode.get(c.id);
        return k == null ? List.of() : nodeSegments.getOrDefault(k, List.of());
    }

    public int maxAdjacentDiameter(ExistingChamber c) {
        int m = 0;
        for (ExistingSegment s : chamberSegments(c)) m = Math.max(m, s.diameter);
        return m;
    }

    /** Часть цепочки к источнику: участок + доля [from, to] вдоль LineString (0..1), в порядке от врезки к источнику. */
    public static class ChainPart {
        public ExistingSegment seg;
        public double from;
        public double to;
        public ChainPart(ExistingSegment seg, double from, double to) { this.seg = seg; this.from = from; this.to = to; }
        public double length() { return Math.abs(to - from) * seg.length; }
    }

    /**
     * Цепочка к источнику от точки на участке (fraction — доля вдоль LineString) или от камеры.
     * Для врезки внутри участка добавленный расход действует только на часть от врезки к источнику (ТП §7).
     */
    public List<ChainPart> upstreamChain(ExistingSegment start, double fraction) {
        List<ChainPart> chain = new ArrayList<>();
        Set<String> guard = new HashSet<>();
        ExistingSegment cur = start;
        double f = fraction;
        while (cur != null && guard.add(cur.id)) {
            Oriented o = oriented.get(cur.id);
            if (o == null || o.sourceEnd < 0) break;
            double toEnd = o.sourceEnd == 0 ? 0.0 : 1.0;
            if (Math.abs(f - toEnd) > 1e-9) chain.add(new ChainPart(cur, f, toEnd));
            ExistingSegment next = nextUpstreamSegment(cur);
            cur = next;
            if (next != null) {
                Oriented no = oriented.get(next.id);
                f = no.sourceEnd == 0 ? 1.0 : 0.0; // входим в следующий участок с дальнего от источника конца
            }
        }
        return chain;
    }

    public List<ChainPart> upstreamChainFromChamber(ExistingChamber c) {
        List<ChainPart> chain = new ArrayList<>();
        Long k = chamberNode.get(c.id);
        if (k == null) return chain;
        // участок, у которого этот узел — дальний от источника конец, и есть путь к источнику
        ExistingSegment best = null; int bestDepth = Integer.MAX_VALUE;
        for (ExistingSegment s : nodeSegments.getOrDefault(k, List.of())) {
            Oriented o = oriented.get(s.id);
            if (o.sourceEnd < 0) continue;
            Coordinate[] cs = s.geom.getCoordinates();
            long farKey = o.sourceEnd == 0 ? key(cs[cs.length - 1]) : key(cs[0]);
            if (farKey == k && o.depth < bestDepth) { best = s; bestDepth = o.depth; }
        }
        if (best == null) return chain;
        Oriented o = oriented.get(best.id);
        return upstreamChain(best, o.sourceEnd == 0 ? 1.0 : 0.0);
    }

    private ExistingSegment nextUpstreamSegment(ExistingSegment s) {
        Oriented o = oriented.get(s.id);
        Coordinate[] cs = s.geom.getCoordinates();
        long n = o.sourceEnd == 0 ? key(cs[0]) : key(cs[cs.length - 1]);
        if (n == sourceNode) return null;
        ExistingSegment best = null; int bestDepth = Integer.MAX_VALUE;
        for (ExistingSegment other : nodeSegments.getOrDefault(n, List.of())) {
            if (other.id.equals(s.id)) continue;
            Oriented oo = oriented.get(other.id);
            if (oo.sourceEnd < 0 || oo.depth >= o.depth) continue;
            if (oo.depth < bestDepth) { best = other; bestDepth = oo.depth; }
        }
        return best;
    }

    /** Ближайшая существующая камера к точке (с расстоянием). */
    public ExistingChamber nearestChamber(Coordinate c, double maxDist) {
        ExistingChamber best = null; double bd = maxDist;
        for (ExistingChamber ch : model.chambers) {
            double d = ch.geom.getCoordinate().distance(c);
            if (d <= bd) { bd = d; best = ch; }
        }
        return best;
    }

    /** Ближайший участок сети к точке и доля вдоль него. */
    public Object[] nearestSegment(Coordinate c, double maxDist) {
        ExistingSegment best = null; double bd = maxDist;
        for (ExistingSegment s : model.segments) {
            double d = s.geom.distance(GeoUtil.point(c));
            if (d <= bd) { bd = d; best = s; }
        }
        if (best == null) return null;
        return new Object[]{best, GeoUtil.fractionAlong(best.geom, c), bd};
    }

    public LineString segmentGeom(String id) { return segmentById.get(id).geom; }
}
