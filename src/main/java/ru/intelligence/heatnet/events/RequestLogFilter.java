package ru.intelligence.heatnet.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * Сквозной идентификатор запроса и запись обращения в лог приложения.
 * Клиент может задать свой X-Request-Id — тогда его же увидит в ответе и в журнале событий,
 * что позволяет сопоставить обращение на стороне интеграции с расчётом на стороне сервиса.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLogFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);
    public static final String HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String id = req.getHeader(HEADER);
        if (id == null || id.isEmpty() || id.length() > 36) id = UUID.randomUUID().toString();
        MDC.put(EventService.MDC_REQUEST_ID, id);
        EventService.CurrentUser.bind(req);
        res.setHeader(HEADER, id);
        long t0 = System.currentTimeMillis();
        try {
            chain.doFilter(req, res);
        } finally {
            long ms = System.currentTimeMillis() - t0;
            if (!req.getRequestURI().startsWith("/swagger") && !req.getRequestURI().startsWith("/v3/api-docs")) {
                log.info("{} {} → {} за {} мс", req.getMethod(), req.getRequestURI(), res.getStatus(), ms);
            }
            MDC.remove(EventService.MDC_REQUEST_ID);
            EventService.CurrentUser.clear();
        }
    }
}
