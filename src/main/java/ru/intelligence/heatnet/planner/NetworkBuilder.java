package ru.intelligence.heatnet.planner;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import ru.intelligence.heatnet.geo.GeoUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Строящаяся новая сеть варианта: узлы (точки ОКС, камеры, техузлы, врезки) и рёбра (ломаные).
 * Ребро хранит цепочку координат; расходы, ДУ и стоимости считаются позже (HydraulicsCalculator).
 */
public class NetworkBuilder {

    public enum NodeKind { CONNECTION_POINT, TIE_IN, CHAMBER, TECH_NODE, EXIT }

    public static class Node {
        public String id;
        public NodeKind kind;
        public Coordinate xy;
        public final List<Edge> edges = new ArrayList<>();
        /** Для TIE_IN: объект существующей сети. */
        public String existingObjectId;
        public String existingObjectType;
        public Double fractionAlong;
        public int degree() { return edges.size(); }
    }

    public static class Edge {
        public String id;
        public Node a;              // ближе к источнику (после ориентации)
        public Node b;
        public List<Coordinate> coords = new ArrayList<>();  // от a к b
        public String specialType;  // null — обычный участок
        public double kSpecial = 1.0;
        public double length() {
            double l = 0;
            for (int i = 1; i < coords.size(); i++) l += coords.get(i - 1).distance(coords.get(i));
            return l;
        }
        public LineString line() { return GeoUtil.line(coords); }
        public Node other(Node n) { return n == a ? b : a; }
    }

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private final Map<String, Integer> counters = new HashMap<>();

    public String nextId(String prefix) {
        int n = counters.merge(prefix, 1, Integer::sum);
        return prefix + "_" + n;
    }

    public Node addNode(NodeKind kind, Coordinate xy, String id) {
        Node n = new Node();
        n.id = id != null ? id : nextId(prefixFor(kind));
        n.kind = kind;
        n.xy = new Coordinate(xy.x, xy.y);
        nodes.put(n.id, n);
        return n;
    }

    private static String prefixFor(NodeKind k) {
        switch (k) {
            case TIE_IN: return "tie";
            case CHAMBER: return "ch";
            case TECH_NODE: return "node";
            case EXIT: return "exit";
            default: return "cp";
        }
    }

    public Edge addEdge(Node a, Node b, List<Coordinate> coords, String specialType, double kSpecial) {
        Edge e = new Edge();
        e.id = nextId("new");
        e.a = a; e.b = b;
        e.coords = new ArrayList<>(coords);
        e.specialType = specialType;
        e.kSpecial = kSpecial;
        a.edges.add(e); b.edges.add(e);
        edges.add(e);
        return e;
    }

    /** Разрезает ребро в точке (ближайшей вершине или проекции) и вставляет узел. */
    public Node splitEdge(Edge e, Coordinate at, NodeKind kind) {
        LineString ls = e.line();
        double f = GeoUtil.fractionAlong(ls, at);
        Coordinate p = GeoUtil.pointAt(ls, f);
        // индекс вставки
        List<Coordinate> before = new ArrayList<>();
        List<Coordinate> after = new ArrayList<>();
        double acc = 0, target = f * ls.getLength();
        boolean passed = false;
        before.add(e.coords.get(0));
        for (int i = 1; i < e.coords.size(); i++) {
            double seg = e.coords.get(i - 1).distance(e.coords.get(i));
            if (!passed && acc + seg >= target - 1e-9) {
                before.add(p);
                after.add(p);
                passed = true;
                if (e.coords.get(i).distance(p) > 1e-6) after.add(e.coords.get(i));
            } else if (passed) {
                after.add(e.coords.get(i));
            } else {
                before.add(e.coords.get(i));
            }
            acc += seg;
        }
        if (!passed) { before.add(p); after.add(p); }
        Node n = addNode(kind, p, null);
        Node b = e.b;
        e.b = n;
        e.coords = dedup(before);
        b.edges.remove(e);
        n.edges.add(e);
        Edge e2 = new Edge();
        e2.id = nextId("new");
        e2.a = n; e2.b = b; e2.coords = dedup(after); e2.specialType = e.specialType; e2.kSpecial = e.kSpecial;
        n.edges.add(e2); b.edges.add(e2);
        edges.add(e2);
        return n;
    }

    public static List<Coordinate> dedup(List<Coordinate> cs) {
        List<Coordinate> out = new ArrayList<>();
        for (Coordinate c : cs) if (out.isEmpty() || out.get(out.size() - 1).distance(c) > 1e-6) out.add(c);
        return out;
    }

    public Map<String, Node> nodes() { return nodes; }
    public List<Edge> edges() { return edges; }

    /** Убрать ребро из сети вместе со ссылками на него в узлах (слияние камер). */
    public void removeEdge(Edge e) {
        e.a.edges.remove(e);
        e.b.edges.remove(e);
        edges.remove(e);
    }

    /** Убрать узел, у которого не осталось рёбер. */
    public void removeNode(Node n) {
        if (!n.edges.isEmpty()) throw new IllegalStateException("у узла " + n.id + " остались рёбра");
        nodes.remove(n.id);
    }

    /** Ребро, содержащее точку (в пределах допуска), либо null. */
    public Edge edgeNear(Coordinate c, double tol) {
        Edge best = null; double bd = tol;
        for (Edge e : edges) {
            double d = e.line().distance(GeoUtil.point(c));
            if (d <= bd) { bd = d; best = e; }
        }
        return best;
    }

    public Node nodeNear(Coordinate c, double tol) {
        Node best = null; double bd = tol;
        for (Node n : nodes.values()) {
            double d = n.xy.distance(c);
            if (d <= bd) { bd = d; best = n; }
        }
        return best;
    }
}
