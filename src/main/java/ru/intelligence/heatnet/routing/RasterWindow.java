package ru.intelligence.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import ru.intelligence.heatnet.geo.GeoUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Растровое окно поиска. Клетка: заблокирована / обычная / в спецзоне (индекс зоны, Kспец, ось).
 *
 * Сетка может быть повёрнута на азимут застройки (bearingDeg): тогда ходы вдоль осей идут параллельно
 * улицам, проездам и существующим сетям — как требует практика трассировки (трасса прокладывается
 * в технических полосах параллельно красным линиям улиц, дорог и проездов). Без поворота трасса вдоль
 * наклонного проезда вырождается в лестницу из диагональных шагов.
 */
public class RasterWindow {
    public final double step;
    /** Азимут оси сетки в градусах (0 — оси проекции). */
    public final double bearingDeg;
    public final int cols, rows;
    public final boolean[] blocked;
    /** Индекс спецзоны + 1 (0 — нет); при наложении хранится зона с максимальным Kспец (Q&A: один спецучасток, max K). */
    public final short[] zone;
    public final List<Zone> zones = new ArrayList<>();
    /** Клетки, «принадлежащие» существующей сети/цели (для остановки поиска). */
    public final int[] goalKind;     // 0 нет; иначе индекс цели + 1
    /** Клетка терминальной цели: поиск в ней завершается, дальше не раскрывается (новая сеть). */
    public final boolean[] terminal;
    public final List<Goal> goals = new ArrayList<>();

    private final double ox, oy, cos, sin, minU, minV;

    public static class Zone {
        public String obstacleId;
        public String type;
        public double k;
        public Double axisBearing;
        public Double minAngleDeg;
        public Geometry geom;
        /** Надбавка за вход в зону (режим глубины: стоимость профиля пересечения), руб. */
        public double entryPenalty = 0;
    }

    public static class Goal {
        public String kind;          // existing_chamber | existing_segment | new_segment | new_chamber
        public String objectId;
        public double terminalCost;  // стоимость завершения в этой цели (врезка, камера)
        public boolean terminalOnly; // через клетки цели нельзя проходить насквозь
        public Geometry geom;
    }

    public RasterWindow(Envelope env, double step) {
        this(env, step, 0);
    }

    public RasterWindow(Envelope env, double step, double bearingDeg) {
        this.step = step;
        this.bearingDeg = ((bearingDeg % 360) + 360) % 360;
        double rad = Math.toRadians(this.bearingDeg);
        this.cos = Math.cos(rad);
        this.sin = Math.sin(rad);
        this.ox = env.getMinX();
        this.oy = env.getMinY();
        double lowU = Double.POSITIVE_INFINITY, highU = Double.NEGATIVE_INFINITY;
        double lowV = Double.POSITIVE_INFINITY, highV = Double.NEGATIVE_INFINITY;
        double[][] corners = {{env.getMinX(), env.getMinY()}, {env.getMaxX(), env.getMinY()},
                              {env.getMinX(), env.getMaxY()}, {env.getMaxX(), env.getMaxY()}};
        for (double[] c : corners) {
            double u = (c[0] - ox) * cos + (c[1] - oy) * sin;
            double v = -(c[0] - ox) * sin + (c[1] - oy) * cos;
            lowU = Math.min(lowU, u); highU = Math.max(highU, u);
            lowV = Math.min(lowV, v); highV = Math.max(highV, v);
        }
        this.minU = Math.floor(lowU / step) * step;
        this.minV = Math.floor(lowV / step) * step;
        this.cols = Math.max(2, (int) Math.ceil((highU - minU) / step) + 1);
        this.rows = Math.max(2, (int) Math.ceil((highV - minV) / step) + 1);
        int n = cols * rows;
        this.blocked = new boolean[n];
        this.zone = new short[n];
        this.goalKind = new int[n];
        this.terminal = new boolean[n];
    }

    public int idx(int c, int r) { return r * cols + c; }
    public boolean inside(int c, int r) { return c >= 0 && r >= 0 && c < cols && r < rows; }
    public int cells() { return cols * rows; }

    /** Мировая координата X центра клетки. */
    public double wx(int c, int r) {
        double u = minU + c * step, v = minV + r * step;
        return ox + u * cos - v * sin;
    }

    /** Мировая координата Y центра клетки. */
    public double wy(int c, int r) {
        double u = minU + c * step, v = minV + r * step;
        return oy + u * sin + v * cos;
    }

    public int colOf(double x, double y) {
        double u = (x - ox) * cos + (y - oy) * sin;
        return (int) Math.round((u - minU) / step);
    }

    public int rowOf(double x, double y) {
        double v = -(x - ox) * sin + (y - oy) * cos;
        return (int) Math.round((v - minV) / step);
    }

    public Coordinate coord(int idx) {
        int c = idx % cols, r = idx / cols;
        return new Coordinate(wx(c, r), wy(c, r));
    }

    /** Помечает заблокированными клетки, центры которых внутри геометрии. */
    public void block(PreparedGeometry pg, Envelope env) {
        forEachCellIn(env, (c, r, p) -> { if (pg.intersects(p)) blocked[idx(c, r)] = true; });
    }

    /** Разблокирует клетки внутри геометрии (например, выходной коридор из ОКС). */
    public void unblock(Geometry g) {
        PreparedGeometry pg = new PreparedGeometryFactory().create(g);
        forEachCellIn(g.getEnvelopeInternal(), (c, r, p) -> { if (pg.intersects(p)) blocked[idx(c, r)] = false; });
    }

    public void addZone(Zone z) {
        zones.add(z);
        short zi = (short) zones.size();
        PreparedGeometry pg = new PreparedGeometryFactory().create(z.geom);
        forEachCellIn(z.geom.getEnvelopeInternal(), (c, r, p) -> {
            if (!pg.intersects(p)) return;
            int i = idx(c, r);
            if (zone[i] == 0 || zones.get(zone[i] - 1).k < z.k) zone[i] = zi;
        });
    }

    public void addGoal(Goal g, double halfWidth) {
        goals.add(g);
        int gi = goals.size();
        Geometry b = g.geom.buffer(Math.max(halfWidth, step * 0.51));
        PreparedGeometry pg = new PreparedGeometryFactory().create(b);
        boolean barrier = Double.isInfinite(g.terminalCost);
        forEachCellIn(b.getEnvelopeInternal(), (c, r, p) -> {
            if (!pg.intersects(p)) return;
            int i = idx(c, r);
            if (barrier) { blocked[i] = true; return; }
            if (goalKind[i] == 0 || goals.get(goalKind[i] - 1).terminalCost > g.terminalCost) goalKind[i] = gi;
            if (g.terminalOnly) terminal[i] = true;
        });
    }

    /** Достижима ли хоть одна клетка-цель из стартовой по свободным клеткам (8-связность, без правил направлений). */
    public boolean reachable(int startCell) {
        int n = cells();
        boolean[] seen = new boolean[n];
        int[] queue = new int[n];
        int head = 0, tail = 0;
        queue[tail++] = startCell; seen[startCell] = true;
        while (head < tail) {
            int cell = queue[head++];
            if (goalKind[cell] > 0 && cell != startCell) return true;
            if (terminal[cell]) continue;
            int c = cell % cols, r = cell / cols;
            for (int d = 0; d < 8; d++) {
                int nc = c + AStarRouter.DC[d], nr = r + AStarRouter.DR[d];
                if (!inside(nc, nr)) continue;
                int ni = idx(nc, nr);
                if (seen[ni] || blocked[ni]) continue;
                seen[ni] = true; queue[tail++] = ni;
            }
        }
        return false;
    }

    public interface CellVisitor { void visit(int c, int r, Point center); }

    /** Обходит клетки, попадающие в мировой прямоугольник env (с учётом поворота сетки). */
    public void forEachCellIn(Envelope env, CellVisitor v) {
        int c0 = Integer.MAX_VALUE, c1 = Integer.MIN_VALUE, r0 = Integer.MAX_VALUE, r1 = Integer.MIN_VALUE;
        double[][] corners = {{env.getMinX(), env.getMinY()}, {env.getMaxX(), env.getMinY()},
                              {env.getMinX(), env.getMaxY()}, {env.getMaxX(), env.getMaxY()}};
        for (double[] p : corners) {
            int c = colOf(p[0], p[1]), r = rowOf(p[0], p[1]);
            c0 = Math.min(c0, c); c1 = Math.max(c1, c);
            r0 = Math.min(r0, r); r1 = Math.max(r1, r);
        }
        c0 = Math.max(0, c0 - 1); c1 = Math.min(cols - 1, c1 + 1);
        r0 = Math.max(0, r0 - 1); r1 = Math.min(rows - 1, r1 + 1);
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                v.visit(c, r, GeoUtil.point(wx(c, r), wy(c, r)));
            }
        }
    }
}
