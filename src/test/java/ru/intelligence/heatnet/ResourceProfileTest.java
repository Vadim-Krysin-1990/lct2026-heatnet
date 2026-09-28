package ru.intelligence.heatnet;

import org.junit.jupiter.api.Test;
import ru.intelligence.heatnet.config.HeatnetProperties;
import ru.intelligence.heatnet.config.RulesService;
import ru.intelligence.heatnet.ingest.InputLoader;
import ru.intelligence.heatnet.model.InputModel;
import ru.intelligence.heatnet.model.Variant;
import ru.intelligence.heatnet.planner.VariantPlanner;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Универсальность по типу ресурса (Q&A 16.09, п. 10 — критерий дополнительной оценки):
 * тот же алгоритм на справочнике водопровода, без единой правки кода.
 */
class ResourceProfileTest {

    @Test
    void waterProfileRunsOnSameAlgorithm() throws Exception {
        java.nio.file.Path dir = Paths.get("rules-profiles", "water");
        assertTrue(Files.isDirectory(dir), "нет профиля правил " + dir.toAbsolutePath());

        HeatnetProperties props = new HeatnetProperties();
        props.setRulesDir(dir.toString());
        RulesService rules = new RulesService(props);
        rules.reload();
        assertEquals("water_supply", rules.reference().resource.code);
        assertEquals("м³/ч", rules.reference().resource.flowUnit);
        assertEquals(8, rules.reference().diameters.size());

        InputLoader loader = new InputLoader(rules);
        InputModel m;
        try (InputStream in = getClass().getResourceAsStream("/contest_dataset.geojson")) {
            m = loader.load(in);
        }
        assertFalse(m.diagnostics.hasErrors(), m.diagnostics.messages.toString());

        VariantPlanner.Outcome out = new VariantPlanner(rules).plan(m);
        assertFalse(out.variants.isEmpty(), "на профиле водопровода варианты не построены");
        Variant best = out.variants.get(0);
        assertTrue(best.unconnectedOksIds.isEmpty(), "не подключены: " + best.unconnectedOksIds);
        assertTrue(best.newNetworkLength > 0);
        // диаметры берутся из профиля водопровода, а не из теплового справочника
        for (Variant.NewSegment s : best.segments) {
            assertTrue(rules.reference().diameters.stream().anyMatch(d -> d.dn == s.diameter),
                    "ДУ " + s.diameter + " отсутствует в справочнике водопровода");
        }
        System.out.println("Профиль «" + rules.reference().resource.title + "»: "
                + best.segments.size() + " участков, " + Math.round(best.newNetworkLength) + " м, "
                + Math.round(best.calculatedCost) + " ₽, S=" + String.format("%.3f", best.score));
    }
}
