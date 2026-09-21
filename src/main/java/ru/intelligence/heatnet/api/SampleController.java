package ru.intelligence.heatnet.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Образцы для демонстрации: конкурсный набор и результат его расчёта.
 * Раздаются сервисом, а не веб-сервером, чтобы на них действовали те же правила доступа,
 * что и на остальные данные: при включённой проверке токенов без входа их не получить.
 */
@RestController
@RequestMapping("/api/samples")
@Tag(name = "Образцы", description = "Конкурсный набор и результат расчёта для просмотрщика")
public class SampleController {

    private static final Map<String, String> FILES = new LinkedHashMap<>();
    static {
        FILES.put("dataset", "contest_dataset.geojson");
        FILES.put("result", "contest_result.geojson");
    }

    @Value("${heatnet.samples-dir:data/samples}")
    private String samplesDir;

    @Operation(summary = "Список доступных образцов")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> list() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : FILES.entrySet()) {
            Path p = Paths.get(samplesDir, e.getValue());
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("file", e.getValue());
            one.put("available", Files.isReadable(p));
            one.put("url", "/api/samples/" + e.getKey());
            m.put(e.getKey(), one);
        }
        return m;
    }

    @Operation(summary = "Образец: dataset — конкурсный набор, result — результат его расчёта")
    @GetMapping(path = "/{name}", produces = "application/geo+json")
    public ResponseEntity<Resource> sample(@PathVariable String name) {
        String file = FILES.get(name);
        if (file == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Неизвестный образец: " + name);
        Path p = Paths.get(samplesDir, file);
        if (!Files.isReadable(p)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Образец не найден: " + file);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType("application/geo+json"))
                .body(new FileSystemResource(p));
    }
}
