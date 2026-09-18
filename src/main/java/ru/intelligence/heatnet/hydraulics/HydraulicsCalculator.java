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

        // 3. предельная длина: связные части одной номенклатуры (Q&A: при ветвлении суммируется вся часть)
        for (int iter = 0; iter < 1 + ref.lengthLimit.allowUpsizeSteps; iter++) {
            boolean changed = false;
            Set<Edge> seen = new HashSet<>();
            for (Edge start : nb.edges()) {
                if (seen.contains(start)) continue;
                int d = dn.get(start).dn;
                List<Edge> comp = new ArrayList<>();
                Deque<Edge> q = new ArrayDeque<>(); q.add(start); seen.add(start);
                while (!q.isEmpty()) {
                    Edge e = q.poll(); comp.add(e);
                    // через врезку не проходим: каждая часть новой сети присоединяется к существующей в одной точке (ТЗ 2.2)
                    for (Node n : new Node[]{e.a, e.b}) {
                        if (n.kind == NodeKind.TIE_IN) continue;
                        for (Edge o : n.edges) if (!seen.contains(o) && dn.get(o).dn == d) { seen.add(o); q.add(o); }
                    }
                }
                double total = 0; for (Edge e : comp) total += e.length();
                double limit = ref.spec(d).maxLengthM;
                if (total > limit + 1e-6) {
                    DiameterSpec next = ref.nextStep(d);
                    int steps = ref.lengthLimit.allowUpsizeSteps;
                    boolean canUpsize = next != null;
                    for (Edge e : comp) if (upsized.get(e) >= steps) canUpsize = false;
                    if (canUpsize) {
                        // поднимаем ДУ у всей части (не более чем на одну ступень, ТП §3 / Q&A)
                        for (Edge e : comp) { dn.put(e, next); upsized.merge(e, 1, Integer::sum); }
                        v.notes.add("Часть сети ДУ" + d + " длиной " + GeoUtil.round(total, 1) + " м превышает предельную " + limit + " м — ДУ поднят до " + next.dn);
                        diag.info("LENGTH_LIMIT_UPSIZE", "Вариант " + v.variantId + ": часть сети ДУ" + d + " (" + GeoUtil.round(total, 1) + " м > " + limit + " м) поднята до ДУ" + next.dn, null);
                        changed = true;
                    } else {
                        diag.warn("LENGTH_LIMIT_EXCEEDED", "Вариант " + v.variantId + ": часть сети ДУ" + d + " длиной " + GeoUtil.round(total, 1) + " м превышает предельную " + limit + " м, ДУ уже поднят на допустимую ступень — требуется ручная проработка (доп. камера/иная трасса)", null);
                        v.notes.add("Превышена предельная длина для ДУ" + d + ": " + GeoUtil.round(total, 1) + " м > " + limit + " м, дальнейшее увеличение ДУ не допускается");
                    }
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
                    // каждое ребро, входящее в врезку, — независимая врезка (Q&A: несколько веток в одну камеру = несколько врезок)
                    int k = 0;
                    for (Edge e : n.edges) {
                        Variant.TieIn t = new Variant.TieIn();
                        t.id = n.edges.size() == 1 ? n.id : n.id + "_" + (++k);
                        t.geom = GeoUtil.point(n.xy);
                        t.existingObjectId = n.existingObjectId;
                        t.existingObjectType = n.existingObjectType;
                        t.requiredDiameter = dn.get(e).dn;
                        t.addedFlowTph = flow.get(e);
                        t.fractionAlong = n.fractionAlong;
                        t.cost = ref.tieIn.cost;
                        if ("heat_chamber".equals(n.existingObjectType)) {
                            ExistingChamber ch = topo.chamber(n.existingObjectId);
                            t.existingDiameter = ch.diameter != null ? ch.diameter : topo.maxAdjacentDiameter(ch);
                        } else {
                            t.existingDiameter = topo.segment(n.existingObjectId).diameter;
                        }
                        v.tieIns.add(t);
                        // сегменты, начинающиеся в узле врезки, должны ссылаться на id врезки
                        Variant.NewSegment s = segOf.get(e);
                        if (s != null) { if (s.startNodeId.equals(n.id)) s.startNodeId = t.id; else if (s.endNodeId.equals(n.id)) s.endNodeId = t.id; }
                    }
                    if (n.edges.size() > 1 && "heat_network".equals(n.existingObjectType)) {
                        // несколько веток в одну точку трубы: там стоит новая камера
                        Variant.NewChamber c = new Variant.NewChamber();
                        c.id = n.id + "_ch"; c.geom = GeoUtil.point(n.xy);
                        c.diameter = maxDnAtNode.getOrDefault(n.id, 0);
                        c.cost = ref.chamberCost(c.diameter);
                        v.chambers.add(c);
                    } else if ("heat_network".equals(n.existingObjectType)) {
                        // врезка в трубу вдали от камеры → новая камера в точке врезки (ТП §2.3, §8.2)
                        Variant.NewChamber c = new Variant.NewChamber();
                        c.id = n.id + "_ch"; c.geom = GeoUtil.point(n.xy);
                        c.diameter = maxDnAtNode.getOrDefault(n.id, 0);
                        c.cost = ref.chamberCost(c.diameter);
                        v.chambers.add(c);
                    }
                    break;
                }
                default: break;
            }
        }

        // 6. реконструкция существующей сети: добавленный расход от каждой врезки к источнику (ТП §7)
        reconstruction(v, diag);

        // 7. сводка
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

    /** Распространение добавленного расхода к источнику и определение реконструируемых частей. */
    private void reconstruction(Variant v, Diagnostics diag) {
        // по каждому существующему участку: список точек деления (доли) и добавленный расход на интервалах
        Map<String, TreeMap<Double, Double>> addedByPart = new HashMap<>(); // seg → (from-fraction sorted) — упрощённо через интервалы
        Map<String, List<double[]>> intervals = new HashMap<>();            // seg → [from, to, added]
        Map<String, Double> addedAtChamber = new HashMap<>();
        for (Variant.TieIn t : v.tieIns) {
            List<NetworkTopology.ChainPart> chain;
            if ("heat_chamber".equals(t.existingObjectType)) {
                chain = topo.upstreamChainFromChamber(topo.chamber(t.existingObjectId));
                addedAtChamber.merge(t.existingObjectId, t.addedFlowTph, Double::sum);
            } else {
                ExistingSegment s = topo.segment(t.existingObjectId);
                double f = t.fractionAlong != null ? t.fractionAlong : GeoUtil.fractionAlong(s.geom, t.geom.getCoordinate());
                chain = topo.upstreamChain(s, f);
            }
            if (chain.isEmpty()) diag.warn("NO_UPSTREAM_CHAIN", "Врезка " + t.id + ": не удалось построить цепочку к источнику", t.id);
            for (NetworkTopology.ChainPart p : chain) {
                intervals.computeIfAbsent(p.seg.id, k -> new ArrayList<>()).add(new double[]{Math.min(p.from, p.to), Math.max(p.from, p.to), t.addedFlowTph});
            }
        }
        // по участкам: разбить на элементарные интервалы по всем границам, просуммировать добавленный расход
        Map<String, Integer> requiredDnAtSegment = new HashMap<>();
        for (Map.Entry<String, List<double[]>> e : intervals.entrySet()) {
            ExistingSegment s = topo.segment(e.getKey());
            TreeMap<Double, Boolean> cuts = new TreeMap<>();
            for (double[] iv : e.getValue()) { cuts.put(iv[0], true); cuts.put(iv[1], true); }
            List<Double> b = new ArrayList<>(cuts.keySet());
            List<double[]> parts = new ArrayList<>(); // [from, to, added]
            for (int i = 0; i + 1 < b.size(); i++) {
                double f0 = b.get(i), f1 = b.get(i + 1);
                if (f1 - f0 < 1e-9) continue;
                double mid = (f0 + f1) / 2, added = 0;
                for (double[] iv : e.getValue()) if (iv[0] <= mid && mid <= iv[1]) added += iv[2];
                parts.add(new double[]{f0, f1, added});
            }
            // слить соседние части с одинаковым добавленным расходом
            List<double[]> merged = new ArrayList<>();
            for (double[] p : parts) {
                if (!merged.isEmpty() && Math.abs(merged.get(merged.size() - 1)[2] - p[2]) < 1e-9 && Math.abs(merged.get(merged.size() - 1)[1] - p[0]) < 1e-9) merged.get(merged.size() - 1)[1] = p[1];
                else merged.add(p);
            }
            for (double[] p : merged) {
                double existing = s.flowOrZero();
                double total = existing + p[2];
                DiameterSpec req = ref.diameterForFlow(total);
                if (req.dn > s.diameter) {
                    Variant.SegmentReconstruction r = new Variant.SegmentReconstruction();
                    r.id = "recon_" + (v.reconstructions.size() + 1);
                    r.existingObjectId = s.id;
                    r.geom = GeoUtil.substring(s.geom, p[0], p[1]);
                    r.existingFlowTph = existing; r.addedFlowTph = p[2]; r.calculatedFlowTph = total;
                    r.existingDiameter = s.diameter; r.requiredDiameter = req.dn;
                    r.length = r.geom.getLength();
                    r.cost = r.length * req.reconCostPerM;
                    v.reconstructions.add(r);
                    requiredDnAtSegment.merge(s.id, req.dn, Math::max);
                }
            }
        }
        // реконструкция камер-врезок (ТП §8.2): max ДУ примыкающих (существующих с учётом реконструкции + новых) > ДУ камеры
        Set<String> doneChambers = new HashSet<>();
        for (Variant.TieIn t : v.tieIns) {
            if (!"heat_chamber".equals(t.existingObjectType) || !doneChambers.add(t.existingObjectId)) continue;
            ExistingChamber ch = topo.chamber(t.existingObjectId);
            int existingDn = ch.diameter != null ? ch.diameter : topo.maxAdjacentDiameter(ch);
            int maxDn = 0;
            for (ExistingSegment s : topo.chamberSegments(ch)) maxDn = Math.max(maxDn, Math.max(s.diameter, requiredDnAtSegment.getOrDefault(s.id, 0)));
            for (Variant.TieIn t2 : v.tieIns) if (t2.existingObjectId.equals(ch.id)) maxDn = Math.max(maxDn, t2.requiredDiameter);
            if (maxDn > existingDn) {
                Variant.ChamberReconstruction r = new Variant.ChamberReconstruction();
                r.id = "chrecon_" + (v.chamberReconstructions.size() + 1);
                r.existingObjectId = ch.id; r.geom = ch.geom;
                r.existingDiameter = existingDn; r.requiredDiameter = maxDn;
                r.cost = ref.chamberCost(maxDn);
                v.chamberReconstructions.add(r);
            }
        }
    }

    private void summarize(Variant v) {
        v.constructionCost = 0; v.newNetworkLength = 0;
        for (Variant.NewSegment s : v.segments) { v.constructionCost += s.cost; v.newNetworkLength += s.length; }
        v.chamberConstructionCost = 0; for (Variant.NewChamber c : v.chambers) v.chamberConstructionCost += c.cost;
        v.tieInCost = 0; for (Variant.TieIn t : v.tieIns) v.tieInCost += t.cost;
        v.reconstructionCost = 0; v.reconstructionLength = 0;
        for (Variant.SegmentReconstruction r : v.reconstructions) { v.reconstructionCost += r.cost; v.reconstructionLength += r.length; }
        v.chamberReconstructionCost = 0; for (Variant.ChamberReconstruction r : v.chamberReconstructions) v.chamberReconstructionCost += r.cost;
        v.unconnectedPenalty = 0;
        Map<String, ConnectionPoint> cp = new HashMap<>();
        for (ConnectionPoint p : model.points) cp.put(p.id, p);
        for (String id : v.unconnectedOksIds) if (cp.containsKey(id)) v.unconnectedPenalty += ref.penalty(cp.get(id).flowTph);
        v.calculatedCost = v.constructionCost + v.chamberConstructionCost + v.tieInCost + v.reconstructionCost + v.chamberReconstructionCost + v.unconnectedPenalty;
        v.length = v.newNetworkLength + v.reconstructionLength;
        v.score = ref.score(v.calculatedCost, v.length);
    }
}
