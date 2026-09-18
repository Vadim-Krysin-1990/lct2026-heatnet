package ru.intelligence.heatnet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** REST-контур на H2: Swagger доступен, /api/process отдаёт корректный GeoJSON по ТП §10, ошибки входа → 422. */
@SpringBootTest
@AutoConfigureMockMvc
class ApiSmokeTest {

    @Autowired MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void swaggerAndRulesAvailable() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        mvc.perform(get("/api/rules/reference")).andExpect(status().isOk());
        mvc.perform(get("/api/rules/restrictions")).andExpect(status().isOk());
    }

    @Test
    void processReturnsGeoJsonPerAppendix() throws Exception {
        byte[] data = getClass().getResourceAsStream("/contest_dataset.geojson").readAllBytes();
        MvcResult res = mvc.perform(multipart("/api/process").file(new MockMultipartFile("file", "in.geojson", "application/geo+json", data)))
                .andExpect(status().isOk()).andReturn();
        res.getAsyncResult();
        JsonNode fc = json.readTree(res.getResponse().getContentAsByteArray());
        assertEquals("FeatureCollection", fc.get("type").asText());
        Set<String> variants = new HashSet<>();
        Set<String> ids = new HashSet<>();
        int summaries = 0;
        for (JsonNode f : fc.get("features")) {
            JsonNode p = f.get("properties");
            String type = p.get("object_type").asText();
            assertTrue(ids.add(p.get("id").asText()), "повтор id " + p.get("id"));
            variants.add(p.get("variant_id").asText());
            assertFalse("oks_connection_point".equals(type), "точки ОКС не должны повторяться в выходе");
            switch (type) {
                case "heat_network":
                    for (String k : new String[]{"start_node_id", "end_node_id", "flow_tph", "diameter", "length", "laying_method", "depth_start", "depth_end", "cost"}) assertTrue(p.has(k), k);
                    assertEquals("LineString", f.get("geometry").get("type").asText());
                    break;
                case "tie_in":
                    for (String k : new String[]{"existing_object_id", "existing_object_type", "existing_diameter", "required_diameter", "cost"}) assertTrue(p.has(k), k);
                    break;
                case "heat_chamber":
                    assertTrue(p.has("diameter") && p.has("cost"));
                    assertFalse(p.has("existing_object_id"));
                    break;
                case "technical_node":
                    assertEquals(3, p.size(), "у техузла только id, object_type, variant_id");
                    break;
                case "heat_network_reconstruction":
                    for (String k : new String[]{"existing_object_id", "existing_flow_tph", "added_flow_tph", "calculated_flow_tph", "existing_diameter", "required_diameter", "length", "cost"}) assertTrue(p.has(k), k);
                    break;
                case "heat_chamber_reconstruction":
                    for (String k : new String[]{"existing_object_id", "existing_diameter", "required_diameter", "cost"}) assertTrue(p.has(k), k);
                    break;
                case "variant_summary":
                    summaries++;
                    assertTrue(f.get("geometry").isNull());
                    for (String k : new String[]{"rank", "construction_cost", "chamber_construction_cost", "tie_in_cost", "reconstruction_cost", "chamber_reconstruction_cost", "unconnected_penalty", "calculated_cost", "new_network_length", "reconstruction_length", "length", "score", "unconnected_oks_ids"}) assertTrue(p.has(k), k);
                    double sum = p.get("construction_cost").asDouble() + p.get("chamber_construction_cost").asDouble() + p.get("tie_in_cost").asDouble()
                            + p.get("reconstruction_cost").asDouble() + p.get("chamber_reconstruction_cost").asDouble() + p.get("unconnected_penalty").asDouble();
                    assertEquals(sum, p.get("calculated_cost").asDouble(), 5.0);
                    break;
                default:
                    throw new AssertionError("неизвестный тип в выходе: " + type);
            }
        }
        assertEquals(variants.size(), summaries, "по одной сводке на вариант");
        assertTrue(variants.size() >= 1 && variants.size() <= 3);
    }

    @Test
    void invalidInputGives422WithDiagnostics() throws Exception {
        String bad = "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{\"id\":1,\"object_type\":\"oks_connection_point\",\"flow_tph\":5},\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6,55.7]}}]}";
        MvcResult res = mvc.perform(multipart("/api/process").file(new MockMultipartFile("file", "bad.geojson", "application/geo+json", bad.getBytes())))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        JsonNode body = json.readTree(res.getResponse().getContentAsString());
        assertTrue(body.get("diagnostics").get("errors").asInt() >= 2, "нет источника и сети");
    }

    @Test
    void jobsQueueWorks() throws Exception {
        byte[] data = getClass().getResourceAsStream("/contest_dataset.geojson").readAllBytes();
        MvcResult res = mvc.perform(multipart("/api/jobs").file(new MockMultipartFile("file", "in.geojson", "application/geo+json", data)))
                .andExpect(status().isAccepted()).andReturn();
        String id = json.readTree(res.getResponse().getContentAsString()).get("id").asText();
        String status = "QUEUED";
        for (int i = 0; i < 120 && !status.equals("DONE") && !status.equals("FAILED"); i++) {
            Thread.sleep(1000);
            status = json.readTree(mvc.perform(get("/api/jobs/" + id)).andReturn().getResponse().getContentAsString()).get("status").asText();
        }
        assertEquals("DONE", status);
        mvc.perform(get("/api/jobs/" + id + "/result")).andExpect(status().isOk());
        mvc.perform(get("/api/jobs/" + id + "/diagnostics")).andExpect(status().isOk());
    }
}
