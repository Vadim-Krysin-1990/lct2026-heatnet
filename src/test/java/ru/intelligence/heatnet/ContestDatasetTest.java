package ru.intelligence.heatnet;

import org.junit.jupiter.api.Test;
import ru.intelligence.heatnet.config.RulesService;
import ru.intelligence.heatnet.export.GeoJsonWriter;
import ru.intelligence.heatnet.ingest.InputLoader;
import ru.intelligence.heatnet.model.Diagnostics;
import ru.intelligence.heatnet.model.InputModel;
import ru.intelligence.heatnet.model.Variant;
import ru.intelligence.heatnet.planner.VariantPlanner;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Сквозной прогон конкурсного набора: загрузка → топология → варианты → выгрузка в target/contest_result.geojson. */
class ContestDatasetTest {

    @Test
    void loadsContestDataset() throws Exception {
        RulesService rules = RulesService.standalone();
        InputLoader loader = new InputLoader(rules);
        InputModel m;
        try (InputStream in = getClass().getResourceAsStream("/contest_dataset.geojson")) {
            assertNotNull(in, "нет тестового датасета");
            m = loader.load(in);
        }
        assertEquals(144, m.totalFeatures);
        assertEquals(1, m.sources.size());
        assertEquals(29, m.segments.size());
        assertEquals(9, m.chambers.size());
        assertEquals(17, m.points.size());
        assertEquals(88, m.restrictions.size());
        assertFalse(m.diagnostics.hasErrors(), "ошибки диагностики: " + m.diagnostics.messages);
        // координаты в UTM 37N: восточнее 400 км, севернее 6 170 км
        double x = m.sources.get(0).geom.getX(), y = m.sources.get(0).geom.getY();
        assertTrue(x > 400_000 && x < 500_000 && y > 6_170_000 && y < 6_180_000, "UTM " + x + "," + y);
    }

    @Test
    void plansVariantsAndWritesResult() throws Exception {
        RulesService rules = RulesService.standalone();
        InputLoader loader = new InputLoader(rules);
        InputModel m;
        try (InputStream in = getClass().getResourceAsStream("/contest_dataset.geojson")) {
            m = loader.load(in);
        }
        VariantPlanner planner = new VariantPlanner(rules);
        VariantPlanner.Outcome out = planner.plan(m);
        for (Diagnostics.Message msg : m.diagnostics.messages) System.out.println(msg.level + " " + msg.code + ": " + msg.text);
        System.out.println("stats: " + m.diagnostics.stats);
        assertFalse(out.variants.isEmpty(), "нет вариантов");
        for (Variant v : out.variants) {
            System.out.printf("Вариант %s (%s): rank %d, участков %d, камер %d, врезок в существующие камеры %d, техузлов %d, неподключено %s, C=%.0f, L=%.1f, S=%.3f, %d мс%n",
                    v.variantId, v.strategy, v.rank, v.segments.size(), v.chambers.size(), v.existingChamberTieInCount, v.techNodes.size(),
                    v.unconnectedOksIds, v.calculatedCost, v.newNetworkLength, v.score, v.computeMillis);
            for (String n : v.notes) System.out.println("   note: " + n);
        }
        Variant first = out.variants.get(0);
        for (Variant.NewSegment s : first.segments) System.out.printf("   seg %s %s→%s flow=%.1f dn=%d L=%.1f %s cost=%.0f served=%s%n", s.id, s.startNodeId, s.endNodeId, s.flowTph, s.diameter, s.length, s.layingMethod, s.cost, s.servedPoints);
        for (Variant.TieIn t : first.tieIns) System.out.printf("   присоединение %s → %s %s (%s)%n", t.id, t.existingObjectType, t.existingObjectId, t.toExistingChamber ? "врезка в существующую камеру, 5 млн" : "новая камера на участке");
        Path target = Paths.get("target");
        Files.createDirectories(target);
        try (FileOutputStream fos = new FileOutputStream(target.resolve("contest_result.geojson").toFile())) {
            new GeoJsonWriter(loader.crs()).write(out.variants, fos);
        }
        Variant best = out.variants.get(0);
        assertEquals(1, best.rank);
        assertTrue(best.unconnectedOksIds.size() < m.points.size(), "ни одна точка не подключена");
    }

    @Test
    void depthModeOnContestDataset() throws Exception {
        RulesService rules = RulesService.standalone();
        InputLoader loader = new InputLoader(rules);
        InputModel m;
        try (InputStream in = getClass().getResourceAsStream("/contest_dataset.geojson")) { m = loader.load(in); }
        VariantPlanner.Outcome out = new VariantPlanner(rules).plan(m, true);
        assertFalse(out.variants.isEmpty());
        for (Variant v : out.variants) {
            for (Variant.NewSegment s : v.segments) { assertNotNull(s.depthStart, s.id); assertNotNull(s.depthEnd, s.id); }
            System.out.printf("Глубина, вариант %s (%s): участков %d, техузлов %d, C=%.0f, L=%.1f, S=%.3f%n", v.variantId, v.strategy, v.segments.size(), v.techNodes.size(), v.calculatedCost, v.newNetworkLength, v.score);
        }
        try (FileOutputStream fos = new FileOutputStream(Paths.get("target").resolve("contest_result_depth.geojson").toFile())) {
            new GeoJsonWriter(loader.crs()).write(out.variants, fos);
        }
    }
}
