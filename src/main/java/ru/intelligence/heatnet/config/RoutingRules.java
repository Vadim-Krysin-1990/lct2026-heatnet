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
    /**
     * Запас по ДУ при построении запретных зон: отступ от препятствия зависит от диаметра, а он
     * известен только после гидравлики. Буфер строится по ДУ суммарного расхода, поднятому на
     * столько ступеней — иначе ствол, которому предельная длина подняла ДУ, окажется ближе нормы.
     */
    public int clearanceDnHeadroomSteps = 1;
    /** Вынос вершины графа видимости наружу от угла запретной зоны, м. */
    public double visibilityVertexOffsetM = 0.35;
    /** Предел числа вершин графа видимости: при большом окне берутся ближние к точке. */
    public int visibilityMaxVertices = 260;
    /** Предельный поворот трассы, град. (ТП от 21.09 §2.1: не круче 90°). */
    public double maxTurnDeg = 90.0;
    /** Штраф за поворот для спрямляемых вариантов: трасса ищется как кратчайшая, изломы снимает спрямление. */
    public double freeAngleTurnPenaltyM = 2.0;
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
    /** Сколько инженерных вариантов обязательно включать в выдачу (геометрия по практике проектирования). */
    public int engineeringVariantsInOutput = 2;
    /** Сколько минимальных по расчёту вариантов обязательно включать в выдачу (лучший показатель S). */
    public int shortestVariantsInOutput = 2;
    /** Устаревшее имя квоты инженерных вариантов; оставлено для совместимости конфигураций. */
    public int orthogonalVariantsInOutput = 1;
    /** Сколько вариантов со спрямлением произвольным углом обязательно включать в выдачу (ТП от 21.09 разрешает любой угол до 90°). */
    public int freeAngleVariantsInOutput = 2;
    /** Сколько вариантов, найденных по графу видимости, обязательно включать в выдачу. */
    public int visibilityVariantsInOutput = 0;
    /** Предельная длина «шпильки» — микроизлома с поворотом больше 90°, который снимается при сборке трассы. */
    public double despikeMaxM = 2.0;
    /**
     * Запас при срезке углов: целимся ниже предела 90°, потому что после склейки коллинеарных
     * звеньев, спрямления и деления участков угол может подрасти на доли градуса.
     */
    public double turnMarginDeg = 0.0;
    /**
     * Порог шпильки для путей графа видимости: его рёбра длиннее растровых, и разворот на стыке
     * коридора выхода с первым ребром графа не укладывается в обычные 2 м.
     */
    public double visibilitySpikeMaxM = 10.0;
    /** Радиус, в пределах которого две тепловые камеры считаются одним узлом и сводятся в одну. */
    public double chamberMergeRadiusM = 60.0;
    /**
     * Во сколько раз ветвление посреди участка дороже оценки по расходу подключаемой точки.
     * Камера считается по наибольшему примыкающему диаметру, а он известен только после гидравлики.
     */
    public double branchOnSegmentCostFactor = 1.0;
    /**
     * Надбавка к стоимости присоединения к существующей сети, руб. Ведёт ветви к общему стволу
     * вместо отдельной врезки у каждой точки: каждая врезка — это ещё одна камера на трубопроводе.
     */
    public double tieInPenaltyRub = 0;

    public List<String> strategies = List.of("orthogonal_city", "orthogonal_alt_tie_in", "orthogonal_shared", "free_angle_shared", "free_angle_separate",
            "visibility_shared", "visibility_separate", "shared_tree", "separate_parts");
}
