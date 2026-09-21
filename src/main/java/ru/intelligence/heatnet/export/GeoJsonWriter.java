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

    public void write(List<Variant> variants, OutputStream out) throws IOException {
        try (JsonGenerator g = mapper.getFactory().createGenerator(out)) {
            g.writeStartObject();
            g.writeStringField("type", "FeatureCollection");
            g.writeStringField("name", "heatnet_result");
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
        if (v.description != null) g.writeStringField("variant_description", v.description);
        g.writeNumberField("construction_cost", Math.round(v.constructionCost));
        g.writeNumberField("chamber_construction_cost", Math.round(v.chamberConstructionCost));
        g.writeNumberField("existing_chamber_tie_in_count", v.existingChamberTieInCount);
        g.writeNumberField("existing_chamber_tie_in_cost", Math.round(v.existingChamberTieInCost));
        g.writeNumberField("unconnected_penalty", Math.round(v.unconnectedPenalty));
        g.writeNumberField("calculated_cost", Math.round(v.calculatedCost));
        g.writeNumberField("new_network_length", GeoUtil.round(v.newNetworkLength, 2));
        g.writeNumberField("score", GeoUtil.round(v.score, 4));
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
