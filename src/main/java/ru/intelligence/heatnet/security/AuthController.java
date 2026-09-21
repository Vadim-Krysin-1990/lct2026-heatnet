package ru.intelligence.heatnet.security;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.intelligence.heatnet.events.EventService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Сведения о входе для клиента: включён ли единый вход, куда идти за токеном и кто сейчас работает.
 * Сам обмен кода на токен выполняет браузер напрямую с Keycloak (Authorization Code + PKCE),
 * сервис токены не хранит.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Вход", description = "Единый вход через Keycloak; при выключенной проверке сервис работает открыто")
public class AuthController {

    @Value("${heatnet.security.enabled:false}")
    private boolean enabled;

    @Value("${heatnet.security.issuer-uri:}")
    private String issuer;

    @Value("${heatnet.security.client-id:heatnet-viewer}")
    private String clientId;

    private final EventService events;

    public AuthController(EventService events) {
        this.events = events;
    }

    @Operation(summary = "Настройки входа для клиента")
    @GetMapping(path = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("issuer", issuer);
        m.put("client_id", clientId);
        m.put("flow", "authorization_code+pkce");
        return m;
    }

    @Operation(summary = "Кто выполняет запрос")
    @GetMapping(path = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> me(Authentication auth) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            m.put("authenticated", false);
            m.put("username", "anonymous");
            m.put("roles", List.of());
            return m;
        }
        List<String> roles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        m.put("authenticated", true);
        m.put("username", auth.getName());
        m.put("roles", roles);
        return m;
    }

    @Operation(summary = "Отметить вход пользователя в журнале событий")
    @GetMapping(path = "/session", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> session(Authentication auth) {
        Map<String, Object> me = me(auth);
        events.info("LOGIN", "Вход в просмотрщик: " + me.get("username"), me);
        return me;
    }
}
