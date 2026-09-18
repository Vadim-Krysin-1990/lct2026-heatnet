package ru.intelligence.heatnet.geo;

import org.locationtech.jts.algorithm.MinimumDiameter;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.linearref.LengthIndexedLine;

import java.util.ArrayList;
import java.util.List;

/** Геометрические утилиты на JTS. */
public final class GeoUtil {
    public static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(PrecisionModel.FLOATING));

    private GeoUtil() {}

    public static Point point(double x, double y) {
        return GF.createPoint(new Coordinate(x, y));
    }

    public static Point point(Coordinate c) {
        return GF.createPoint(new Coordinate(c.x, c.y));
    }

    public static LineString line(List<Coordinate> coords) {
        return GF.createLineString(coords.toArray(new Coordinate[0]));
    }

    public static LineString line(Coordinate a, Coordinate b) {
        return GF.createLineString(new Coordinate[]{a, b});
    }

    /** Подгеометрия линии между долями [f0, f1] длины (0..1). */
    public static LineString substring(LineString ls, double f0, double f1) {
        LengthIndexedLine lil = new LengthIndexedLine(ls);
        double len = ls.getLength();
        Geometry g = lil.extractLine(Math.min(f0, f1) * len, Math.max(f0, f1) * len);
        if (g instanceof LineString) return (LineString) g;
        return GF.createLineString(g.getCoordinates());
    }

    /** Доля длины (0..1) от начала линии до проекции точки. */
    public static double fractionAlong(LineString ls, Coordinate c) {
        LengthIndexedLine lil = new LengthIndexedLine(ls);
        double len = ls.getLength();
        return len == 0 ? 0 : lil.project(c) / len;
    }

    public static Coordinate pointAt(LineString ls, double fraction) {
        LengthIndexedLine lil = new LengthIndexedLine(ls);
        return lil.extractPoint(fraction * ls.getLength());
    }

    /** Угол направления вектора в градусах [0, 360). */
    public static double bearingDeg(Coordinate a, Coordinate b) {
        double deg = Math.toDegrees(Math.atan2(b.y - a.y, b.x - a.x));
        return (deg + 360) % 360;
    }

    /** Острый угол между двумя направлениями (0..90). */
    public static double acuteAngleDeg(double bearing1, double bearing2) {
        double d = Math.abs(bearing1 - bearing2) % 180;
        return Math.min(d, 180 - d);
    }

    /**
     * Ось вытянутого полигона (дорога, ж/д, трамвай): направление длинной стороны минимального
     * охватывающего прямоугольника. Возвращает [bearingDeg, aspectRatio]; aspectRatio≈1 — ось не определена.
     */
    public static double[] polygonAxis(Geometry polygon) {
        MinimumDiameter md = new MinimumDiameter(polygon);
        Geometry rect = md.getMinimumRectangle();
        Coordinate[] cs = rect.getCoordinates();
        if (cs.length < 4) return new double[]{0, 1};
        double l1 = cs[0].distance(cs[1]);
        double l2 = cs[1].distance(cs[2]);
        double bearing = l1 >= l2 ? bearingDeg(cs[0], cs[1]) : bearingDeg(cs[1], cs[2]);
        double aspect = Math.max(l1, l2) / Math.max(1e-9, Math.min(l1, l2));
        return new double[]{bearing, aspect};
    }

    /** Внешние кольца всех полигонов геометрии. */
    public static List<LinearRing> exteriorRings(Geometry g) {
        List<LinearRing> rings = new ArrayList<>();
        for (int i = 0; i < g.getNumGeometries(); i++) {
            Geometry p = g.getGeometryN(i);
            if (p instanceof Polygon) rings.add(((Polygon) p).getExteriorRing());
        }
        return rings;
    }

    public static double round(double v, int digits) {
        double m = Math.pow(10, digits);
        return Math.round(v * m) / m;
    }
}
