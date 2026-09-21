package ru.intelligence.heatnet.events;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Журнал событий сервиса: кто, когда и что запускал, чем закончилось (ТЗ 3.4). */
@RestController
@RequestMapping("/api/events")
@Tag(name = "Журнал событий")
public class EventController {

    private final EventService events;

    public EventController(EventService events) {
        this.events = events;
    }

    @Operation(summary = "Список событий", description = "Фильтры по коду события, уровню, пользователю и периоду; сортировка — от новых к старым.")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> list(
            @Parameter(description = "Код события, например PROCESS_DONE") @RequestParam(required = false) String type,
            @Parameter(description = "INFO, WARNING или ERROR") @RequestParam(required = false) String level,
            @Parameter(description = "Пользователь") @RequestParam(required = false) String actor,
            @Parameter(description = "С какого момента (ISO-8601)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "По какой момент (ISO-8601)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Page<EventEntity> found = events.search(type, level, actor, from, to, page, size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (EventEntity e : found.getContent()) items.add(toMap(e));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", found.getTotalElements());
        m.put("page", found.getNumber());
        m.put("size", found.getSize());
        m.put("items", items);
        return m;
    }

    @Operation(summary = "Сводка по журналу", description = "Сколько событий каждого вида записано.")
    @GetMapping(path = "/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> stats() {
        return events.stats();
    }

    private static Map<String, Object> toMap(EventEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("ts", e.getTs());
        m.put("level", e.getLevel());
        m.put("type", e.getType());
        m.put("actor", e.getActor());
        m.put("roles", e.getRoles());
        m.put("request_id", e.getRequestId());
        m.put("job_id", e.getJobId());
        m.put("ip", e.getIp());
        m.put("message", e.getMessage());
        m.put("details", e.getDetails());
        m.put("duration_ms", e.getDurationMs());
        return m;
    }
}
