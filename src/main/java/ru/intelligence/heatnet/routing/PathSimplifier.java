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
        return simplify(w, cells, true);
    }

    /**
     * @param allowDiagonalCuts разрешено ли срезать угол по диагонали. Для инженерной трассировки
     *        запрещено: срезка превращает один прямой угол (самокомпенсация) в два косых излома,
     *        каждый из которых требует неподвижной опоры.
     */
    public static List<Coordinate> simplify(RasterWindow w, List<Integer> cells, boolean allowDiagonalCuts) {
        List<Coordinate> pts = new ArrayList<>();
        if (cells.isEmpty()) return pts;
        // 1. коллинеарные шаги
        int prevDc = Integer.MIN_VALUE, prevDr = Integer.MIN_VALUE;
        for (int i = 0; i < cells.size(); i++) {
            int cell = cells.get(i);
            int c = cell % w.cols, r = cell / w.cols;
            if (i == 0) { pts.add(new Coordinate(w.wx(c, r), w.wy(c, r))); continue; }
            int pc = cells.get(i - 1) % w.cols, pr = cells.get(i - 1) / w.cols;
            int dc = Integer.signum(c - pc), dr = Integer.signum(r - pr);
            if (dc == prevDc && dr == prevDr) {
                pts.set(pts.size() - 1, new Coordinate(w.wx(c, r), w.wy(c, r)));
            } else {
                pts.add(new Coordinate(w.wx(c, r), w.wy(c, r)));
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
                if (b.distance(c) <= 3 * w.step + 1e-9 && isStandardDirection(w, a, c, allowDiagonalCuts) && lineFree(w, a, c)) {
                    pts.remove(i + 1);
                    changed = true;
                    break;
                }
                if (a.distance(b) <= 3 * w.step + 1e-9 && i > 0) {
                    Coordinate p = pts.get(i - 1);
                    if (isStandardDirection(w, p, b, allowDiagonalCuts) && lineFree(w, p, b)) {
                        pts.remove(i);
                        changed = true;
                        break;
                    }
                }
            }
        }
        return pts;
    }

    /** Направление a→c кратно 45° в системе сетки (с точностью шага). */
    static boolean isStandardDirection(RasterWindow w, Coordinate a, Coordinate c, boolean allowDiagonal) {
        double du = w.colOf(c.x, c.y) - w.colOf(a.x, a.y);
        double dv = w.rowOf(c.x, c.y) - w.rowOf(a.x, a.y);
        if (du == 0 && dv == 0) return false;
        if (du == 0 || dv == 0) return true;
        return allowDiagonal && Math.abs(du) == Math.abs(dv);
    }

    /** Все клетки на отрезке свободны и не заходят в спецзоны иначе, чем прямо. */
    static boolean lineFree(RasterWindow w, Coordinate a, Coordinate b) {
        int c0 = w.colOf(a.x, a.y), r0 = w.rowOf(a.x, a.y);
        int c1 = w.colOf(b.x, b.y), r1 = w.rowOf(b.x, b.y);
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

    /**
     * Спрямление трассы произвольным углом (ТП в редакции от 21.09.2026 §2.1: допустим любой поворот
     * до 90° включительно, удорожания за «нестандартный» угол больше нет). Вершина снимается, если
     * прямая между её соседями свободна и повороты в соседних вершинах остаются не круче 90°.
     * Первые {@code fixedHead} вершин (точка подключения и выход из здания по нормали) не трогаются,
     * последняя вершина (врезка) — тоже.
     */
    public static List<Coordinate> pullStraight(RasterWindow w, List<Coordinate> pts, int fixedHead) {
        List<Coordinate> out = new ArrayList<>(pts);
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 200) {
            changed = false;
            for (int i = Math.max(1, fixedHead); i + 1 < out.size(); i++) {
                Coordinate a = out.get(i - 1), c = out.get(i + 1);
                if (!segmentFree(w, a, c)) continue;
                if (i - 2 >= 0 && turnDeg(out.get(i - 2), a, c) > 90 + 1e-6) continue;
                if (i + 2 < out.size() && turnDeg(a, c, out.get(i + 2)) > 90 + 1e-6) continue;
                out.remove(i);
                changed = true;
                break;
            }
        }
        return out;
    }

    /** Поворот в вершине b в градусах: 0 — прямо, 90 — прямой угол. */
    public static double turnDeg(Coordinate a, Coordinate b, Coordinate c) {
        double b1 = Math.atan2(b.y - a.y, b.x - a.x), b2 = Math.atan2(c.y - b.y, c.x - b.x);
        double d = Math.toDegrees(b2 - b1);
        d = ((d + 180) % 360 + 360) % 360 - 180;
        return Math.abs(d);
    }

    /** Свободен ли отрезок произвольного направления: выборка с шагом полклетки по маске и зонам. */
    public static boolean segmentFree(RasterWindow w, Coordinate a, Coordinate b) {
        double len = a.distance(b);
        if (len < 1e-9) return true;
        int n = (int) Math.ceil(len / (w.step * 0.5));
        short z0 = w.zone[w.idx(w.colOf(a.x, a.y), w.rowOf(a.x, a.y))];
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            double x = a.x + (b.x - a.x) * t, y = a.y + (b.y - a.y) * t;
            int c = w.colOf(x, y), r = w.rowOf(x, y);
            if (!w.inside(c, r) || w.blocked[w.idx(c, r)]) return false;
            short z = w.zone[w.idx(c, r)];
            if (z != z0 && z != 0) return false;   // в спецзону срезкой не заходим: угол пересечения там нормируется
        }
        return true;
    }
}
