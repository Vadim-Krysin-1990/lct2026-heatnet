package ru.intelligence.heatnet.config;

import java.util.List;

/** Параметры алгоритма трассировки (rules/routing.yml). */
public class RoutingRules {
    public double gridStepM = 2.0;
    public double turnPenaltyM = 12.0;
    /** Штраф за поворот для инженерных (ортогональных) вариантов — длинные прямые участки. */
    public double orthogonalTurnPenaltyM = 14.0;
    /** Во сколько раз поворот на 45° дороже прямого угла для инженерных вариантов. */
    public double sharpTurnFactor = 10.0;
    /** Азимут сетки: null — определяется по преобладающему направлению существующей сети. */
    public Double gridBearingDeg = null;
    public double windowMarginM = 80.0;
    public double candidateRadiusM = 700.0;
    public double candidateRadiusMaxM = 5000.0;
    public int maxCandidates = 6;
    public int maxExitCandidates = 8;
    public double lineZoneMinAngleDeg = 45.0;
    public double snapToleranceM = 0.5;
    public double altTieInExclusionRadiusM = 30.0;
    public int maxVariants = 3;
    /** Сколько инженерных (ортогональных) вариантов обязательно включать в выдачу. */
    public int orthogonalVariantsInOutput = 3;
    public List<String> strategies = List.of("orthogonal_city", "orthogonal_alt_tie_in", "orthogonal_shared", "shared_tree", "separate_parts");
}
