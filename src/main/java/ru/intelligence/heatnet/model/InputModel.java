package ru.intelligence.heatnet.model;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Входные данные, приведённые к расчётной проекции (UTM 37N). */
public class InputModel {

    public static class Source {
        public String id;
        public String name;
        public Point geom;
    }

    public static class ExistingSegment {
        public String id;
        public int diameter;
        /** Текущий расчётный расход, т/ч; null — в данных отсутствует (принимается 0 с предупреждением). */
        public Double flowTph;
        public String upstreamObjectId;
        public LineString geom;
        public double length;
        public double flowOrZero() { return flowTph == null ? 0 : flowTph; }
    }

    public static class ExistingChamber {
        public String id;
        /** Максимальный ДУ примыкающих участков; null — восстанавливается по геометрии. */
        public Integer diameter;
        public String upstreamObjectId;
        public Point geom;
    }

    public static class ConnectionPoint {
        public String id;
        public String oksId;
        public double flowTph;
        public Double heatLoad;
        public Point geom;
    }

    public static class Restriction {
        public String id;
        public String type;
        public Geometry geom;
        public Map<String, Object> props = new LinkedHashMap<>();
    }

    public final List<Source> sources = new ArrayList<>();
    public final List<ExistingSegment> segments = new ArrayList<>();
    public final List<ExistingChamber> chambers = new ArrayList<>();
    public final List<ConnectionPoint> points = new ArrayList<>();
    public final List<Restriction> restrictions = new ArrayList<>();
    /** Полигоны ОКС (object_type oks_future / oks_existing), если переданы отдельно от ограничений. */
    public final List<Restriction> oksPolygons = new ArrayList<>();
    /** id входных объектов, записанные во входном файле числом: тип идентификатора сохраняется в выходе (ТП §7.2). */
    public final java.util.Set<String> numericIds = new java.util.HashSet<>();
    public final Diagnostics diagnostics = new Diagnostics();
    public int totalFeatures;

    public double totalNewFlow() {
        double s = 0;
        for (ConnectionPoint p : points) s += p.flowTph;
        return s;
    }
}
