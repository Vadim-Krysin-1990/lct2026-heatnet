package ru.intelligence.heatnet.depth;

import ru.intelligence.heatnet.config.ReferenceRules;
import ru.intelligence.heatnet.config.RestrictionRules;

/**
 * Правила дополнительной задачи «трассировка с учётом глубины» (приложение к ТЗ, ТП §4.3, §6).
 * Глубина h — от условной поверхности Z=0 до верха расчётного габарита новой сети.
 */
public class DepthRules {

    /** Решение по одному пересечению линейной коммуникации. */
    public static class Crossing {
        public String type;
        /** above | below | null (конфликт) */
        public String position;
        public double depth;          // глубина полки, м
        public double rampLength;     // горизонтальная длина одного участка спуска/подъёма, м
        public double extraCost;      // добавочная стоимость профиля относительно прокладки на обычной глубине, руб.
        public double verticalClearance; // расчётный вертикальный просвет, м
        public String reason;
    }

    private final ReferenceRules ref;
    private final RestrictionRules rr;

    public DepthRules(ReferenceRules ref, RestrictionRules rr) {
        this.ref = ref;
        this.rr = rr;
    }

    public double normalDepth() { return ref.depth.normalTopDepthM; }

    /** Kгл = 1 + 0,1·(h − 3) при h > 3, иначе 1 (ТП §6.1). */
    public double kDepth(double h) {
        double free = ref.depth.freeDepthM;
        return h > free ? 1 + ref.depth.extraCostPerMDepth * (h - free) : 1.0;
    }

    /** Средний коэффициент для наклонного участка между глубинами a и b (линейная зависимость, ТП §6.1). */
    public double kDepthAvg(double a, double b) {
        return (kDepth(a) + kDepth(b)) / 2.0;
    }

    /** Верх и высота существующей коммуникации данного типа (ТП §4.3); для теплосети высота — по ДУ. */
    public double[] utilityTopAndHeight(String type, Integer existingDn) {
        ReferenceRules.Utility u = ref.existingUtilities.get(type);
        if (u == null) return null;
        double height = u.heightM != null ? u.heightM : (existingDn != null ? ref.spec(existingDn).heightM : 0.5);
        return new double[]{u.topDepthM, height};
    }

    /**
     * Выбор прохождения пересечения: выше или ниже существующей коммуникации, с минимальной стоимостью
     * (приложение к ТЗ, п. 4.3–4.4). newDn — ДУ участка новой сети, cnew — стоимость 1 м.
     */
    public Crossing decide(String type, Integer existingDn, int newDn, double cnew) {
        Crossing c = new Crossing();
        c.type = type;
        RestrictionRules.Rule rule = rr.resolve(type);
        double[] th = utilityTopAndHeight(type, existingDn);
        if (rule.depth == null || th == null) {
            c.position = "none"; c.depth = normalDepth(); c.reason = "правило по глубине для типа не задано";
            return c;
        }
        double top = th[0], height = th[1];
        double clearance = rule.depth.minVerticalClearanceM != null ? rule.depth.minVerticalClearanceM : 0.5;
        double hn = ref.spec(newDn).heightM;
        double normal = normalDepth();
        double minTop = ref.depth.minTopDepthM, maxTop = ref.depth.maxTopDepthM;
        double slope = ref.depth.maxSlope;
        double plateau = 2 * ref.depth.plateauHalfLengthM;
        c.verticalClearance = clearance;

        Double best = null;
        // сверху: низ новой сети не ниже (верх объекта − просвет)
        if (!"below".equalsIgnoreCase(rule.depth.position)) {
            double hAbove = top - clearance - hn;
            if (hAbove >= minTop - 1e-9) {
                double cost = profileExtraCost(hAbove, normal, cnew, slope, plateau);
                if (hAbove >= normal) { hAbove = normal; cost = 0; } // объект глубже — остаёмся на обычной глубине
                best = cost; c.position = "above"; c.depth = hAbove; c.extraCost = cost;
            }
        }
        // снизу: верх новой сети не выше (низ объекта + просвет)
        double hBelow = top + height + clearance;
        if (hBelow <= normal) hBelow = normal;
        if (hBelow <= maxTop + 1e-9) {
            double cost = profileExtraCost(hBelow, normal, cnew, slope, plateau);
            if (best == null || cost < best) { best = cost; c.position = "below"; c.depth = hBelow; c.extraCost = cost; }
        }
        if (best == null) {
            c.position = null; c.depth = normal;
            c.reason = "ни сверху (h=" + round(top - clearance - hn) + " < " + minTop + "), ни снизу (h=" + round(hBelow) + " > " + maxTop + ") пройти нельзя";
            return c;
        }
        c.rampLength = Math.abs(c.depth - normal) / slope;
        return c;
    }

    /** Добавочная стоимость профиля: удлинение на наклонах + надбавка Kгл ниже 3 м на полке и наклонах. */
    double profileExtraCost(double h, double normal, double cnew, double slope, double plateau) {
        double dh = Math.abs(h - normal);
        if (dh < 1e-9) return 0;
        double ramp = dh / slope;
        double incline = 2 * (Math.hypot(ramp, dh) - ramp) * cnew;
        double kPlateau = kDepth(h) - 1;
        double kRamp = kDepthAvg(normal, h) - 1;
        return incline + plateau * cnew * kPlateau + 2 * Math.hypot(ramp, dh) * cnew * kRamp;
    }

    private static double round(double v) { return Math.round(v * 100) / 100.0; }
}
