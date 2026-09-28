package ru.intelligence.heatnet.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Справочник техприложения (rules/reference.yml). Имена полей — snake_case в YAML. */
public class ReferenceRules {

    public Resource resource = new Resource();

    /**
     * Тип моделируемого ресурса. Задаётся справочником, а не кодом: алгоритм трассировки одинаков
     * для теплосети, водопровода и кабельных линий, меняются только таблицы и правила.
     * Пример профиля — `rules-profiles/water`.
     */
    public static class Resource {
        public String code = "heat_network";
        public String title = "Тепловая сеть";
        public String flowUnit = "т/ч";
        public String source;
    }

    public static class Crs {
        public String input = "EPSG:4326";
        public String calcProj4 = "+proj=utm +zone=37 +datum=WGS84 +units=m +no_defs";
    }

    public static class DiameterSpec {
        public int dn;
        public double capacityTph;
        public double maxLengthM;
        public double newCostPerM;
        public double shellOdM;
        public double gapM;
        public double widthM;
        public double heightM;
    }

    public static class LengthLimit {
        public int allowUpsizeSteps = 1;
    }

    public static class CostByDn {
        public int maxDn;
        public double cost;
    }

    public static class Chambers {
        public int maxBranches = 4;
        public double existingChamberTieInRadiusM = 10;
        public List<CostByDn> costByMaxDn = new ArrayList<>();
    }

    public static class TieIn {
        public double cost = 5_000_000;
    }

    public static class UnconnectedPenalty {
        public double fixed = 100_000_000;
        public double perTph = 500_000;
    }

    public static class Ranking {
        public double costWeight = 0.7;
        public double costBase = 25_000_000;
        public double lengthWeight = 0.3;
        public double lengthBase = 100;
    }

    public static class Depth {
        public double normalTopDepthM = 3.0;
        public double minTopDepthM = 0.7;
        public double maxSlope = 0.10;
        public double plateauHalfLengthM = 2.0;
        public double extraCostPerMDepth = 0.10;
        public double freeDepthM = 3.0;
        public double stepM = 0.5;
        public double maxTopDepthM = 10.0;
    }

    public static class Utility {
        public Double widthM;
        public Double heightM;
        public double topDepthM;
    }

    public Crs crs = new Crs();
    public List<DiameterSpec> diameters = new ArrayList<>();
    public LengthLimit lengthLimit = new LengthLimit();
    public Chambers chambers = new Chambers();
    public TieIn tieIn = new TieIn();
    public UnconnectedPenalty unconnectedPenalty = new UnconnectedPenalty();
    public Ranking ranking = new Ranking();
    public Depth depth = new Depth();
    public Map<String, Utility> existingUtilities = Map.of();
    public double nonstandardAngleCostFactor = 1.5;
    public Map<String, Double> layingCostFactors = Map.of();

    // ---- удобные методы ----

    /** Минимальный ДУ с пропускной способностью не меньше расхода (ТП §3). */
    public DiameterSpec diameterForFlow(double flowTph) {
        for (DiameterSpec d : diameters) {
            if (d.capacityTph + 1e-9 >= flowTph) return d;
        }
        return diameters.get(diameters.size() - 1);
    }

    public DiameterSpec spec(int dn) {
        for (DiameterSpec d : diameters) if (d.dn == dn) return d;
        // неизвестный ДУ существующей сети — ближайший больший
        for (DiameterSpec d : diameters) if (d.dn >= dn) return d;
        return diameters.get(diameters.size() - 1);
    }

    /** Следующая ступень номенклатуры; null, если крупнее нет. */
    public DiameterSpec nextStep(int dn) {
        for (int i = 0; i < diameters.size(); i++) {
            if (diameters.get(i).dn == dn) return i + 1 < diameters.size() ? diameters.get(i + 1) : null;
        }
        return null;
    }

    /** Стоимость камеры (новой или реконструкции) по наибольшему ДУ примыкающих участков (ТП §8.2). */
    public double chamberCost(int maxDn) {
        for (CostByDn c : chambers.costByMaxDn) if (maxDn <= c.maxDn) return c.cost;
        return chambers.costByMaxDn.isEmpty() ? 0 : chambers.costByMaxDn.get(chambers.costByMaxDn.size() - 1).cost;
    }

    public double penalty(double flowTph) {
        return unconnectedPenalty.fixed + unconnectedPenalty.perTph * flowTph;
    }

    /** S = w_c·(C / C0) + w_l·(L / L0) (ТП §9). */
    public double score(double cost, double length) {
        return ranking.costWeight * (cost / ranking.costBase) + ranking.lengthWeight * (length / ranking.lengthBase);
    }
}
