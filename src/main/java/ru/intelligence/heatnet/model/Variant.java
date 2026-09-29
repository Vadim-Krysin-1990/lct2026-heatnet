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

    /**
     * Присоединение новой сети к существующей. Отдельным объектом выхода не является (ТП от 21.09.2026,
     * §2.4): либо новая камера в точке присоединения, либо врезка в существующую камеру за 5 млн.
     */
    public static class TieIn {
        public String id;
        public Point geom;
        public String existingObjectId;
        /** heat_network | heat_chamber */
        public String existingObjectType;
        public int existingDiameter;
        public int requiredDiameter;
        public double cost;
        public double addedFlowTph;
        public Double fractionAlong;
        /** true — присоединение к существующей камере (врезка 5 млн), false — новая камера на участке. */
        public boolean toExistingChamber;
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

    /**
     * Пересечение новой сети с существующей линейной коммуникацией в режиме глубины
     * (приложение к ТЗ, разд. 7, пп. 3–4: место, прохождение сверху или снизу, вертикальное расстояние).
     */
    public static class DepthCrossing {
        public String utilityId;
        public String utilityType;
        /** above | below | conflict */
        public String position;
        /** расчётное вертикальное расстояние между габаритами, м */
        public double verticalClearanceM;
        /** требуемое по правилам вертикальное расстояние, м */
        public double requiredClearanceM;
        /** глубина верха новой сети в месте пересечения, м */
        public double newTopDepthM;
        public String segmentId;
        /** положение вдоль цепочки участков от её начала, м */
        public double atM;
        public Point geom;
        public String note;
    }

    /**
     * Влияние новых подключений на участок существующей сети (ТЗ 2.11, раздел 4 п. 6):
     * добавленный расход, требуемый условный диаметр и признак нехватки текущего ДУ.
     * Информационный расчёт: реконструкция не выполняется (Разъяснения п. 14).
     */
    public static class ExistingImpact {
        public String segmentId;
        public int currentDiameter;
        public double currentFlowTph;
        /** был ли текущий расход задан во входных данных */
        public boolean flowKnown;
        public double addedFlowTph;
        public double totalFlowTph;
        /** какая доля длины участка оказалась под добавленной нагрузкой (врезка внутри участка) */
        public double loadedShare;
        public int requiredDiameter;
        public boolean needsUpsize;
    }

    public String variantId;
    public String strategy;
    /** Короткое название варианта для подписи в выдаче и на карте. */
    public String name;
    public String description;
    public int rank;
    /** Метод поиска трассы: grid — растровая сетка, visibility — граф видимости. */
    public String routingMethod = "grid";
    /** Категория варианта: engineering — инженерный, shortest — минимальный, control — контрольный. */
    public String kind = "control";
    /** Название категории для человека. */
    public String kindTitle = "Контрольный";
    /** Время поиска трасс, мс (без инженерного расчёта). */
    public long routingMillis;
    /** Время инженерного расчёта: расходы, диаметры, узлы, стоимость, мс. */
    public long engineeringMillis;

    public final List<NewSegment> segments = new ArrayList<>();
    public final List<TieIn> tieIns = new ArrayList<>();
    public final List<NewChamber> chambers = new ArrayList<>();
    public final List<TechNode> techNodes = new ArrayList<>();
    public final List<String> unconnectedOksIds = new ArrayList<>();
    public final List<String> notes = new ArrayList<>();
    /** Места пересечений с существующими коммуникациями, заполняется только в режиме глубины. */
    public final List<DepthCrossing> depthCrossings = new ArrayList<>();
    /** Участки существующей сети, на которые пришёлся расход новых подключений. */
    public final List<ExistingImpact> existingImpact = new ArrayList<>();
    /**
     * Нарушения обязательных правил, найденные самопроверкой после сборки варианта: отступ меньше
     * нормы, поворот круче 90°, пересечение новых участков вне узла. Варианты с нарушениями в выдачу
     * не попадают, пока есть чистые альтернативы.
     */
    public int ruleViolations;
    /** Чем именно вариант нарушает правила — для диагностики отбора. */
    public final List<String> violationNotes = new ArrayList<>();

    // сводка (ТП §10.7)
    public double constructionCost;
    public double chamberConstructionCost;
    /** Количество врезок в существующие тепловые камеры. */
    public int existingChamberTieInCount;
    /** Стоимость врезок в существующие камеры, входит в construction_cost. */
    public double existingChamberTieInCost;
    public double unconnectedPenalty;
    public double calculatedCost;
    public double newNetworkLength;
    public double score;
    public long computeMillis;
    // метрики качества трассы (в выходной GeoJSON не идут, только в диагностику и сравнение вариантов)
    public double turnsPerKm;
    public double medianStraightM;
    public int sharpTurns;
}
