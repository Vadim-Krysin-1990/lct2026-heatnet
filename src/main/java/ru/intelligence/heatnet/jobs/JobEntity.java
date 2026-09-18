package ru.intelligence.heatnet.jobs;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.persistence.Table;
import java.time.Instant;

/** Задание на расчёт: хранится в PostgreSQL (ТЗ 3.2), файлы — на диске в HEATNET_STORAGE. */
@Entity
@Table(name = "jobs")
public class JobEntity {

    public enum Status { QUEUED, RUNNING, DONE, FAILED }

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "original_filename", length = 512)
    private String originalFilename;

    @Column(name = "input_size")
    private long inputSize;

    @Column(name = "input_path", length = 1024)
    private String inputPath;

    @Column(name = "result_path", length = 1024)
    private String resultPath;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "compute_millis")
    private Long computeMillis;

    @Column(name = "variants_count")
    private Integer variantsCount;

    @Column(name = "best_score")
    private Double bestScore;

    @Column(name = "best_cost")
    private Double bestCost;

    @Column(name = "unconnected_count")
    private Integer unconnectedCount;

    @Lob
    @Column(name = "diagnostics_json")
    private String diagnosticsJson;

    @Lob
    @Column(name = "error_text")
    private String errorText;

    @Column(name = "options_json", length = 2048)
    private String optionsJson;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public long getInputSize() { return inputSize; }
    public void setInputSize(long inputSize) { this.inputSize = inputSize; }
    public String getInputPath() { return inputPath; }
    public void setInputPath(String inputPath) { this.inputPath = inputPath; }
    public String getResultPath() { return resultPath; }
    public void setResultPath(String resultPath) { this.resultPath = resultPath; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Long getComputeMillis() { return computeMillis; }
    public void setComputeMillis(Long computeMillis) { this.computeMillis = computeMillis; }
    public Integer getVariantsCount() { return variantsCount; }
    public void setVariantsCount(Integer variantsCount) { this.variantsCount = variantsCount; }
    public Double getBestScore() { return bestScore; }
    public void setBestScore(Double bestScore) { this.bestScore = bestScore; }
    public Double getBestCost() { return bestCost; }
    public void setBestCost(Double bestCost) { this.bestCost = bestCost; }
    public Integer getUnconnectedCount() { return unconnectedCount; }
    public void setUnconnectedCount(Integer unconnectedCount) { this.unconnectedCount = unconnectedCount; }
    public String getDiagnosticsJson() { return diagnosticsJson; }
    public void setDiagnosticsJson(String diagnosticsJson) { this.diagnosticsJson = diagnosticsJson; }
    public String getErrorText() { return errorText; }
    public void setErrorText(String errorText) { this.errorText = errorText; }
    public String getOptionsJson() { return optionsJson; }
    public void setOptionsJson(String optionsJson) { this.optionsJson = optionsJson; }
}
