package com.copymylife.copymylife.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Routine assistant: asks the Python AI service (ai/app.py, POST /similar, the same service and
// settings as US-26 matching) which texts are closest in meaning to each query. Anything going
// wrong there comes out as Unavailable, and the assistant then answers without examples or the
// goal check instead of failing.
@Component
public class SemanticSearchClient {

    public record Item(long id, String text) {
    }

    public record Scored(long id, double score) {
    }

    private final ObjectMapper json;
    private final URI similarUri;
    private final String token;
    private final Duration timeout;
    private final HttpClient httpClient;

    public SemanticSearchClient(
            ObjectMapper json,
            @Value("${ai.matching.url:http://127.0.0.1:8001}") String baseUrl,
            @Value("${ai.matching.token:}") String token,
            @Value("${ai.assistant.timeout-seconds:10}") long timeoutSeconds
    ) {
        this.json = json;
        this.similarUri = URI.create(baseUrl.replaceAll("/+$", "") + "/similar");
        this.token = token;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 3)))
                .build();
    }

    // One list per query, best first; only ids that were sent and scores between -1 and 1.
    public List<List<Scored>> rank(List<String> queries, List<Item> items, int limit, double minScore) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("queries", queries);
        payload.put("items", items.stream().map(item -> Map.of("id", item.id(), "text", item.text())).toList());
        payload.put("limit", limit);
        payload.put("minScore", minScore);

        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(similarUri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload), StandardCharsets.UTF_8));

            if (!token.isBlank()) {
                request.header("X-AI-Token", token);
            }

            HttpResponse<String> response =
                    httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                throw new Unavailable("The AI service answered " + response.statusCode());
            }

            JsonNode results = json.readTree(response.body()).path("results");

            if (!results.isArray() || results.size() != queries.size()) {
                throw new Unavailable("The AI service gave no result list per query");
            }

            Set<Long> sent = new HashSet<>();
            items.forEach(item -> sent.add(item.id()));
            List<List<Scored>> ranked = new ArrayList<>();

            for (JsonNode list : results) {
                List<Scored> scored = new ArrayList<>();
                for (JsonNode entry : list) {
                    JsonNode id = entry.path("id");
                    JsonNode score = entry.path("score");
                    if (id.isIntegralNumber() && score.isNumber() && sent.contains(id.longValue())
                            && score.doubleValue() >= -1 && score.doubleValue() <= 1
                            && scored.stream().noneMatch(s -> s.id() == id.longValue())) {
                        scored.add(new Scored(id.longValue(), score.doubleValue()));
                    }
                }
                ranked.add(scored);
            }
            return ranked;

        } catch (IOException | JacksonException exception) {
            throw new Unavailable("The AI service could not be reached: " + exception.getClass().getSimpleName());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Interrupted while waiting for the AI service");
        }
    }

    public static class Unavailable extends RuntimeException {

        public Unavailable(String message) {
            super(message);
        }
    }
}
