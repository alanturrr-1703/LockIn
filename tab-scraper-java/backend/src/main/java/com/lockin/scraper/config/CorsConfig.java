package com.lockin.scraper.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Global CORS configuration.
 *
 * The Chrome extension makes requests from a chrome-extension:// origin,
 * and local dev tools hit the API from http://localhost:*  — both are
 * allowed here so nothing gets silently blocked during development.
 *
 * Tighten the allowed origins before any public / production deployment.
 */
@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {

            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        // Chrome extensions fire requests from a chrome-extension:// scheme.
                        // Spring needs the literal origin pattern; a wildcard does NOT cover
                        // chrome-extension://* so we allow all origins here for development.
                        .allowedOriginPatterns("*")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD")
                        .allowedHeaders("*")
                        // Credentials (cookies / auth headers) are not used by the extension,
                        // but set to false so allowedOriginPatterns("*") stays valid.
                        .allowCredentials(false)
                        .maxAge(3600);
            }
        };
    }
}
