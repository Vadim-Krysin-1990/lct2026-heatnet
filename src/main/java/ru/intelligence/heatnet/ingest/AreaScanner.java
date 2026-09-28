package ru.intelligence.heatnet.ingest;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.locationtech.jts.geom.Envelope;

import java.io.IOException;
import java.io.InputStream;

/**
 * Первый проход по входному файлу: быстро находит область расчёта — охват точек присоединения,
 * существующей сети, камер и источника. Геометрия не строится, координаты читаются потоково
 * как числа, поэтому проход по файлу на 3 ГБ стоит секунды и почти не требует памяти.
 *
 * Область нужна, чтобы во втором проходе отбросить ограничения, до которых трасса заведомо
 * не дотянется: в наборах масштаба города их миллионы, и только они и не помещаются в память.
 */
public final class AreaScanner {

    /** Типы объектов, по которым определяется область расчёта. */
    private static final java.util.Set<String> ANCHORS = java.util.Set.of(
            "oks_connection_point", "heat_network", "heat_chamber", "source");

    private AreaScanner() {}

    /**
     * @param marginDeg запас вокруг найденной области, в градусах (0,02° ≈ 2,2 км по широте Москвы)
     * @return область в WGS 84 или null, если опорных объектов в файле нет
     */
    public static Envelope scan(InputStream in, double marginDeg) throws IOException {
        Envelope env = new Envelope();
        JsonFactory f = new JsonFactory();
        try (JsonParser p = f.createParser(in)) {
            // доходим до массива features верхнего уровня: объекты разбираем только внутри него,
            // иначе первый же START_OBJECT — это сам FeatureCollection, и разбор охватит весь файл
            if (!seekFeatures(p)) return null;
            while (p.nextToken() == JsonToken.START_OBJECT) {
                String objectType = null;
                Envelope local = new Envelope();
                int depth = 1;
                while (depth > 0 && p.nextToken() != null) {
                    JsonToken t = p.currentToken();
                    if (t == JsonToken.START_OBJECT) depth++;
                    else if (t == JsonToken.END_OBJECT) depth--;
                    else if (t == JsonToken.FIELD_NAME) {
                        String name = p.currentName();
                        if ("object_type".equals(name)) {
                            p.nextToken();
                            objectType = p.getValueAsString();
                        } else if ("coordinates".equals(name)) {
                            p.nextToken();
                            readCoords(p, local);
                        }
                    }
                }
                if (objectType != null && ANCHORS.contains(objectType) && !local.isNull()) {
                    env.expandToInclude(local);
                }
            }
        }
        if (env.isNull()) return null;
        env.expandBy(marginDeg);
        return env;
    }

    /** Перематывает поток до начала массива features. */
    private static boolean seekFeatures(JsonParser p) throws IOException {
        while (p.nextToken() != null) {
            if (p.currentToken() == JsonToken.FIELD_NAME && "features".equals(p.currentName())) {
                return p.nextToken() == JsonToken.START_ARRAY;
            }
        }
        return false;
    }

    /** Рекурсивно читает массив координат любой вложенности, накапливая охват. */
    private static void readCoords(JsonParser p, Envelope env) throws IOException {
        if (p.currentToken() != JsonToken.START_ARRAY) return;
        // если первый элемент — число, это пара [lon, lat]
        JsonToken t = p.nextToken();
        if (t == JsonToken.VALUE_NUMBER_FLOAT || t == JsonToken.VALUE_NUMBER_INT) {
            double x = p.getDoubleValue();
            t = p.nextToken();
            if (t == JsonToken.VALUE_NUMBER_FLOAT || t == JsonToken.VALUE_NUMBER_INT) {
                env.expandToInclude(x, p.getDoubleValue());
            }
            while (p.currentToken() != JsonToken.END_ARRAY && p.nextToken() != null) { /* высота и прочее */ }
            return;
        }
        while (t != null && t != JsonToken.END_ARRAY) {
            if (t == JsonToken.START_ARRAY) readCoords(p, env);
            t = p.nextToken();
        }
    }
}
