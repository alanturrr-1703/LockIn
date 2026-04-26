package com.lockin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * OllamaMonitor
 *
 * Async health checker for the local Ollama instance.
 * Pings http://localhost:11434/ and returns true if Ollama responds with 200.
 * Never throws — all errors resolve to false.
 */
public class OllamaMonitor {

    private static final String OLLAMA_URL = "http://localhost:11434/";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private OllamaMonitor() {}

    /**
     * Fires an async GET to Ollama's root endpoint.
     *
     * @return CompletableFuture<Boolean> — true if Ollama is up, false otherwise.
     */
    public static CompletableFuture<Boolean> checkAsync() {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(OLLAMA_URL))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();

        return CLIENT
                .sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> resp.statusCode() == 200)
                .exceptionally(e -> false);
    }
}
