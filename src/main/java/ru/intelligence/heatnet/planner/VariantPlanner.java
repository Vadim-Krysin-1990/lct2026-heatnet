package ru.intelligence.heatnet.planner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.intelligence.heatnet.config.ReferenceRules;
import ru.intelligence.heatnet.config.RulesService;
import ru.intelligence.heatnet.geo.GeoUtil;
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

    public Outcome plan(InputModel model) { return plan(model, false, 0); }

    public Outcome plan(InputModel model, boolean depthMode) { return plan(model, depthMode, 0); }

    /**
     * @param depthMode дополнительная задача: трассировка с учётом глубины
     * @param maxVariantsOverride сколько вариантов включить в выдачу; 0 — из правил (ТП §2.8: до трёх).
     *        Значения больше трёх используются для демонстрации и сравнения стратегий.
     */
    public Outcome plan(InputModel model, boolean depthMode, int maxVariantsOverride) {
        long t0 = System.currentTimeMillis();
        Diagnostics diag = model.diagnostics;
        ReferenceRules ref = rules.reference();
        NetworkTopology topo = new NetworkTopology(model, rules.routing().snapToleranceM);
        // отступ от ОКС — по верхней оценке ДУ новой сети (ДУ для суммарного расхода всех точек)
        int clearanceDn = ref.diameterForFlow(model.totalNewFlow()).dn;
        // отступ нормируется по фактическому ДУ участка, а он может оказаться выше расчётного по расходу
        // (предельная длина поднимает ДУ на ступень), поэтому зоны строятся с запасом
        for (int i = 0; i < rules.routing().clearanceDnHeadroomSteps; i++) {
            ReferenceRules.DiameterSpec next = ref.nextStep(clearanceDn);
            if (next == null) break;
            clearanceDn = next.dn;
        }
        ObstacleField field = new ObstacleField(model, rules.restrictions(), ref, rules.routing(), clearanceDn);
        diag.stats.put("clearance_dn", clearanceDn);
        diag.stats.put("resource", ref.resource.code);
        if (!"heat_network".equals(ref.resource.code)) {
            diag.info("RESOURCE", "Расчёт выполняется по справочнику ресурса «" + ref.resource.title
                    + "» (" + ref.resource.code + "): расходы в " + ref.resource.flowUnit
                    + ". Алгоритм трассировки от типа ресурса не зависит", null);
        }
        double gridBearing = rules.routing().gridBearingDeg != null ? rules.routing().gridBearingDeg : dominantBearing(model);
        diag.info("GRID_BEARING", "Азимут сетки трассировки: " + GeoUtil.round(gridBearing, 1) + "° — преобладающее направление существующей сети и застройки; инженерные варианты строятся вдоль него с поворотами 90°", null);
        diag.stats.put("grid_bearing_deg", GeoUtil.round(gridBearing, 1));

        List<Variant> all = new ArrayList<>();
        int idx = 0;
        Variant first = null;
        for (String name : rules.routing().strategies) {
            RoutePlanner.Strategy st = strategy(name, first, topo);
            if (st == null) continue;
            st.depthMode = depthMode;
            if (st.gridBearingDeg < 0) st.gridBearingDeg = gridBearing;
            idx++;
            long ts = System.currentTimeMillis();
            Variant v = new Variant();
            v.variantId = String.valueOf(idx);
            v.strategy = st.name;
            v.name = st.title != null ? st.title : st.name;
            v.description = st.description;
            NetworkBuilder nb = new NetworkBuilder();
            RoutePlanner planner = new RoutePlanner(rules, model, topo, field);
            List<RoutePlanner.PointResult> res = planner.plan(st, nb, diag);
            int expanded = 0;
            for (RoutePlanner.PointResult r : res) {
                expanded += r.expanded;
                if (!r.connected) v.unconnectedOksIds.add(r.cp.id);
            }
            HydraulicsCalculator hc = new HydraulicsCalculator(ref, topo, model);
            hc.compute(nb, v, diag);
            if (depthMode) new ru.intelligence.heatnet.depth.DepthProfiler(ref, rules.restrictions(), model).apply(v, diag, hc);
            v.computeMillis = System.currentTimeMillis() - ts;
            int connected = res.size() - v.unconnectedOksIds.size();
            diag.info("TECH_FEASIBILITY", "Вариант " + v.variantId + ": техническая возможность подключения по технологическим коридорам (наличие трассы с соблюдением ограничений) подтверждена для " + connected + " из " + res.size() + " точек присоединения" + (v.unconnectedOksIds.isEmpty() ? "" : "; требуют ручной проработки: " + v.unconnectedOksIds), null);
            new ClearanceValidator(model, ref, rules.restrictions(), field).check(v, diag);
            double[] q = quality(v, model);
            v.turnsPerKm = q[0]; v.medianStraightM = q[1]; v.sharpTurns = (int) q[2];
            diag.info("TRACE_QUALITY", "Вариант " + v.variantId + " (" + st.name + "): " + GeoUtil.round(q[0], 1)
                    + " поворотов на км, медиана прямого участка " + GeoUtil.round(q[1], 1) + " м, прямых углов " + (int) q[3]
                    + ", косых изломов на трассе " + (int) q[2] + " (сверх них " + (int) q[4] + " поворотов на выходе из зданий по нормали к стене — требование заказчика)", null);
            diag.info("VARIANT", "Вариант " + v.variantId + " (" + st.name + "): стоимость " + Math.round(v.calculatedCost) + " ₽, длина " + Math.round(v.newNetworkLength) + " м, S=" + String.format("%.3f", v.score) + ", раскрыто клеток " + expanded + ", " + v.computeMillis + " мс", null);
            all.add(v);
            if (first == null) first = v;
        }
        // отбраковка неотличимых вариантов: одинаковые множества врезок и близкая стоимость/длина
        List<Variant> distinct = new ArrayList<>();
        for (Variant v : all) {
            boolean dup = false;
            for (Variant d : distinct) if (similar(v, d)) { dup = true; break; }
            if (dup) { diag.info("VARIANT_DUPLICATE", "Вариант " + v.variantId + " (" + v.strategy + ") не отличается содержательно от уже включённого — отброшен", null); continue; }
            distinct.add(v);
        }
        // отбор: ТП §2.8 разрешает предложить до трёх вариантов, какие именно — решает сервис.
        // В выдачу обязательно попадают инженерные (ортогональные) трассировки: у них качество геометрии
        // ближе к практике проектирования — длинные прямые участки и повороты 90°. Остальные места
        // занимают лучшие по показателю S. Порядок (rank) назначается строго по S, как требует ТП §9.
        int max = maxVariantsOverride > 0 ? maxVariantsOverride : rules.routing().maxVariants;
        if (max > rules.routing().maxVariants)
            diag.info("VARIANTS_EXTENDED", "Запрошено вариантов: " + max + ". Техническое приложение (§2.8) допускает до "
                    + rules.routing().maxVariants + " содержательно разных вариантов — в конкурсную выдачу идут первые "
                    + rules.routing().maxVariants + " по рангу, остальные показаны для сравнения стратегий", null);
        List<Variant> selected = new ArrayList<>();
        for (Variant v : distinct) {
            if (selected.size() >= Math.min(max, rules.routing().orthogonalVariantsInOutput)) break;
            if (v.strategy != null && v.strategy.startsWith("orthogonal") && v.unconnectedOksIds.isEmpty()) selected.add(v);
        }
        // квота на спрямлённые варианты: короче и дешевле, геометрия дальше от инженерной практики
        int freeQuota = Math.min(max - selected.size(), rules.routing().freeAngleVariantsInOutput);
        if (freeQuota > 0) {
            List<Variant> free = new ArrayList<>();
            for (Variant v : distinct) if (v.strategy != null && v.strategy.startsWith("free_angle") && v.unconnectedOksIds.isEmpty()) free.add(v);
            free.sort(Comparator.comparingDouble(v -> v.score));
            for (Variant v : free) { if (freeQuota-- <= 0) break; if (!selected.contains(v)) selected.add(v); }
        }
        List<Variant> rest = new ArrayList<>(distinct);
        rest.removeAll(selected);
        rest.sort(Comparator.comparingDouble(v -> v.score));
        for (Variant v : rest) {
            if (selected.size() >= max) break;
            selected.add(v);
        }
        for (Variant v : distinct) {
            if (!selected.contains(v)) diag.info("VARIANT_NOT_OFFERED", "Вариант " + v.variantId + " (" + v.strategy + ", S=" + GeoUtil.round(v.score, 3)
                    + ", " + GeoUtil.round(v.turnsPerKm, 1) + " поворотов на км, косых изломов на трассе " + v.sharpTurns
                    + ") рассчитан как контрольный и в выдачу не включён: геометрия с косыми изломами уступает инженерным вариантам", null);
        }
        selected.sort(Comparator.comparingDouble(v -> v.score));
        Outcome out = new Outcome();
        out.topology = topo;
        int rank = 0;
        for (Variant v : selected) {
            v.rank = ++rank;
            v.variantId = String.valueOf(rank);
            renumber(v);
            out.variants.add(v);
        }
        out.millis = System.currentTimeMillis() - t0;
        diag.stats.put("compute_millis", out.millis);
        diag.stats.put("depth_mode", depthMode);
        return out;
    }

    /** Стратегия по имени; alt_tie_in строится относительно первого варианта (другие точки врезки). */
    private RoutePlanner.Strategy strategy(String name, Variant first, NetworkTopology topo) {
        RoutePlanner.Strategy s = new RoutePlanner.Strategy();
        s.name = name;
        switch (name) {
            case "orthogonal_city": {
                s.title = "Инженерный: вдоль застройки, свои врезки";
                s.attachToNewNetwork = true; s.order = "distance";
                s.turnPenaltyM = rules.routing().orthogonalTurnPenaltyM;
                s.sharpTurnFactor = rules.routing().sharpTurnFactor;
                s.directions = 8;
                s.gridBearingDeg = -1;   // будет заменён азимутом застройки
                s.attachPenalty = ref().tieIn.cost + ref().chamberCost(0);
                s.description = "Инженерная трассировка вдоль застройки: сетка развёрнута по преобладающему направлению существующей сети, повороты прямые (самокомпенсация), косые изломы исключены; подключение ближних точек собственными врезками, чтобы не перегружать существующую сеть";
                break;
            }
            case "orthogonal_alt_tie_in": {
                s.title = "Инженерный: общий ствол, другие врезки";
                if (first == null) return null;
                s.attachToNewNetwork = true; s.order = "flow_desc";
                s.turnPenaltyM = rules.routing().orthogonalTurnPenaltyM;
                s.sharpTurnFactor = rules.routing().sharpTurnFactor;
                s.directions = 8;
                s.gridBearingDeg = -1;
                double r0 = rules.routing().altTieInExclusionRadiusM;
                java.util.Set<String> ex0 = new java.util.HashSet<>();
                for (Variant.TieIn t : first.tieIns) {
                    for (InputModel.ExistingChamber ch : topo.chambers()) if (ch.geom.distance(t.geom) <= r0) ex0.add(ch.id);
                    for (InputModel.ExistingSegment seg : topo.segments()) if (seg.geom.distance(t.geom) <= r0) ex0.add(seg.id);
                }
                s.excludedTieIns.addAll(ex0);
                s.description = "Инженерная трассировка с общим стволом: та же геометрия вдоль застройки, ствол строится от крупных потребителей, точки врезки отличаются от первого варианта";
                break;
            }
            case "orthogonal_shared": {
                s.title = "Инженерный: общая сеть, минимум врезок";
                s.attachToNewNetwork = true; s.order = "distance";
                s.turnPenaltyM = rules.routing().orthogonalTurnPenaltyM;
                s.sharpTurnFactor = rules.routing().sharpTurnFactor;
                s.directions = 8;
                s.gridBearingDeg = -1;
                s.description = "Инженерная трассировка общей сетью: геометрия вдоль застройки, точки объединяются в одно дерево с ветвлениями в камерах — меньше врезок в существующую сеть";
                break;
            }
            case "free_angle_shared": {
                s.title = "Кратчайший: спрямление, общая сеть";
                s.attachToNewNetwork = true; s.order = "distance";
                s.turnPenaltyM = rules.routing().freeAngleTurnPenaltyM;
                s.sharpTurnFactor = 1;
                s.directions = 8;
                s.gridBearingDeg = -1;
                s.freeAngle = true;
                s.description = "Кратчайшая трассировка со спрямлением: после поиска по сетке трасса спрямляется произвольным углом там, где прямая свободна (ТП от 21.09 §2.1 допускает любой поворот до 90°); точки объединяются в одно дерево";
                break;
            }
            case "free_angle_separate": {
                s.title = "Кратчайший: спрямление, свои врезки";
                s.attachToNewNetwork = true; s.order = "distance";
                s.turnPenaltyM = rules.routing().freeAngleTurnPenaltyM;
                s.sharpTurnFactor = 1;
                s.directions = 8;
                s.gridBearingDeg = -1;
                s.freeAngle = true;
                s.attachPenalty = ref().tieIn.cost + ref().chamberCost(0);
                s.description = "Кратчайшая трассировка со спрямлением, ближние к существующей сети точки получают собственные врезки";
                break;
            }
            case "shared_tree":
                s.title = "Контрольный: общая сеть по сетке";
                s.attachToNewNetwork = true; s.order = "distance";
                s.description = "Общая сеть: ближние к существующей сети точки образуют ствол, остальные присоединяются к новой сети через камеры";
                break;
            case "alt_tie_in": {
                s.title = "Контрольный: другие точки врезки";
                if (first == null) return null;
                s.attachToNewNetwork = true; s.order = "distance";
                double r = rules.routing().altTieInExclusionRadiusM;
                java.util.Set<String> excl = new java.util.HashSet<>();
                for (Variant.TieIn t : first.tieIns) {
                    for (InputModel.ExistingChamber ch : topo.chambers()) if (ch.geom.distance(t.geom) <= r) excl.add(ch.id);
                    for (InputModel.ExistingSegment seg : topo.segments()) if (seg.geom.distance(t.geom) <= r) excl.add(seg.id);
                }
                s.excludedTieIns.addAll(excl);
                s.description = "Другие точки врезки: объекты существующей сети в " + Math.round(r) + " м от врезок варианта 1 исключены, сеть построена заново";
                break;
            }
            case "separate_parts":
                s.title = "Контрольный: отдельные части сети";
                s.attachToNewNetwork = true; s.order = "distance";
                s.attachPenalty = ref().tieIn.cost + ref().chamberCost(0);
                s.description = "Отдельные части сети: присоединение к уже построенной новой сети штрафуется, точки вблизи существующей сети получают собственные врезки";
                break;
            case "flow_first":
                s.title = "Контрольный: ствол от крупных потребителей";
                s.attachToNewNetwork = true; s.order = "flow_desc";
                s.description = "Общая сеть от крупных потребителей: ствол строится от точек с наибольшим расходом";
                break;
            case "far_first":
                s.title = "Контрольный: ствол от дальних точек";
                s.attachToNewNetwork = true; s.order = "distance_desc";
                s.description = "Общая сеть: ствол от самых удалённых точек";
                break;
            case "independent":
                s.title = "Контрольный: раздельное подключение";
                s.attachToNewNetwork = false; s.order = "distance";
                s.description = "Раздельное подключение: каждая точка своей врезкой; пересекающиеся трассы объединяются";
                break;
            default:
                s.attachToNewNetwork = true; s.order = "distance"; s.description = name;
        }
        return s;
    }

    private ReferenceRules ref() { return rules.reference(); }

    /**
     * Преобладающее направление существующей сети (и вместе с ней застройки), градусы 0..90.
     * Круговое среднее по учетверённому углу: у ортогональной сетки направления b и b+90° равнозначны.
     */
    static double dominantBearing(InputModel model) {
        double sx = 0, sy = 0;
        for (InputModel.ExistingSegment s : model.segments) {
            org.locationtech.jts.geom.Coordinate[] cs = s.geom.getCoordinates();
            for (int i = 1; i < cs.length; i++) {
                double len = cs[i - 1].distance(cs[i]);
                if (len < 1e-6) continue;
                double b = Math.toRadians(GeoUtil.bearingDeg(cs[i - 1], cs[i]));
                sx += len * Math.cos(4 * b);
                sy += len * Math.sin(4 * b);
            }
        }
        if (sx == 0 && sy == 0) return 0;
        double a = Math.toDegrees(Math.atan2(sy, sx)) / 4.0;
        return ((a % 90) + 90) % 90;
    }

    /**
     * Метрики качества трассы: поворотов на км, медиана прямого участка, косых изломов на трассе,
     * прямых углов, поворотов на выходе из здания. Выход от точки присоединения идёт по нормали к стене
     * (требование заказчика), поэтому стык нормали с магистральным направлением дефектом не считается.
     */
    static double[] quality(Variant v, InputModel model) {
        java.util.List<Double> straights = new java.util.ArrayList<>();
        int turns = 0, sharp = 0, right = 0, exitTurns = 0;
        double total = 0;
        for (Variant.NewSegment s : v.segments) {
            org.locationtech.jts.geom.Coordinate[] cs = s.geom.getCoordinates();
            double run = 0;
            for (int i = 1; i < cs.length; i++) {
                double len = cs[i - 1].distance(cs[i]);
                total += len; run += len;
                if (i + 1 < cs.length) {
                    double d = GeoUtil.acuteAngleDeg(GeoUtil.bearingDeg(cs[i - 1], cs[i]), GeoUtil.bearingDeg(cs[i], cs[i + 1]));
                    if (d > 1) {
                        turns++;
                        boolean atExit = false;
                        for (InputModel.ConnectionPoint p : model.points) {
                            if (p.geom.getCoordinate().distance(cs[i]) <= 25) { atExit = true; break; }
                        }
                        if (d >= 70 && d <= 110) right++;
                        else if (d >= 15 && atExit) exitTurns++;
                        else if (d >= 15) sharp++;   // косой излом: внутренний угол 135° — самокомпенсации не даёт
                        straights.add(run); run = 0;
                    }
                }
            }
            if (run > 0) straights.add(run);
        }
        java.util.Collections.sort(straights);
        double median = straights.isEmpty() ? 0 : straights.get(straights.size() / 2);
        double perKm = total > 0 ? turns / (total / 1000.0) : 0;
        return new double[]{perKm, median, sharp, right, exitTurns};
    }

    private static boolean similar(Variant a, Variant b) {
        Set<String> ta = new HashSet<>(), tb = new HashSet<>();
        for (Variant.TieIn t : a.tieIns) ta.add(t.existingObjectId + "@" + Math.round(t.geom.getX() / 5) + "," + Math.round(t.geom.getY() / 5));
        for (Variant.TieIn t : b.tieIns) tb.add(t.existingObjectId + "@" + Math.round(t.geom.getX() / 5) + "," + Math.round(t.geom.getY() / 5));
        if (!ta.equals(tb)) return false;
        if (a.chambers.size() != b.chambers.size()) return false;
        double dc = Math.abs(a.calculatedCost - b.calculatedCost) / Math.max(1, Math.max(a.calculatedCost, b.calculatedCost));
        double dl = Math.abs(a.newNetworkLength - b.newNetworkLength) / Math.max(1, Math.max(a.newNetworkLength, b.newNetworkLength));
        return dc < 0.03 && dl < 0.03;
    }

    /** Переименовать id объектов с префиксом варианта, чтобы id были уникальны во всём файле. */
    private static void renumber(Variant v) {
        String p = "v" + v.variantId + "_";
        java.util.Map<String, String> map = new java.util.HashMap<>();
        for (Variant.NewChamber c : v.chambers) { String n = p + c.id; map.put(c.id, n); c.id = n; }
        for (Variant.TieIn t : v.tieIns) t.id = p + t.id;
        for (Variant.TechNode t : v.techNodes) { String n = p + t.id; map.put(t.id, n); t.id = n; }
        for (Variant.NewSegment s : v.segments) {
            s.id = p + s.id;
            s.startNodeId = map.getOrDefault(s.startNodeId, s.startNodeId);
            s.endNodeId = map.getOrDefault(s.endNodeId, s.endNodeId);
        }
    }
}
