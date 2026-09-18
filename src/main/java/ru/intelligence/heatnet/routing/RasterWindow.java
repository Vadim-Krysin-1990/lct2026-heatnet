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
 * Растеризация — по центрам клеток через PreparedGeometry (точность = шаг сетки).
 */
public class RasterWindow {
    public final double minX, minY, step;
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

    public static class Zone {
        public String obstacleId;
        public String type;
        public double k;
        public Double axisBearing;
        public Double minAngleDeg;
        public Geometry geom;
    }

    public static class Goal {
        public String kind;          // existing_chamber | existing_segment | new_segment | new_chamber
        public String objectId;
        public double terminalCost;  // стоимость завершения в этой цели (врезка, камера)
        public boolean terminalOnly; // через клетки цели нельзя проходить насквозь
        public Geometry geom;
    }

    public RasterWindow(Envelope env, double step) {
        this.step = step;
        this.minX = Math.floor(env.getMinX() / step) * step;
        this.minY = Math.floor(env.getMinY() / step) * step;
        this.cols = Math.max(2, (int) Math.ceil((env.getMaxX() - minX) / step) + 1);
        this.rows = Math.max(2, (int) Math.ceil((env.getMaxY() - minY) / step) + 1);
        int n = cols * rows;
        this.blocked = new boolean[n];
        this.zone = new short[n];
        this.goalKind = new int[n];
        this.terminal = new boolean[n];
    }

    public int idx(int c, int r) { return r * cols + c; }
    public int col(double x) { return (int) Math.round((x - minX) / step); }
    public int row(double y) { return (int) Math.round((y - minY) / step); }
    public double x(int c) { return minX + c * step; }
    public double y(int r) { return minY + r * step; }
    public boolean inside(int c, int r) { return c >= 0 && r >= 0 && c < cols && r < rows; }
    public Coordinate coord(int idx) { return new Coordinate(x(idx % cols), y(idx / cols)); }
    public int cells() { return cols * rows; }

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

    public interface CellVisitor { void visit(int c, int r, Point center); }

    public void forEachCellIn(Envelope env, CellVisitor v) {
        int c0 = Math.max(0, col(env.getMinX()) - 1), c1 = Math.min(cols - 1, col(env.getMaxX()) + 1);
        int r0 = Math.max(0, row(env.getMinY()) - 1), r1 = Math.min(rows - 1, row(env.getMaxY()) + 1);
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                v.visit(c, r, GeoUtil.point(x(c), y(r)));
            }
        }
    }
}
