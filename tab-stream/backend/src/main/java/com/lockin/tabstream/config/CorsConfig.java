package com.lockin.tabstream.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Global CORS configuration for all REST endpoints.
 *
 * <p>The Chrome extension communicates from a {@code chrome-extension://} origin,
 * which is not a standard HTTP origin and is therefore not matched by the simple
 * wildcard {@code *}. Using {@code allowedOriginPatterns("*")} instructs Spring
 * to echo back whatever origin the client sends, which covers both regular web
 * origins and the extension's non-HTTP scheme.
 *
 * <p>Note: {@code allowCredentials(true)} is intentionally omitted here because
 * it is incompatible with a wildcard origin pattern when credentials are not
 * actually required by this API.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                // allowedOriginPatterns("*") is required (instead of allowedOrigins("*"))
                // to support the chrome-extension:// scheme sent by the browser extension.
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Type", "X-Requested-With")
                .maxAge(3600);
    }
}
