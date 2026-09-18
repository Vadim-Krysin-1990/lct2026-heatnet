package ru.intelligence.heatnet.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.intelligence.heatnet.config.RulesService;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Просмотр действующих правил (справочники ТП, ограничения, параметры трассировки) и их перечитывание. */
@RestController
@RequestMapping("/api/rules")
@Tag(name = "Правила", description = "Справочники техприложения и правила ограничений — редактируются в YAML без перекомпиляции")
public class RulesController {

    private final RulesService rules;

    public RulesController(RulesService rules) {
        this.rules = rules;
    }

    @Operation(summary = "Справочник расчёта (табл. 4.1, 4.2, 8.2, 8.3, 9 ТП)")
    @GetMapping("/reference")
    public Object reference() { return rules.reference(); }

    @Operation(summary = "Правила пространственных ограничений (табл. 5.1 ТП)")
    @GetMapping("/restrictions")
    public Object restrictions() { return rules.restrictions(); }

    @Operation(summary = "Параметры трассировки")
    @GetMapping("/routing")
    public Object routing() { return rules.routing(); }

    @Operation(summary = "Исходный YAML правил")
    @GetMapping(value = "/yaml/{name}", produces = MediaType.TEXT_PLAIN_VALUE)
    public String yaml(@org.springframework.web.bind.annotation.PathVariable String name) throws IOException {
        if (!name.matches("reference|restrictions|routing")) throw new IllegalArgumentException("name ∈ reference|restrictions|routing");
        return rules.rawYaml(name + ".yml");
    }

    @Operation(summary = "Перечитать правила из каталога HEATNET_RULES / classpath")
    @PostMapping("/reload")
    public Map<String, Object> reload() {
        rules.reload();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("diameters", rules.reference().diameters.size());
        m.put("restriction_types", rules.restrictions().types.size());
        m.put("grid_step_m", rules.routing().gridStepM);
        return m;
    }
}
