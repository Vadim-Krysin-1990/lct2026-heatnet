package ru.intelligence.heatnet.api;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Hidden;

import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ответ на ошибку в JSON вместо стандартной страницы Spring: сервис отдаёт только данные,
 * а по неверному обращению возвращает подсказку, что и каким методом вызывать.
 */
@RestController
@Hidden   // служебный обработчик ошибок, в документации API не нужен
public class JsonErrorController implements ErrorController {

    @RequestMapping(value = "/error", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> handle(HttpServletRequest request) {
        Object statusObj = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = statusObj instanceof Integer ? (Integer) statusObj : 500;
        Object path = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("path", path);
        if (status == 405) body.put("error", "Метод не поддерживается. Расчёт вызывается POST-запросом с файлом: POST /api/process (поле file)");
        else if (status == 404) body.put("error", "Адрес не найден. Точки входа: /api/process, /api/jobs, /api/rules, /swagger-ui.html");
        else body.put("error", "Ошибка обработки запроса");
        body.put("docs", "/swagger-ui.html");
        return ResponseEntity.status(status).body(body);
    }
}
