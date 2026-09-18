package ru.intelligence.heatnet.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
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

    public ProcessController(JobService jobs) {
        this.jobs = jobs;
    }

    @Operation(summary = "Рассчитать варианты подключения и вернуть выходной GeoJSON",
            description = "Все перспективные ОКС обрабатываются за один запуск. Ответ — FeatureCollection по ТП §10; "
                    + "при ошибках входных данных — 422 с диагностикой. Время расчёта — в заголовке X-Compute-Millis.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = "application/geo+json")
    public ResponseEntity<StreamingResponseBody> process(
            @Parameter(description = "Входной файл GeoJSON") @RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Пустой файл");
        JobService.SyncResult r;
        try (InputStream in = file.getInputStream()) {
            r = jobs.processSync(in);
        }
        if (r.model.diagnostics.hasErrors()) {
            throw new InputValidationException(JobService.diagnosticsMap(r.model.diagnostics));
        }
        GeoJsonWriter writer = new GeoJsonWriter(r.loader.crs());
        StreamingResponseBody body = out -> writer.write(r.variants, out);
        Map<String, Object> summary = new LinkedHashMap<>();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"result.geojson\"")
                .header("X-Compute-Millis", String.valueOf(r.millis))
                .header("X-Variants", String.valueOf(r.variants.size()))
                .header("X-Diagnostics-Warnings", String.valueOf(r.model.diagnostics.count(ru.intelligence.heatnet.model.Diagnostics.Level.WARNING)))
                .contentType(MediaType.parseMediaType("application/geo+json"))
                .body(body);
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
