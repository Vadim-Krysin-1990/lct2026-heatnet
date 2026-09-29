package ru.intelligence.heatnet.hydraulics;

import ru.intelligence.heatnet.config.ReferenceRules;
import ru.intelligence.heatnet.geo.GeoUtil;
import ru.intelligence.heatnet.model.Diagnostics;
import ru.intelligence.heatnet.model.InputModel;
import ru.intelligence.heatnet.model.Variant;
import ru.intelligence.heatnet.network.NetworkTopology;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Влияние новых подключений на существующую сеть (ТЗ 2.11, раздел 4 п. 6): добавленный расход
 * распространяется от каждой врезки по цепочке участков к источнику, по суммарному расходу
 * определяется требуемый условный диаметр и отмечаются участки, которым текущего ДУ не хватает.
 *
 * Расчёт информационный: реконструкция существующей сети и камер в расчётной модели не выполняется
 * (Разъяснения от 21.09.2026, п. 14), поэтому в стоимость варианта и в ранжирование он не входит.
 */
public class ExistingLoadAnalyzer {

    private final ReferenceRules ref;

    public ExistingLoadAnalyzer(ReferenceRules ref) {
        this.ref = ref;
    }

    public void analyze(Variant v, NetworkTopology topo, Diagnostics diag) {
        v.existingImpact.clear();
        if (topo == null) return;
        // суммарный добавленный расход на каждом участке существующей сети и доля участка под нагрузкой
        Map<String, double[]> added = new LinkedHashMap<>();   // id → [расход т/ч, максимальная доля длины]
        Map<String, InputModel.ExistingSegment> segs = new LinkedHashMap<>();
        for (Variant.TieIn t : v.tieIns) {
            if (t.addedFlowTph <= 0) continue;
            List<NetworkTopology.ChainPart> chain;
            if (t.toExistingChamber) {
                InputModel.ExistingChamber c = topo.chamber(t.existingObjectId);
                chain = c == null ? new ArrayList<>() : topo.upstreamChainFromChamber(c);
            } else {
                InputModel.ExistingSegment s = topo.segment(t.existingObjectId);
                if (s == null) continue;
                double f = t.fractionAlong != null ? t.fractionAlong : 0.5;
                chain = topo.upstreamChain(s, f);
            }
            if (chain.isEmpty()) {
                diag.warn("EXISTING_LOAD_NO_CHAIN", "Вариант " + v.variantId + ": от врезки " + t.id
                        + " не удалось построить цепочку к источнику — влияние на существующую сеть не определено",
                        t.existingObjectId);
                continue;
            }
            for (NetworkTopology.ChainPart p : chain) {
                segs.put(p.seg.id, p.seg);
                double share = p.seg.length > 0 ? Math.min(1.0, p.length() / p.seg.length) : 1.0;
                double[] a = added.computeIfAbsent(p.seg.id, k -> new double[]{0, 0});
                a[0] += t.addedFlowTph;
                a[1] = Math.max(a[1], share);
            }
        }
        int upsize = 0;
        for (Map.Entry<String, double[]> e : added.entrySet()) {
            InputModel.ExistingSegment s = segs.get(e.getKey());
            Variant.ExistingImpact im = new Variant.ExistingImpact();
            im.segmentId = s.id;
            im.currentDiameter = s.diameter;
            im.currentFlowTph = GeoUtil.round(s.flowOrZero(), 3);
            im.flowKnown = s.flowTph != null;
            im.addedFlowTph = GeoUtil.round(e.getValue()[0], 3);
            im.totalFlowTph = GeoUtil.round(s.flowOrZero() + e.getValue()[0], 3);
            im.loadedShare = GeoUtil.round(e.getValue()[1], 3);
            im.requiredDiameter = ref.diameterForFlow(im.totalFlowTph).dn;
            im.needsUpsize = im.requiredDiameter > im.currentDiameter;
            if (im.needsUpsize) upsize++;
            v.existingImpact.add(im);
        }
        if (!v.existingImpact.isEmpty()) {
            boolean anyKnown = v.existingImpact.stream().anyMatch(i -> i.flowKnown);
            diag.info("EXISTING_LOAD", "Вариант " + v.variantId + ": новый расход распространён по "
                    + v.existingImpact.size() + " участкам существующей сети к источнику, требуют увеличения ДУ: "
                    + upsize + (anyKnown ? "" : " (текущий расход существующей сети во входных данных не задан, принят 0)"), null);
        }
    }
}
