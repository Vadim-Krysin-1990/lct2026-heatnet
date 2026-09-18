package ru.intelligence.heatnet.ingest;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import ru.intelligence.heatnet.geo.GeoUtil;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Потоковое чтение GeoJSON FeatureCollection (ТЗ 3.2: файлы до 3 ГБ не загружаются в память целиком).
 * Каждый Feature разбирается по отдельности и передаётся потребителю.
 */
public class GeoJsonStreamReader {

    public interface FeatureConsumer {
        void accept(long index, Map<String, Object> properties, Geometry wgsGeometry, String geometryType) throws IOException;
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final GeometryFactory gf = GeoUtil.GF;

    /** Возвращает число прочитанных Feature. */
    public long read(InputStream in, FeatureConsumer consumer) throws IOException {
        JsonFactory jf = mapper.getFactory();
        long count = 0;
        try (JsonParser p = jf.createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException("Ожидался объект FeatureCollection");
            boolean sawFeatures = false;
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.getCurrentName();
                p.nextToken();
                if ("features".equals(name)) {
                    if (p.currentToken() != JsonToken.START_ARRAY) throw new IOException("features должен быть массивом");
                    sawFeatures = true;
                    while (p.nextToken() == JsonToken.START_OBJECT) {
                        JsonNode feature = mapper.readTree(p);
                        JsonNode props = feature.get("properties");
                        JsonNode geomNode = feature.get("geometry");
                        Map<String, Object> pm = props == null || props.isNull()
                                ? new LinkedHashMap<>()
                                : mapper.convertValue(props, new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
                        Geometry g = null;
                        String gtype = null;
                        if (geomNode != null && !geomNode.isNull()) {
                            gtype = geomNode.path("type").asText(null);
                            g = parseGeometry(geomNode);
                        }
                        consumer.accept(count, pm, g, gtype);
                        count++;
                    }
                } else {
                    p.skipChildren();
                }
            }
            if (!sawFeatures) throw new IOException("В файле нет массива features");
        }
        return count;
    }

    // ---- разбор геометрии GeoJSON → JTS ----

    public Geometry parseGeometry(JsonNode node) throws IOException {
        String type = node.path("type").asText("");
        JsonNode c = node.get("coordinates");
        switch (type) {
            case "Point": return gf.createPoint(coord(c));
            case "MultiPoint": {
                List<Coordinate> cs = coords(c);
                return gf.createMultiPointFromCoords(cs.toArray(new Coordinate[0]));
            }
            case "LineString": return gf.createLineString(coords(c).toArray(new Coordinate[0]));
            case "MultiLineString": {
                List<org.locationtech.jts.geom.LineString> ls = new ArrayList<>();
                for (JsonNode l : c) ls.add(gf.createLineString(coords(l).toArray(new Coordinate[0])));
                return gf.createMultiLineString(ls.toArray(new org.locationtech.jts.geom.LineString[0]));
            }
            case "Polygon": return polygon(c);
            case "MultiPolygon": {
                List<Polygon> ps = new ArrayList<>();
                for (JsonNode poly : c) ps.add(polygon(poly));
                return gf.createMultiPolygon(ps.toArray(new Polygon[0]));
            }
            case "GeometryCollection": {
                List<Geometry> gs = new ArrayList<>();
                for (JsonNode gn : node.path("geometries")) gs.add(parseGeometry(gn));
                return gf.createGeometryCollection(gs.toArray(new Geometry[0]));
            }
            default: throw new IOException("Неподдерживаемый тип геометрии: " + type);
        }
    }

    private Polygon polygon(JsonNode rings) throws IOException {
        if (rings == null || rings.size() == 0) throw new IOException("Полигон без колец");
        LinearRing shell = ring(rings.get(0));
        LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int i = 1; i < rings.size(); i++) holes[i - 1] = ring(rings.get(i));
        return gf.createPolygon(shell, holes);
    }

    private LinearRing ring(JsonNode ring) throws IOException {
        List<Coordinate> cs = coords(ring);
        if (cs.size() < 4) throw new IOException("Кольцо полигона короче 4 точек");
        if (!cs.get(0).equals2D(cs.get(cs.size() - 1))) cs.add(cs.get(0).copy());
        return gf.createLinearRing(cs.toArray(new Coordinate[0]));
    }

    private List<Coordinate> coords(JsonNode arr) throws IOException {
        List<Coordinate> cs = new ArrayList<>(arr.size());
        for (JsonNode n : arr) cs.add(coord(n));
        return cs;
    }

    private Coordinate coord(JsonNode n) throws IOException {
        if (n == null || !n.isArray() || n.size() < 2) throw new IOException("Некорректная координата: " + n);
        double x = n.get(0).asDouble();
        double y = n.get(1).asDouble();
        if (n.size() >= 3 && n.get(2).isNumber()) return new Coordinate(x, y, n.get(2).asDouble());
        return new Coordinate(x, y);
    }
}
