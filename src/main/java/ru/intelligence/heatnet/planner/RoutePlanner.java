package ru.intelligence.heatnet.planner;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.intelligence.heatnet.config.ReferenceRules;
import ru.intelligence.heatnet.config.RoutingRules;
import ru.intelligence.heatnet.config.RulesService;
import ru.intelligence.heatnet.geo.GeoUtil;
import ru.intelligence.heatnet.model.Diagnostics;
import ru.intelligence.heatnet.model.InputModel;
import ru.intelligence.heatnet.model.InputModel.ConnectionPoint;
import ru.intelligence.heatnet.model.InputModel.ExistingChamber;
import ru.intelligence.heatnet.model.InputModel.ExistingSegment;
import ru.intelligence.heatnet.network.NetworkTopology;
import ru.intelligence.heatnet.planner.NetworkBuilder.Edge;
import ru.intelligence.heatnet.planner.NetworkBuilder.Node;
import ru.intelligence.heatnet.planner.NetworkBuilder.NodeKind;
import ru.intelligence.heatnet.routing.AStarRouter;
import ru.intelligence.heatnet.routing.ObstacleField;
import ru.intelligence.heatnet.routing.PathSimplifier;
import ru.intelligence.heatnet.routing.RasterWindow;
import ru.intelligence.heatnet.routing.VisibilityRouter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Последовательное построение новой сети для одного варианта.
 * Стратегия задаёт порядок точек и набор допустимых целей: только существующая сеть (independent)
 * или существующая + уже построенная новая сеть (shared_tree).
 */
public class RoutePlanner {
    private static final Logger log = LoggerFactory.getLogger(RoutePlanner.class);

    public static class Strategy {
        public String name;
        public boolean attachToNewNetwork = true;
        /** distance — ближние сначала; flow_desc — большие расходы сначала; distance_desc — дальние сначала. */
        public String order = "distance";
        /** Исключить эти существующие объекты из кандидатов врезки (для альтернативных вариантов). */
        public List<String> excludedTieIns = new ArrayList<>();
        /** Надбавка к стоимости присоединения к уже построенной новой сети (вариант «отдельные части сети»). */
        public double attachPenalty = 0;
        /** Режим с учётом глубины: стоимость профиля пересечений влияет на выбор маршрута. */
        public boolean depthMode = false;
        /** Разрешённых направлений: 8 (повороты кратны 45°) или 4 (только 90°, как у существующей сети). */
        public int directions = 8;
        /** Азимут сетки (град.): трасса идёт параллельно застройке и существующим сетям. */
        public double gridBearingDeg = 0;
        /** Штраф за поворот в «метрах стоимости»; больше — длиннее прямые участки. */
        public Double turnPenaltyM = null;
        /** Во сколько раз поворот на 45° дороже прямого угла (косой излом требует неподвижных опор). */
        public double sharpTurnFactor = 1;
        /** Спрямлять трассу произвольным углом (ТП от 21.09 §2.1: допустим любой поворот до 90°). */
        public boolean freeAngle = false;
        /** Искать по графу видимости вместо растровой сетки: трасса идёт по касательным к препятствиям. */
        public boolean visibility = false;
        /** Короткое название варианта («какой он») для подписи в выдаче. */
        public String title;
        public String description;
    }

    public static class PointResult {
        public ConnectionPoint cp;
        public boolean connected;
        public String reason;
        public Node leaf;
        public double routeCost;
        public int expanded;
        public long millis;
    }

    private final RulesService rules;
    private final InputModel model;
    private final NetworkTopology topo;
    private final ObstacleField field;
    private final ReferenceRules ref;
    private final RoutingRules rr;
    private final List<InputModel.Restriction> buildings = new ArrayList<>();
    private final int clearanceDn;
    private final double clearance;
    private final ru.intelligence.heatnet.depth.DepthRules depthRules;

    public RoutePlanner(RulesService rules, InputModel model, NetworkTopology topo, ObstacleField field) {
        this.rules = rules;
        this.model = model;
        this.topo = topo;
        this.field = field;
        this.ref = rules.reference();
        this.rr = rules.routing();
        this.clearanceDn = field.clearanceDn();
        this.clearance = rules.restrictions().resolve("oks").clearance(clearanceDn, rules.restrictions().defaultClearanceM);
        this.depthRules = new ru.intelligence.heatnet.depth.DepthRules(ref, rules.restrictions());
        for (InputModel.Restriction r : model.restrictions) if (r.type != null && r.type.startsWith("oks")) buildings.add(r);
    }

    /** Строит сеть варианта; возвращает результат по каждой точке. */
    public List<PointResult> plan(Strategy strategy, NetworkBuilder nb, Diagnostics diag) {
        List<ConnectionPoint> order = new ArrayList<>(model.points);
        Map<String, Double> distToNet = new HashMap<>();
        for (ConnectionPoint cp : order) {
            Object[] ns = topo.nearestSegment(cp.geom.getCoordinate(), Double.MAX_VALUE);
            distToNet.put(cp.id, ns == null ? Double.MAX_VALUE : (double) ns[2]);
        }
        switch (strategy.order) {
            case "flow_desc": order.sort(Comparator.comparingDouble((ConnectionPoint c) -> -c.flowTph)); break;
            case "distance_desc": order.sort(Comparator.comparingDouble((ConnectionPoint c) -> -distToNet.get(c.id))); break;
            default: order.sort(Comparator.comparingDouble(c -> distToNet.get(c.id)));
        }
        // в инженерных вариантах выход из здания идёт строго вдоль оси сетки: иначе первый же стык
        // с магистральным направлением даёт косой излом
        double exitStep = (strategy.directions <= 4 || strategy.sharpTurnFactor > 1) ? 90 : 45;
        ExitFinder exits = new ExitFinder(field, clearance, rr.gridStepM, strategy.gridBearingDeg, exitStep);
        List<PointResult> results = new ArrayList<>();
        for (ConnectionPoint cp : order) {
            long t0 = System.currentTimeMillis();
            PointResult pr = new PointResult();
            pr.cp = cp;
            try {
                routeOne(cp, strategy, nb, exits, pr, diag);
            } catch (RuntimeException ex) {
                log.warn("Точка {}: ошибка трассировки: {}", cp.id, ex.toString());
                pr.connected = false;
                pr.reason = "ошибка расчёта: " + ex.getMessage();
                diag.warn("ROUTE_ERROR", "Точка " + cp.id + ": " + ex, cp.id);
            }
            pr.millis = System.currentTimeMillis() - t0;
            results.add(pr);
        }
        return results;
    }

    private void routeOne(ConnectionPoint cp, Strategy strategy, NetworkBuilder nb, ExitFinder exits, PointResult pr, Diagnostics diag) {
        List<ExitFinder.Exit> candidates = exits.findAll(cp, buildings);
        if (candidates.size() > rr.maxExitCandidates) candidates = candidates.subList(0, rr.maxExitCandidates);
        double cnew = ref.diameterForFlow(cp.flowTph).newCostPerM;
        int tried = 0;
        if (routeWithExits(cp, strategy, nb, candidates, cnew, pr, diag)) return;
        if (!strategy.attachToNewNetwork && !nb.edges().isEmpty()) {
            // раздельное подключение невозможно без пересечения уже построенных участков → объединяем в общую сеть (ТЗ 2.3)
            Strategy merged = new Strategy();
            merged.name = strategy.name; merged.order = strategy.order; merged.excludedTieIns = strategy.excludedTieIns; merged.attachToNewNetwork = true; merged.depthMode = strategy.depthMode; merged.attachPenalty = strategy.attachPenalty;
            merged.directions = strategy.directions; merged.gridBearingDeg = strategy.gridBearingDeg; merged.turnPenaltyM = strategy.turnPenaltyM; merged.sharpTurnFactor = strategy.sharpTurnFactor;
            diag.info("ROUTE_MERGE", "Точка " + cp.id + ": отдельная трасса пересекала бы уже построенную сеть — точка присоединена к новой сети", cp.id);
            if (routeWithExits(cp, merged, nb, candidates, cnew, pr, diag)) return;
        }
        pr.connected = false;
        pr.reason = "маршрут не найден (перебрано выходов: " + candidates.size() + ")";
        diag.warn("ROUTE_NOT_FOUND", "Точка " + cp.id + ": " + pr.reason, cp.id);
    }

    private boolean routeWithExits(ConnectionPoint cp, Strategy strategy, NetworkBuilder nb, List<ExitFinder.Exit> candidates, double cnew, PointResult pr, Diagnostics diag) {
        if (routeWithExits(cp, strategy, nb, candidates, cnew, pr, diag, strategy.directions)) return true;
        if (strategy.directions <= 4) {
            diag.info("ROUTE_DIAGONAL_FALLBACK", "Точка " + cp.id + ": ортогональный ход не найден, участок построен с поворотами 45°", cp.id);
            return routeWithExits(cp, strategy, nb, candidates, cnew, pr, diag, 8);
        }
        return false;
    }

    private boolean routeWithExits(ConnectionPoint cp, Strategy strategy, NetworkBuilder nb, List<ExitFinder.Exit> candidates, double cnew, PointResult pr, Diagnostics diag, int directions) {
        double turnPenalty = strategy.turnPenaltyM != null ? strategy.turnPenaltyM : rr.turnPenaltyM;
        double radius = rr.candidateRadiusM;
        while (radius <= rr.candidateRadiusMaxM) {
            Envelope env = new Envelope(cp.geom.getCoordinate());
            env.expandBy(radius);
            List<RasterWindow.Goal> goals = collectGoals(cp, strategy, nb, env);
            if (goals.isEmpty()) { radius *= 2; continue; }
            Envelope win = new Envelope(cp.geom.getCoordinate());
            for (ExitFinder.Exit e : candidates) win.expandToInclude(e.exitPoint);
            for (RasterWindow.Goal g : goals) win.expandToInclude(g.geom.getEnvelopeInternal());
            win.expandBy(rr.windowMarginM);
            RasterWindow w = buildWindow(win, goals, strategy, cp);
            int dirStepIdx = directions <= 4 ? 2 : 1;
            boolean[] base = w.blocked.clone();
            int tried = 0;
            for (ExitFinder.Exit exit : candidates) {
                tried++;
                System.arraycopy(base, 0, w.blocked, 0, base.length);
                if (exit.building != null) w.unblock(ExitFinder.corridorGeom(exit, rr.gridStepM * 0.75));
                Coordinate start = exit.exitPoint;
                if (exit.direction >= 0 && exit.direction % dirStepIdx != 0) continue;   // выход не по оси сетки
                int sc = w.colOf(start.x, start.y), sr = w.rowOf(start.x, start.y);
                if (!w.inside(sc, sr)) continue;
                int startCell = w.idx(sc, sr);
                w.blocked[startCell] = false;
                if (!w.reachable(startCell)) {
                    diag.info("ROUTE_RETRY", "Точка " + cp.id + ": выход №" + tried + " (здание " + (exit.building == null ? "-" : exit.building.id) + ", " + exit.direction * 45 + "°) ведёт в замкнутый карман — пропущен", cp.id);
                    continue;
                }
                boolean visibilityFailed = false;
                if (strategy.visibility) {
                    // второй метод: поиск по графу видимости, растр используется только как
                    // маска запретных зон и как источник целей
                    VisibilityRouter vr = new VisibilityRouter(w, field, cnew, turnPenalty, rr.maxTurnDeg,
                            rr.visibilityVertexOffsetM, rr.visibilityMaxVertices);
                    Double bearing = exit.direction >= 0 ? (w.bearingDeg + exit.direction * 45.0) % 360 : null;
                    String ownId = exit.building != null ? exit.building.id : null;
                    VisibilityRouter.Result vres = vr.route(start, bearing, win, ownId);
                    if (vres == null) {
                        // граф видимости не связал старт с целью — не бросаем точку, а достраиваем
                        // её обычным поиском по сетке: неподключённая точка стоит штрафа в 100 млн
                        visibilityFailed = true;
                        diag.info("ROUTE_VISIBILITY_FALLBACK", "Точка " + cp.id + ": граф видимости из выхода №"
                                + tried + " не дал пути, трасса достроена поиском по растровой сетке", cp.id);
                    } else {
                        pr.expanded = vres.expanded;
                        pr.routeCost = vres.cost;
                        commitPath(cp, exit, w, vres.path, vres.goalIndex, nb, pr, diag, strategy);
                        return true;
                    }
                }
                AStarRouter router = new AStarRouter(w, cnew, turnPenalty, directions, strategy.sharpTurnFactor, rr.maxTurnDeg);
                AStarRouter.Result res = router.route(startCell, exit.direction);
                if (res == null) {
                    diag.info("ROUTE_RETRY", "Точка " + cp.id + ": из выхода №" + tried + " (здание " + (exit.building == null ? "-" : exit.building.id) + ", " + exit.direction * 45 + "°) в окне " + w.cols + "×" + w.rows + " пути нет с учётом правил пересечений", cp.id);
                    continue;
                }
                if (exit.relaxed) diag.warn("EXIT_NEAR_NEIGHBOUR", "Точка " + cp.id + ": выход из здания " + exit.building.id + " проходит ближе нормативного отступа к соседнему зданию — свободного коридора нет", cp.id);
                if (exit.enclosed) diag.warn("EXIT_ENCLOSED", "Точка " + cp.id + ": здание " + exit.building.id + " окружено, свободного выхода нет", cp.id);
                pr.expanded = res.expanded;
                pr.routeCost = res.cost;
                commit(cp, exit, w, res, nb, pr, diag, strategy);
                return true;
            }
            radius *= 2;
        }
        return false;
    }

    /** Кандидаты врезки/присоединения с терминальной стоимостью (ТП §8.2). */
    private List<RasterWindow.Goal> collectGoals(ConnectionPoint cp, Strategy strategy, NetworkBuilder nb, Envelope env) {
        List<RasterWindow.Goal> goals = new ArrayList<>();
        int dn = ref.diameterForFlow(cp.flowTph).dn;
        double newChamber = ref.chamberCost(dn);
        double tieRadius = ref.chambers.existingChamberTieInRadiusM;
        // существующие камеры
        List<Geometry> chamberZones = new ArrayList<>();
        for (ExistingChamber ch : topo.chambers()) {
            if (!env.contains(ch.geom.getCoordinate())) continue;
            if (strategy.excludedTieIns.contains(ch.id)) continue;
            int deg = topo.chamberDegree(ch) + countNewEdgesAt(nb, ch.geom.getCoordinate());
            if (deg + 1 > ref.chambers.maxBranches) continue;   // камера заполнена
            RasterWindow.Goal g = new RasterWindow.Goal();
            g.kind = "existing_chamber"; g.objectId = ch.id; g.geom = ch.geom;
            g.terminalCost = ref.tieIn.cost;     // врезка; реконструкция камеры считается позже
            goals.add(g);
            chamberZones.add(ch.geom.buffer(tieRadius));
        }
        // существующие участки: врезка в трубу + новая камера; исключаем зоны 10 м у камер (там — только камера)
        @SuppressWarnings("unchecked")
        List<ExistingSegment> segs = topo.segmentIndex().query(env);
        for (ExistingSegment s : segs) {
            if (strategy.excludedTieIns.contains(s.id)) continue;
            Geometry g = s.geom;
            for (Geometry z : chamberZones) g = g.difference(z);
            if (g.isEmpty()) continue;
            RasterWindow.Goal goal = new RasterWindow.Goal();
            goal.kind = "existing_segment"; goal.objectId = s.id; goal.geom = g;
            // присоединение к участку — новая камера, её стоимость уже включает врезку (ТП §2.4, §3.2);
            // раньше здесь ошибочно прибавлялась ещё и стоимость врезки в камеру, и поиск
            // систематически предпочитал уход в существующую камеру за 5 млн
            goal.terminalCost = newChamber;
            goals.add(goal);
        }
        // новая сеть (общее дерево)
        if (strategy.attachToNewNetwork) {
            for (Node n : nb.nodes().values()) {
                if (!env.contains(n.xy)) continue;
                if (n.kind == NodeKind.CHAMBER && n.degree() < ref.chambers.maxBranches) {
                    RasterWindow.Goal g = new RasterWindow.Goal();
                    g.kind = "new_chamber"; g.objectId = n.id; g.geom = GeoUtil.point(n.xy); g.terminalCost = strategy.attachPenalty; g.terminalOnly = true;
                    goals.add(g);
                }
                if (n.kind == NodeKind.TIE_IN && n.degree() < ref.chambers.maxBranches && n.existingObjectType != null) {
                    // врезка-камера: можно подсадить ещё ветку (новая камера в точке врезки уже есть)
                    RasterWindow.Goal g = new RasterWindow.Goal();
                    g.kind = "new_chamber"; g.objectId = n.id; g.geom = GeoUtil.point(n.xy); g.terminalCost = strategy.attachPenalty; g.terminalOnly = true;
                    goals.add(g);
                }
            }
            for (Edge e : nb.edges()) {
                LineString ls = e.line();
                if (!env.intersects(ls.getEnvelopeInternal())) continue;
                if (e.specialType != null) continue;           // на спецучастке ветвление не делаем
                // не ветвиться вплотную к концам ребра: оставляем по шагу сетки
                RasterWindow.Goal g = new RasterWindow.Goal();
                g.kind = "new_segment"; g.objectId = e.id;
                g.geom = ls.getLength() > 3 * rr.gridStepM ? GeoUtil.substring(ls, rr.gridStepM / ls.getLength(), 1 - rr.gridStepM / ls.getLength()) : ls;
                g.terminalCost = newChamber + strategy.attachPenalty;
                g.terminalOnly = true;
                goals.add(g);
            }
            // уже построенные участки нельзя пересекать вне узла: их клетки терминальны, спецучастки — заблокированы
            for (Edge e : nb.edges()) {
                if (e.specialType == null) continue;
                RasterWindow.Goal g = new RasterWindow.Goal();
                g.kind = "new_segment_special"; g.objectId = e.id; g.geom = e.line(); g.terminalCost = Double.POSITIVE_INFINITY; g.terminalOnly = true;
                goals.add(g);
            }
        } else {
            // раздельное подключение: новая сеть — непроходимое препятствие (ТЗ 2.3: участки не пересекаются)
            for (Edge e : nb.edges()) {
                RasterWindow.Goal g = new RasterWindow.Goal();
                g.kind = "new_segment_special"; g.objectId = e.id; g.geom = e.line(); g.terminalCost = Double.POSITIVE_INFINITY; g.terminalOnly = true;
                goals.add(g);
            }
        }
        return goals;
    }

    private int countNewEdgesAt(NetworkBuilder nb, Coordinate c) {
        Node n = nb.nodeNear(c, 0.5);
        return n == null ? 0 : n.degree();
    }

    private RasterWindow buildWindow(Envelope env, List<RasterWindow.Goal> goals, Strategy strategy, ConnectionPoint cp) {
        RasterWindow w = new RasterWindow(env, rr.gridStepM, strategy.gridBearingDeg);
        double halfWidth = ref.spec(clearanceDn).widthM / 2;
        for (ObstacleField.Obstacle o : field.query(env)) {
            if (o.getBlocked() != null) w.block(o.getBlocked(), o.envelope);
            if (o.getSpecialZoneGeom() != null) {
                RasterWindow.Zone z = new RasterWindow.Zone();
                z.obstacleId = o.id; z.type = o.type; z.k = o.kSpecial; z.axisBearing = o.axisBearing; z.minAngleDeg = o.minAngleDeg;
                z.geom = o.getSpecialZoneGeom();
                if (strategy.depthMode && o.rule.isLine() && o.rule.depth != null) {
                    Integer dnExisting = "heat_network".equals(o.type) && topo.segment(o.id) != null ? topo.segment(o.id).diameter : null;
                    int dnNew = ref.diameterForFlow(cp.flowTph).dn;
                    ru.intelligence.heatnet.depth.DepthRules.Crossing d = depthRules.decide(o.type, dnExisting, dnNew, ref.spec(dnNew).newCostPerM);
                    z.entryPenalty = d.position == null ? ref.tieIn.cost * 4 : d.extraCost;   // конфликт по глубине — сильный штраф, но не запрет
                }
                w.addZone(z);
            }
        }
        for (RasterWindow.Goal g : goals) w.addGoal(g, halfWidth);
        // цели не должны быть заблокированы буфером существующей сети
        for (int i = 0; i < w.cells(); i++) if (w.goalKind[i] > 0) w.blocked[i] = false;
        return w;
    }

    /** Переносит найденный путь в строящуюся сеть: узлы, рёбра, разрезы по спецзонам и по целям. */
    private void commit(ConnectionPoint cp, ExitFinder.Exit exit, RasterWindow w, AStarRouter.Result res, NetworkBuilder nb, PointResult pr, Diagnostics diag, Strategy strategy) {
        commitPath(cp, exit, w, PathSimplifier.simplify(w, res.cells, strategy.sharpTurnFactor <= 1),
                res.goalIndex, nb, pr, diag, strategy);
    }

    /** Общая часть переноса найденной ломаной в сеть — для обоих методов поиска. */
    private void commitPath(ConnectionPoint cp, ExitFinder.Exit exit, RasterWindow w, List<Coordinate> path,
                            int goalIndex, NetworkBuilder nb, PointResult pr, Diagnostics diag, Strategy strategy) {
        RasterWindow.Goal goal = w.goals.get(goalIndex);
        // точный конец: проекция последней клетки на геометрию цели
        Coordinate last = path.get(path.size() - 1);
        Coordinate exact = nearestOn(goal.geom, last);
        if (exact.distance(last) > 1e-6) path.set(path.size() - 1, exact);
        // полный путь: точка подключения → коридор выхода → путь
        List<Coordinate> full = new ArrayList<>();
        full.add(cp.geom.getCoordinate());
        if (exit.corridor.size() > 1 && exit.exitPoint.distance(path.get(0)) > 1e-6) full.add(exit.exitPoint);
        for (Coordinate c : path) if (full.get(full.size() - 1).distance(c) > 1e-6) full.add(c);
        full = mergeCollinear(full);
        // «шпильки» — микроизломы с поворотом круче 90° на стыке коридора выхода с растровым путём
        // и на проекции конца на цель; ТП §2.1 такие повороты запрещает
        full = mergeCollinear(despike(full, rr.despikeMaxM));
        if (strategy.freeAngle) full = mergeCollinear(PathSimplifier.pullStraight(w, full, 2));

        Node leaf = nb.addNode(NodeKind.CONNECTION_POINT, cp.geom.getCoordinate(), cp.id);
        Node end;
        switch (goal.kind) {
            case "existing_chamber": {
                ExistingChamber ch = topo.chamber(goal.objectId);
                end = nb.nodeNear(ch.geom.getCoordinate(), 0.5);
                if (end == null) {
                    end = nb.addNode(NodeKind.TIE_IN, ch.geom.getCoordinate(), null);
                    end.existingObjectId = ch.id; end.existingObjectType = "heat_chamber";
                }
                break;
            }
            case "existing_segment": {
                ExistingSegment s = topo.segment(goal.objectId);
                // правило 10 м: если рядом камера с запасом лучей — уходим в камеру
                ExistingChamber near = topo.nearestChamber(exact, ref.chambers.existingChamberTieInRadiusM);
                if (near != null && topo.chamberDegree(near) + countNewEdgesAt(nb, near.geom.getCoordinate()) + 1 <= ref.chambers.maxBranches) {
                    end = nb.nodeNear(near.geom.getCoordinate(), 0.5);
                    if (end == null) { end = nb.addNode(NodeKind.TIE_IN, near.geom.getCoordinate(), null); end.existingObjectId = near.id; end.existingObjectType = "heat_chamber"; }
                    full.set(full.size() - 1, near.geom.getCoordinate());
                } else {
                    end = nb.addNode(NodeKind.TIE_IN, exact, null);
                    end.existingObjectId = s.id; end.existingObjectType = "heat_network";
                    end.fractionAlong = GeoUtil.fractionAlong(s.geom, exact);
                }
                break;
            }
            case "new_chamber": {
                end = nb.nodes().get(goal.objectId);
                full.set(full.size() - 1, end.xy);
                break;
            }
            case "new_segment": {
                Edge e = findEdge(nb, goal.objectId);
                end = nb.splitEdge(e, exact, NodeKind.CHAMBER);
                full.set(full.size() - 1, end.xy);
                break;
            }
            default: throw new IllegalStateException("неизвестный вид цели " + goal.kind);
        }
        // Конец пути мог сместиться уже после упрощения (перенос в существующую камеру по правилу 10 м,
        // проекция на цель), поэтому нормализуем геометрию в последнюю очередь: снимаем шпильки и
        // срезаем оставшиеся повороты круче 90° — ТП §2.1 их запрещает.
        full = mergeCollinear(despike(full, rr.despikeMaxM));
        full = mergeCollinear(clampTurns(w, full, rr.maxTurnDeg));

        // разрезать путь по спецзонам: участки со special отдельными рёбрами (ТП §5, §8.1)
        List<List<Coordinate>> pieces = new ArrayList<>();
        List<String> types = new ArrayList<>();
        List<Double> ks = new ArrayList<>();
        splitBySpecialZones(w, full, pieces, types, ks);
        Node prev = leaf;
        for (int i = 0; i < pieces.size(); i++) {
            Node next = (i == pieces.size() - 1) ? end : nb.addNode(NodeKind.TECH_NODE, pieces.get(i).get(pieces.get(i).size() - 1), null);
            // ребро ориентируем «к источнику»: a = next (ближе к сети), b = prev
            List<Coordinate> rev = new ArrayList<>(pieces.get(i));
            java.util.Collections.reverse(rev);
            nb.addEdge(next, prev, rev, types.get(i), ks.get(i));
            prev = next;
        }
        pr.connected = true;
        pr.leaf = leaf;
    }

    private static Edge findEdge(NetworkBuilder nb, String id) {
        for (Edge e : nb.edges()) if (e.id.equals(id)) return e;
        throw new IllegalStateException("нет ребра " + id);
    }

    private static Coordinate nearestOn(Geometry g, Coordinate c) {
        Coordinate[] near = org.locationtech.jts.operation.distance.DistanceOp.nearestPoints(g, GeoUtil.point(c));
        return near[0];
    }

    /**
     * Приводит повороты к допустимым: вершина с изломом круче maxTurnDeg срезается фаской — вместо
     * одного запрещённого поворота получаются два разрешённых. Длина фаски берётся от более короткого
     * звена, срезка выполняется только если оба новых отрезка свободны. Концы трассы (точка подключения
     * и место присоединения) неподвижны.
     */
    static List<Coordinate> clampTurns(RasterWindow w, List<Coordinate> pts, double maxTurnDeg) {
        List<Coordinate> out = new ArrayList<>(pts);
        for (int pass = 0; pass < 4; pass++) {
            boolean changed = false;
            for (int i = 1; i + 1 < out.size(); i++) {
                Coordinate a = out.get(i - 1), b = out.get(i), c = out.get(i + 1);
                if (PathSimplifier.turnDeg(a, b, c) <= maxTurnDeg + 1e-6) continue;
                double la = a.distance(b), lc = b.distance(c);
                double maxCut = Math.min(Math.min(la, lc) * 0.5, 4.0);
                // фаска у самой границы буфера может не пройти по свободе — пробуем короче
                Coordinate p1 = null, p2 = null;
                for (double cut = maxCut; cut >= 0.2; cut /= 2) {
                    Coordinate q1 = along(b, a, cut), q2 = along(b, c, cut);
                    if (PathSimplifier.segmentFree(w, a, q1) && PathSimplifier.segmentFree(w, q1, q2)
                            && PathSimplifier.segmentFree(w, q2, c)) { p1 = q1; p2 = q2; break; }
                }
                if (p1 == null) continue;
                out.set(i, p1);
                out.add(i + 1, p2);
                changed = true;
                i++;
            }
            if (!changed) break;
        }
        return out;
    }

    /** Точка на отрезке from→to на расстоянии dist от from. */
    private static Coordinate along(Coordinate from, Coordinate to, double dist) {
        double len = from.distance(to);
        if (len < 1e-9) return new Coordinate(from);
        double t = dist / len;
        return new Coordinate(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t);
    }

    /**
     * Снимает «шпильки»: отрезки короче maxSpike, на концах которых трасса разворачивается круче 90°.
     * Внутренняя шпилька схлопывается в середину, у концов трассы — убирается лишняя вершина
     * (точка подключения и точка врезки неподвижны).
     */
    static List<Coordinate> despike(List<Coordinate> pts, double maxSpike) {
        List<Coordinate> out = new ArrayList<>(pts);
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 100 && out.size() > 2) {
            changed = false;
            for (int i = 0; i + 1 < out.size(); i++) {
                if (out.get(i).distance(out.get(i + 1)) > maxSpike) continue;
                boolean spike = (i > 0 && PathSimplifier.turnDeg(out.get(i - 1), out.get(i), out.get(i + 1)) > 90 + 1e-6)
                        || (i + 2 < out.size() && PathSimplifier.turnDeg(out.get(i), out.get(i + 1), out.get(i + 2)) > 90 + 1e-6);
                if (!spike) continue;
                if (i == 0) out.remove(1);
                else if (i + 1 == out.size() - 1) out.remove(i);
                else {
                    Coordinate a = out.get(i), b = out.get(i + 1);
                    out.set(i, new Coordinate((a.x + b.x) / 2, (a.y + b.y) / 2));
                    out.remove(i + 1);
                }
                changed = true;
                break;
            }
        }
        return out;
    }

    private static List<Coordinate> mergeCollinear(List<Coordinate> pts) {
        List<Coordinate> out = new ArrayList<>();
        for (Coordinate c : pts) {
            if (out.size() >= 2) {
                Coordinate a = out.get(out.size() - 2), b = out.get(out.size() - 1);
                double cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
                double dot = (b.x - a.x) * (c.x - b.x) + (b.y - a.y) * (c.y - b.y);
                if (Math.abs(cross) < 1e-6 && dot > 0) { out.set(out.size() - 1, c); continue; }
            }
            out.add(c);
        }
        return out;
    }

    /**
     * Делит ломаную на куски по принадлежности спецзонам растра: обычный / special(type, K).
     * Точки деления — пересечения с границами зон, найденные шагом по отрезку.
     */
    private void splitBySpecialZones(RasterWindow w, List<Coordinate> path, List<List<Coordinate>> pieces, List<String> types, List<Double> ks) {
        List<List<Coordinate>> raw = new ArrayList<>();
        List<Short> rawZone = new ArrayList<>();
        List<Coordinate> cur = new ArrayList<>();
        short curZone = -1;
        cur.add(path.get(0));
        for (int i = 1; i < path.size(); i++) {
            Coordinate a = path.get(i - 1), b = path.get(i);
            double len = a.distance(b);
            int n = Math.max(1, (int) Math.ceil(len / (w.step / 2)));
            for (int s = 1; s <= n; s++) {
                double t = (double) s / n;
                Coordinate q = new Coordinate(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t);
                short z = zoneAt(w, q);
                if (curZone == -1) curZone = z;
                if (z != curZone) {
                    cur.add(q);
                    raw.add(NetworkBuilder.dedup(cur)); rawZone.add(curZone);
                    cur = new ArrayList<>();
                    cur.add(q);
                    curZone = z;
                }
            }
            cur.add(b);
        }
        raw.add(NetworkBuilder.dedup(cur)); rawZone.add(curZone < 0 ? 0 : curZone);
        // очистка: куски короче 3/4 шага сетки или из одной точки сливаются с соседом (тип — от более длинного)
        List<List<Coordinate>> out = new ArrayList<>();
        List<Short> outZone = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            List<Coordinate> piece = raw.get(i);
            short z = rawZone.get(i);
            double len = piece.size() < 2 ? 0 : GeoUtil.line(piece).getLength();
            if (!out.isEmpty() && (len < w.step * 0.75 || outZone.get(outZone.size() - 1) == z)) {
                List<Coordinate> prev = out.get(out.size() - 1);
                List<Coordinate> merged = new ArrayList<>(prev);
                merged.addAll(piece);
                merged = NetworkBuilder.dedup(merged);
                double prevLen = prev.size() < 2 ? 0 : GeoUtil.line(prev).getLength();
                out.set(out.size() - 1, merged);
                if (len > prevLen && outZone.get(outZone.size() - 1) != z) outZone.set(outZone.size() - 1, z);
            } else {
                out.add(piece); outZone.add(z);
            }
        }
        // первый кусок мог оказаться коротким — слить со следующим
        if (out.size() > 1 && (out.get(0).size() < 2 || GeoUtil.line(out.get(0)).getLength() < w.step * 0.75)) {
            List<Coordinate> merged = new ArrayList<>(out.get(0)); merged.addAll(out.get(1));
            out.set(1, NetworkBuilder.dedup(merged)); out.remove(0); outZone.remove(0);
        }
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).size() < 2) continue;
            pieces.add(out.get(i));
            short z = outZone.get(i);
            types.add(z <= 0 ? null : w.zones.get(z - 1).type);
            ks.add(z <= 0 ? 1.0 : w.zones.get(z - 1).k);
        }
        if (pieces.isEmpty()) { pieces.add(NetworkBuilder.dedup(path)); types.add(null); ks.add(1.0); }
    }

    private static short zoneAt(RasterWindow w, Coordinate q) {
        int c = w.colOf(q.x, q.y), r = w.rowOf(q.x, q.y);
        if (!w.inside(c, r)) return 0;
        return w.zone[w.idx(c, r)];
    }

    public Point exitPointOf(ConnectionPoint cp) {
        return GeoUtil.point(new ExitFinder(field, clearance, rr.gridStepM).find(cp, buildings).exitPoint);
    }
}
