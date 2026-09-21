package ru.intelligence.heatnet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.intelligence.heatnet.config.RulesService;
import ru.intelligence.heatnet.export.GeoJsonWriter;
import ru.intelligence.heatnet.ingest.InputLoader;
import ru.intelligence.heatnet.model.InputModel;
import ru.intelligence.heatnet.planner.VariantPlanner;

import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Проверка потоковой обработки большого файла: mvn test -Dtest=LargeFileTest -Dheatnet.large.file=/path/large.geojson -DargLine=-Xmx1200m */
class LargeFileTest {

    @Test
    @EnabledIfSystemProperty(named = "heatnet.large.file", matches = ".+")
    void processesLargeFile() throws Exception {
        Path file = Paths.get(System.getProperty("heatnet.large.file"));
        long size = Files.size(file);
        Runtime rt = Runtime.getRuntime();
        long t0 = System.currentTimeMillis();
        RulesService rules = RulesService.standalone();
        InputLoader loader = new InputLoader(rules);
        InputModel m;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) { m = loader.load(in); }
        long t1 = System.currentTimeMillis();
        System.gc();
        long usedAfterLoad = rt.totalMemory() - rt.freeMemory();
        assertFalse(m.diagnostics.hasErrors(), m.diagnostics.messages.toString());
        VariantPlanner.Outcome out = new VariantPlanner(rules).plan(m);
        long t2 = System.currentTimeMillis();
        long usedAfterPlan = rt.totalMemory() - rt.freeMemory();
        try (FileOutputStream fos = new FileOutputStream("target/large_result.geojson")) { new GeoJsonWriter(loader.crs(), m.numericIds).write(out.variants, fos); }
        long t3 = System.currentTimeMillis();
        System.out.printf("LARGE: файл %.1f МБ, объектов %d, ограничений %d; загрузка %d мс, расчёт %d мс (вариантов %d), выгрузка %d мс; память после загрузки %d МБ, после расчёта %d МБ, maxHeap %d МБ%n",
                size / 1e6, m.totalFeatures, m.restrictions.size(), t1 - t0, t2 - t1, out.variants.size(), t3 - t2, usedAfterLoad >> 20, usedAfterPlan >> 20, rt.maxMemory() >> 20);
    }
}
