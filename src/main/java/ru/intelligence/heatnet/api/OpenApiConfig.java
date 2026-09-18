package ru.intelligence.heatnet.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
                .title("heatnet — сервис моделирования трасс подключения к тепловым сетям")
                .description("ЛЦТ 2026, задача Москва №2 (ДИТ), команда Intelligence. Вход: GeoJSON конкурсной структуры (WGS 84). "
                        + "Выход: GeoJSON по техническому приложению §10 — новые участки, врезки, камеры, технические узлы, реконструкция, сводка по вариантам.")
                .version("0.1.0"));
    }
}
