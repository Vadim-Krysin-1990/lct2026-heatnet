package ru.intelligence.heatnet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.intelligence.heatnet.config.RulesService;
import ru.intelligence.heatnet.export.InputGeoJsonWriter;
import ru.intelligence.heatnet.ingest.InputLoader;
import ru.intelligence.heatnet.model.InputModel;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Обратная запись входных объектов: по ней просмотрщик строит карту загруженного файла,
 * который сам в браузер не передать. Геометрия входа бывает любая, включая мультиполигоны.
 */
class InputWriterTest {

    @Test
    void writesEveryInputGeometryBack() throws Exception {
        RulesService rules = RulesService.standalone();
        InputLoader loader = new InputLoader(rules);
        InputModel m;
        try (InputStream in = getClass().getResourceAsStream("/contest_dataset.geojson")) { m = loader.load(in); }
        assertFalse(m.diagnostics.hasErrors(), m.diagnostics.messages.toString());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new InputGeoJsonWriter(loader.crs(), m.numericIds).write(m, out);
        JsonNode fc = new ObjectMapper().readTree(out.toByteArray());

        assertEquals("FeatureCollection", fc.get("type").asText());
        assertEquals(m.totalFeatures, fc.get("total_features").asInt());
        int expected = m.sources.size() + m.segments.size() + m.chambers.size()
                + m.points.size() + m.restrictions.size();
        assertEquals(expected, fc.get("features").size());

        Set<String> types = new HashSet<>();
        Set<String> geomTypes = new HashSet<>();
        for (JsonNode f : fc.get("features")) {
            JsonNode g = f.get("geometry");
            assertFalse(g.isNull(), "у входного объекта должна быть геометрия");
            geomTypes.add(g.get("type").asText());
            assertTrue(g.has("coordinates") || g.has("geometries"), g.get("type").asText());
            types.add(f.get("properties").get("object_type").asText());
        }
        assertTrue(types.contains("source") && types.contains("heat_network")
                && types.contains("oks_connection_point") && types.contains("restriction"), types.toString());
        // полигональные ограничения набора должны выжить, а не упасть как «неподдерживаемая геометрия»
        assertTrue(geomTypes.contains("Polygon") || geomTypes.contains("MultiPolygon"), geomTypes.toString());

        // обратная загрузка записанного файла даёт тот же состав объектов
        InputLoader again = new InputLoader(rules);
        InputModel back = again.load(new java.io.ByteArrayInputStream(out.toByteArray()));
        assertFalse(back.diagnostics.hasErrors(), back.diagnostics.messages.toString());
        assertEquals(m.points.size(), back.points.size());
        assertEquals(m.segments.size(), back.segments.size());
        assertEquals(m.restrictions.size(), back.restrictions.size());
    }
}
