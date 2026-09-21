package ru.intelligence.heatnet.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Журнал событий: одна точка записи для всех действий сервиса.
 * Запись в журнал не должна влиять на расчёт: ошибка хранилища только логируется,
 * а сама запись идёт в отдельной транзакции.
 */
@Service
public class EventService {
    private static final Logger log = LoggerFactory.getLogger(EventService.class);
    public static final String MDC_REQUEST_ID = "requestId";

    private final EventRepository repo;
    private final ObjectMapper json = new ObjectMapper();

    public EventService(EventRepository repo) {
        this.repo = repo;
    }

    public void info(String type, String message, Map<String, Object> details) {
        write(EventEntity.Level.INFO, type, message, details, null, null);
    }

    public void warn(String type, String message, Map<String, Object> details) {
        write(EventEntity.Level.WARNING, type, message, details, null, null);
    }

    public void error(String type, String message, Map<String, Object> details) {
        write(EventEntity.Level.ERROR, type, message, details, null, null);
    }

    public void job(String type, String message, String jobId, Map<String, Object> details, Long durationMs) {
        write(EventEntity.Level.INFO, type, message, details, jobId, durationMs);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(EventEntity.Level level, String type, String message,
                      Map<String, Object> details, String jobId, Long durationMs) {
        EventEntity e = new EventEntity();
        e.setId(UUID.randomUUID().toString());
        e.setTs(Instant.now());
        e.setLevel(level.name());
        e.setType(type);
        e.setMessage(trim(message, 1024));
        e.setJobId(jobId);
        e.setDurationMs(durationMs);
        e.setRequestId(MDC.get(MDC_REQUEST_ID));
        e.setActor(CurrentUser.name());
        e.setRoles(CurrentUser.roles());
        e.setIp(CurrentUser.ip());
        if (details != null && !details.isEmpty()) {
            try { e.setDetails(trim(json.writeValueAsString(details), 4096)); } catch (Exception ignore) { }
        }
        log.info("событие {} [{}] {}", type, level, message);
        try {
            repo.save(e);
        } catch (Exception ex) {
            log.warn("журнал событий недоступен, запись {} не сохранена: {}", type, ex.toString());
        }
    }

    public Page<EventEntity> search(String type, String level, String actor, Instant from, Instant to, int page, int size) {
        Specification<EventEntity> spec = (root, q, cb) -> {
            java.util.List<javax.persistence.criteria.Predicate> ps = new java.util.ArrayList<>();
            if (empty(type) != null) ps.add(cb.equal(root.get("type"), type));
            if (empty(level) != null) ps.add(cb.equal(root.get("level"), level.toUpperCase()));
            if (empty(actor) != null) ps.add(cb.equal(root.get("actor"), actor));
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("ts"), from));
            if (to != null) ps.add(cb.lessThanOrEqualTo(root.get("ts"), to));
            return ps.isEmpty() ? cb.conjunction() : cb.and(ps.toArray(new javax.persistence.criteria.Predicate[0]));
        };
        return repo.findAll(spec, PageRequest.of(Math.max(0, page), Math.min(500, Math.max(1, size)), Sort.by(Sort.Direction.DESC, "ts")));
    }

    public Map<String, Object> stats() {
        Map<String, Object> byType = new LinkedHashMap<>();
        for (Object[] row : repo.countByType()) byType.put(String.valueOf(row[0]), row[1]);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", repo.count());
        m.put("by_type", byType);
        return m;
    }

    private static String empty(String s) { return s == null || s.isEmpty() ? null : s; }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Сведения о том, кто выполняет запрос: из токена, если вход включён, иначе анонимно. */
    public static final class CurrentUser {
        private static final ThreadLocal<HttpServletRequest> REQUEST = new ThreadLocal<>();

        private CurrentUser() {}

        public static void bind(HttpServletRequest r) { REQUEST.set(r); }
        public static void clear() { REQUEST.remove(); }

        public static String name() {
            Object p = principal();
            if (p == null) return "anonymous";
            return String.valueOf(p);
        }

        public static String roles() {
            try {
                org.springframework.security.core.Authentication a =
                        org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                if (a == null) return null;
                StringBuilder sb = new StringBuilder();
                a.getAuthorities().forEach(g -> { if (sb.length() > 0) sb.append(','); sb.append(g.getAuthority()); });
                return sb.length() == 0 ? null : sb.toString();
            } catch (Throwable ignore) {
                return null;
            }
        }

        public static String ip() {
            HttpServletRequest r = REQUEST.get();
            if (r == null) return null;
            String fwd = r.getHeader("X-Forwarded-For");
            return fwd != null && !fwd.isEmpty() ? fwd.split(",")[0].trim() : r.getRemoteAddr();
        }

        private static Object principal() {
            try {
                org.springframework.security.core.Authentication a =
                        org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                if (a == null || !a.isAuthenticated()) return null;
                if ("anonymousUser".equals(a.getPrincipal())) return null;
                return a.getName();
            } catch (Throwable ignore) {
                return null;
            }
        }
    }
}
