package ru.intelligence.heatnet.planner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.intelligence.heatnet.config.ReferenceRules;
import ru.intelligence.heatnet.config.RulesService;
import ru.intelligence.heatnet.hydraulics.HydraulicsCalculator;
import ru.intelligence.heatnet.model.Diagnostics;
import ru.intelligence.heatnet.model.InputModel;
import ru.intelligence.heatnet.model.Variant;
import ru.intelligence.heatnet.network.NetworkTopology;
import ru.intelligence.heatnet.routing.ObstacleField;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Формирует до N содержательно разных вариантов (ТЗ 2.8): разные стратегии присоединения,
 * порядок и исключение врезок; отбраковывает дубликаты; ранжирует по S (ТП §9).
 */
public class VariantPlanner {
    private static final Logger log = LoggerFactory.getLogger(VariantPlanner.class);

    private final RulesService rules;

    public VariantPlanner(RulesService rules) {
        this.rules = rules;
    }

    public static class Outcome {
        public List<Variant> variants = new ArrayList<>();
        public NetworkTopology topology;
        public long millis;
    }

    public Outcome plan(InputModel model) {
        long t0 = System.currentTimeMillis();
        Diagnostics diag = model.diagnostics;
        ReferenceRules ref = rules.reference();
        NetworkTopology topo = new NetworkTopology(model, rules.routing().snapToleranceM);
        // отступ от ОКС — по верхней оценке ДУ новой сети (ДУ для суммарного расхода всех точек)
        int clearanceDn = ref.diameterForFlow(model.totalNewFlow()).dn;
        ObstacleField field = new ObstacleField(model, rules.restrictions(), ref, rules.routing(), clearanceDn);
        diag.stats.put("clearance_dn", clearanceDn);

        List<RoutePlanner.Strategy> strategies = strategies(model, topo);
        List<Variant> all = new ArrayList<>();
        int idx = 0;
        for (RoutePlanner.Strategy st : strategies) {
            idx++;
            long ts = System.currentTimeMillis();
            Variant v = new Variant();
            v.variantId = String.valueOf(idx);
            v.strategy = st.name;
            v.description = st.description;
            NetworkBuilder nb = new NetworkBuilder();
            RoutePlanner planner = new RoutePlanner(rules, model, topo, field);
            List<RoutePlanner.PointResult> res = planner.plan(st, nb, diag);
            int expanded = 0;
            for (RoutePlanner.PointResult r : res) {
                expanded += r.expanded;
                if (!r.connected) v.unconnectedOksIds.add(r.cp.id);
            }
            new HydraulicsCalculator(ref, topo, model).compute(nb, v, diag);
            // Q&A: неподключение допустимо, если трасса дороже штрафа — проверяем пост-фактум по ветвям-листьям
            v.computeMillis = System.currentTimeMillis() - ts;
            diag.info("VARIANT", "Вариант " + v.variantId + " (" + st.name + "): стоимость " + Math.round(v.calculatedCost) + " ₽, длина " + Math.round(v.length) + " м, S=" + String.format("%.3f", v.score) + ", раскрыто клеток " + expanded + ", " + v.computeMillis + " мс", null);
            all.add(v);
        }
        // отбраковка неотличимых вариантов: одинаковые множества врезок и близкая стоимость/длина
        List<Variant> distinct = new ArrayList<>();
        for (Variant v : all) {
            boolean dup = false;
            for (Variant d : distinct) if (similar(v, d)) { dup = true; break; }
            if (dup) { diag.info("VARIANT_DUPLICATE", "Вариант " + v.variantId + " (" + v.strategy + ") не отличается содержательно от уже включённого — отброшен", null); continue; }
            distinct.add(v);
        }
        distinct.sort(Comparator.comparingDouble(v -> v.score));
        int max = rules.routing().maxVariants;
        Outcome out = new Outcome();
        out.topology = topo;
        int rank = 0;
        for (Variant v : distinct) {
            if (rank >= max) break;
            v.rank = ++rank;
            v.variantId = String.valueOf(rank);
            renumber(v);
            out.variants.add(v);
        }
        out.millis = System.currentTimeMillis() - t0;
        diag.stats.put("compute_millis", out.millis);
        return out;
    }

    private List<RoutePlanner.Strategy> strategies(InputModel model, NetworkTopology topo) {
        List<RoutePlanner.Strategy> list = new ArrayList<>();
        for (String name : rules.routing().strategies) {
            RoutePlanner.Strategy s = new RoutePlanner.Strategy();
            s.name = name;
            switch (name) {
                case "shared_tree":
                    s.attachToNewNetwork = true; s.order = "distance";
                    s.description = "Общая сеть: ближние к существующей сети точки образуют ствол, остальные присоединяются к новой сети через камеры";
                    break;
                case "independent":
                    s.attachToNewNetwork = false; s.order = "distance";
                    s.description = "Раздельное подключение: каждая точка своей врезкой в существующую сеть";
                    break;
                case "alt_tie_in":
                    s.attachToNewNetwork = true; s.order = "flow_desc";
                    s.description = "Общая сеть от крупных потребителей: ствол строится от точек с наибольшим расходом, врезки — альтернативные";
                    break;
                case "shared_tree_far_first":
                    s.attachToNewNetwork = true; s.order = "distance_desc";
                    s.description = "Общая сеть: ствол от самых удалённых точек";
                    break;
                default:
                    s.attachToNewNetwork = true; s.order = "distance"; s.description = name;
            }
            list.add(s);
        }
        return list;
    }

    private static boolean similar(Variant a, Variant b) {
        Set<String> ta = new HashSet<>(), tb = new HashSet<>();
        for (Variant.TieIn t : a.tieIns) ta.add(t.existingObjectId + "@" + Math.round(t.geom.getX() / 5) + "," + Math.round(t.geom.getY() / 5));
        for (Variant.TieIn t : b.tieIns) tb.add(t.existingObjectId + "@" + Math.round(t.geom.getX() / 5) + "," + Math.round(t.geom.getY() / 5));
        if (!ta.equals(tb)) return false;
        if (a.chambers.size() != b.chambers.size()) return false;
        double dc = Math.abs(a.calculatedCost - b.calculatedCost) / Math.max(1, Math.max(a.calculatedCost, b.calculatedCost));
        double dl = Math.abs(a.length - b.length) / Math.max(1, Math.max(a.length, b.length));
        return dc < 0.01 && dl < 0.01;
    }

    /** Переименовать id объектов с префиксом варианта, чтобы id были уникальны во всём файле. */
    private static void renumber(Variant v) {
        String p = "v" + v.variantId + "_";
        java.util.Map<String, String> map = new java.util.HashMap<>();
        for (Variant.TieIn t : v.tieIns) { String n = p + t.id; map.put(t.id, n); t.id = n; }
        for (Variant.NewChamber c : v.chambers) { String n = p + c.id; map.put(c.id, n); c.id = n; }
        for (Variant.TechNode t : v.techNodes) { String n = p + t.id; map.put(t.id, n); t.id = n; }
        for (Variant.NewSegment s : v.segments) {
            s.id = p + s.id;
            s.startNodeId = map.getOrDefault(s.startNodeId, s.startNodeId);
            s.endNodeId = map.getOrDefault(s.endNodeId, s.endNodeId);
        }
        for (Variant.SegmentReconstruction r : v.reconstructions) r.id = p + r.id;
        for (Variant.ChamberReconstruction r : v.chamberReconstructions) r.id = p + r.id;
    }
}
