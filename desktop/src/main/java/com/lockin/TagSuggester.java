package com.lockin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * TagSuggester
 *
 * Asks the local gemma4:e2b model to generate 40–50 interest tags for a
 * profile, matching the logic in background.js suggestTags().
 *
 * The returned future never completes exceptionally — any error resolves to
 * an empty list so callers don't need to handle failures.
 */
public class TagSuggester {

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final String OLLAMA_URL = "http://localhost:11434/api/chat";
    private static final String MODEL      = "gemma4:e2b";
    private static final int    MAX_TAGS   = 50;

    // ── Shared infrastructure (thread-safe after construction) ────────────────

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ── Private constructor — static utility class ────────────────────────────

    private TagSuggester() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Fires an async request to gemma4:e2b and returns suggested tags.
     *
     * <p>The prompt mirrors background.js {@code suggestTags()} exactly so the
     * desktop and extension produce consistent tag sets.</p>
     *
     * @param profileName   display name of the profile
     * @param profilePrompt the plain-English focus description
     * @param existingTags  tags already on the profile (avoided in the merge)
     * @return CompletableFuture resolving to a deduplicated list of new tags,
     *         or an empty list on any error
     */
    public static CompletableFuture<List<String>> suggestAsync(
            String       profileName,
            String       profilePrompt,
            List<String> existingTags) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                String userContent = String.join("\n",
                    "Suggest 40 to 50 concise lowercase interest tags for this profile.",
                    "Return JSON exactly like {\"tags\":[\"tag1\",\"tag2\"]}.",
                    "Profile name: " + (profileName.isBlank() ? "(unnamed)" : profileName),
                    "Profile description: " + (profilePrompt.isBlank() ? "(none)" : profilePrompt),
                    "Existing tags: " + (existingTags.isEmpty()
                            ? "(none)"
                            : String.join(", ", existingTags))
                );

                var messages = List.of(
                        Map.of("role", "system", "content", "Return valid JSON only."),
                        Map.of("role", "user",   "content", userContent)
                );

                byte[] body = MAPPER.writeValueAsBytes(
                        Map.of("model", MODEL, "stream", false, "messages", messages)
                );

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(OLLAMA_URL))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(120))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();

                HttpResponse<String> resp =
                        HTTP.send(req, HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() != 200) {
                    System.err.println("[TagSuggester] Ollama returned " + resp.statusCode());
                    return List.of();
                }

                String content = MAPPER.readTree(resp.body())
                        .path("message").path("content").asText("").trim();

                return parseTags(content, existingTags);

            } catch (Exception e) {
                System.err.println("[TagSuggester] Error: " + e.getMessage());
                return List.of();
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Extracts the {@code tags} array from the model's JSON response,
     * normalises every tag (lowercase, trimmed), deduplicates, and returns
     * up to {@link #MAX_TAGS} entries.
     *
     * <p>Falls back to a best-effort substring search for a JSON object if
     * the model wraps its answer in markdown fences or extra prose.</p>
     */
    private static List<String> parseTags(String content, List<String> existing) {
        JsonNode parsed = tryParseJson(content);
        if (parsed == null) return List.of();

        JsonNode tagsNode = parsed.path("tags");
        if (!tagsNode.isArray()) return List.of();

        // Preserve insertion order, deduplicate
        LinkedHashSet<String> seen = new LinkedHashSet<>(normalise(existing));
        List<String> fresh = new ArrayList<>();

        for (JsonNode tag : tagsNode) {
            String t = tag.asText("").trim().toLowerCase().replaceAll("\\s+", " ");
            if (!t.isEmpty() && seen.add(t)) {
                fresh.add(t);
            }
        }

        // Cap total
        if (fresh.size() > MAX_TAGS) fresh = fresh.subList(0, MAX_TAGS);

        System.out.printf("[TagSuggester] Suggested %d new tag(s) via %s%n",
                fresh.size(), MODEL);
        return fresh;
    }

    /** Attempts to parse the model's output as JSON, stripping markdown fences. */
    private static JsonNode tryParseJson(String text) {
        // Direct parse
        try { return MAPPER.readTree(text); } catch (Exception ignored) {}

        // Strip markdown fences then retry
        String stripped = text
                .replaceAll("(?s)```json\\s*", "")
                .replaceAll("(?s)```\\s*", "")
                .trim();
        try { return MAPPER.readTree(stripped); } catch (Exception ignored) {}

        // Find the first { ... } block
        int first = stripped.indexOf('{');
        int last  = stripped.lastIndexOf('}');
        if (first >= 0 && last > first) {
            try {
                return MAPPER.readTree(stripped.substring(first, last + 1));
            } catch (Exception ignored) {}
        }

        System.err.println("[TagSuggester] Could not parse JSON from model response.");
        return null;
    }

    /** Normalises a list of tags to lowercase-trimmed strings. */
    private static List<String> normalise(List<String> tags) {
        List<String> out = new ArrayList<>();
        for (String t : tags) {
            String n = t.trim().toLowerCase().replaceAll("\\s+", " ");
            if (!n.isEmpty()) out.add(n);
        }
        return out;
    }
}
