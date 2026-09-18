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

    public GeoJsonWriter(CrsTransformer crs) {
        this.crs = crs;
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
            g.writeStringField("start_node_id", s.startNodeId);
            g.writeStringField("end_node_id", s.endNodeId);
            g.writeNumberField("flow_tph", GeoUtil.round(s.flowTph, 3));
            g.writeNumberField("diameter", s.diameter);
            g.writeNumberField("length", GeoUtil.round(s.length, 2));
            g.writeStringField("laying_method", s.layingMethod);
            if (s.depthStart == null) g.writeNullField("depth_start"); else g.writeNumberField("depth_start", GeoUtil.round(s.depthStart, 2));
            if (s.depthEnd == null) g.writeNullField("depth_end"); else g.writeNumberField("depth_end", GeoUtil.round(s.depthEnd, 2));
            g.writeNumberField("cost", Math.round(s.cost));
            endFeature(g);
        }
        for (Variant.TieIn t : v.tieIns) {
            startFeature(g, t.geom);
            g.writeStringField("id", t.id);
            g.writeStringField("object_type", "tie_in");
            g.writeStringField("variant_id", v.variantId);
            g.writeStringField("existing_object_id", t.existingObjectId);
            g.writeStringField("existing_object_type", t.existingObjectType);
            g.writeNumberField("existing_diameter", t.existingDiameter);
            g.writeNumberField("required_diameter", t.requiredDiameter);
            g.writeNumberField("cost", Math.round(t.cost));
            endFeature(g);
        }
        for (Variant.SegmentReconstruction r : v.reconstructions) {
            startFeature(g, r.geom);
            g.writeStringField("id", r.id);
            g.writeStringField("object_type", "heat_network_reconstruction");
            g.writeStringField("variant_id", v.variantId);
            g.writeStringField("existing_object_id", r.existingObjectId);
            g.writeNumberField("existing_flow_tph", GeoUtil.round(r.existingFlowTph, 3));
            g.writeNumberField("added_flow_tph", GeoUtil.round(r.addedFlowTph, 3));
            g.writeNumberField("calculated_flow_tph", GeoUtil.round(r.calculatedFlowTph, 3));
            g.writeNumberField("existing_diameter", r.existingDiameter);
            g.writeNumberField("required_diameter", r.requiredDiameter);
            g.writeNumberField("length", GeoUtil.round(r.length, 2));
            g.writeNumberField("cost", Math.round(r.cost));
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
        for (Variant.ChamberReconstruction r : v.chamberReconstructions) {
            startFeature(g, r.geom);
            g.writeStringField("id", r.id);
            g.writeStringField("object_type", "heat_chamber_reconstruction");
            g.writeStringField("variant_id", v.variantId);
            g.writeStringField("existing_object_id", r.existingObjectId);
            g.writeNumberField("existing_diameter", r.existingDiameter);
            g.writeNumberField("required_diameter", r.requiredDiameter);
            g.writeNumberField("cost", Math.round(r.cost));
            endFeature(g);
        }
        for (Variant.TechNode t : v.techNodes) {
            startFeature(g, t.geom);
            g.writeStringField("id", t.id);
            g.writeStringField("object_type", "technical_node");
            g.writeStringField("variant_id", v.variantId);
            endFeature(g);
        }
        // сводка
        g.writeStartObject();
        g.writeStringField("type", "Feature");
        g.writeNullField("geometry");
        g.writeObjectFieldStart("properties");
        g.writeStringField("id", "summary_" + v.variantId);
        g.writeStringField("object_type", "variant_summary");
        g.writeStringField("variant_id", v.variantId);
        g.writeNumberField("rank", v.rank);
        g.writeNumberField("construction_cost", Math.round(v.constructionCost));
        g.writeNumberField("chamber_construction_cost", Math.round(v.chamberConstructionCost));
        g.writeNumberField("tie_in_cost", Math.round(v.tieInCost));
        g.writeNumberField("reconstruction_cost", Math.round(v.reconstructionCost));
        g.writeNumberField("chamber_reconstruction_cost", Math.round(v.chamberReconstructionCost));
        g.writeNumberField("unconnected_penalty", Math.round(v.unconnectedPenalty));
        g.writeNumberField("calculated_cost", Math.round(v.calculatedCost));
        g.writeNumberField("new_network_length", GeoUtil.round(v.newNetworkLength, 2));
        g.writeNumberField("reconstruction_length", GeoUtil.round(v.reconstructionLength, 2));
        g.writeNumberField("length", GeoUtil.round(v.length, 2));
        g.writeNumberField("score", GeoUtil.round(v.score, 3));
        g.writeArrayFieldStart("unconnected_oks_ids");
        for (String id : v.unconnectedOksIds) g.writeString(id);
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
