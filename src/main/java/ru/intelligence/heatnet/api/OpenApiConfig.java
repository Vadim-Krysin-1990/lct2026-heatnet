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
                .title("ЦАТ — цифровой аналитик трасс подключения к тепловым сетям")
                .description("Сервис по данным о существующей сети, точках присоединения новых объектов и городских "
                        + "ограничениях строит и сравнивает варианты подключения: место присоединения, трасса, диаметры, "
                        + "камеры и технические узлы, стоимость и ранжирование. Инженеру остаётся проверить и выбрать.\n\n"
                        + "ЛЦТ 2026, задача Москва №2 (ДИТ), команда Intelligence. "
                        + "Вход: GeoJSON конкурсной структуры (WGS 84). Выход: GeoJSON по техническому приложению, раздел 7 — "
                        + "участки новой сети, тепловые камеры, технические узлы и сводка по каждому варианту.")
                .version("1.0.0"));
    }
}
