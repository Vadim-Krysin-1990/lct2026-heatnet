package ru.intelligence.heatnet.api;

import com.fasterxml.jackson.core.type.TypeReference;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.intelligence.heatnet.jobs.JobEntity;
import ru.intelligence.heatnet.jobs.JobService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Асинхронный контур: загрузка файла (до 3 ГБ, потоково на диск) → задание в очереди → статус → результат.
 */
@RestController
@RequestMapping("/api/jobs")
@Tag(name = "Задания", description = "Загрузка входного GeoJSON, очередь расчётов, статус и выгрузка результата")
public class JobController {

    private final JobService jobs;

    public JobController(JobService jobs) {
        this.jobs = jobs;
    }

    @Operation(summary = "Поставить расчёт в очередь (для больших файлов)",
            description = "Файл сохраняется на диск потоково (ТЗ 3.2, до 3 ГБ). Возвращает id задания.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> submit(
            @Parameter(description = "Входной файл GeoJSON (FeatureCollection, WGS 84)") @RequestParam("file") MultipartFile file,
            @Parameter(description = "Считать с учётом глубины заложения — отдельный набор вариантов "
                    + "с продольным профилем у пересечений подземных коммуникаций")
            @RequestParam(name = "depth", defaultValue = "false") boolean depth,
            @Parameter(description = "Сколько вариантов вернуть; 0 — по правилам приложения, до трёх")
            @RequestParam(name = "variants", defaultValue = "0") int variants) throws IOException {
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Пустой файл");
        JobEntity j;
        try (InputStream in = file.getInputStream()) {
            j = jobs.submit(in, file.getOriginalFilename(), Map.of("depth", depth, "variants", variants));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toMap(j));
    }

    @Operation(summary = "Список последних заданий",
            description = "Кто и что считал: имя файла, размер, статус, время расчёта, лучший вариант. "
                    + "Удобно, чтобы найти идентификатор задания, если он потерялся.")
    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JobEntity j : jobs.recent()) out.add(toMap(j));
        return out;
    }

    @Operation(summary = "Статус задания: готово или ещё считается",
            description = "Опрашивайте этот метод, пока status не станет DONE или FAILED. Значения: "
                    + "QUEUED — ждёт свободного расчётчика, RUNNING — считается, DONE — готово (result_url "
                    + "укажет, откуда забрать файл), FAILED — не получилось, причина в поле error и в диагностике. "
                    + "У готового задания видны время расчёта, число вариантов и показатели лучшего из них.")
    @GetMapping("/{id}")
    public Map<String, Object> status(@PathVariable String id) {
        return toMap(find(id));
    }

    @Operation(summary = "Забрать результат расчёта",
            description = "Выходной GeoJSON того же состава, что у синхронного метода: участки новой сети, "
                    + "тепловые камеры, технические узлы и сводка по каждому варианту. Доступен только когда "
                    + "задание завершилось успешно; отдаётся потоком, файл не собирается в памяти целиком.")
    @GetMapping(value = "/{id}/result", produces = "application/geo+json")
    public ResponseEntity<FileSystemResource> result(@PathVariable String id) {
        JobEntity j = find(id);
        if (!JobEntity.Status.DONE.name().equals(j.getStatus()) || j.getResultPath() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Задание в состоянии " + j.getStatus());
        }
        FileSystemResource res = new FileSystemResource(Paths.get(j.getResultPath()));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"result_" + id + ".geojson\"")
                .contentType(MediaType.parseMediaType("application/geo+json"))
                .body(res);
    }

    @Operation(summary = "Диагностика: что было не так с данными и как считалось",
            description = "Разбор входного файла и хода расчёта: пропущенные и восстановленные атрибуты, "
                    + "невалидная геометрия, какие объекты пропущены как лежащие вне области расчёта, "
                    + "выбранный азимут сетки, качество геометрии каждого варианта, проверка отступов по "
                    + "фактическим диаметрам, причины, по которым точка осталась без трассы. "
                    + "Уровни сообщений: ERROR — данные не приняты, WARNING — принято с оговоркой, INFO — "
                    + "пояснение к расчёту.")
    @GetMapping("/{id}/diagnostics")
    public Map<String, Object> diagnostics(@PathVariable String id) throws IOException {
        JobEntity j = find(id);
        if (j.getDiagnosticsJson() == null) return Map.of("status", j.getStatus(), "messages", List.of());
        return jobs.json().readValue(j.getDiagnosticsJson(), new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private JobEntity find(String id) {
        return jobs.get(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Нет задания " + id));
    }

    static Map<String, Object> toMap(JobEntity j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.getId());
        m.put("status", j.getStatus());
        m.put("original_filename", j.getOriginalFilename());
        m.put("input_size", j.getInputSize());
        m.put("created_at", j.getCreatedAt());
        m.put("started_at", j.getStartedAt());
        m.put("finished_at", j.getFinishedAt());
        m.put("compute_millis", j.getComputeMillis());
        m.put("variants_count", j.getVariantsCount());
        m.put("best_score", j.getBestScore());
        m.put("best_cost", j.getBestCost());
        m.put("unconnected_count", j.getUnconnectedCount());
        m.put("error", j.getErrorText());
        m.put("result_url", JobEntity.Status.DONE.name().equals(j.getStatus()) ? "/api/jobs/" + j.getId() + "/result" : null);
        m.put("diagnostics_url", "/api/jobs/" + j.getId() + "/diagnostics");
        boolean hasResult = j.getResultPath() != null && Files.exists(Paths.get(j.getResultPath()));
        m.put("result_available", hasResult);
        return m;
    }
}
