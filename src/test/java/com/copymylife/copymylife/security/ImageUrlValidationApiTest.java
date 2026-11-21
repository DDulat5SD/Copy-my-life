package com.copymylife.copymylife.security;

import com.copymylife.copymylife.service.EmailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(
        named = "TEST_DB_URL",
        matches = "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):\\d+/\\w+_test"
)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=${TEST_DB_URL}",
                "spring.datasource.username=${TEST_DB_USERNAME:test}",
                "spring.datasource.password=${TEST_DB_PASSWORD:test}",
                "jwt.secret=dGVzdC1vbmx5LWtleS10ZXN0LW9ubHkta2V5LXRlc3Qtb25seS1rZXktdGVzdC1vbmx5",
                "spring.mail.username=test",
                "spring.mail.password=test",
                "MAILJET_API_KEY=test",
                "MAILJET_SECRET_KEY=test",
                "MAILJET_SENDER_EMAIL=test@invalid.test"
        }
)
class ImageUrlValidationApiTest {

    private static final String BOUNDARY = "UrlRegressionBoundary";

    private static final List<String> DANGEROUS = List.of(
            "javascript:alert(1)",
            "JaVaScRiPt:alert(1)",
            "  javascript:alert(1)",
            "java\tscript:alert(1)",
            "vbscript:msgbox(1)",
            "data:text/html,<script>alert(1)</script>",
            "data:image/svg+xml,<svg onload=alert(1)>",
            "file:///etc/passwd"
    );

    private static final List<String> ALLOWED = List.of(
            "https://i.ibb.co/abc/photo.jpg",
            "http://example.com/a.png",
            "/uploads/routines/a.png",
            "img/halloween/01-billy.jpg",
            "data:image/png;base64,iVBORw0KGgo="
    );

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @MockitoBean
    private EmailService emailService;

    @Test
    void profileRejectsDangerousAvatarUrlsAndKeepsThePreviousOne() throws Exception {
        String token = register();
        assertEquals(200, updateProfile(token, "https://example.com/first.png").statusCode());

        for (String url : DANGEROUS) {
            assertEquals(400, updateProfile(token, url).statusCode(), url);
        }

        JsonNode profile = json.readTree(send(HttpRequest.newBuilder(uri("/users/me"))
                .header("Authorization", "Bearer " + token).GET()).body());
        assertEquals("https://example.com/first.png", profile.path("avatarUrl").asString());
    }

    @Test
    void profileStillAcceptsNormalAvatarUrlsAndClearing() throws Exception {
        String token = register();

        for (String url : ALLOWED) {
            assertEquals(200, updateProfile(token, url).statusCode(), url);
        }
        assertEquals(200, updateProfile(token, null).statusCode());
    }

    @Test
    void routineRejectsDangerousImageUrlsOnCreateAndUpdate() throws Exception {
        String token = register();
        HttpResponse<String> created = saveRoutine(token, "POST", "/routines", "https://example.com/first.png");
        assertEquals(200, created.statusCode(), created.body());
        long id = json.readTree(created.body()).path("id").asLong();

        for (String url : DANGEROUS) {
            assertEquals(400, saveRoutine(token, "POST", "/routines", url).statusCode(), url);
            assertEquals(400, saveRoutine(token, "PUT", "/routines/" + id, url).statusCode(), url);
        }

        JsonNode stored = json.readTree(send(HttpRequest.newBuilder(uri("/routines/" + id))
                .header("Authorization", "Bearer " + token).GET()).body());
        assertEquals("https://example.com/first.png", stored.path("imageUrl").asString());
    }

    @Test
    void routineStillAcceptsNormalImageUrls() throws Exception {
        String token = register();

        for (String url : ALLOWED) {
            assertEquals(200, saveRoutine(token, "POST", "/routines", url).statusCode(), url);
        }
    }

    @Test
    void hostileTextIsStoredVerbatimAndReturnedAsJson() throws Exception {
        String token = register();
        String payload = "<img src=x onerror=alert(1)> & \"quoted\"";

        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/users/me"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("name", "Valid", "bio", payload)))));

        assertEquals(200, response.statusCode());
        assertEquals(payload, json.readTree(response.body()).path("bio").asString());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
    }

    @Test
    void helperScriptIsPublicBecausePagesLoadItWithoutAToken() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/safe-dom.js")).GET());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("SafeDom"));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String register() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "name", "Url QA",
                "email", "url_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test",
                "password", "Audit#12345"
        ));
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/auth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body()).path("token").asString();
    }

    private HttpResponse<String> updateProfile(String token, String avatarUrl) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Url QA");
        body.put("avatarUrl", avatarUrl);
        return send(HttpRequest.newBuilder(uri("/users/me"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
    }

    private HttpResponse<String> saveRoutine(String token, String method, String path, String imageUrl) throws Exception {
        StringBuilder body = new StringBuilder();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", "Url routine");
        fields.put("description", "d");
        fields.put("category", "Morning");
        fields.put("durationMinutes", "30");
        fields.put("isPublic", "false");
        fields.put("imageUrl", imageUrl);
        fields.forEach((key, value) -> body.append("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n")
                .append(value).append("\r\n"));
        body.append("--").append(BOUNDARY).append("--\r\n");

        return send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method(method, HttpRequest.BodyPublishers.ofString(body.toString())));
    }
}
