package ru.intelligence.heatnet.geo;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.CoordinateSequenceFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/** Преобразование WGS 84 (вход/выход) ↔ расчётная проекция (UTM 37N по умолчанию). ТП §2. */
public class CrsTransformer {
    private final CoordinateTransform toCalc;
    private final CoordinateTransform toWgs;

    public CrsTransformer(String calcProj4) {
        CRSFactory f = new CRSFactory();
        CoordinateReferenceSystem wgs = f.createFromParameters("WGS84", "+proj=longlat +datum=WGS84 +no_defs");
        CoordinateReferenceSystem calc = f.createFromParameters("CALC", calcProj4);
        CoordinateTransformFactory tf = new CoordinateTransformFactory();
        toCalc = tf.createTransform(wgs, calc);
        toWgs = tf.createTransform(calc, wgs);
    }

    public Coordinate toCalc(double lon, double lat) {
        ProjCoordinate out = new ProjCoordinate();
        toCalc.transform(new ProjCoordinate(lon, lat), out);
        return new Coordinate(out.x, out.y);
    }

    public Coordinate toWgs(double x, double y) {
        ProjCoordinate out = new ProjCoordinate();
        toWgs.transform(new ProjCoordinate(x, y), out);
        return new Coordinate(out.x, out.y);
    }

    /** Возвращает копию геометрии в расчётной проекции (Z сохраняется). */
    public Geometry toCalc(Geometry wgs) {
        Geometry g = wgs.copy();
        g.apply(filter(toCalc));
        g.geometryChanged();
        return g;
    }

    public Geometry toWgs(Geometry calc) {
        Geometry g = calc.copy();
        g.apply(filter(toWgs));
        g.geometryChanged();
        return g;
    }

    private static CoordinateSequenceFilter filter(CoordinateTransform t) {
        return new CoordinateSequenceFilter() {
            @Override
            public void filter(CoordinateSequence seq, int i) {
                ProjCoordinate out = new ProjCoordinate();
                t.transform(new ProjCoordinate(seq.getX(i), seq.getY(i)), out);
                seq.setOrdinate(i, 0, out.x);
                seq.setOrdinate(i, 1, out.y);
            }
            @Override public boolean isDone() { return false; }
            @Override public boolean isGeometryChanged() { return true; }
        };
    }
}
