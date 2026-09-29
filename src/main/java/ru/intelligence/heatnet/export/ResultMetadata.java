package ru.intelligence.heatnet.export;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Прослеживаемость результата: чем, из чего и когда он получен. Кладётся в корень выходного GeoJSON
 * отдельным объектом metadata — обязательный состав раздела 7 от этого не меняется, а проверяющий
 * видит, какому входному файлу и какой редакции правил соответствует выдача.
 */
public final class ResultMetadata {

    private ResultMetadata() {
    }

    public static Map<String, Object> of(String inputSha256, int totalFeatures, long computeMillis,
                                         boolean depthMode, String methods) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("service", "ЦАТ — цифровой аналитик трасс подключения к тепловым сетям");
        m.put("service_version", version());
        m.put("appendix_revision", "техническое приложение от 21.09.2026");
        m.put("generated_at", Instant.now().toString());
        if (inputSha256 != null) m.put("input_sha256", inputSha256);
        if (totalFeatures > 0) m.put("input_features", totalFeatures);
        m.put("compute_millis", computeMillis);
        m.put("depth_mode", depthMode);
        m.put("routing_methods", methods == null || methods.isEmpty() ? "grid" : methods);
        m.put("crs_input", "EPSG:4326");
        m.put("crs_calculation", "EPSG:32637");
        return m;
    }

    private static String version() {
        String v = ResultMetadata.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }
}
