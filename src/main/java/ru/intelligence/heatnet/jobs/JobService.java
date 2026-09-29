package ru.intelligence.heatnet.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.intelligence.heatnet.config.HeatnetProperties;
import ru.intelligence.heatnet.config.RulesService;
import ru.intelligence.heatnet.export.GeoJsonWriter;
import ru.intelligence.heatnet.ingest.InputLoader;
import ru.intelligence.heatnet.model.Diagnostics;
import ru.intelligence.heatnet.model.InputModel;
import ru.intelligence.heatnet.model.Variant;
import ru.intelligence.heatnet.planner.VariantPlanner;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Очередь расчётов: входной файл сохраняется на диск потоково, расчёт идёт в пуле воркеров,
 * состояние и результат — в PostgreSQL и на диске. До 50 пользователей (ТЗ 3.2) — задания ждут в очереди.
 */
@Service
public class JobService {
    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository repo;
    private final RulesService rules;
    private final HeatnetProperties props;
    private final ObjectMapper json = new ObjectMapper();
    private ExecutorService pool;
    private Path storage;

    private final ru.intelligence.heatnet.events.EventService events;

    public JobService(JobRepository repo, RulesService rules, HeatnetProperties props,
                      ru.intelligence.heatnet.events.EventService events) {
        this.repo = repo;
        this.rules = rules;
        this.props = props;
        this.events = events;
    }

    @PostConstruct
    void init() throws IOException {
        storage = Paths.get(props.getStorageDir());
        Files.createDirectories(storage.resolve("input"));
        Files.createDirectories(storage.resolve("result"));
        Files.createDirectories(storage.resolve("input-area"));
        Files.createDirectories(storage.resolve("last"));
        pool = Executors.newFixedThreadPool(Math.max(1, props.getWorkers()));
        // задания, оставшиеся RUNNING после перезапуска, — в очередь заново
        for (JobEntity j : repo.findByStatusOrderByCreatedAtAsc(JobEntity.Status.RUNNING.name())) {
            j.setStatus(JobEntity.Status.QUEUED.name());
            repo.save(j);
        }
        for (JobEntity j : repo.findByStatusOrderByCreatedAtAsc(JobEntity.Status.QUEUED.name())) pool.submit(() -> run(j.getId()));
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    /** Принимает поток входного файла, сохраняет на диск, ставит в очередь. */
    public JobEntity submit(InputStream in, String originalFilename, Map<String, Object> options) throws IOException {
        String id = UUID.randomUUID().toString();
        Path input = storage.resolve("input").resolve(id + ".geojson");
        Files.copy(in, input, StandardCopyOption.REPLACE_EXISTING);
        JobEntity j = new JobEntity();
        j.setId(id);
        j.setStatus(JobEntity.Status.QUEUED.name());
        j.setOriginalFilename(originalFilename);
        j.setInputSize(Files.size(input));
        j.setInputPath(input.toString());
        j.setCreatedAt(Instant.now());
        j.setOptionsJson(json.writeValueAsString(options == null ? Map.of() : options));
        repo.save(j);
        pool.submit(() -> run(id));
        return j;
    }

    /** Синхронный расчёт для небольших файлов: возвращает варианты и диагностику без очереди. */
    public SyncResult processSync(InputStream in, boolean depth) throws IOException {
        return processSync(in, depth, 0);
    }

    public SyncResult processSync(InputStream in, boolean depth, int variants) throws IOException {
        return processSync(in, depth, variants, null);
    }

    public SyncResult processSync(InputStream in, boolean depth, int variants, String methods) throws IOException {
        long t0 = System.currentTimeMillis();
        InputLoader loader = new InputLoader(rules);
        InputModel model = loader.load(new BufferedInputStream(in));
        SyncResult r = new SyncResult();
        r.model = model;
        r.loader = loader;
        if (model.diagnostics.hasErrors()) {
            r.variants = new ArrayList<>();
            return r;
        }
        VariantPlanner.Outcome out = new VariantPlanner(rules).plan(model, depth, variants, methods);
        r.variants = out.variants;
        r.millis = System.currentTimeMillis() - t0;
        return r;
    }

    /**
     * Последний успешный расчёт: просмотрщик открывается на нём, а не на образце, поэтому после
     * перезагрузки страницы видно то, что считали последним. Хранится рядом с заданиями.
     */
    public Path lastPath(String name) {
        return storage.resolve("last").resolve(name);
    }

    /** Запомнить расчёт как последний: результат, объекты области расчёта и описание. */
    public void rememberLast(Path result, Path inputArea, Map<String, Object> info) {
        try {
            Files.copy(result, lastPath("result.geojson"), StandardCopyOption.REPLACE_EXISTING);
            if (inputArea != null && Files.isReadable(inputArea)) {
                Files.copy(inputArea, lastPath("input-area.geojson"), StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(lastPath("input-area.geojson"));
            }
            Files.write(lastPath("info.json"), json.writeValueAsBytes(info));
        } catch (Exception e) {
            log.warn("не удалось запомнить последний расчёт: {}", e.toString());
        }
    }

    /** Запомнить синхронный расчёт: результат уже собран в памяти, объекты области пишем рядом. */
    public void rememberSyncResult(byte[] result, SyncResult r, Map<String, Object> info) {
        try {
            Files.write(lastPath("result.geojson"), result);
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(lastPath("input-area.geojson")), 1 << 16)) {
                new ru.intelligence.heatnet.export.InputGeoJsonWriter(r.loader.crs(), r.model.numericIds)
                        .write(r.model, os);
            } catch (Exception e) {
                Files.deleteIfExists(lastPath("input-area.geojson"));
            }
            Files.write(lastPath("info.json"), json.writeValueAsBytes(info));
        } catch (Exception e) {
            log.warn("не удалось запомнить последний расчёт: {}", e.toString());
        }
    }

    /** Описание последнего расчёта или null, если расчётов ещё не было. */
    public Map<String, Object> lastInfo() {
        try {
            Path p = lastPath("info.json");
            if (!Files.isReadable(p) || !Files.isReadable(lastPath("result.geojson"))) return null;
            return json.readValue(Files.readAllBytes(p), Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** Файл с входными объектами области расчёта (для просмотрщика). */
    public Path inputAreaPath(String id) {
        return storage.resolve("input-area").resolve(id + ".geojson");
    }

    /**
     * SHA-256 входного потока: прослеживаемость результата — видно, из какого именно файла он получен.
     * Считается отдельным проходом, потому что разбор закрывает поток по своему усмотрению.
     */
    public static String sha256(InputStream in) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) >= 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (Exception e) {
            log.warn("не удалось посчитать SHA-256 входного потока: {}", e.toString());
            return null;
        }
    }

    /** Контрольная сумма уже сохранённого файла задания. */
    private static String sha256(java.nio.file.Path p) {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(p), 1 << 20)) {
            return sha256(in);
        } catch (Exception e) {
            log.warn("не удалось посчитать SHA-256 файла {}: {}", p, e.toString());
            return null;
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    public static class SyncResult {
        public InputModel model;
        public InputLoader loader;
        public List<Variant> variants;
        public long millis;
        /** SHA-256 входного файла — прослеживаемость результата. */
        public String inputSha256;
    }

    void run(String id) {
        Optional<JobEntity> oj = repo.findById(id);
        if (oj.isEmpty()) return;
        JobEntity j = oj.get();
        j.setStatus(JobEntity.Status.RUNNING.name());
        j.setStartedAt(Instant.now());
        repo.save(j);
        long t0 = System.currentTimeMillis();
        try {
            InputLoader loader = new InputLoader(rules);
            InputModel model;
            java.nio.file.Path input = Paths.get(j.getInputPath());
            // Большой файл читаем в два прохода: сначала быстро находим область расчёта по точкам
            // присоединения и сети, затем грузим только то, что в неё попадает. Для наборов масштаба
            // города это разница между отказом по памяти и расчётом за минуты.
            org.locationtech.jts.geom.Envelope area = null;
            long size = Files.size(input);
            if (size > props.getAreaScanThresholdBytes()) {
                long ts = System.currentTimeMillis();
                try (InputStream in = new BufferedInputStream(Files.newInputStream(input), 1 << 20)) {
                    area = ru.intelligence.heatnet.ingest.AreaScanner.scan(in, props.getAreaMarginDeg());
                }
                log.info("Область расчёта определена за {} мс: {}", System.currentTimeMillis() - ts, area);
            }
            try (InputStream in = new BufferedInputStream(Files.newInputStream(input), 1 << 20)) {
                model = loader.load(in, area);
            }
            if (model.diagnostics.hasErrors()) {
                j.setStatus(JobEntity.Status.FAILED.name());
                j.setErrorText("Входные данные не прошли проверку: " + model.diagnostics.count(Diagnostics.Level.ERROR) + " ошибок, см. диагностику");
                j.setDiagnosticsJson(json.writeValueAsString(diagnosticsMap(model.diagnostics)));
                j.setFinishedAt(Instant.now());
                repo.save(j);
                return;
            }
            boolean depth = false;
            int variants = 0;
            String methods = null;
            try {
                Map<?, ?> opts = json.readValue(j.getOptionsJson() == null ? "{}" : j.getOptionsJson(), Map.class);
                depth = Boolean.TRUE.equals(opts.get("depth"));
                Object v = opts.get("variants");
                if (v instanceof Number) variants = ((Number) v).intValue();
                Object m = opts.get("methods");
                if (m != null) methods = String.valueOf(m);
            } catch (Exception ignore) { }
            VariantPlanner.Outcome out = new VariantPlanner(rules).plan(model, depth, variants, methods);
            Path result = storage.resolve("result").resolve(id + ".geojson");
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(result), 1 << 16)) {
                new GeoJsonWriter(loader.crs(), model.numericIds)
                        .metadata(ru.intelligence.heatnet.export.ResultMetadata.of(sha256(input), model.totalFeatures,
                                System.currentTimeMillis() - t0, depth, methods))
                        .write(out.variants, os);
            }
            j.setResultPath(result.toString());
            // объекты входа, попавшие в область расчёта: просмотрщик рисует по ним карту загруженного
            // файла — сам файл (до 3 ГБ) в браузер не передать
            Path inputArea = storage.resolve("input-area").resolve(id + ".geojson");
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(inputArea), 1 << 16)) {
                new ru.intelligence.heatnet.export.InputGeoJsonWriter(loader.crs(), model.numericIds).write(model, os);
            } catch (Exception e) {
                // вспомогательный файл для карты просмотрщика: его отсутствие не должно ронять расчёт
                log.warn("Не удалось записать входные объекты области расчёта для задания {}: {}", id, e.toString());
                try { Files.deleteIfExists(inputArea); } catch (Exception ignore) { }
            }
            j.setVariantsCount(out.variants.size());
            if (!out.variants.isEmpty()) {
                j.setBestScore(out.variants.get(0).score);
                j.setBestCost(out.variants.get(0).calculatedCost);
                j.setUnconnectedCount(out.variants.get(0).unconnectedOksIds.size());
            }
            j.setComputeMillis(System.currentTimeMillis() - t0);
            Map<String, Object> lastInfo = new java.util.LinkedHashMap<>();
            lastInfo.put("source", "задание " + id);
            lastInfo.put("file", j.getOriginalFilename());
            lastInfo.put("size_bytes", j.getInputSize());
            lastInfo.put("input_features", model.totalFeatures);
            lastInfo.put("variants", out.variants.size());
            lastInfo.put("compute_millis", j.getComputeMillis());
            lastInfo.put("depth_mode", depth);
            lastInfo.put("methods", methods == null || methods.isEmpty() ? "grid" : methods);
            lastInfo.put("finished_at", Instant.now().toString());
            rememberLast(result, inputArea, lastInfo);
            j.setDiagnosticsJson(json.writeValueAsString(diagnosticsMap(model.diagnostics)));
            j.setStatus(JobEntity.Status.DONE.name());
            Map<String, Object> ev = new java.util.LinkedHashMap<>();
            ev.put("file", j.getOriginalFilename());
            ev.put("size_bytes", j.getInputSize());
            ev.put("variants", out.variants.size());
            ev.put("depth", depth);
            if (!out.variants.isEmpty()) {
                ev.put("best_variant", out.variants.get(0).name);
                ev.put("best_cost", Math.round(out.variants.get(0).calculatedCost));
                ev.put("best_length_m", Math.round(out.variants.get(0).newNetworkLength));
            }
            events.job("JOB_DONE", "Задание рассчитано: вариантов " + out.variants.size(), id, ev, j.getComputeMillis());
        } catch (Exception e) {
            events.error("JOB_FAILED", "Задание " + id + " завершилось ошибкой: " + e, java.util.Map.of("job_id", id));
            log.error("Задание {} завершилось ошибкой", id, e);
            j.setStatus(JobEntity.Status.FAILED.name());
            j.setErrorText(e.toString());
        }
        j.setFinishedAt(Instant.now());
        repo.save(j);
    }

    public static Map<String, Object> diagnosticsMap(Diagnostics d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("errors", d.count(Diagnostics.Level.ERROR));
        m.put("warnings", d.count(Diagnostics.Level.WARNING));
        m.put("stats", d.stats);
        List<Map<String, Object>> msgs = new ArrayList<>();
        for (Diagnostics.Message msg : d.messages) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("level", msg.level.name());
            mm.put("code", msg.code);
            mm.put("text", msg.text);
            if (msg.objectId != null) mm.put("object_id", msg.objectId);
            msgs.add(mm);
        }
        m.put("messages", msgs);
        return m;
    }

    public Optional<JobEntity> get(String id) { return repo.findById(id); }
    public List<JobEntity> recent() { return repo.findTop50ByOrderByCreatedAtDesc(); }
    public ObjectMapper json() { return json; }
}
