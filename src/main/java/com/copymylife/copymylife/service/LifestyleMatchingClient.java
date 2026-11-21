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
import java.util.Map;

// US-26: the one place that talks to the Python matching service (ai/app.py, POST /match).
// Whatever goes wrong there - not running, too slow, refusing or answering nonsense - comes out
// as AiUnavailableException, so the rest of CopyMyLife never depends on it.
@Component
public class LifestyleMatchingClient {

    private final ObjectMapper json;
    private final URI matchUri;
    private final String token;
    private final Duration timeout;
    private final HttpClient httpClient;

    public LifestyleMatchingClient(
            ObjectMapper json,
            @Value("${ai.matching.url:http://127.0.0.1:8001}") String baseUrl,
            @Value("${ai.matching.token:}") String token,
            @Value("${ai.matching.timeout-seconds:20}") long timeoutSeconds
    ) {
        this.json = json;
        this.matchUri = URI.create(baseUrl.replaceAll("/+$", "") + "/match");
        this.token = token;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 3)))
                .build();
    }

    public JsonNode match(Map<String, Object> payload) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(matchUri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload), StandardCharsets.UTF_8));

            if (!token.isBlank()) {
                request.header("X-AI-Token", token);
            }

            HttpResponse<String> response =
                    httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                throw new AiUnavailableException("The matching service answered " + response.statusCode());
            }

            JsonNode reply = json.readTree(response.body());

            if (!reply.path("matches").isArray()) {
                throw new AiUnavailableException("The matching service gave no list of matches");
            }

            return reply;

        } catch (IOException | JacksonException exception) {
            throw new AiUnavailableException("The matching service could not be reached: " + exception.getClass().getSimpleName());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiUnavailableException("Interrupted while waiting for the matching service");
        }
    }

    public static class AiUnavailableException extends RuntimeException {

        public AiUnavailableException(String message) {
            super(message);
        }
    }
}
