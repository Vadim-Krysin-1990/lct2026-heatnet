package ru.intelligence.heatnet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Сервис моделирования трасс подключения перспективных ОКС к тепловым сетям.
 * ЛЦТ 2026, задача Москва №2 (ДИТ), команда Intelligence.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class HeatnetApplication {
    public static void main(String[] args) {
        SpringApplication.run(HeatnetApplication.class, args);
    }
}
