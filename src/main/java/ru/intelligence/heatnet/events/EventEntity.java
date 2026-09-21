package ru.intelligence.heatnet.events;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.Table;
import java.time.Instant;

/**
 * Запись журнала событий сервиса (ТЗ 3.4: журналирование действий и расчётов).
 * Журнал хранится в PostgreSQL рядом с заданиями: события переживают перезапуск контейнера
 * и доступны через REST, а не только в выводе docker logs.
 */
@Entity
@Table(name = "event_log", indexes = {
        @Index(name = "ix_event_ts", columnList = "ts"),
        @Index(name = "ix_event_type", columnList = "type")
})
public class EventEntity {

    /** Уровень события: обычный ход работы, предупреждение, отказ. */
    public enum Level { INFO, WARNING, ERROR }

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private Instant ts;

    @Column(nullable = false, length = 16)
    private String level;

    /** Код события: UPLOAD, PROCESS_DONE, VALIDATION_FAILED, RULES_CHANGED, LOGIN … */
    @Column(nullable = false, length = 48)
    private String type;

    /** Кто выполнил действие: имя из токена Keycloak либо «anonymous», если вход выключен. */
    @Column(length = 128)
    private String actor;

    /** Роли пользователя на момент действия. */
    @Column(length = 256)
    private String roles;

    /** Сквозной идентификатор запроса: связывает запись журнала с логами приложения. */
    @Column(name = "request_id", length = 36)
    private String requestId;

    @Column(name = "job_id", length = 36)
    private String jobId;

    @Column(length = 64)
    private String ip;

    @Column(length = 1024)
    private String message;

    /** Подробности в JSON: размер файла, число вариантов, стоимость, длительность. */
    @Column(length = 4096)
    private String details;

    @Column(name = "duration_ms")
    private Long durationMs;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Instant getTs() { return ts; }
    public void setTs(Instant ts) { this.ts = ts; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getRoles() { return roles; }
    public void setRoles(String roles) { this.roles = roles; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
}
