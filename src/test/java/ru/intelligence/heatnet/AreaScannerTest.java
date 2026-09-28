package ru.intelligence.heatnet;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import ru.intelligence.heatnet.ingest.AreaScanner;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Область расчёта по опорным объектам: точки присоединения, сеть, камеры, источник. */
class AreaScannerTest {

    @Test
    void findsAreaOfContestDataset() throws Exception {
        Envelope env;
        try (InputStream in = getClass().getResourceAsStream("/contest_dataset.geojson")) {
            assertNotNull(in);
            env = AreaScanner.scan(in, 0.02);
        }
        assertNotNull(env, "область не определена");
        // конкурсный набор — район ТЭЦ «ЗИЛ»: примерно 37,63…37,65 в. д., 55,69…55,71 с. ш.
        assertTrue(env.getMinX() > 37.5 && env.getMaxX() < 37.8, "долгота вне ожидаемого диапазона: " + env);
        assertTrue(env.getMinY() > 55.5 && env.getMaxY() < 55.9, "широта вне ожидаемого диапазона: " + env);
        // запас 0,02° с каждой стороны добавлен
        assertTrue(env.getWidth() > 0.04 && env.getWidth() < 0.2, "ширина области: " + env.getWidth());
        System.out.println("Область расчёта: " + env + ", ширина " + String.format("%.4f", env.getWidth()) + "°");
    }
}
