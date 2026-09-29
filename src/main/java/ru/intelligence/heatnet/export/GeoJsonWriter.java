package ru.intelligence.heatnet.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import ru.intelligence.heatnet.geo.CrsTransformer;
import ru.intelligence.heatnet.geo.GeoUtil;
import ru.intelligence.heatnet.model.Variant;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Потоковая запись выходного GeoJSON строго по ТП §10: один FeatureCollection в WGS 84, все варианты
 * в одном массиве features, у каждого типа только свои атрибуты, одна variant_summary на вариант.
 */
public class GeoJsonWriter {
    private final CrsTransformer crs;
    private final ObjectMapper mapper = new ObjectMapper();
    /** id входных объектов, которые во входном файле были числами (ТП §7.2: тип идентификатора сохраняется). */
    private final java.util.Set<String> numericIds;

    public GeoJsonWriter(CrsTransformer crs) {
        this(crs, java.util.Collections.emptySet());
    }

    public GeoJsonWriter(CrsTransformer crs, java.util.Set<String> numericIds) {
        this.crs = crs;
        this.numericIds = numericIds == null ? java.util.Collections.emptySet() : numericIds;
    }

    /** Ссылка на узел: если во входных данных id был числом, пишем числом. */
    private void writeRef(JsonGenerator g, String field, String id) throws IOException {
        if (numericIds.contains(id)) {
            try { g.writeNumberField(field, Long.parseLong(id)); return; } catch (NumberFormatException ignore) { }
            try { g.writeNumberField(field, Double.parseDouble(id)); return; } catch (NumberFormatException ignore) { }
        }
        g.writeStringField(field, id);
    }

    /** Прослеживаемость: чем и из чего получен файл. Не входит в обязательный состав раздела 7. */
    private java.util.Map<String, Object> metadata;

    public GeoJsonWriter metadata(java.util.Map<String, Object> m) {
        this.metadata = m;
        return this;
    }

    public void write(List<Variant> variants, OutputStream out) throws IOException {
        try (JsonGenerator g = mapper.getFactory().createGenerator(out)) {
            g.writeStartObject();
            g.writeStringField("type", "FeatureCollection");
            g.writeStringField("name", "heatnet_result");
            if (metadata != null && !metadata.isEmpty()) {
                g.writeObjectFieldStart("metadata");
                for (java.util.Map.Entry<String, Object> e : metadata.entrySet()) {
                    Object v = e.getValue();
                    if (v == null) continue;
                    if (v instanceof Integer || v instanceof Long) g.writeNumberField(e.getKey(), ((Number) v).longValue());
                    else if (v instanceof Number) g.writeNumberField(e.getKey(), ((Number) v).doubleValue());
                    else if (v instanceof Boolean) g.writeBooleanField(e.getKey(), (Boolean) v);
                    else g.writeStringField(e.getKey(), String.valueOf(v));
                }
                g.writeEndObject();
            }
            g.writeObjectFieldStart("crs");
            g.writeStringField("type", "name");
            g.writeObjectFieldStart("properties");
            g.writeStringField("name", "urn:ogc:def:crs:OGC:1.3:CRS84");
            g.writeEndObject();
            g.writeEndObject();
            g.writeArrayFieldStart("features");
            for (Variant v : variants) writeVariant(g, v);
            g.writeEndArray();
            g.writeEndObject();
        }
    }

    private void writeVariant(JsonGenerator g, Variant v) throws IOException {
        for (Variant.NewSegment s : v.segments) {
            startFeature(g, s.geom);
            g.writeStringField("id", s.id);
            g.writeStringField("object_type", "heat_network");
            g.writeStringField("variant_id", v.variantId);
            writeRef(g, "start_node_id", s.startNodeId);
            writeRef(g, "end_node_id", s.endNodeId);
            g.writeNumberField("flow_tph", GeoUtil.round(s.flowTph, 3));
            g.writeNumberField("diameter", s.diameter);
            g.writeNumberField("length", GeoUtil.round(s.length, 2));
            g.writeStringField("laying_method", s.layingMethod);
            if (s.depthStart == null) g.writeNullField("depth_start"); else g.writeNumberField("depth_start", GeoUtil.round(s.depthStart, 2));
            if (s.depthEnd == null) g.writeNullField("depth_end"); else g.writeNumberField("depth_end", GeoUtil.round(s.depthEnd, 2));
            g.writeNumberField("cost", Math.round(s.cost));
            endFeature(g);
        }
        for (Variant.NewChamber c : v.chambers) {
            startFeature(g, c.geom);
            g.writeStringField("id", c.id);
            g.writeStringField("object_type", "heat_chamber");
            g.writeStringField("variant_id", v.variantId);
            g.writeNumberField("diameter", c.diameter);
            g.writeNumberField("cost", Math.round(c.cost));
            endFeature(g);
        }
        for (Variant.TechNode t : v.techNodes) {
            startFeature(g, t.geom);
            g.writeStringField("id", t.id);
            g.writeStringField("object_type", "technical_node");
            g.writeStringField("variant_id", v.variantId);
            endFeature(g);
        }
        // сводка по варианту (ТП от 21.09.2026, §7.2)
        g.writeStartObject();
        g.writeStringField("type", "Feature");
        g.writeNullField("geometry");
        g.writeObjectFieldStart("properties");
        g.writeStringField("id", "summary_" + v.variantId);
        g.writeStringField("object_type", "variant_summary");
        g.writeStringField("variant_id", v.variantId);
        g.writeNumberField("rank", v.rank);
        // подпись «какой это вариант»: стратегия трассировки словами (дополнительный атрибут сводки)
        if (v.name != null) g.writeStringField("variant_name", v.name);
        // категория варианта: инженерный, минимальный по расчёту или контрольный
        g.writeStringField("variant_kind", v.kind);
        g.writeStringField("variant_kind_title", v.kindTitle);
        if (v.description != null) g.writeStringField("variant_description", v.description);
        // метод поиска и время по этапам: видно, чем получен вариант и сколько это стоило
        g.writeStringField("routing_method", v.routingMethod);
        g.writeNumberField("routing_millis", v.routingMillis);
        g.writeNumberField("engineering_millis", v.engineeringMillis);
        g.writeNumberField("compute_millis", v.computeMillis);
        // метрики геометрии: по ним видно, чем метод отличается от метода, а не только цифрой стоимости
        g.writeNumberField("turns_per_km", GeoUtil.round(v.turnsPerKm, 1));
        g.writeNumberField("median_straight_m", GeoUtil.round(v.medianStraightM, 1));
        g.writeNumberField("oblique_turns", v.sharpTurns);
        g.writeNumberField("construction_cost", Math.round(v.constructionCost));
        g.writeNumberField("chamber_construction_cost", Math.round(v.chamberConstructionCost));
        g.writeNumberField("existing_chamber_tie_in_count", v.existingChamberTieInCount);
        g.writeNumberField("existing_chamber_tie_in_cost", Math.round(v.existingChamberTieInCost));
        g.writeNumberField("unconnected_penalty", Math.round(v.unconnectedPenalty));
        g.writeNumberField("calculated_cost", Math.round(v.calculatedCost));
        g.writeNumberField("new_network_length", GeoUtil.round(v.newNetworkLength, 2));
        g.writeNumberField("score", GeoUtil.round(v.score, 4));
        // места пересечений с существующими коммуникациями в режиме глубины:
        // сторона прохождения и вертикальное расстояние (приложение к ТЗ, разд. 7, пп. 3–4)
        if (!v.depthCrossings.isEmpty()) {
            g.writeArrayFieldStart("depth_crossings");
            for (Variant.DepthCrossing c : v.depthCrossings) {
                g.writeStartObject();
                writeRef(g, "utility_id", c.utilityId);
                g.writeStringField("utility_type", c.utilityType);
                g.writeStringField("position", c.position);
                if (!"conflict".equals(c.position)) g.writeNumberField("vertical_clearance_m", c.verticalClearanceM);
                g.writeNumberField("required_clearance_m", c.requiredClearanceM);
                g.writeNumberField("new_top_depth_m", c.newTopDepthM);
                g.writeStringField("segment_id", c.segmentId);
                g.writeNumberField("at_m", c.atM);
                if (c.geom != null) {
                    org.locationtech.jts.geom.Coordinate w = crs.toWgs(c.geom).getCoordinate();
                    g.writeNumberField("lon", GeoUtil.round(w.x, 9));
                    g.writeNumberField("lat", GeoUtil.round(w.y, 9));
                }
                if (c.note != null) g.writeStringField("note", c.note);
                g.writeEndObject();
            }
            g.writeEndArray();
        }
        // влияние новых подключений на существующую сеть: добавленный расход по цепочке к источнику
        // и требуемый ДУ (ТЗ 2.11, разд. 4 п. 6). Реконструкция не выполняется (Разъяснения п. 14),
        // поэтому в стоимость варианта эти участки не входят.
        if (!v.existingImpact.isEmpty()) {
            g.writeArrayFieldStart("existing_network_impact");
            for (Variant.ExistingImpact im : v.existingImpact) {
                g.writeStartObject();
                writeRef(g, "segment_id", im.segmentId);
                g.writeNumberField("current_diameter", im.currentDiameter);
                g.writeNumberField("current_flow_tph", im.currentFlowTph);
                g.writeBooleanField("current_flow_known", im.flowKnown);
                g.writeNumberField("added_flow_tph", im.addedFlowTph);
                g.writeNumberField("total_flow_tph", im.totalFlowTph);
                g.writeNumberField("loaded_share", im.loadedShare);
                g.writeNumberField("required_diameter", im.requiredDiameter);
                g.writeBooleanField("needs_upsize", im.needsUpsize);
                g.writeEndObject();
            }
            g.writeEndArray();
        }
        // пометки по варианту: что требует ручной проработки (заполняются самопроверкой)
        if (!v.notes.isEmpty()) {
            g.writeArrayFieldStart("notes");
            for (String n : v.notes) g.writeString(n);
            g.writeEndArray();
        }
        g.writeArrayFieldStart("unconnected_oks_ids");
        for (String id : v.unconnectedOksIds) {
            if (numericIds.contains(id)) {
                try { g.writeNumber(Long.parseLong(id)); continue; } catch (NumberFormatException ignore) { }
            }
            g.writeString(id);
        }
        g.writeEndArray();
        g.writeEndObject();
        g.writeEndObject();
    }

    private void startFeature(JsonGenerator g, Geometry calcGeom) throws IOException {
        g.writeStartObject();
        g.writeStringField("type", "Feature");
        g.writeFieldName("geometry");
        writeGeometry(g, crs.toWgs(calcGeom));
        g.writeObjectFieldStart("properties");
    }

    private void endFeature(JsonGenerator g) throws IOException {
        g.writeEndObject();
        g.writeEndObject();
    }

    /** Те же две операции нужны при обратной записи входных объектов (InputGeoJsonWriter). */
    void writeGeometryPublic(JsonGenerator g, Geometry geom) throws IOException { writeGeometry(g, geom); }

    void writeRefPublic(JsonGenerator g, String field, String id) throws IOException { writeRef(g, field, id); }

    private void writeGeometry(JsonGenerator g, Geometry geom) throws IOException {
        g.writeStartObject();
        if (geom instanceof Point) {
            g.writeStringField("type", "Point");
            g.writeFieldName("coordinates");
            writeCoord(g, geom.getCoordinate());
        } else if (geom instanceof LineString) {
            g.writeStringField("type", "LineString");
            g.writeArrayFieldStart("coordinates");
            for (Coordinate c : geom.getCoordinates()) writeCoord(g, c);
            g.writeEndArray();
        } else {
            throw new IOException("Неподдерживаемая геометрия выхода: " + geom.getGeometryType());
        }
        g.writeEndObject();
    }

    private static void writeCoord(JsonGenerator g, Coordinate c) throws IOException {
        g.writeStartArray();
        g.writeNumber(GeoUtil.round(c.x, 8));
        g.writeNumber(GeoUtil.round(c.y, 8));
        if (!Double.isNaN(c.getZ())) g.writeNumber(GeoUtil.round(c.getZ(), 3));
        g.writeEndArray();
    }
}
