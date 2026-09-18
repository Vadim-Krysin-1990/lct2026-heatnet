package ru.intelligence.heatnet.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Правила учёта пространственных ограничений (rules/restrictions.yml, ТП табл. 5.1). */
public class RestrictionRules {

    public static class ClearanceByDn {
        public int maxDn;
        public double m;
    }

    public static class DepthRule {
        /** below — только под объектом; any — выше или ниже. */
        public String position = "any";
        public Double minTopDepthM;
        public Double minVerticalClearanceM;
    }

    public static class Rule {
        public String title;
        public String aliasOf;
        /** forbidden | special */
        public String rule = "forbidden";
        /** polygon | line */
        public String geometry = "polygon";
        public Double clearanceM;
        public List<ClearanceByDn> clearanceByDn = new ArrayList<>();
        public Double minCrossingAngleDeg;
        public double zoneMarginM = 0;
        public double kSpecial = 1.0;
        public DepthRule depth;
        public String source;

        public boolean isSpecial() { return "special".equalsIgnoreCase(rule); }
        public boolean isLine() { return "line".equalsIgnoreCase(geometry); }

        /** Минимальное горизонтальное расстояние для ДУ новой сети. */
        public double clearance(int dn, double fallback) {
            if (!clearanceByDn.isEmpty()) {
                for (ClearanceByDn c : clearanceByDn) if (dn <= c.maxDn) return c.m;
                return clearanceByDn.get(clearanceByDn.size() - 1).m;
            }
            return clearanceM != null ? clearanceM : fallback;
        }
    }

    public String defaultRule = "forbidden";
    public double defaultClearanceM = 1.0;
    public Map<String, Rule> types = new LinkedHashMap<>();

    /** Правило по типу с разрешением alias_of; для неизвестного типа — правило по умолчанию. */
    public Rule resolve(String type) {
        Rule r = types.get(type);
        int guard = 0;
        while (r != null && r.aliasOf != null && guard++ < 5) r = types.get(r.aliasOf);
        if (r == null) {
            Rule d = new Rule();
            d.title = "неизвестный тип: " + type;
            d.rule = defaultRule;
            d.clearanceM = defaultClearanceM;
            d.source = "правило по умолчанию";
            return d;
        }
        return r;
    }

    public boolean isKnown(String type) {
        return types.containsKey(type);
    }
}
