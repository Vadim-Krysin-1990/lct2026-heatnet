package ru.intelligence.heatnet.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Загрузка справочников и правил из YAML. Порядок: каталог HEATNET_RULES (если задан и файл есть),
 * иначе classpath:rules/. Правила можно перечитать без перезапуска (reload()).
 */
@Service
public class RulesService {
    private static final Logger log = LoggerFactory.getLogger(RulesService.class);

    private final HeatnetProperties props;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private volatile ReferenceRules reference;
    private volatile RestrictionRules restrictions;
    private volatile RoutingRules routing;

    public RulesService(HeatnetProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void reload() {
        reference = load("reference.yml", ReferenceRules.class);
        restrictions = load("restrictions.yml", RestrictionRules.class);
        routing = load("routing.yml", RoutingRules.class);
        log.info("Правила загружены: ресурс «{}», ДУ {} ступеней, типов ограничений {}, шаг сетки {} м",
                reference.resource.title, reference.diameters.size(), restrictions.types.size(), routing.gridStepM);
    }

    private <T> T load(String name, Class<T> type) {
        try {
            if (props.getRulesDir() != null && !props.getRulesDir().isBlank()) {
                Path p = Paths.get(props.getRulesDir(), name);
                if (Files.exists(p)) {
                    log.info("Правила {} из {}", name, p);
                    return yaml.readValue(p.toFile(), type);
                }
            }
            try (InputStream in = getClass().getResourceAsStream("/rules/" + name)) {
                if (in == null) throw new IllegalStateException("Нет ресурса rules/" + name);
                return yaml.readValue(in, type);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать правила " + name + ": " + e.getMessage(), e);
        }
    }

    /** Загрузка правил из заданного каталога или classpath без Spring (для тестов и CLI). */
    public static RulesService standalone() {
        RulesService s = new RulesService(new HeatnetProperties());
        s.reload();
        return s;
    }

    public ReferenceRules reference() { return reference; }
    public RestrictionRules restrictions() { return restrictions; }
    public RoutingRules routing() { return routing; }

    public String rawYaml(String name) throws IOException {
        if (props.getRulesDir() != null && !props.getRulesDir().isBlank()) {
            Path p = Paths.get(props.getRulesDir(), name);
            if (Files.exists(p)) return Files.readString(p);
        }
        try (InputStream in = getClass().getResourceAsStream("/rules/" + name)) {
            if (in == null) throw new IOException("нет ресурса " + name);
            return new String(in.readAllBytes());
        }
    }
}
