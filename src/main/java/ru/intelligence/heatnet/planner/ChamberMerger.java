package ru.intelligence.heatnet.planner;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import ru.intelligence.heatnet.config.ReferenceRules;
import ru.intelligence.heatnet.geo.GeoUtil;
import ru.intelligence.heatnet.model.Diagnostics;
import ru.intelligence.heatnet.planner.NetworkBuilder.Edge;
import ru.intelligence.heatnet.planner.NetworkBuilder.Node;
import ru.intelligence.heatnet.planner.NetworkBuilder.NodeKind;
import ru.intelligence.heatnet.routing.ObstacleField;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Сводит близко стоящие тепловые камеры в одну.
 *
 * Камера — самая дорогая штучная позиция сметы: на конкурсном наборе четырнадцать камер дают
 * 56 млн из 276. Дерево собирается по геометрии путей, поэтому два разветвления нередко оказываются
 * в двадцати-тридцати метрах друг от друга, хотя по существу это один узел. Слияние убирает одну
 * камеру, укорачивает трассу на участок между ними и не меняет ни одного маршрута целиком.
 *
 * Слияние выполняется только когда оно безопасно: у объединённой камеры не больше четырёх лучей,
 * а новые прямые до неё не задевают запретные зоны. Проверка идёт по тем же буферам препятствий,
 * что и поиск трассы, с оценочным диаметром — фактические отступы после подбора диаметров
 * перепроверяет ClearanceValidator.
 */
public class ChamberMerger {

    private final ObstacleField field;
    private final ReferenceRules ref;
    private final double radiusM;
    private final int maxBranches;
    private final double maxTurnDeg;

    public ChamberMerger(ObstacleField field, ReferenceRules ref, double radiusM, int maxBranches, double maxTurnDeg) {
        this.field = field;
        this.ref = ref;
        this.radiusM = radiusM;
        this.maxBranches = maxBranches;
        this.maxTurnDeg = maxTurnDeg;
    }

    /** @return сколько камер убрано. */
    public int merge(NetworkBuilder nb, Diagnostics diag, String variantId) {
        int removed = 0;
        for (int pass = 0; pass < 4; pass++) {
            List<Node> chambers = new ArrayList<>();
            for (Node n : nb.nodes().values()) if (n.kind == NodeKind.CHAMBER) chambers.add(n);
            List<Node[]> pairs = new ArrayList<>();
            for (int i = 0; i < chambers.size(); i++) {
                for (int j = i + 1; j < chambers.size(); j++) {
                    Node a = chambers.get(i), b = chambers.get(j);
                    if (a.xy.distance(b.xy) <= radiusM) pairs.add(new Node[]{a, b});
                }
            }
            pairs.sort(Comparator.comparingDouble(p -> p[0].xy.distance(p[1].xy)));
            boolean merged = false;
            for (Node[] p : pairs) {
                if (tryMerge(nb, p[0], p[1], diag, variantId)) {
                    removed++;
                    merged = true;
                    break;                                   // список узлов изменился — пересобираем
                }
            }
            if (!merged) break;
        }
        return removed;
    }

    /** Что получится, если оставить камеру keep и убрать drop. */
    private static class Plan {
        Node keep, drop;
        List<Edge> moving = new ArrayList<>();
        List<List<Coordinate>> coords = new ArrayList<>();
        List<Edge> between = new ArrayList<>();
        double deltaLength;          // насколько удлинится сеть, м
    }

    private boolean tryMerge(NetworkBuilder nb, Node a, Node b, Diagnostics diag, String variantId) {
        if (a.edges.isEmpty() || b.edges.isEmpty()) return false;
        List<Edge> between = new ArrayList<>();
        for (Edge e : a.edges) if (e.other(a) == b) between.add(e);
        if (a.degree() + b.degree() - 2 * between.size() > maxBranches) return false;

        // пробуем оба направления и берём то, где сеть удлиняется меньше
        Plan best = null;
        for (Node keep : new Node[]{a, b}) {
            Plan p = plan(keep, keep == a ? b : a, between);
            if (p == null) continue;
            if (best == null || p.deltaLength < best.deltaLength) best = p;
        }
        if (best == null) return false;

        // камера экономится, но перенос ветвей удлиняет трассу: сливаем, только если это выгодно.
        // deltaLength уже учитывает исчезнувший участок между камерами
        double saved = ref.chamberCost(chamberDn(best.drop));
        double added = best.deltaLength * costPerM(best.drop);
        if (added >= saved) return false;

        for (int i = 0; i < best.moving.size(); i++) {
            Edge e = best.moving.get(i);
            e.coords = best.coords.get(i);
            if (e.a == best.drop) e.a = best.keep;
            else e.b = best.keep;
            best.keep.edges.add(e);
        }
        for (Edge e : best.between) nb.removeEdge(e);
        best.drop.edges.clear();
        nb.removeNode(best.drop);
        diag.info("CHAMBER_MERGE", "Вариант " + variantId + ": камеры " + a.id + " и " + b.id
                + " в " + GeoUtil.round(a.xy.distance(b.xy), 1) + " м друг от друга сведены в одну ("
                + best.keep.id + "): минус камера " + Math.round(saved / 1e6) + " млн, трасса "
                + (best.deltaLength >= 0 ? "длиннее" : "короче") + " на "
                + GeoUtil.round(Math.abs(best.deltaLength), 1) + " м", null);
        return true;
    }

    private Plan plan(Node keep, Node drop, List<Edge> between) {
        Plan p = new Plan();
        p.keep = keep;
        p.drop = drop;
        p.between = between;
        for (Edge e : drop.edges) {
            if (between.contains(e)) continue;
            List<Coordinate> coords = reroute(e, drop, keep);
            if (coords == null) return null;
            p.moving.add(e);
            p.coords.add(coords);
            p.deltaLength += length(coords) - e.length();
        }
        for (Edge e : between) p.deltaLength -= e.length();
        return p;
    }

    private static double length(List<Coordinate> cs) {
        double l = 0;
        for (int i = 1; i < cs.size(); i++) l += cs.get(i - 1).distance(cs.get(i));
        return l;
    }

    /** Диаметр камеры — по наибольшему примыкающему участку; на этом этапе он ещё не подобран,
     *  поэтому берём оценку по числу лучей: чем больше ветвей, тем крупнее узел. */
    private int chamberDn(Node n) {
        return field.clearanceDn();
    }

    private double costPerM(Node n) {
        return ref.spec(field.clearanceDn()).newCostPerM;
    }

    /**
     * Геометрия ветви после переноса: хвост у сливаемой камеры заменяется прямой к оставшейся.
     * Возвращает null, если так пройти нельзя — по свободе места или по углу поворота.
     */
    private List<Coordinate> reroute(Edge e, Node drop, Node keep) {
        List<Coordinate> coords = new ArrayList<>(e.coords);
        boolean atEnd = coords.get(coords.size() - 1).distance(drop.xy) < 0.5;
        if (!atEnd && coords.get(0).distance(drop.xy) >= 0.5) return null;
        if (!atEnd) java.util.Collections.reverse(coords);     // работаем с хвостом в конце
        // снимаем вершины, оказавшиеся ближе к новой камере, чем последняя точка ветви
        double d = keep.xy.distance(drop.xy);
        while (coords.size() > 2 && coords.get(coords.size() - 2).distance(keep.xy) < d) {
            coords.remove(coords.size() - 1);
        }
        coords.set(coords.size() - 1, new Coordinate(keep.xy.x, keep.xy.y));
        if (coords.size() < 2 || coords.get(coords.size() - 2).distance(keep.xy) < 0.5) return null;
        if (!freeLine(coords.get(coords.size() - 2), coords.get(coords.size() - 1))) return null;
        if (coords.size() >= 3) {
            double turn = ru.intelligence.heatnet.routing.PathSimplifier.turnDeg(
                    coords.get(coords.size() - 3), coords.get(coords.size() - 2), coords.get(coords.size() - 1));
            if (turn > maxTurnDeg + 1e-6) return null;
        }
        if (!atEnd) java.util.Collections.reverse(coords);
        return coords;
    }

    /** Отрезок не должен заходить в запретные зоны: проверка по тем же буферам, что и поиск трассы. */
    private boolean freeLine(Coordinate from, Coordinate to) {
        LineString line = GeoUtil.line(java.util.Arrays.asList(from, to));
        Envelope env = new Envelope(line.getEnvelopeInternal());
        env.expandBy(2.0);
        for (ObstacleField.Obstacle o : field.query(env)) {
            if (!o.isForbidden()) continue;
            if (o.getBlocked().intersects(line)) return false;
        }
        return true;
    }
}
