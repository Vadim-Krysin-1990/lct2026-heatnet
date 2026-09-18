package ru.intelligence.heatnet.routing;

import org.locationtech.jts.geom.Coordinate;

import java.util.ArrayList;
import java.util.List;

/**
 * Превращает цепочку клеток в ломаную с минимумом вершин: сливает коллинеарные шаги,
 * убирает «ступеньки» (два коротких поворота подряд), когда срезка не задевает препятствия.
 */
public final class PathSimplifier {
    private PathSimplifier() {}

    public static List<Coordinate> simplify(RasterWindow w, List<Integer> cells) {
        List<Coordinate> pts = new ArrayList<>();
        if (cells.isEmpty()) return pts;
        // 1. коллинеарные шаги
        int prevDc = Integer.MIN_VALUE, prevDr = Integer.MIN_VALUE;
        for (int i = 0; i < cells.size(); i++) {
            int cell = cells.get(i);
            int c = cell % w.cols, r = cell / w.cols;
            if (i == 0) { pts.add(new Coordinate(w.x(c), w.y(r))); continue; }
            int pc = cells.get(i - 1) % w.cols, pr = cells.get(i - 1) / w.cols;
            int dc = Integer.signum(c - pc), dr = Integer.signum(r - pr);
            if (dc == prevDc && dr == prevDr) {
                pts.set(pts.size() - 1, new Coordinate(w.x(c), w.y(r)));
            } else {
                pts.add(new Coordinate(w.x(c), w.y(r)));
            }
            prevDc = dc; prevDr = dr;
        }
        // 2. ступеньки: A-B-C-D, где B-C короткий, и отрезок A-C или B-D проходит по свободным клеткам
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 50) {
            changed = false;
            for (int i = 0; i + 2 < pts.size(); i++) {
                Coordinate a = pts.get(i), b = pts.get(i + 1), c = pts.get(i + 2);
                if (b.distance(c) <= 3 * w.step + 1e-9 && isStandardDirection(a, c, w.step) && lineFree(w, a, c)) {
                    pts.remove(i + 1);
                    changed = true;
                    break;
                }
                if (a.distance(b) <= 3 * w.step + 1e-9 && i > 0) {
                    Coordinate p = pts.get(i - 1);
                    if (isStandardDirection(p, b, w.step) && lineFree(w, p, b)) {
                        pts.remove(i);
                        changed = true;
                        break;
                    }
                }
            }
        }
        return pts;
    }

    /** Направление a→c кратно 45° (с точностью сетки). */
    static boolean isStandardDirection(Coordinate a, Coordinate c, double step) {
        double dx = Math.round((c.x - a.x) / step), dy = Math.round((c.y - a.y) / step);
        if (dx == 0 && dy == 0) return false;
        return dx == 0 || dy == 0 || Math.abs(dx) == Math.abs(dy);
    }

    /** Все клетки на отрезке свободны и не заходят в спецзоны иначе, чем прямо. */
    static boolean lineFree(RasterWindow w, Coordinate a, Coordinate b) {
        int c0 = w.col(a.x), r0 = w.row(a.y), c1 = w.col(b.x), r1 = w.row(b.y);
        int dc = Integer.signum(c1 - c0), dr = Integer.signum(r1 - r0);
        int n = Math.max(Math.abs(c1 - c0), Math.abs(r1 - r0));
        int c = c0, r = r0;
        short z0 = w.zone[w.idx(c0, r0)];
        for (int i = 0; i <= n; i++) {
            if (!w.inside(c, r) || w.blocked[w.idx(c, r)]) return false;
            if (w.zone[w.idx(c, r)] != z0 && w.zone[w.idx(c, r)] != 0) return false; // не пересекать новые спецзоны при срезке
            if (dc != 0 && dr != 0 && i < n) {
                if (w.blocked[w.idx(c + dc, r)] && w.blocked[w.idx(c, r + dr)]) return false;
            }
            c += dc; r += dr;
        }
        return true;
    }
}
