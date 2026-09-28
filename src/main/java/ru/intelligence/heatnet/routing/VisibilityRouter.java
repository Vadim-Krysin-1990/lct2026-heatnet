package ru.intelligence.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import ru.intelligence.heatnet.geo.GeoUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Поиск трассы по графу видимости — второй метод трассировки рядом с поиском по растровой сетке.
 *
 * Идея: кратчайший путь в плоскости с многоугольными препятствиями всегда проходит по их углам,
 * поэтому вершинами графа берутся углы запретных зон, вынесенные наружу по биссектрисе, а рёбрами —
 * отрезки прямой видимости между ними. Трасса получается из длинных прямых, идущих по касательной
 * к препятствиям, тогда как растровый поиск может двигаться только по узлам сетки и на каждом
 * обходе теряет метры.
 *
 * Ограничения техприложения соблюдаются те же: поворот не круче maxTurnDeg, вход в зону
 * специального прохода только под нормируемым углом, стоимость ребра учитывает K(спец) на той
 * части отрезка, которая проходит внутри зоны.
 */
public class VisibilityRouter {

    /** Результат: ломаная от старта до выбранной цели. */
    public static class Result {
        public List<Coordinate> path;
        public int goalIndex;
        public double cost;
        public int expanded;
    }

    private final RasterWindow w;              // геометрия окна, зоны и цели берутся те же
    private final ObstacleField field;
    private final double costPerM;
    private final double turnPenaltyM;
    private final double maxTurnDeg;
    private final double vertexOffset;         // вынос вершины наружу от угла препятствия
    private final int maxVertices;
    /** Допуск на касание запретной зоны: короткие заходы у старта и цели не запрещают ребро. */
    private static final double BLOCK_TOLERANCE_M = 0.05;

    private final List<Coordinate> nodes = new ArrayList<>();
    private final List<Geometry> blockers = new ArrayList<>();
    /** Индексы запретных зон и спецпроходов: видимость проверяется только по кандидатам. */
    private STRtree blockIndex = new STRtree();
    private STRtree zoneIndex = new STRtree();
    private final java.util.Map<Geometry, PreparedGeometry> prepared = new java.util.IdentityHashMap<>();
    private String ownId;
    /** Предельная длина ребра: полный граф на большом окне строить незачем. */
    private double maxEdgeLen = Double.MAX_VALUE;

    public VisibilityRouter(RasterWindow w, ObstacleField field, double costPerM, double turnPenaltyM,
                            double maxTurnDeg, double vertexOffset, int maxVertices) {
        this.w = w;
        this.field = field;
        this.costPerM = costPerM;
        this.turnPenaltyM = turnPenaltyM;
        this.maxTurnDeg = maxTurnDeg;
        this.vertexOffset = vertexOffset;
        this.maxVertices = maxVertices;
    }

    /**
     * @param start точка выхода из здания
     * @param startBearing направление выхода (градусы) или null, если не задано
     * @return путь или null, если граф не связал старт с целью
     */
    public Result route(Coordinate start, Double startBearing, Envelope window) {
        return route(start, startBearing, window, null);
    }

    /**
     * @param ownBuildingId здание, из которого выходит трасса: его запретная зона не блокирует
     *        видимость, иначе из точки выхода не существует ни одного законного ребра
     */
    public Result route(Coordinate start, Double startBearing, Envelope window, String ownBuildingId) {
        this.ownId = ownBuildingId;
        Envelope area = corridor(start, window);
        buildNodes(start, area);
        window = area;
        if (nodes.size() < 2) return null;

        final int n = nodes.size();
        maxEdgeLen = Math.max(250.0, Math.hypot(window.getWidth(), window.getHeight()));

        // списки видимости: считаются один раз, дальше поиск работает только с ними
        List<List<int[]>> adj = new ArrayList<>(n);          // [сосед, стоимость в сотых долях]
        List<List<Double>> adjCost = new ArrayList<>(n);
        for (int i = 0; i < n; i++) { adj.add(new ArrayList<>()); adjCost.add(new ArrayList<>()); }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double len = nodes.get(i).distance(nodes.get(j));
                if (len < 1e-6 || len > maxEdgeLen) continue;
                Double c = edgeCost(nodes.get(i), nodes.get(j), len);
                if (c == null) continue;
                adj.get(i).add(new int[]{j}); adjCost.get(i).add(c);
                adj.get(j).add(new int[]{i}); adjCost.get(j).add(c);
            }
        }

        // Состояние поиска — пара «откуда пришли, где стоим»: без неё нельзя проверить поворот,
        // а поворот круче 90° техприложение запрещает (§2.1).
        java.util.Map<Long, Double> dist = new java.util.HashMap<>();
        java.util.Map<Long, Long> prevState = new java.util.HashMap<>();
        PriorityQueue<Object[]> open = new PriorityQueue<>(Comparator.comparingDouble(a -> (Double) a[0]));
        long startState = key(-1, 0);
        dist.put(startState, 0.0);
        open.add(new Object[]{0.0, startState});

        int expanded = 0;
        long bestState = -1;
        double bestCost = Double.POSITIVE_INFINITY;
        java.util.Set<Long> done = new java.util.HashSet<>();

        while (!open.isEmpty()) {
            Object[] top = open.poll();
            long st = (Long) top[1];
            if (!done.add(st)) continue;
            double d = dist.getOrDefault(st, Double.POSITIVE_INFINITY);
            if (d >= bestCost) break;
            expanded++;
            int u = (int) (st & 0xFFFFFFFFL);
            int from = (int) (st >> 32) - 1;

            int gi = goalIndexOf(u);
            if (gi >= 0 && u != 0) {
                double total = d + w.goals.get(gi).terminalCost;
                if (total < bestCost) { bestCost = total; bestState = st; }
                if (w.goals.get(gi).terminalOnly) continue;
            }
            List<int[]> ns = adj.get(u);
            List<Double> cs = adjCost.get(u);
            for (int k = 0; k < ns.size(); k++) {
                int v = ns.get(k)[0];
                if (v == from) continue;                    // возврат по тому же ребру
                double turn;
                if (from >= 0) {
                    turn = PathSimplifier.turnDeg(nodes.get(from), nodes.get(u), nodes.get(v));
                } else if (startBearing != null) {
                    turn = GeoUtil.acuteAngleDeg(bearing(nodes.get(u), nodes.get(v)), startBearing);
                } else {
                    turn = 0;
                }
                if (turn > maxTurnDeg + 1e-6) continue;
                double nd = d + cs.get(k) + (turn > 1 ? turnPenaltyM * costPerM * (turn / 90.0) : 0);
                long nst = key(u, v);
                if (nd < dist.getOrDefault(nst, Double.POSITIVE_INFINITY)) {
                    dist.put(nst, nd);
                    prevState.put(nst, st);
                    open.add(new Object[]{nd, nst});
                }
            }
        }
        if (bestState < 0) return null;

        Result r = new Result();
        r.cost = bestCost;
        r.goalIndex = goalIndexOf((int) (bestState & 0xFFFFFFFFL));
        r.expanded = expanded;
        List<Coordinate> path = new ArrayList<>();
        Long cur = bestState;
        while (cur != null) {
            path.add(new Coordinate(nodes.get((int) (cur & 0xFFFFFFFFL))));
            cur = prevState.get(cur);
        }
        java.util.Collections.reverse(path);
        r.path = path;
        return r;
    }

    /** Ключ состояния «пришли из from в node»; from = -1 для старта. */
    private static long key(int from, int node) {
        return ((long) (from + 1) << 32) | (node & 0xFFFFFFFFL);
    }

    // ---------- построение графа ----------

    private final List<Integer> goalNode = new ArrayList<>();   // индекс цели для вершины, -1 если обычная

    private int goalIndexOf(int node) {
        return node < goalNode.size() ? goalNode.get(node) : -1;
    }

    /**
     * Область графа — коридор вокруг прямой «старт → ближайшая цель» с запасом.
     * На полном окне поиска вершин тысячи, а проверок видимости квадратично больше,
     * тогда как трасса физически идёт в узкой полосе между началом и местом присоединения.
     */
    private Envelope corridor(Coordinate start, Envelope window) {
        Coordinate nearest = null;
        double best = Double.MAX_VALUE;
        for (RasterWindow.Goal g : w.goals) {
            Coordinate c = org.locationtech.jts.operation.distance.DistanceOp
                    .nearestPoints(g.geom, GeoUtil.point(start))[0];
            double d = c.distance(start);
            if (d < best) { best = d; nearest = c; }
        }
        if (nearest == null) return window;
        Envelope e = new Envelope(start);
        e.expandToInclude(nearest);
        e.expandBy(Math.max(60.0, best * 0.6));
        Envelope res = e.intersection(window);
        return res == null || res.isNull() ? window : res;
    }

    private void buildNodes(Coordinate start, Envelope window) {
        nodes.clear(); goalNode.clear(); blockers.clear(); prepared.clear();
        blockIndex = new STRtree(); zoneIndex = new STRtree();
        nodes.add(new Coordinate(start)); goalNode.add(-1);

        // запретные зоны окна: они же — источник вершин и препятствия для видимости
        for (ObstacleField.Obstacle o : field.query(window)) {
            if (ownId != null && ownId.equals(o.id)) continue;   // своё здание не блокирует выход
            Geometry g = o.getBlockedGeom();
            if (g == null || g.isEmpty()) continue;
            blockers.add(g);
            blockIndex.insert(g.getEnvelopeInternal(), g);
            prepared.put(g, new PreparedGeometryFactory().create(g));
        }
        for (RasterWindow.Zone z : w.zones) {
            if (z.geom != null && !z.geom.isEmpty()) zoneIndex.insert(z.geom.getEnvelopeInternal(), z);
        }
        blockIndex.build();
        zoneIndex.build();
        List<Coordinate> corners = new ArrayList<>();
        for (Geometry g : blockers) {
            for (int i = 0; i < g.getNumGeometries(); i++) {
                Geometry part = g.getGeometryN(i);
                if (!(part instanceof Polygon)) continue;
                // упрощение контура перед сбором вершин: мелкие изломы буфера графу не нужны
                Geometry simple = org.locationtech.jts.simplify.DouglasPeuckerSimplifier
                        .simplify(part, Math.max(0.2, vertexOffset));
                if (!(simple instanceof Polygon) || simple.isEmpty()) simple = part;
                addRingCorners(((Polygon) simple).getExteriorRing().getCoordinates(), corners, window);
            }
        }
        // при большом окне вершин может быть слишком много — оставляем ближние к старту
        if (corners.size() > maxVertices) {
            corners.sort(Comparator.comparingDouble(c -> c.distance(start)));
            corners = new ArrayList<>(corners.subList(0, maxVertices));
        }
        for (Coordinate c : corners) { nodes.add(c); goalNode.add(-1); }

        // точки целей: ближайшая точка геометрии цели к старту и её вершины
        for (int gi = 0; gi < w.goals.size(); gi++) {
            RasterWindow.Goal goal = w.goals.get(gi);
            for (Coordinate c : goalPoints(goal, start)) {
                if (!window.contains(c)) continue;
                nodes.add(c); goalNode.add(gi);
            }
        }
    }

    /** Точки цели, в которые имеет смысл приходить: проекция старта и вершины ломаной. */
    private List<Coordinate> goalPoints(RasterWindow.Goal goal, Coordinate start) {
        List<Coordinate> out = new ArrayList<>();
        Geometry g = goal.geom;
        Coordinate[] near = org.locationtech.jts.operation.distance.DistanceOp
                .nearestPoints(g, GeoUtil.point(start));
        out.add(new Coordinate(near[0]));
        Coordinate[] cs = g.getCoordinates();
        if (cs.length > 2) {
            // добавляем вершины линии цели с прореживанием: врезка возможна не только в проекции
            double step = Math.max(1, cs.length / 12.0);
            for (double i = 0; i < cs.length; i += step) out.add(new Coordinate(cs[(int) i]));
        } else {
            for (Coordinate c : cs) out.add(new Coordinate(c));
        }
        return out;
    }

    private void addRingCorners(Coordinate[] ring, List<Coordinate> out, Envelope window) {
        int m = ring.length - 1;
        if (m < 3) return;
        for (int i = 0; i < m; i++) {
            Coordinate p = ring[(i - 1 + m) % m], c = ring[i], nx = ring[(i + 1) % m];
            // выносим вершину наружу по биссектрисе, иначе она лежит на самой границе зоны
            double a1 = Math.atan2(c.y - p.y, c.x - p.x), a2 = Math.atan2(nx.y - c.y, nx.x - c.x);
            double bis = (a1 + a2) / 2 + Math.PI / 2;
            Coordinate cand = new Coordinate(c.x + Math.cos(bis) * vertexOffset, c.y + Math.sin(bis) * vertexOffset);
            if (!window.contains(cand) || blockedAt(cand)) {
                cand = new Coordinate(c.x - Math.cos(bis) * vertexOffset, c.y - Math.sin(bis) * vertexOffset);
                if (!window.contains(cand) || blockedAt(cand)) continue;
            }
            out.add(cand);
        }
    }

    private boolean blockedAt(Coordinate c) {
        int col = w.colOf(c.x, c.y), row = w.rowOf(c.x, c.y);
        return !w.inside(col, row) || w.blocked[w.idx(col, row)];
    }

    /**
     * Стоимость ребра: длина по цене метра плюс надбавка за ту часть, что идёт внутри зоны
     * специального прохода. null — ребро запрещено (пересекает запретную зону или входит
     * в спецзону под недопустимым углом).
     */
    private Double edgeCost(Coordinate a, Coordinate b, double len) {
        LineString seg = GeoUtil.line(a, b);
        // Вершины графа стоят вплотную к границам зон, а точка подключения и место присоединения
        // могут лежать прямо в зоне, поэтому касание не считается пересечением: запрещаем ребро
        // только когда внутри запретной зоны остаётся ощутимый отрезок.
        @SuppressWarnings("unchecked")
        List<Geometry> nearBlockers = blockIndex.query(seg.getEnvelopeInternal());
        for (Geometry g : nearBlockers) {
            PreparedGeometry pg = prepared.get(g);
            if (pg != null ? !pg.intersects(seg) : !g.intersects(seg)) continue;
            if (seg.intersection(g).getLength() > BLOCK_TOLERANCE_M) return null;
        }
        double extra = 0;
        @SuppressWarnings("unchecked")
        List<RasterWindow.Zone> nearZones = zoneIndex.query(seg.getEnvelopeInternal());
        for (RasterWindow.Zone z : nearZones) {
            if (z.geom == null || !z.geom.intersects(seg)) continue;
            if (z.axisBearing != null && z.minAngleDeg != null
                    && GeoUtil.acuteAngleDeg(bearing(a, b), z.axisBearing) + 1e-6 < z.minAngleDeg) {
                return null;
            }
            Geometry inside = seg.intersection(z.geom);
            extra += inside.getLength() * (z.k - 1);
        }
        return (len + extra) * costPerM;
    }

    private static double bearing(Coordinate a, Coordinate b) {
        double d = Math.toDegrees(Math.atan2(b.x - a.x, b.y - a.y));
        return (d + 360) % 360;
    }
}
