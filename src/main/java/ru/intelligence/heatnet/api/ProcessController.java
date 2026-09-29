package ru.intelligence.heatnet.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ru.intelligence.heatnet.events.EventService;
import ru.intelligence.heatnet.export.GeoJsonWriter;
import ru.intelligence.heatnet.jobs.JobService;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Синхронный контур для проверки: файл → сразу выходной GeoJSON в ответе (ТЗ раздел 4, шаги 1–3).
 * Для больших наборов используйте /api/jobs.
 */
@RestController
@RequestMapping("/api/process")
@Tag(name = "Расчёт", description = "Синхронный расчёт: входной GeoJSON → выходной GeoJSON одним запросом")
public class ProcessController {

    private final JobService jobs;
    private final EventService events;

    public ProcessController(JobService jobs, EventService events) {
        this.jobs = jobs;
        this.events = events;
    }

    @Operation(summary = "Рассчитать варианты подключения: файл на входе — файл на выходе",
            description = "Основной метод сервиса. Принимает один GeoJSON конкурсной структуры и за один запуск "
                    + "обрабатывает все точки присоединения, которые в нём есть: подбирает места присоединения, "
                    + "прокладывает трассы в обход ограничений, собирает их в общую сеть с камерами в точках "
                    + "ветвления, считает расходы и диаметры с проверкой предельной длины, ставит технические "
                    + "узлы, оценивает стоимость и ранжирует варианты по показателю S.\n\n"
                    + "**Что вернётся.** FeatureCollection в WGS 84 по разделу 7 технического приложения: "
                    + "участки новой сети (heat_network), тепловые камеры (heat_chamber), технические узлы "
                    + "(technical_node) и сводка по каждому варианту (variant_summary). У сводки есть "
                    + "дополнительные поля variant_name и variant_description — они поясняют словами, чем "
                    + "вариант отличается от остальных.\n\n"
                    + "**Сколько ждать.** Конкурсный набор (17 точек, 88 ограничений) — около 10 секунд на шести "
                    + "ядрах. Для файлов от сотен мегабайт используйте POST /api/jobs: этот метод держит "
                    + "соединение открытым до конца расчёта.\n\n"
                    + "**Если входные данные негодные** — ответ 422 с перечнем ошибок, а не пятисотая: в теле "
                    + "указано, какой объект и чем не подошёл.\n\n"
                    + "**Заголовки ответа:** X-Compute-Millis — время расчёта, X-Variants — сколько вариантов "
                    + "в файле, X-Depth-Mode — считалось ли с глубиной, X-Diagnostics-Warnings — число "
                    + "предупреждений, X-Request-Id — сквозной идентификатор запроса для сопоставления с журналом.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "application/geo+json")
    public ResponseEntity<StreamingResponseBody> process(
            @Parameter(description = "Входной GeoJSON: существующая сеть, камеры, источник, точки присоединения "
                    + "перспективных объектов и пространственные ограничения. Кодировка UTF-8, координаты в WGS 84")
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "Дополнительная задача: считать с учётом глубины заложения. Трасса идёт "
                    + "на обычной отметке 3 м, а у пересечений с подземными коммуникациями проходит выше или ниже "
                    + "— что дешевле — с полкой постоянной глубины и уклоном не круче 0,10. В выходе заполняются "
                    + "depth_start и depth_end, добавляются технические узлы в вершинах профиля. Результат "
                    + "не смешивается с плоским расчётом: это отдельный набор вариантов")
            @RequestParam(name = "depth", defaultValue = "false") boolean depth,
            @Parameter(description = "Сколько вариантов вернуть. 0 — по правилам приложения, то есть до трёх. "
                    + "Сервис всегда считает семь стратегий (инженерные вдоль застройки, кратчайшие со спрямлением "
                    + "и контрольные); большее значение отдаёт их все — удобно, чтобы сравнить подходы на демонстрации")
            @RequestParam(name = "variants", defaultValue = "0") int variants,
            @Parameter(description = "Какой метод поиска считать: grid — по растровой сетке, быстро (по умолчанию); "
                    + "visibility — по графу видимости, точнее по геометрии, но в разы дольше; all — оба и сравнение")
            @RequestParam(name = "methods", defaultValue = "grid") String methods) throws IOException {
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Пустой файл");
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("file", file.getOriginalFilename());
        ev.put("size_bytes", file.getSize());
        ev.put("depth", depth);
        ev.put("variants_requested", variants);
        ev.put("methods", methods);
        events.info("PROCESS_START", "Принят файл на синхронный расчёт: " + file.getOriginalFilename(), ev);
        // контрольная сумма входа — отдельным проходом: по ней в результате видно, из какого файла он получен
        String sha;
        try (InputStream in = file.getInputStream()) {
            sha = JobService.sha256(in);
        }
        JobService.SyncResult r;
        try (InputStream in = file.getInputStream()) {
            r = jobs.processSync(in, depth, variants, methods);
        }
        r.inputSha256 = sha;
        if (r.model.diagnostics.hasErrors()) {
            Map<String, Object> bad = new LinkedHashMap<>(ev);
            bad.put("errors", r.model.diagnostics.count(ru.intelligence.heatnet.model.Diagnostics.Level.ERROR));
            events.error("VALIDATION_FAILED", "Входные данные не прошли проверку: " + file.getOriginalFilename(), bad);
            throw new InputValidationException(JobService.diagnosticsMap(r.model.diagnostics));
        }
        Map<String, Object> done = new LinkedHashMap<>(ev);
        done.put("variants", r.variants.size());
        done.put("features_in", r.model.totalFeatures);
        done.put("connection_points", r.model.points.size());
        if (!r.variants.isEmpty()) {
            done.put("best_variant", r.variants.get(0).name);
            done.put("best_cost", Math.round(r.variants.get(0).calculatedCost));
            done.put("best_length_m", Math.round(r.variants.get(0).newNetworkLength));
            done.put("best_score", r.variants.get(0).score);
        }
        events.write(ru.intelligence.heatnet.events.EventEntity.Level.INFO, "PROCESS_DONE",
                "Расчёт выполнен: вариантов " + r.variants.size() + ", " + r.millis + " мс", done, null, r.millis);
        GeoJsonWriter writer = new GeoJsonWriter(r.loader.crs(), r.model.numericIds)
                .metadata(ru.intelligence.heatnet.export.ResultMetadata.of(r.inputSha256, r.model.totalFeatures,
                        r.millis, depth, methods));
        // расчёт запоминается как последний: просмотрщик после перезагрузки страницы открывает его,
        // а не образец — видно то, что считали, а не то, что было вшито в стенд
        byte[] resultBytes = null;
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(1 << 20);
            writer.write(r.variants, buf);
            resultBytes = buf.toByteArray();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("source", "синхронный расчёт");
            info.put("file", file.getOriginalFilename());
            info.put("size_bytes", file.getSize());
            info.put("input_features", r.model.totalFeatures);
            info.put("variants", r.variants.size());
            info.put("compute_millis", r.millis);
            info.put("depth_mode", depth);
            info.put("methods", methods);
            info.put("finished_at", java.time.Instant.now().toString());
            jobs.rememberSyncResult(resultBytes, r, info);
        } catch (Exception e) {
            resultBytes = null;                       // не удалось — отдаём потоком, как раньше
        }
        final byte[] ready = resultBytes;
        StreamingResponseBody body = ready != null ? out -> out.write(ready) : out -> writer.write(r.variants, out);
        Map<String, Object> summary = new LinkedHashMap<>();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"result.geojson\"")
                .header("X-Compute-Millis", String.valueOf(r.millis))
                .header("X-Variants", String.valueOf(r.variants.size()))
                .header("X-Depth-Mode", String.valueOf(depth))
                .header("X-Diagnostics-Warnings", String.valueOf(r.model.diagnostics.count(ru.intelligence.heatnet.model.Diagnostics.Level.WARNING)))
                .contentType(MediaType.parseMediaType("application/geo+json"))
                .body(body);
    }

    @Operation(summary = "Подсказка по использованию расчёта",
            description = "Расчёт выполняется методом POST с файлом; GET отвечает короткой инструкцией, чтобы ссылка, открытая в браузере, не приводила к странице ошибки.")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> usage() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("service", "heatnet — сервис моделирования трасс подключения к тепловым сетям");
        m.put("method", "POST /api/process — форма multipart/form-data, поле file: входной GeoJSON");
        m.put("example", "curl -F \"file=@contest_dataset.geojson\" http://<host>/api/process -o result.geojson");
        m.put("depth_mode", "POST /api/process?depth=true — дополнительная задача: трассировка с учётом глубины");
        m.put("variants", "POST /api/process?variants=7 — показать все рассчитанные стратегии (в конкурсной выдаче по умолчанию три)");
        m.put("large_files", "POST /api/jobs — очередь для файлов до 3 ГБ, затем GET /api/jobs/{id}/result");
        m.put("docs", "/swagger-ui.html");
        m.put("rules", "/api/rules/reference, /api/rules/restrictions, /api/rules/routing");
        return m;
    }

    /** 422 с телом-диагностикой. */
    public static class InputValidationException extends RuntimeException {
        public final Map<String, Object> diagnostics;
        public InputValidationException(Map<String, Object> diagnostics) {
            super("Входные данные не прошли проверку");
            this.diagnostics = diagnostics;
        }
    }
}
