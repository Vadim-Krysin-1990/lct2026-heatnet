package ru.intelligence.heatnet.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Доступ к сервису. Два режима, переключаются свойством heatnet.security.enabled:
 *
 * - выключено (по умолчанию): контур конкурсной проверки — сервис поднимается одним
 *   docker-compose up, без внешних систем, все методы открыты, в журнале событий actor = anonymous;
 * - включено: сервис работает как resource server и принимает JWT Keycloak. Роли realm'а
 *   переносятся в права ROLE_*, на запись (расчёт, правила) нужна роль инженера или администратора,
 *   на чтение журнала — любая из ролей. Вход и отзыв доступа живут в Keycloak, а не в коде.
 */
@Configuration
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${heatnet.security.enabled:false}")
    private boolean enabled;

    @Value("${heatnet.security.cors-origins:*}")
    private String corsOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf().disable()
                .cors().configurationSource(corsSource()).and()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
                .headers().frameOptions().sameOrigin().and();
        if (!enabled) {
            log.info("Проверка токенов выключена (heatnet.security.enabled=false): открытый контур для конкурсной проверки");
            http.authorizeRequests().anyRequest().permitAll();
            return http.build();
        }
        log.info("Проверка токенов включена: сервис принимает JWT Keycloak");
        http.authorizeRequests()
                .antMatchers("/", "/index.html", "/viewer/**", "/lib/**", "/actuator/health",
                        "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/api/auth/config").permitAll()
                .antMatchers(org.springframework.http.HttpMethod.POST, "/api/process", "/api/jobs", "/api/rules/reload")
                    .hasAnyRole("HEATNET-ENGINEER", "HEATNET-ADMIN")
                .antMatchers("/api/**").hasAnyRole("HEATNET-VIEWER", "HEATNET-ENGINEER", "HEATNET-ADMIN")
                .anyRequest().authenticated()
                .and()
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter())));
        return http.build();
    }

    private CorsConfigurationSource corsSource() {
        CorsConfiguration c = new CorsConfiguration();
        for (String o : corsOrigins.split(",")) c.addAllowedOriginPattern(o.trim());
        c.addAllowedMethod("*");
        c.addAllowedHeader("*");
        c.addExposedHeader("X-Request-Id");
        c.addExposedHeader("X-Compute-Millis");
        c.addExposedHeader("X-Variants");
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", c);
        return src;
    }

    /** Роли realm'а Keycloak (realm_access.roles) → права ROLE_* Spring Security. */
    private Converter<Jwt, AbstractAuthenticationToken> converter() {
        return jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            Object realmAccess = jwt.getClaims().get("realm_access");
            if (realmAccess instanceof Map) {
                Object roles = ((Map<?, ?>) realmAccess).get("roles");
                if (roles instanceof List) {
                    for (Object r : (List<?>) roles) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_" + String.valueOf(r).toUpperCase()));
                    }
                }
            }
            String name = jwt.getClaimAsString("preferred_username");
            return new JwtAuthenticationToken(jwt, authorities, name != null ? name : jwt.getSubject());
        };
    }
}
