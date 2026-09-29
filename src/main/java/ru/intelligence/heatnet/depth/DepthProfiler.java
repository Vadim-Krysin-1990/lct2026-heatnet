package ru.intelligence.heatnet.depth;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.linearref.LengthIndexedLine;
import ru.intelligence.heatnet.config.ReferenceRules;
import ru.intelligence.heatnet.config.RestrictionRules;
import ru.intelligence.heatnet.geo.GeoUtil;
import ru.intelligence.heatnet.hydraulics.HydraulicsCalculator;
import ru.intelligence.heatnet.model.Diagnostics;
import ru.intelligence.heatnet.model.InputModel;
import ru.intelligence.heatnet.model.Variant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Профиль новой сети по глубине (дополнительная задача). Для каждой цепочки участков между узлами-не-техузлами:
 * находит пересечения с линейными коммуникациями (газ, кабель, существующая теплосеть), выбирает проход сверху/снизу,
 * строит профиль «спуск — полка 4 м — подъём» с уклоном ≤ 0,10, объединяет близкие пересечения, делит участки
 * в вершинах профиля и на отметке 3,0 м (технические узлы), считает длину с наклоном, Kгл и стоимость, пишет Z.
 */
public class DepthProfiler {

    private static class Utility {
        String id; String type; LineString geom; Integer dn;
    }

    private final ReferenceRules ref;
    private final RestrictionRules rr;
    private final DepthRules rules;
    private final List<Utility> utilities = new ArrayList<>();
    private final STRtree index = new STRtree();

    public DepthProfiler(ReferenceRules ref, RestrictionRules rr, InputModel model) {
        this.ref = ref;
        this.rr = rr;
        this.rules = new DepthRules(ref, rr);
        for (InputModel.Restriction r : model.restrictions) {
            RestrictionRules.Rule rule = rr.resolve(r.type);
            if (!rule.isLine() || rule.depth == null) continue;
            addUtility(r.id, r.type, r.geom, null);
        }
        RestrictionRules.Rule hn = rr.resolve("heat_network");
        if (hn.depth != null) for (InputModel.ExistingSegment s : model.segments) addUtility(s.id, "heat_network", s.geom, s.diameter);
        index.build();
    }

    private void addUtility(String id, String type, Geometry g, Integer dn) {
        for (int i = 0; i < g.getNumGeometries(); i++) {
            Geometry part = g.getGeometryN(i);
            if (!(part instanceof LineString)) continue;
            Utility u = new Utility();
            u.id = id; u.type = type; u.geom = (LineString) part; u.dn = dn;
            utilities.add(u);
            index.insert(part.getEnvelopeInternal(), u);
        }
    }

    /** Одна вершина профиля: расстояние вдоль цепочки и глубина. */
    private static class Vertex {
        double s; double depth;
        Vertex(double s, double depth) { this.s = s; this.depth = depth; }
    }

    private static class Group {
        double a, b;        // начало и конец полки вдоль цепочки
        double depth;
        String position;
        List<String> types = new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public void apply(Variant v, Diagnostics diag, HydraulicsCalculator hydraulics) {
        double normal = rules.normalDepth();
        v.depthCrossings.clear();
        double slope = ref.depth.maxSlope;
        double half = ref.depth.plateauHalfLengthM;
        // 1. цепочки: последовательности участков, связанных через технические узлы
        Set<String> techIds = new java.util.HashSet<>();
        for (Variant.TechNode t : v.techNodes) techIds.add(t.id);
        Map<String, List<Variant.NewSegment>> byStart = new HashMap<>();
        Map<String, Variant.NewSegment> byEnd = new HashMap<>();
        for (Variant.NewSegment s : v.segments) { byStart.computeIfAbsent(s.startNodeId, k -> new ArrayList<>()).add(s); byEnd.put(s.endNodeId, s); }
        List<List<Variant.NewSegment>> chains = new ArrayList<>();
        Set<Variant.NewSegment> used = new java.util.HashSet<>();
        for (Variant.NewSegment s : v.segments) {
            if (used.contains(s) || (techIds.contains(s.startNodeId) && byEnd.containsKey(s.startNodeId))) continue;
            List<Variant.NewSegment> chain = new ArrayList<>();
            Variant.NewSegment cur = s;
            while (cur != null && used.add(cur)) {
                chain.add(cur);
                if (!techIds.contains(cur.endNodeId)) break;
                List<Variant.NewSegment> next = byStart.get(cur.endNodeId);
                cur = next == null || next.size() != 1 ? null : next.get(0);
            }
            chains.add(chain);
        }
        List<Variant.NewSegment> out = new ArrayList<>();
        List<Variant.TechNode> nodes = new ArrayList<>(v.techNodes);
        int conflicts = 0, crossingsTotal = 0;
        int nodeCounter = nodes.size();
        for (List<Variant.NewSegment> chain : chains) {
            // геометрия цепочки и интервалы участков
            List<Coordinate> coords = new ArrayList<>();
            List<double[]> segRange = new ArrayList<>(); // [s0, s1] каждого исходного участка
            double acc = 0;
            for (Variant.NewSegment s : chain) {
                Coordinate[] cs = s.geom.getCoordinates();
                for (int i = 0; i < cs.length; i++) if (coords.isEmpty() || i > 0) coords.add(new Coordinate(cs[i].x, cs[i].y));
                segRange.add(new double[]{acc, acc + s.length});
                acc += s.length;
            }
            LineString line = GeoUtil.line(coords);
            double total = line.getLength();
            LengthIndexedLine lil = new LengthIndexedLine(line);
            // 2. пересечения
            List<Group> groups = new ArrayList<>();
            List<Utility> cand = index.query(line.getEnvelopeInternal());
            List<double[]> crossings = new ArrayList<>(); // [s, indexOfUtility, dnNew, cnew]
            for (Utility u : cand) {
                Geometry inter = line.intersection(u.geom);
                if (inter.isEmpty()) continue;
                for (Coordinate c : inter.getCoordinates()) {
                    double s = lil.project(c);
                    if (s < 1.0 || s > total - 1.0) continue;   // конец цепочки на самой сети — врезка, не пересечение
                    Variant.NewSegment seg = segmentAt(chain, segRange, s);
                    crossings.add(new double[]{s, utilities.indexOf(u), seg.diameter, ref.spec(seg.diameter).newCostPerM});
                }
            }
            crossings.sort(Comparator.comparingDouble(a -> a[0]));
            List<Object[]> decided = new ArrayList<>();   // [s, Utility, DepthRules.Crossing] для выхода
            for (double[] cr : crossings) {
                Utility u = utilities.get((int) cr[1]);
                DepthRules.Crossing d = rules.decide(u.type, u.dn, (int) cr[2], cr[3]);
                crossingsTotal++;
                if ("none".equals(d.position)) continue;
                decided.add(new Object[]{cr[0], u, d});
                if (d.position == null) {
                    conflicts++;
                    String note = "Пересечение " + u.type + " " + u.id + " на " + GeoUtil.round(cr[0], 1) + " м участка " + segmentAt(chain, segRange, cr[0]).id + ": " + d.reason + " — требуется ручная проработка";
                    v.notes.add(note);
                    diag.warn("DEPTH_CONFLICT", "Вариант " + v.variantId + ": " + note, u.id);
                    continue;
                }
                if (Math.abs(d.depth - normal) < 1e-9) continue;   // объект глубже/выше настолько, что профиль не меняется
                Group g = new Group();
                g.a = cr[0] - half; g.b = cr[0] + half; g.depth = d.depth; g.position = d.position; g.types.add(u.type + " " + u.id);
                // объединение с предыдущей группой, если наклоны перекрываются (Q&A: без возврата на 3 м)
                if (!groups.isEmpty()) {
                    Group p = groups.get(groups.size() - 1);
                    double pRamp = Math.abs(p.depth - normal) / slope, ramp = Math.abs(g.depth - normal) / slope;
                    boolean sameSide = (p.depth > normal) == (g.depth > normal);
                    if (sameSide && g.a - ramp <= p.b + pRamp) {
                        p.b = g.b;
                        p.depth = p.depth > normal ? Math.max(p.depth, g.depth) : Math.min(p.depth, g.depth);
                        p.types.addAll(g.types);
                        continue;
                    }
                }
                groups.add(g);
            }
            // 3. вершины профиля
            List<Vertex> verts = new ArrayList<>();
            verts.add(new Vertex(0, normal));
            double lastEnd = 0;
            for (Group g : groups) {
                double ramp = Math.abs(g.depth - normal) / slope;
                double a0 = Math.max(lastEnd, g.a - ramp), b1 = Math.min(total, g.b + ramp);
                if (a0 > g.a) {
                    // не хватает места на спуск — начинаем с той глубины, что успеваем набрать (в диагностику)
                    diag.warn("DEPTH_RAMP_SHORT", "Вариант " + v.variantId + ": участок спуска к пересечению (" + String.join(", ", g.types) + ") короче " + GeoUtil.round(ramp, 1) + " м — уклон превышает допустимый", null);
                }
                verts.add(new Vertex(a0, normal));
                verts.add(new Vertex(Math.max(a0, g.a), g.depth));
                verts.add(new Vertex(Math.min(b1, g.b), g.depth));
                verts.add(new Vertex(b1, normal));
                lastEnd = b1;
            }
            verts.add(new Vertex(total, normal));
            // 3a. места пересечений в выход: сторона прохождения и вертикальное расстояние
            // (приложение к ТЗ, разд. 7, пп. 3–4). Глубина берётся фактическая, по построенному профилю.
            for (Object[] rec : decided) {
                double s = (Double) rec[0];
                Utility u = (Utility) rec[1];
                DepthRules.Crossing d = (DepthRules.Crossing) rec[2];
                Variant.NewSegment src = segmentAt(chain, segRange, s);
                Variant.DepthCrossing dc = new Variant.DepthCrossing();
                dc.utilityId = u.id;
                dc.utilityType = u.type;
                dc.segmentId = src.id;
                dc.atM = GeoUtil.round(s, 1);
                dc.geom = GeoUtil.point(lil.extractPoint(s));
                dc.requiredClearanceM = GeoUtil.round(d.verticalClearance, 2);
                double h = depthAt(verts, s);
                dc.newTopDepthM = GeoUtil.round(h, 2);
                if (d.position == null) {
                    dc.position = "conflict";
                    dc.note = d.reason;
                } else {
                    dc.position = d.position;
                    double[] th = rules.utilityTopAndHeight(u.type, u.dn);
                    double hNew = ref.spec(src.diameter).heightM;
                    double clear = "above".equals(d.position) ? th[0] - (h + hNew) : h - (th[0] + th[1]);
                    dc.verticalClearanceM = GeoUtil.round(clear, 2);
                    if (clear < d.verticalClearance - 0.01) {
                        dc.note = "фактическое вертикальное расстояние меньше требуемого — требуется ручная проработка";
                        diag.warn("DEPTH_CLEARANCE", "Вариант " + v.variantId + ": пересечение " + u.type + " " + u.id
                                + " на " + dc.atM + " м участка " + src.id + ": просвет " + dc.verticalClearanceM
                                + " м при норме " + dc.requiredClearanceM + " м", u.id);
                    }
                }
                v.depthCrossings.add(dc);
            }
            // 4. точки деления: вершины профиля + границы исходных участков (спецзоны, смена ДУ)
            TreeSet<Double> cuts = new TreeSet<>();
            for (Vertex vt : verts) cuts.add(clamp(vt.s, 0, total));
            for (double[] r : segRange) { cuts.add(r[0]); cuts.add(r[1]); }
            List<Double> cutList = new ArrayList<>();
            for (double c : cuts) if (cutList.isEmpty() || c - cutList.get(cutList.size() - 1) > 0.05) cutList.add(c);
            if (cutList.get(cutList.size() - 1) < total - 0.05) cutList.add(total);
            // 5. выпуск подучастков
            String prevNode = chain.get(0).startNodeId;
            for (int i = 0; i + 1 < cutList.size(); i++) {
                double s0 = cutList.get(i), s1 = cutList.get(i + 1);
                Variant.NewSegment src = segmentAt(chain, segRange, (s0 + s1) / 2);
                Variant.NewSegment ns = new Variant.NewSegment();
                ns.id = src.id + (chainNeedsSplit(cutList, segRange) ? "_" + (i + 1) : "");
                ns.flowTph = src.flowTph; ns.diameter = src.diameter; ns.layingMethod = src.layingMethod;
                ns.specialType = src.specialType; ns.kSpecial = src.kSpecial; ns.servedPoints = src.servedPoints;
                double d0 = depthAt(verts, s0), d1 = depthAt(verts, s1);
                ns.depthStart = GeoUtil.round(d0, 2); ns.depthEnd = GeoUtil.round(d1, 2);
                ns.kDepth = (d0 > normal + 1e-9 || d1 > normal + 1e-9) ? rules.kDepthAvg(d0, d1) : 1.0;
                Geometry sub = lil.extractLine(s0, s1);
                Coordinate[] sc = sub.getCoordinates();
                // ТП от 21.09.2026, §5: Z-координаты не требуются, вертикальное положение задаётся
                // атрибутами depth_start/depth_end; длина для стоимости и предельной длины —
                // по горизонтальной проекции в EPSG:32637
                // деление линии по длине может продублировать вершину, если точка деления совпала с ней;
                // нулевой отрезок не несёт геометрии, но даёт ложный резкий поворот в проверках
                List<Coordinate> plan = new ArrayList<>(sc.length);
                for (Coordinate c : sc) {
                    Coordinate prev = plan.isEmpty() ? null : plan.get(plan.size() - 1);
                    if (prev == null || prev.distance(c) > 0.01) plan.add(new Coordinate(c.x, c.y));
                }
                if (plan.size() < 2) plan.add(new Coordinate(sc[sc.length - 1].x, sc[sc.length - 1].y));
                ns.geom = GeoUtil.line(plan);
                ns.length = s1 - s0;
                ns.cost = ns.length * ref.spec(ns.diameter).newCostPerM * ns.kSpecial * ns.kDepth;
                // узлы
                boolean lastPiece = i + 2 == cutList.size();
                String endNode;
                if (lastPiece) endNode = chain.get(chain.size() - 1).endNodeId;
                else {
                    Variant.NewSegment origAtCut = null;
                    for (int j = 0; j < segRange.size(); j++) if (Math.abs(segRange.get(j)[1] - s1) < 0.05 && j + 1 < chain.size()) origAtCut = chain.get(j);
                    if (origAtCut != null) endNode = origAtCut.endNodeId;   // существующий техузел на границе исходных участков
                    else {
                        Variant.TechNode t = new Variant.TechNode();
                        t.id = "dnode_" + (++nodeCounter);
                        t.geom = GeoUtil.point(plan.get(plan.size() - 1));
                        nodes.add(t);
                        endNode = t.id;
                    }
                }
                ns.startNodeId = prevNode; ns.endNodeId = endNode;
                prevNode = endNode;
                out.add(ns);
            }
        }
        v.segments.clear(); v.segments.addAll(out);
        v.techNodes.clear(); v.techNodes.addAll(nodes);
        diag.info("DEPTH", "Вариант " + v.variantId + ": пересечений коммуникаций " + crossingsTotal + ", конфликтов " + conflicts + ", участков после профилирования " + out.size(), null);
        hydraulics.summarize(v);
    }

    private static boolean chainNeedsSplit(List<Double> cuts, List<double[]> segRange) {
        return cuts.size() - 1 != segRange.size();
    }

    private static Variant.NewSegment segmentAt(List<Variant.NewSegment> chain, List<double[]> ranges, double s) {
        for (int i = 0; i < ranges.size(); i++) if (s <= ranges.get(i)[1] + 1e-9) return chain.get(i);
        return chain.get(chain.size() - 1);
    }

    private static double depthAt(List<Vertex> verts, double s) {
        for (int i = 0; i + 1 < verts.size(); i++) {
            Vertex a = verts.get(i), b = verts.get(i + 1);
            if (s >= a.s - 1e-9 && s <= b.s + 1e-9) {
                if (b.s - a.s < 1e-9) return b.depth;
                double t = (s - a.s) / (b.s - a.s);
                return a.depth + (b.depth - a.depth) * t;
            }
        }
        return verts.get(verts.size() - 1).depth;
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    public DepthRules rules() { return rules; }
}
