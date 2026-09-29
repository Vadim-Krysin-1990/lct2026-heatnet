package ru.intelligence.heatnet.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Машиночитаемое описание выходного файла. Позволяет проверить результат независимо от сервиса:
 * любой валидатор JSON Schema Draft 2020-12 скажет, соблюдён ли обязательный состав раздела 7.
 */
@RestController
@RequestMapping("/api/schema")
@Tag(name = "Схема результата", description = "JSON Schema выходного GeoJSON для независимой проверки")
public class SchemaController {

    @Operation(summary = "JSON Schema выходного файла (Draft 2020-12)",
            description = "Проверка результата без обращения к сервису: "
                    + "`check-jsonschema --schemafile result.schema.json result.geojson` или любой другой "
                    + "валидатор. Схема описывает обязательный состав раздела 7 технического приложения: "
                    + "типы объектов, геометрию и обязательные атрибуты. Дополнительные свойства разрешены — "
                    + "раздел 7 их допускает, и сервис пишет пояснения к вариантам, время по этапам, влияние "
                    + "на существующую сеть и пересечения по глубине.")
    @GetMapping(value = "/result", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Resource> resultSchema() {
        ClassPathResource r = new ClassPathResource("schema/result.schema.json");
        if (!r.exists()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "схема не найдена в сборке");
        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=\"result.schema.json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(r);
    }
}
