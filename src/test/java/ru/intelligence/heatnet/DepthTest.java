package ru.intelligence.heatnet;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.intelligence.heatnet.config.RulesService;
import ru.intelligence.heatnet.depth.DepthRules;
import ru.intelligence.heatnet.export.GeoJsonWriter;
import ru.intelligence.heatnet.ingest.InputLoader;
import ru.intelligence.heatnet.model.Diagnostics;
import ru.intelligence.heatnet.model.InputModel;
import ru.intelligence.heatnet.model.Variant;
import ru.intelligence.heatnet.planner.VariantPlanner;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Дополнительная задача: синтетический случай — точка над сетью, между ними газопровод, кабель и дорога. */
class DepthTest {
    private final RulesService rules = RulesService.standalone();

    @Test
    void crossingDecisionsFollowAppendix() {
        DepthRules d = new DepthRules(rules.reference(), rules.restrictions());
        // газопровод: верх 2,8, габарит 0,4, просвет 0,2; новая ДУ125 (h=0,225): сверху h = 2,8−0,2−0,225 = 2,375 ≥ 0,7 → дешевле, чем снизу (3,4)
        DepthRules.Crossing g = d.decide("gas_pipeline", null, 125, 97275);
        assertEquals("above", g.position);
        assertEquals(2.375, g.depth, 1e-6);
        assertEquals(6.25, g.rampLength, 1e-6);
        // кабель: верх 2,7, просвет 0,5 → сверху 1,975
        DepthRules.Crossing k = d.decide("power_cable", null, 125, 97275);
        assertEquals("above", k.position);
        assertEquals(1.975, k.depth, 1e-6);
        // существующая теплосеть ДУ500 (высота 0,71): сверху 3,0−0,5−0,225 = 2,275; снизу 3,0+0,71+0,5 = 4,21 (Kгл 1,121)
        DepthRules.Crossing h = d.decide("heat_network", 500, 125, 97275);
        assertEquals("above", h.position);
        assertEquals(2.275, h.depth, 1e-6);
        assertEquals(1.121, d.kDepth(4.21), 1e-9);
        assertEquals(1.0, d.kDepth(2.0), 1e-9);
        // крупная новая труба ДУ1400 (высота 1,6) над кабелем не проходит (2,7−0,5−1,6 = 0,6 < 0,7) → снизу 2,7+0,2+0,5 = 3,4
        DepthRules.Crossing big = d.decide("power_cable", null, 1400, 683417);
        assertEquals("below", big.position);
        assertEquals(3.4, big.depth, 1e-6);
    }

    @Test
    void depthModeBuildsProfile() throws Exception {
        InputLoader loader = new InputLoader(rules);
        InputModel m;
        try (InputStream in = getClass().getResourceAsStream("/depth_case.geojson")) { m = loader.load(in); }
        assertFalse(m.diagnostics.hasErrors(), m.diagnostics.messages.toString());

        VariantPlanner.Outcome flat = new VariantPlanner(rules).plan(m, false);
        Variant f = flat.variants.get(0);
        assertTrue(f.unconnectedOksIds.isEmpty());
        for (Variant.NewSegment s : f.segments) { assertNull(s.depthStart); assertNull(s.depthEnd); }

        VariantPlanner.Outcome deep = new VariantPlanner(rules).plan(m, true);
        for (Diagnostics.Message msg : m.diagnostics.messages) if (msg.code.startsWith("DEPTH") || msg.code.equals("VARIANT")) System.out.println(msg.level + " " + msg.code + ": " + msg.text);
        Variant v = deep.variants.get(0);
        assertTrue(v.unconnectedOksIds.isEmpty());
        boolean shallow = false;
        double minDepth = 99, maxDepth = 0;
        for (Variant.NewSegment s : v.segments) {
            assertNotNull(s.depthStart, s.id); assertNotNull(s.depthEnd, s.id);
            minDepth = Math.min(minDepth, Math.min(s.depthStart, s.depthEnd));
            maxDepth = Math.max(maxDepth, Math.max(s.depthStart, s.depthEnd));
            if (s.depthStart < 2.99 || s.depthEnd < 2.99) shallow = true;

            System.out.printf("   %s %s→%s dn=%d L=%.2f %s depth %.2f→%.2f Kгл=%.3f cost=%.0f%n", s.id, s.startNodeId, s.endNodeId, s.diameter, s.length, s.layingMethod, s.depthStart, s.depthEnd, s.kDepth, s.cost);
        }
        assertTrue(shallow, "должны быть участки над кабелем/газом мельче 3 м");
        // ТП от 21.09.2026, §5: Z в геометрии не требуется, глубина задаётся атрибутами
        assertEquals(1.975, minDepth, 0.01);
        assertEquals(3.0, maxDepth, 0.01);
        assertTrue(v.techNodes.size() >= 4, "техузлы в вершинах профиля: " + v.techNodes.size());
        assertTrue(v.segments.size() > f.segments.size());
        // сводка пересчитана
        double sum = 0; for (Variant.NewSegment s : v.segments) sum += s.cost;
        assertEquals(sum + v.chamberConstructionCost + v.existingChamberTieInCost, v.constructionCost, 1.0);
        assertEquals(v.constructionCost + v.unconnectedPenalty, v.calculatedCost, 1.0);
        Files.createDirectories(Paths.get("target"));
        try (FileOutputStream fos = new FileOutputStream("target/depth_case_result.geojson")) { new GeoJsonWriter(loader.crs()).write(deep.variants, fos); }
    }
}
