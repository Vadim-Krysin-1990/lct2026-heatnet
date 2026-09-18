package ru.intelligence.heatnet.config;

import java.util.List;

/** Параметры алгоритма трассировки (rules/routing.yml). */
public class RoutingRules {
    public double gridStepM = 2.0;
    public double turnPenaltyM = 12.0;
    public double windowMarginM = 80.0;
    public double candidateRadiusM = 700.0;
    public double candidateRadiusMaxM = 5000.0;
    public int maxCandidates = 6;
    public double lineZoneMinAngleDeg = 45.0;
    public double snapToleranceM = 0.5;
    public int maxVariants = 3;
    public List<String> strategies = List.of("shared_tree", "independent", "alt_tie_in");
}
