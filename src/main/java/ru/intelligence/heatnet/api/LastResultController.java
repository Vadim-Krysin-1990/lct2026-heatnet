package ru.intelligence.heatnet.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.intelligence.heatnet.jobs.JobService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Последний выполненный расчёт. Просмотрщик открывается на нём, поэтому после перезагрузки
 * страницы виден результат, который считали последним, а не вшитый в стенд образец.
 */
@RestController
@RequestMapping("/api/result/last")
@Tag(name = "Последний расчёт", description = "Что считали последним: результат, объекты области расчёта и описание")
public class LastResultController {

    private final JobService jobs;

    public LastResultController(JobService jobs) {
        this.jobs = jobs;
    }

    @Operation(summary = "Описание последнего расчёта",
            description = "Имя входного файла, размер, число объектов, варианты, время расчёта, режим и методы. "
                    + "404 — расчётов на этом сервисе ещё не было.")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> info() {
        Map<String, Object> m = jobs.lastInfo();
        if (m == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "расчётов ещё не было");
        return m;
    }

    @Operation(summary = "Результат последнего расчёта",
            description = "Тот же GeoJSON, что вернул расчёт: участки новой сети, камеры, узлы и сводки вариантов.")
    @GetMapping(value = "/result", produces = "application/geo+json")
    public ResponseEntity<FileSystemResource> result() {
        return file("result.geojson", "last_result.geojson");
    }

    @Operation(summary = "Входные объекты области последнего расчёта",
            description = "Источник, существующая сеть и камеры, точки присоединения и ограничения — то, "
                    + "по чему просмотрщик рисует карту загруженных данных.")
    @GetMapping(value = "/input-area", produces = "application/geo+json")
    public ResponseEntity<FileSystemResource> inputArea() {
        return file("input-area.geojson", "last_input_area.geojson");
    }

    private ResponseEntity<FileSystemResource> file(String name, String download) {
        Path p = jobs.lastPath(name);
        if (!Files.isReadable(p)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "нет файла " + name);
        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=\"" + download + "\"")
                .header("Cache-Control", "no-store")
                .contentType(MediaType.parseMediaType("application/geo+json"))
                .body(new FileSystemResource(p));
    }
}
