package ru.intelligence.heatnet.hydraulics;

import org.locationtech.jts.geom.Coordinate;
import ru.intelligence.heatnet.config.ReferenceRules;
import ru.intelligence.heatnet.config.ReferenceRules.DiameterSpec;
import ru.intelligence.heatnet.geo.GeoUtil;
import ru.intelligence.heatnet.model.Diagnostics;
import ru.intelligence.heatnet.model.InputModel;
import ru.intelligence.heatnet.model.InputModel.ConnectionPoint;
import ru.intelligence.heatnet.model.InputModel.ExistingChamber;
import ru.intelligence.heatnet.model.InputModel.ExistingSegment;
import ru.intelligence.heatnet.model.Variant;
import ru.intelligence.heatnet.network.NetworkTopology;
import ru.intelligence.heatnet.planner.NetworkBuilder;
import ru.intelligence.heatnet.planner.NetworkBuilder.Edge;
import ru.intelligence.heatnet.planner.NetworkBuilder.Node;
import ru.intelligence.heatnet.planner.NetworkBuilder.NodeKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Расчёт построенной сети по правилам ТП §3, §7, §8:
 * расходы по дереву, подбор ДУ, предельная длина по номенклатуре, технические узлы,
 * врезки и камеры, реконструкция существующей сети и камер-врезок, стоимость и сводка.
 */
public class HydraulicsCalculator {

    private final ReferenceRules ref;
    private final NetworkTopology topo;
    private final InputModel model;

    public HydraulicsCalculator(ReferenceRules ref, NetworkTopology topo, InputModel model) {
        this.ref = ref;
        this.topo = topo;
        this.model = model;
    }

    /** Заполняет вариант объектами выхода. Узлы TECH_NODE без смены параметров схлопываются. */
    public void compute(NetworkBuilder nb, Variant v, Diagnostics diag) {
        Map<String, ConnectionPoint> cpById = new HashMap<>();
        for (ConnectionPoint cp : model.points) cpById.put(cp.id, cp);

        // 1. ориентация рёбер от корней (TIE_IN) к листьям и расход
        Map<Edge, Double> flow = new HashMap<>();
        Map<Edge, Set<String>> served = new HashMap<>();
        Map<Edge, Node> upstreamNode = new HashMap<>();  // узел ребра, ближний к источнику
        List<Node> roots = new ArrayList<>();
        for (Node n : nb.nodes().values()) if (n.kind == NodeKind.TIE_IN) roots.add(n);
        Set<Edge> visited = new HashSet<>();
        for (Node root : roots) {
            // DFS с пост-обработкой
            Deque<Object[]> stack = new ArrayDeque<>();
            stack.push(new Object[]{root, null});
            List<Object[]> order = new ArrayList<>();
            while (!stack.isEmpty()) {
                Object[] cur = stack.pop();
                Node n = (Node) cur[0]; Edge from = (Edge) cur[1];
                order.add(cur);
                for (Edge e : n.edges) {
                    if (e == from || visited.contains(e)) continue;
                    visited.add(e);
                    upstreamNode.put(e, n);
                    stack.push(new Object[]{e.other(n), e});
                }
            }
            for (int i = order.size() - 1; i >= 0; i--) {
                Node n = (Node) order.get(i)[0]; Edge from = (Edge) order.get(i)[1];
                double f = 0; Set<String> s = new HashSet<>();
                if (n.kind == NodeKind.CONNECTION_POINT && cpById.containsKey(n.id)) { f += cpById.get(n.id).flowTph; s.add(n.id); }
                for (Edge e : n.edges) if (e != from && upstreamNode.get(e) == n) { f += flow.getOrDefault(e, 0.0); s.addAll(served.getOrDefault(e, Set.of())); }
                if (from != null) { flow.put(from, f); served.put(from, s); }
            }
        }
        for (Edge e : nb.edges()) if (!flow.containsKey(e)) {
            flow.put(e, 0.0); served.put(e, Set.of());
            diag.warn("EDGE_NOT_ROOTED", "Участок " + e.id + " не связан с врезкой", e.id);
        }

        // 2. ДУ по расходу
        Map<Edge, DiameterSpec> dn = new HashMap<>();
        Map<Edge, Integer> upsized = new HashMap<>();
        for (Edge e : nb.edges()) { dn.put(e, ref.diameterForFlow(flow.get(e))); upsized.put(e, 0); }

        // 3. предельная длина проверяется отдельно по каждому непрерывному пути от точки подключения
        // к месту присоединения; длины параллельных ветвей не суммируются (ТП §2.3, Разъяснения п. 2).
        // Если минимальный по расходу ДУ не удовлетворяет предельной длине, берётся следующий ДУ,
        // удовлетворяющий обоим условиям; завышать ДУ произвольно нельзя.
        for (int iter = 0; iter < 8; iter++) {
            boolean changed = false;
            for (Node leaf : nb.nodes().values()) {
                if (leaf.kind != NodeKind.CONNECTION_POINT) continue;
                List<Edge> path = new ArrayList<>();
                Node cur = leaf; Edge from = null;
                while (true) {
                    Edge up = null;
                    for (Edge e : cur.edges) if (e != from && upstreamNode.get(e) != cur) { up = e; break; }
                    if (up == null) break;
                    path.add(up);
                    cur = upstreamNode.get(up);
                    from = up;
                    if (cur == null || cur.kind == NodeKind.TIE_IN) break;
                }
                // группы подряд идущих участков одного ДУ вдоль пути
                int k = 0;
                while (k < path.size()) {
                    int d = dn.get(path.get(k)).dn;
                    double run = 0; int end = k;
                    while (end < path.size() && dn.get(path.get(end)).dn == d) { run += path.get(end).length(); end++; }
                    double limit = ref.spec(d).maxLengthM;
                    if (run > limit + 1e-6) {
                        DiameterSpec next = ref.nextStep(d);
                        if (next != null) {
                            for (int q = k; q < end; q++) dn.put(path.get(q), next);
                            diag.info("LENGTH_LIMIT_UPSIZE", "Вариант " + v.variantId + ": путь от точки " + leaf.id
                                    + " содержит непрерывную часть ДУ" + d + " длиной " + GeoUtil.round(run, 1)
                                    + " м при предельной " + limit + " м — выбран следующий ДУ" + next.dn, leaf.id);
                            changed = true;
                        } else {
                            diag.warn("LENGTH_LIMIT_EXCEEDED", "Вариант " + v.variantId + ": путь от точки " + leaf.id
                                    + ": часть ДУ" + d + " длиной " + GeoUtil.round(run, 1) + " м превышает предельную "
                                    + limit + " м, увеличить ДУ нельзя — требуется ручная проработка", leaf.id);
                            v.notes.add("Превышена предельная длина для ДУ" + d + " на пути от точки " + leaf.id);
                        }
                    }
                    k = end;
                }
            }
            // ДУ по направлению к месту присоединения не уменьшается (ТП §2.3)
            for (Edge e : nb.edges()) {
                Node up = upstreamNode.get(e);
                if (up == null) continue;
                for (Edge o : up.edges) {
                    if (o == e || upstreamNode.get(o) == up) continue;   // o — ребро выше по потоку
                    if (dn.get(o).dn < dn.get(e).dn) { dn.put(o, dn.get(e)); changed = true; }
                }
            }
            if (!changed) break;
        }

        // 4. схлопнуть техузлы без смены параметров; создать техузлы там, где ДУ/способ меняется в узле степени 2
        // (узлы CHAMBER и TIE_IN остаются всегда)
        Map<String, Variant.NewSegment> outSegs = new LinkedHashMap<>();
        Map<Edge, Variant.NewSegment> segOf = new HashMap<>();
        Set<Node> keepNodes = new HashSet<>();
        for (Node n : nb.nodes().values()) {
            if (n.kind == NodeKind.TECH_NODE && n.degree() == 2) {
                Edge e1 = n.edges.get(0), e2 = n.edges.get(1);
                boolean same = dn.get(e1).dn == dn.get(e2).dn && sameSpecial(e1, e2);
                if (same) continue; // схлопывается ниже
            }
            keepNodes.add(n);
        }
        // обход цепочек: от каждого сохраняемого узла идём по рёбрам до следующего сохраняемого
        Set<Edge> done = new HashSet<>();
        for (Node n : keepNodes) {
            for (Edge e0 : n.edges) {
                if (done.contains(e0)) continue;
                List<Coordinate> coords = new ArrayList<>();
                Node cur = n; Edge e = e0;
                double len = 0;
                List<Coordinate> ec = e.a == cur ? e.coords : reversed(e.coords);
                coords.addAll(ec);
                done.add(e);
                Node nxt = e.other(cur);
                while (!keepNodes.contains(nxt)) {
                    Edge e2 = nxt.edges.get(0) == e ? nxt.edges.get(1) : nxt.edges.get(0);
                    List<Coordinate> c2 = e2.a == nxt ? e2.coords : reversed(e2.coords);
                    for (int i = 1; i < c2.size(); i++) coords.add(c2.get(i));
                    done.add(e2);
                    e = e2; nxt = e2.other(nxt);
                }
                // ориентация a→b: от узла, ближнего к источнику
                Node up = upstreamNode.get(e0);
                Node start = n, end = nxt;
                if (up != null && up != n) { // n — дальний конец
                    start = nxt; end = n; coords = reversed(coords);
                }
                Variant.NewSegment s = new Variant.NewSegment();
                s.id = nb.nextId("seg");
                s.startNodeId = start.id; s.endNodeId = end.id;
                s.geom = GeoUtil.line(NetworkBuilder.dedup(coords));
                s.length = s.geom.getLength();
                s.flowTph = flow.get(e0);
                s.diameter = dn.get(e0).dn;
                s.layingMethod = e0.specialType == null ? "base" : "special";
                s.specialType = e0.specialType;
                s.kSpecial = e0.kSpecial;
                s.servedPoints = new ArrayList<>(served.get(e0));
                s.cost = s.length * ref.spec(s.diameter).newCostPerM * s.kSpecial * s.kDepth;
                outSegs.put(s.id, s);
                segOf.put(e0, s);
            }
        }
        v.segments.addAll(outSegs.values());

        // 5. узлы: камеры, техузлы, врезки
        Map<String, Integer> maxDnAtNode = new HashMap<>();
        for (Variant.NewSegment s : v.segments) {
            maxDnAtNode.merge(s.startNodeId, s.diameter, Math::max);
            maxDnAtNode.merge(s.endNodeId, s.diameter, Math::max);
        }
        for (Node n : keepNodes) {
            switch (n.kind) {
                case CHAMBER: {
                    Variant.NewChamber c = new Variant.NewChamber();
                    c.id = n.id; c.geom = GeoUtil.point(n.xy);
                    c.diameter = maxDnAtNode.getOrDefault(n.id, 0);
                    c.cost = ref.chamberCost(c.diameter);
                    v.chambers.add(c);
                    break;
                }
                case TECH_NODE: {
                    Variant.TechNode t = new Variant.TechNode();
                    t.id = n.id; t.geom = GeoUtil.point(n.xy);
                    v.techNodes.add(t);
                    break;
                }
                case TIE_IN: {
                    boolean existingChamber = "heat_chamber".equals(n.existingObjectType);
                    if (existingChamber) {
                        // присоединение к существующей камере: каждый входящий участок — врезка 5 млн (ТП §3.2),
                        // отдельный объект в выход не пишется, участки ссылаются на id существующей камеры
                        ExistingChamber ch = topo.chamber(n.existingObjectId);
                        int existingDn = ch != null && ch.diameter != null ? ch.diameter : (ch != null ? topo.maxAdjacentDiameter(ch) : 0);
                        int k = 0;
                        for (Edge e : n.edges) {
                            Variant.TieIn t = new Variant.TieIn();
                            t.id = n.id + "_" + (++k);
                            t.geom = GeoUtil.point(n.xy);
                            t.existingObjectId = n.existingObjectId;
                            t.existingObjectType = "heat_chamber";
                            t.existingDiameter = existingDn;
                            t.requiredDiameter = dn.get(e).dn;
                            t.addedFlowTph = flow.get(e);
                            t.cost = ref.tieIn.cost;
                            t.toExistingChamber = true;
                            v.tieIns.add(t);
                            Variant.NewSegment s2 = segOf.get(e);
                            if (s2 != null) {
                                if (s2.startNodeId.equals(n.id)) s2.startNodeId = n.existingObjectId;
                                else if (s2.endNodeId.equals(n.id)) s2.endNodeId = n.existingObjectId;
                            }
                        }
                    } else {
                        // присоединение к участку существующей сети: в точке ставится новая камера,
                        // её стоимость уже включает присоединение, отдельной врезки нет (ТП §2.4, §3.2)
                        Variant.NewChamber c = new Variant.NewChamber();
                        c.id = n.id; c.geom = GeoUtil.point(n.xy);
                        c.diameter = maxDnAtNode.getOrDefault(n.id, 0);
                        c.cost = ref.chamberCost(c.diameter);
                        v.chambers.add(c);
                        Variant.TieIn t = new Variant.TieIn();
                        t.id = n.id; t.geom = GeoUtil.point(n.xy);
                        t.existingObjectId = n.existingObjectId;
                        t.existingObjectType = "heat_network";
                        t.existingDiameter = topo.segment(n.existingObjectId) != null ? topo.segment(n.existingObjectId).diameter : 0;
                        t.requiredDiameter = c.diameter;
                        // суммарный расход, который новая сеть приносит в этот узел существующей сети:
                        // нужен для распространения нагрузки к источнику (ExistingLoadAnalyzer)
                        double added = 0;
                        for (Edge e : n.edges) added += flow.get(e);
                        t.addedFlowTph = added;
                        t.fractionAlong = n.fractionAlong;
                        t.cost = 0;
                        t.toExistingChamber = false;
                        v.tieIns.add(t);
                    }
                    break;
                }
                default: break;
            }
        }

        // 6. сводка (реконструкция существующей сети в расчётной модели не выполняется — ТП от 21.09.2026, §2.4)
        summarize(v);
    }

    private static boolean sameSpecial(Edge a, Edge b) {
        if (a.specialType == null && b.specialType == null) return true;
        return a.specialType != null && a.specialType.equals(b.specialType) && a.kSpecial == b.kSpecial;
    }

    private static List<Coordinate> reversed(List<Coordinate> cs) {
        List<Coordinate> r = new ArrayList<>(cs);
        java.util.Collections.reverse(r);
        return r;
    }

    /** Сводка по варианту (ТП от 21.09.2026, §6): стоимость строительства + штраф; L — длина новых участков. */
    public void summarize(Variant v) {
        v.constructionCost = 0; v.newNetworkLength = 0;
        double segments = 0;
        for (Variant.NewSegment s : v.segments) { segments += s.cost; v.newNetworkLength += s.length; }
        v.chamberConstructionCost = 0;
        for (Variant.NewChamber c : v.chambers) v.chamberConstructionCost += c.cost;
        v.existingChamberTieInCount = 0; v.existingChamberTieInCost = 0;
        for (Variant.TieIn t : v.tieIns) {
            if (t.toExistingChamber) { v.existingChamberTieInCount++; v.existingChamberTieInCost += t.cost; }
        }
        v.constructionCost = segments + v.chamberConstructionCost + v.existingChamberTieInCost;
        v.unconnectedPenalty = 0;
        Map<String, ConnectionPoint> cp = new HashMap<>();
        for (ConnectionPoint p : model.points) cp.put(p.id, p);
        for (String id : v.unconnectedOksIds) if (cp.containsKey(id)) v.unconnectedPenalty += ref.penalty(cp.get(id).flowTph);
        v.calculatedCost = v.constructionCost + v.unconnectedPenalty;
        v.score = ref.score(v.calculatedCost, v.newNetworkLength);
    }

}
