package ru.intelligence.heatnet;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import ru.intelligence.heatnet.config.RulesService;
import ru.intelligence.heatnet.ingest.InputLoader;
import ru.intelligence.heatnet.model.InputModel;
import ru.intelligence.heatnet.model.Variant;
import ru.intelligence.heatnet.planner.VariantPlanner;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Перебор параметров трассировки в одном запуске: ищем, где показатель S становится лучше,
 * не нарушая правил. Не часть проверки решения — запускается вручную:
 * mvn -o test -Dtest=ExperimentTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false
 */

@Disabled("ручной перебор параметров, а не проверка решения: запускается командой mvn -o test -Dtest=ExperimentTest")
class ExperimentTest {

    private static class Case {
        final String title;
        final Consumer<RulesService> tune;
        Case(String title, Consumer<RulesService> tune) { this.title = title; this.tune = tune; }
    }

    @Test
    void sweep() throws Exception {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("базовый", r -> { }));
        for (double f : new double[]{1.3, 1.6, 2.0, 2.5}) {
            cases.add(new Case("ветвление на участке ×" + f, r -> r.routing().branchOnSegmentCostFactor = f));
        }
        cases.add(new Case("×1,6 + слияние 90 м", r -> {
            r.routing().branchOnSegmentCostFactor = 1.6;
            r.routing().chamberMergeRadiusM = 90;
        }));
        for (double mln : new double[]{5, 12, 25}) {
            cases.add(new Case("надбавка за врезку " + (int) mln + " млн",
                    r -> r.routing().tieInPenaltyRub = mln * 1e6));
        }
        cases.add(new Case("шаг сетки 1,5 м", r -> r.routing().gridStepM = 1.5));
        cases.add(new Case("штраф за поворот 1 м", r -> r.routing().freeAngleTurnPenaltyM = 1.0));
        cases.add(new Case("штраф за поворот 4 м", r -> r.routing().freeAngleTurnPenaltyM = 4.0));
        cases.add(new Case("радиус кандидатов 1200 м", r -> r.routing().candidateRadiusM = 1200));

        System.out.println("\n================ перебор параметров ================");
        for (Case c : cases) {
            RulesService rules = RulesService.standalone();
            c.tune.accept(rules);
            InputModel m;
            try (InputStream in = getClass().getResourceAsStream("/contest_dataset.geojson")) {
                m = new InputLoader(rules).load(in);
            }
            long t0 = System.currentTimeMillis();
            VariantPlanner.Outcome out = new VariantPlanner(rules).plan(m, false, 0, "grid");
            long ms = System.currentTimeMillis() - t0;
            Variant best = null;
            for (Variant v : out.variants) if (best == null || v.score < best.score) best = v;
            int violations = 0;
            for (Variant v : out.variants) violations += v.ruleViolations;
            System.out.printf("%-34s S=%.4f  L=%.0f м  C=%.1f млн  камер=%d  нарушений=%d  %d с  (%s)%n",
                    c.title, best.score, best.newNetworkLength, best.calculatedCost / 1e6,
                    best.chambers.size(), violations, ms / 1000, best.strategy);
        }
        System.out.println("====================================================\n");
    }
}
