package ru.intelligence.heatnet.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.intelligence.heatnet.config.RulesService;
import ru.intelligence.heatnet.events.EventService;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Просмотр действующих правил (справочники ТП, ограничения, параметры трассировки) и их перечитывание. */
@RestController
@RequestMapping("/api/rules")
@Tag(name = "Правила", description = "Справочники техприложения и правила ограничений — редактируются в YAML без перекомпиляции")
public class RulesController {

    private final RulesService rules;
    private final EventService events;

    public RulesController(RulesService rules, EventService events) {
        this.rules = rules;
        this.events = events;
    }

    @Operation(summary = "Справочник расчёта: диаметры, цены, камеры, веса ранжирования",
            description = "Таблица 1 технического приложения и связанные параметры: для каждого условного "
                    + "диаметра — пропускная способность в т/ч, предельная длина непрерывного участка, цена метра "
                    + "нового строительства и расчётные габариты пары труб. Дальше — стоимость камер и врезки, "
                    + "штраф за неподключённую точку, веса показателя S и параметры расчёта глубины. "
                    + "Редактируется в файле rules/reference.yml без пересборки сервиса.")
    @GetMapping("/reference")
    public Object reference() { return rules.reference(); }

    @Operation(summary = "Правила ограничений: от чего и на сколько отступать",
            description = "Таблица 2 технического приложения по типам городских объектов. Для каждого типа: "
                    + "запрет прохода или специальный проход, минимальное расстояние в метрах, ширина зоны "
                    + "специального участка, минимальный угол пересечения и коэффициент удорожания. "
                    + "У каждого правила указан источник — пункт приложения или свода правил, чтобы эксперт "
                    + "видел, откуда взято число. Редактируется в rules/restrictions.yml.")
    @GetMapping("/restrictions")
    public Object restrictions() { return rules.restrictions(); }

    @Operation(summary = "Параметры трассировки: шаг сетки, штрафы, стратегии",
            description = "Настройки алгоритма, не являющиеся нормативными: шаг растровой сетки поиска, "
                    + "штрафы за поворот, предельный угол поворота, радиус поиска мест присоединения, "
                    + "число вариантов в выдаче и перечень стратегий. Ими настраивается качество трассы: "
                    + "чем больше штраф за поворот, тем длиннее прямые участки. Файл rules/routing.yml.")
    @GetMapping("/routing")
    public Object routing() { return rules.routing(); }

    @Operation(summary = "Исходный YAML правил как текст",
            description = "Отдаёт файл правил в том виде, в каком его читает сервис, — вместе с комментариями "
                    + "и ссылками на источники. Имена: reference, restrictions, routing.")
    @GetMapping(value = "/yaml/{name}", produces = MediaType.TEXT_PLAIN_VALUE)
    public String yaml(@org.springframework.web.bind.annotation.PathVariable String name) throws IOException {
        if (!name.matches("reference|restrictions|routing")) throw new IllegalArgumentException("name ∈ reference|restrictions|routing");
        return rules.rawYaml(name + ".yml");
    }

    @Operation(summary = "Перечитать правила с диска",
            description = "Применяет изменения в YAML без перезапуска сервиса: правку норматива или цены "
                    + "делает эксперт, а не программист. Читается каталог из HEATNET_RULES, если он задан, "
                    + "иначе встроенные правила. В ответе — что получилось загрузить; событие пишется в журнал.")
    @PostMapping("/reload")
    public Map<String, Object> reload() {
        rules.reload();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("diameters", rules.reference().diameters.size());
        m.put("restriction_types", rules.restrictions().types.size());
        m.put("grid_step_m", rules.routing().gridStepM);
        // правила задают нормативную часть расчёта, поэтому их перечитывание — событие журнала
        events.info("RULES_RELOADED", "Правила перечитаны из YAML", m);
        return m;
    }
}
