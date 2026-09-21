package ru.intelligence.heatnet.model;

import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;

import java.util.ArrayList;
import java.util.List;

/** Один вариант подключения: объекты выходного GeoJSON (ТП §10) в расчётной проекции. */
public class Variant {

    public static class NewSegment {
        public String id;
        public String startNodeId;
        public String endNodeId;
        public LineString geom;
        public double flowTph;
        public int diameter;
        public double length;
        /** base | special */
        public String layingMethod = "base";
        /** Тип пересекаемого ограничения для special (служебное поле, в выход не идёт). */
        public String specialType;
        public double kSpecial = 1.0;
        public Double depthStart;
        public Double depthEnd;
        public double kDepth = 1.0;
        public double cost;
        /** Служебное: точки подключения, которые питаются через участок. */
        public List<String> servedPoints = new ArrayList<>();
    }

    public static class TieIn {
        public String id;
        public Point geom;
        public String existingObjectId;
        /** heat_network | heat_chamber */
        public String existingObjectType;
        public int existingDiameter;
        public int requiredDiameter;
        public double cost;
        /** Служебное: добавленный расход через врезку. */
        public double addedFlowTph;
        /** Служебное: доля вдоль участка (0..1 от начала LineString), если врезка в участок. */
        public Double fractionAlong;
    }

    public static class NewChamber {
        public String id;
        public Point geom;
        public int diameter;
        public double cost;
    }

    public static class TechNode {
        public String id;
        public Point geom;
    }

    public static class SegmentReconstruction {
        public String id;
        public String existingObjectId;
        public LineString geom;
        public double existingFlowTph;
        public double addedFlowTph;
        public double calculatedFlowTph;
        public int existingDiameter;
        public int requiredDiameter;
        public double length;
        public double cost;
    }

    public static class ChamberReconstruction {
        public String id;
        public String existingObjectId;
        public Point geom;
        public int existingDiameter;
        public int requiredDiameter;
        public double cost;
    }

    public String variantId;
    public String strategy;
    public String description;
    public int rank;

    public final List<NewSegment> segments = new ArrayList<>();
    public final List<TieIn> tieIns = new ArrayList<>();
    public final List<NewChamber> chambers = new ArrayList<>();
    public final List<TechNode> techNodes = new ArrayList<>();
    public final List<SegmentReconstruction> reconstructions = new ArrayList<>();
    public final List<ChamberReconstruction> chamberReconstructions = new ArrayList<>();
    public final List<String> unconnectedOksIds = new ArrayList<>();
    public final List<String> notes = new ArrayList<>();

    // сводка (ТП §10.7)
    public double constructionCost;
    public double chamberConstructionCost;
    public double tieInCost;
    public double reconstructionCost;
    public double chamberReconstructionCost;
    public double unconnectedPenalty;
    public double calculatedCost;
    public double newNetworkLength;
    public double reconstructionLength;
    public double length;
    public double score;
    public long computeMillis;
    // метрики качества трассы (в выходной GeoJSON не идут, только в диагностику и сравнение вариантов)
    public double turnsPerKm;
    public double medianStraightM;
    public int sharpTurns;
}
