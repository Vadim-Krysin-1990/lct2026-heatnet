package ru.intelligence.heatnet.export;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import ru.intelligence.heatnet.geo.CrsTransformer;
import ru.intelligence.heatnet.model.InputModel;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

/**
 * Обратная запись разобранных входных объектов в GeoJSON (WGS 84).
 * Нужна просмотрщику: после расчёта большого файла карта должна показывать объекты
 * из загруженных данных, а не из образца, а сам файл в браузер не передать.
 * Пишутся только объекты, оставленные загрузчиком в окне расчёта.
 */
public class InputGeoJsonWriter {

    private final CrsTransformer crs;
    private final ObjectMapper mapper = new ObjectMapper();
    private final GeoJsonWriter geo;

    public InputGeoJsonWriter(CrsTransformer crs, java.util.Set<String> numericIds) {
        this.crs = crs;
        this.geo = new GeoJsonWriter(crs, numericIds);
    }

    public void write(InputModel m, OutputStream out) throws IOException {
        try (JsonGenerator g = mapper.getFactory().createGenerator(out)) {
            g.writeStartObject();
            g.writeStringField("type", "FeatureCollection");
            g.writeStringField("name", "heatnet_input_area");
            g.writeNumberField("total_features", m.totalFeatures);
            g.writeArrayFieldStart("features");
            for (InputModel.Source s : m.sources) {
                start(g, s.geom);
                id(g, s.id); g.writeStringField("object_type", "source");
                if (s.name != null) g.writeStringField("name", s.name);
                end(g);
            }
            for (InputModel.ExistingSegment s : m.segments) {
                start(g, s.geom);
                id(g, s.id); g.writeStringField("object_type", "heat_network");
                g.writeNumberField("diameter", s.diameter);
                if (s.flowTph != null) g.writeNumberField("flow_tph", s.flowTph);
                if (s.upstreamObjectId != null) g.writeStringField("upstream_object_id", s.upstreamObjectId);
                end(g);
            }
            for (InputModel.ExistingChamber c : m.chambers) {
                start(g, c.geom);
                id(g, c.id); g.writeStringField("object_type", "heat_chamber");
                if (c.diameter != null) g.writeNumberField("diameter", c.diameter);
                end(g);
            }
            for (InputModel.ConnectionPoint p : m.points) {
                start(g, p.geom);
                id(g, p.id); g.writeStringField("object_type", "oks_connection_point");
                g.writeNumberField("flow_tph", p.flowTph);
                if (p.heatLoad != null) g.writeNumberField("heat_load", p.heatLoad);
                if (p.oksId != null) g.writeStringField("oks_id", p.oksId);
                end(g);
            }
            for (InputModel.Restriction r : m.restrictions) {
                start(g, r.geom);
                id(g, r.id); g.writeStringField("object_type", "restriction");
                g.writeStringField("restriction_type", r.type);
                for (Map.Entry<String, Object> e : r.props.entrySet()) {
                    Object val = e.getValue();
                    if (val instanceof Number) g.writeNumberField(e.getKey(), ((Number) val).doubleValue());
                    else if (val instanceof Boolean) g.writeBooleanField(e.getKey(), (Boolean) val);
                    else if (val != null) g.writeStringField(e.getKey(), String.valueOf(val));
                }
                end(g);
            }
            g.writeEndArray();
            g.writeEndObject();
        }
    }

    private void start(JsonGenerator g, Geometry calc) throws IOException {
        g.writeStartObject();
        g.writeStringField("type", "Feature");
        g.writeFieldName("geometry");
        writeAny(g, crs.toWgs(calc));
        g.writeObjectFieldStart("properties");
    }

    /**
     * Входные объекты бывают любой геометрии, включая полигоны и мультигеометрии,
     * тогда как выходные — только точки и линии.
     */
    private void writeAny(JsonGenerator g, Geometry geom) throws IOException {
        g.writeStartObject();
        g.writeStringField("type", geom.getGeometryType());
        if (geom instanceof GeometryCollection && !(geom instanceof MultiPoint)
                && !(geom instanceof MultiLineString) && !(geom instanceof MultiPolygon)) {
            g.writeArrayFieldStart("geometries");
            for (int i = 0; i < geom.getNumGeometries(); i++) writeAny(g, geom.getGeometryN(i));
            g.writeEndArray();
            g.writeEndObject();
            return;
        }
        g.writeFieldName("coordinates");
        if (geom instanceof Point) {
            coord(g, geom.getCoordinate());
        } else if (geom instanceof LineString) {
            line(g, ((LineString) geom).getCoordinates());
        } else if (geom instanceof Polygon) {
            polygon(g, (Polygon) geom);
        } else if (geom instanceof MultiPoint) {
            g.writeStartArray();
            for (int i = 0; i < geom.getNumGeometries(); i++) coord(g, geom.getGeometryN(i).getCoordinate());
            g.writeEndArray();
        } else if (geom instanceof MultiLineString) {
            g.writeStartArray();
            for (int i = 0; i < geom.getNumGeometries(); i++) line(g, geom.getGeometryN(i).getCoordinates());
            g.writeEndArray();
        } else if (geom instanceof MultiPolygon) {
            g.writeStartArray();
            for (int i = 0; i < geom.getNumGeometries(); i++) polygon(g, (Polygon) geom.getGeometryN(i));
            g.writeEndArray();
        } else {
            throw new IOException("Неподдерживаемая геометрия входа: " + geom.getGeometryType());
        }
        g.writeEndObject();
    }

    private void polygon(JsonGenerator g, Polygon p) throws IOException {
        g.writeStartArray();
        line(g, p.getExteriorRing().getCoordinates());
        for (int i = 0; i < p.getNumInteriorRing(); i++) line(g, p.getInteriorRingN(i).getCoordinates());
        g.writeEndArray();
    }

    private void line(JsonGenerator g, Coordinate[] cs) throws IOException {
        g.writeStartArray();
        for (Coordinate c : cs) coord(g, c);
        g.writeEndArray();
    }

    private void coord(JsonGenerator g, Coordinate c) throws IOException {
        g.writeStartArray();
        g.writeNumber(Math.round(c.x * 1e9) / 1e9);
        g.writeNumber(Math.round(c.y * 1e9) / 1e9);
        g.writeEndArray();
    }

    private void id(JsonGenerator g, String id) throws IOException {
        geo.writeRefPublic(g, "id", id);
    }

    private void end(JsonGenerator g) throws IOException {
        g.writeEndObject();
        g.writeEndObject();
    }
}
