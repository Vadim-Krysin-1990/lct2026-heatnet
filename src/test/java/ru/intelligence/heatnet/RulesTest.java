package ru.intelligence.heatnet;

import org.junit.jupiter.api.Test;
import ru.intelligence.heatnet.config.ReferenceRules;
import ru.intelligence.heatnet.config.RestrictionRules;
import ru.intelligence.heatnet.config.RulesService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Правила техприложения: подбор ДУ (табл. 4.1), стоимость камер (§8.2), штраф (§8.3), показатель S (§9), табл. 5.1. */
class RulesTest {
    private final RulesService rules = RulesService.standalone();

    @Test
    void diameterIsMinimalWithEnoughCapacity() {
        ReferenceRules r = rules.reference();
        assertEquals(50, r.diameterForFlow(3.5).dn);
        assertEquals(65, r.diameterForFlow(3.6).dn);
        assertEquals(100, r.diameterForFlow(20.63).dn);
        assertEquals(125, r.diameterForFlow(29.42).dn);
        assertEquals(200, r.diameterForFlow(100).dn);     // пример из Q&A: 100 т/ч → ДУ 200
        assertEquals(250, r.diameterForFlow(200).dn);
        assertEquals(300, r.diameterForFlow(437.4).dn);
        assertEquals(400, r.diameterForFlow(437.5).dn);
        assertEquals(250, r.nextStep(200).dn);
        assertNull(r.nextStep(1400));
    }

    @Test
    void chamberCostScale() {
        ReferenceRules r = rules.reference();
        assertEquals(3_000_000, r.chamberCost(50));
        assertEquals(3_000_000, r.chamberCost(200));
        assertEquals(5_000_000, r.chamberCost(250));
        assertEquals(5_000_000, r.chamberCost(500));
        assertEquals(8_000_000, r.chamberCost(600));
        assertEquals(12_000_000, r.chamberCost(1400));
        assertEquals(5_000_000, r.tieIn.cost);
    }

    @Test
    void penaltyAndScoreMatchAppendixExample() {
        ReferenceRules r = rules.reference();
        assertEquals(100_000_000 + 500_000 * 24.87, r.penalty(24.87), 1e-6);
        // пример ТП §10.8: calculated_cost 51 094 590, length 220.2 → score 2.091
        assertEquals(2.091, r.score(51_094_590, 220.2), 0.0005);
        // проверка весов: 0.7 стоимость, 0.3 длина (ТП §9)
        assertEquals(0.7, r.ranking.costWeight);
        assertEquals(0.3, r.ranking.lengthWeight);
    }

    @Test
    void restrictionRulesFromTable51() {
        RestrictionRules rr = rules.restrictions();
        RestrictionRules.Rule oks = rr.resolve("oks");
        assertFalse(oks.isSpecial());
        assertEquals(5.0, oks.clearance(400, 1.0));
        assertEquals(7.0, oks.clearance(500, 1.0));
        assertEquals(7.0, oks.clearance(800, 1.0));
        assertEquals(9.0, oks.clearance(900, 1.0));
        assertEquals(oks.clearanceByDn, rr.resolve("oks_future").clearanceByDn);
        // ТП от 21.09.2026, табл. 2: железная дорога — непроходимое ограничение с отступом 1 м
        assertFalse(rr.resolve("railway").isSpecial());
        assertEquals(1.0, rr.resolve("railway").clearance(200, 1.0));
        RestrictionRules.Rule road = rr.resolve("road");
        assertTrue(road.isSpecial());
        assertEquals(1.60, road.kSpecial);
        assertEquals(3.0, road.zoneMarginM);
        assertEquals(45.0, road.minCrossingAngleDeg);
        assertEquals(1.75, rr.resolve("tram_tracks").kSpecial);
        assertEquals(1.25, rr.resolve("gas_pipeline").kSpecial);
        assertTrue(rr.resolve("gas_pipeline").isLine());
        assertEquals(1.15, rr.resolve("power_cable").kSpecial);
        assertEquals(1.05, rr.resolve("heat_network").kSpecial);
        assertFalse(rr.resolve("water").isSpecial());
        // неизвестный тип → правило по умолчанию (запрет)
        assertFalse(rr.resolve("metro").isSpecial());
        assertFalse(rr.isKnown("metro"));
    }

    @Test
    void gaugesFromTable1() {
        ReferenceRules r = rules.reference();
        assertEquals(0.880, r.spec(200).widthM, 1e-9);
        assertEquals(0.315, r.spec(200).heightM, 1e-9);
        assertEquals(1042, r.spec(200).maxLengthM, 1e-9);
        assertEquals(120_275, r.spec(200).newCostPerM, 1e-9);
    }
}
