package ru.intelligence.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;
import ru.intelligence.heatnet.geo.GeoUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * A* по 8 направлениям с состоянием (клетка, направление): стоимость шага = длина × cost/м × Kспец(клетка)
 * + штраф за поворот (45° — базовый, 90° — двойной, 135° — тройной; развороты запрещены).
 * Внутри спецзоны повороты запрещены (спецучасток — прямой), вход в зону с осью — только под углом ≥ min_angle.
 * Цели — клетки с goalKind; завершение в цели добавляет её терминальную стоимость (врезка, камера),
 * поэтому первая извлечённая «финишная» вершина — глобально дешёвая с учётом стоимости присоединения.
 * Терминальные цели (новая сеть) не раскрываются дальше — так новые участки не пересекаются вне узлов.
 */
public class AStarRouter {

    private static final double SQRT2 = Math.sqrt(2);
    public static final int[] DC = {1, 1, 0, -1, -1, -1, 0, 1};
    public static final int[] DR = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final double[] DIR_BEARING = {0, 45, 90, 135, 180, 225, 270, 315};

    public static class Result {
        public List<Integer> cells;
        public int goalIndex;
        public double cost;
        public int expanded;
    }

    private final RasterWindow w;
    private final double costPerM;
    private final double turnPenaltyM;

    public AStarRouter(RasterWindow w, double costPerM, double turnPenaltyM) {
        this.w = w;
        this.costPerM = costPerM;
        this.turnPenaltyM = turnPenaltyM;
    }

    public Result route(int startCell, int startDir) {
        int n = w.cells();
        double[] g = new double[n * 8];
        Arrays.fill(g, Double.POSITIVE_INFINITY);
        int[] parent = new int[n * 8];
        Arrays.fill(parent, -1);
        boolean[] closed = new boolean[n * 8];
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        double[] h = heuristic();
        int expanded = 0;
        if (startDir < 0) {
            for (int d = 0; d < 8; d++) { int s = startCell * 8 + d; g[s] = 0; open.add(new double[]{h[startCell], s, 0, -1}); }
        } else {
            int s = startCell * 8 + startDir; g[s] = 0; open.add(new double[]{h[startCell], s, 0, -1});
        }
        double bestFinish = Double.POSITIVE_INFINITY;
        while (!open.isEmpty()) {
            double[] top = open.poll();
            int state = (int) top[1];
            if (top[2] == 1) {
                Result r = new Result();
                r.cost = top[0];
                r.goalIndex = (int) top[3];
                r.expanded = expanded;
                r.cells = new ArrayList<>();
                int s = state;
                while (s >= 0) { r.cells.add(s / 8); s = parent[s]; }
                Collections.reverse(r.cells);
                List<Integer> dedup = new ArrayList<>();
                for (int c : r.cells) if (dedup.isEmpty() || dedup.get(dedup.size() - 1) != c) dedup.add(c);
                r.cells = dedup;
                return r;
            }
            if (closed[state]) continue;
            closed[state] = true;
            expanded++;
            int cell = state / 8, dir = state % 8;
            int c = cell % w.cols, r = cell / w.cols;
            double gc = g[state];
            if (gc > bestFinish) break;
            int gk = w.goalKind[cell];
            if (gk > 0 && cell != startCell) {
                RasterWindow.Goal goal = w.goals.get(gk - 1);
                double total = gc + goal.terminalCost;
                if (total < bestFinish) bestFinish = total;
                open.add(new double[]{total, state, 1, gk - 1});
                if (goal.terminalOnly) continue;
            }
            short zHere = w.zone[cell];
            for (int nd = 0; nd < 8; nd++) {
                int turn = Math.abs(nd - dir); if (turn > 4) turn = 8 - turn;
                if (turn == 4) continue;
                if (zHere != 0 && turn != 0) continue;
                int nc = c + DC[nd], nr = r + DR[nd];
                if (!w.inside(nc, nr)) continue;
                int ncell = w.idx(nc, nr);
                if (w.blocked[ncell]) continue;
                if (DC[nd] != 0 && DR[nd] != 0) {
                    int s1 = w.idx(c + DC[nd], r), s2 = w.idx(c, r + DR[nd]);
                    if ((w.blocked[s1] || w.terminal[s1]) && (w.blocked[s2] || w.terminal[s2])) continue;
                }
                short zNext = w.zone[ncell];
                double k = 1.0;
                if (zNext != 0) {
                    RasterWindow.Zone z = w.zones.get(zNext - 1);
                    k = z.k;
                    if (z.axisBearing != null && z.minAngleDeg != null
                            && GeoUtil.acuteAngleDeg(DIR_BEARING[nd], z.axisBearing) + 1e-6 < z.minAngleDeg) continue;
                    if (zHere != 0 && zHere != zNext) k = Math.max(k, w.zones.get(zHere - 1).k);
                }
                double len = (DC[nd] != 0 && DR[nd] != 0) ? w.step * SQRT2 : w.step;
                double stepCost = len * costPerM * k + turn * turnPenaltyM * costPerM;
                int ns = ncell * 8 + nd;
                double ng = gc + stepCost;
                if (ng < g[ns]) {
                    g[ns] = ng;
                    parent[ns] = state;
                    open.add(new double[]{ng + h[ncell], ns, 0, -1});
                }
            }
        }
        return null;
    }

    /**
     * Эвристика: октильное расстояние до ближайшей цели × cost/м, посчитанное двухпроходным
     * chamfer-преобразованием (точное для октильной метрики без препятствий, значит допустимое).
     */
    private double[] heuristic() {
        int n = w.cells(), cols = w.cols, rows = w.rows;
        double[] d = new double[n];
        Arrays.fill(d, Double.POSITIVE_INFINITY);
        boolean any = false;
        for (int i = 0; i < n; i++) if (w.goalKind[i] > 0) { d[i] = 0; any = true; }
        if (!any) { Arrays.fill(d, 0); return d; }
        double a = w.step, b = w.step * SQRT2;
        // прямой проход
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int i = r * cols + c;
                double v = d[i];
                if (c > 0) v = Math.min(v, d[i - 1] + a);
                if (r > 0) {
                    v = Math.min(v, d[i - cols] + a);
                    if (c > 0) v = Math.min(v, d[i - cols - 1] + b);
                    if (c + 1 < cols) v = Math.min(v, d[i - cols + 1] + b);
                }
                d[i] = v;
            }
        }
        // обратный проход
        for (int r = rows - 1; r >= 0; r--) {
            for (int c = cols - 1; c >= 0; c--) {
                int i = r * cols + c;
                double v = d[i];
                if (c + 1 < cols) v = Math.min(v, d[i + 1] + a);
                if (r + 1 < rows) {
                    v = Math.min(v, d[i + cols] + a);
                    if (c + 1 < cols) v = Math.min(v, d[i + cols + 1] + b);
                    if (c > 0) v = Math.min(v, d[i + cols - 1] + b);
                }
                d[i] = v;
            }
        }
        for (int i = 0; i < n; i++) d[i] = d[i] * costPerM * 0.999;
        return d;
    }

    static Coordinate unused() { return null; }
}
