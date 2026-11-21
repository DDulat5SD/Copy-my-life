package com.copymylife.copymylife.feature;

import com.copymylife.copymylife.service.EmailService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-26 end to end with the real model: the backend asks the Python service in ai/ (started
 * here on a free local port), which runs the multilingual MiniLM ONNX model. An English-speaking
 * user with a morning gym-and-books routine is matched with a Russian member who lives the same
 * way before a Kazakh member who games at night (AC1, AC2), and the other way round once the user
 * changes interests and routine (AC3).
 * The service runs as on Railway (ai/Dockerfile): listening on "::" and PORT, open to other
 * hosts, and demanding X-AI-Token, which the backend sends from ai.matching.token.
 * Needs TEST_DB_URL, AI_PYTHON (a Python with ai/requirements.txt) and CML_AI_MODEL_DIR.
 */
@EnabledIfEnvironmentVariable(
        named = "TEST_DB_URL",
        matches = "jdbc:postgresql://(127\\.0\\.0\\.1|localhost):\\d+/\\w+_test"
)
@EnabledIfEnvironmentVariable(named = "AI_PYTHON", matches = ".+")
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
                "MAILJET_SENDER_EMAIL=test@invalid.test",
                "app.upload-dir=target/test-uploads",
                "ai.matching.timeout-seconds=60"
        }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LifestyleMatchingAiIntegrationTest {

    private static final String BOUNDARY = "LifestyleAiBoundary";
    private static final String RUN = UUID.randomUUID().toString().substring(0, 6);
    private static final String TOKEN = UUID.randomUUID() + "-" + UUID.randomUUID();
    private static Process service;
    private static Path serviceLog;

    @DynamicPropertySource
    static void matchingService(DynamicPropertyRegistry registry) {
        registry.add("ai.matching.url", LifestyleMatchingAiIntegrationTest::startService);
        registry.add("ai.matching.token", () -> TOKEN);
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private EmailService emailService;

    private record Account(long id, String token) {
    }

    @AfterAll
    void stopService() {
        if (service != null) {
            service.descendants().forEach(ProcessHandle::destroyForcibly);
            service.destroyForcibly();
        }
    }

    @Test
    void theModelMatchesLifestylesAcrossLanguagesAndFollowsChanges() throws Exception {
        assertTrue(jdbc.queryForObject("select current_database()", String.class).endsWith("_test"));

        Account me = register("Aru " + RUN);
        profile(me, "Aru " + RUN, "Java, gym, reading", "Get stronger and read more books");
        long mine = routine(me, "Morning routine", false);
        habit(me, mine, "Gym workout", "06:30", List.of("MON", "WED", "FRI"));
        habit(me, mine, "Read a book", "07:30", List.of("MON", "TUE", "WED", "THU", "FRI"));

        Account russian = register("Дмитрий " + RUN);
        profile(russian, "Дмитрий " + RUN, "программирование, фитнес, книги", "Утром спортзал, потом книги");
        long russianRoutine = routine(russian, "Утренний режим", true);
        habit(russian, russianRoutine, "Фитнес", "06:00", List.of("MON", "WED", "FRI"));
        habit(russian, russianRoutine, "Чтение книг", "07:00", List.of("MON", "TUE", "WED", "THU", "FRI"));

        Account kazakh = register("Нұрлан " + RUN);
        profile(kazakh, "Нұрлан " + RUN, "компьютер ойындары, фильмдер", "Түнде ойын ойнаймын");
        long kazakhRoutine = routine(kazakh, "Түнгі режим", true);
        habit(kazakh, kazakhRoutine, "Ойын ойнау", "23:00", List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"));
        habit(kazakh, kazakhRoutine, "Фильм көру", "01:00", List.of("SAT", "SUN"));

        // AC1: the Russian member who lives like the user comes first.
        JsonNode before = matches(me);
        assertEquals("OK", before.path("status").asString(), before.toString());
        assertRankedAbove(before, russian.id(), kazakh.id());
        JsonNode card = card(before, russian.id());
        assertTrue(card.path("components").path("semantic").isNumber(), card.toString());
        assertEquals(100, card.path("components").path("schedule").asInt(), "the same mornings: " + card);
        assertTrue(card.path("sharedInterests").size() >= 2, "gym ~ фитнес, reading ~ книги: " + card);
        assertEquals(List.of("morning"), texts(card.path("sharedTimesOfDay")));
        // AC2: the card leads to the member's public routine.
        assertEquals(russianRoutine, card.path("publicRoutines").get(0).path("id").asLong());
        assertEquals(200, get("/routines/" + russianRoutine, me).statusCode());

        // AC3: the user now games and watches films at night.
        profile(me, "Aru " + RUN, "computer games, movies", "Play more games");
        long step = jdbc.queryForObject("select id from routine_habits where routine_id = ? order by position limit 1", Long.class, mine);
        long habit = jdbc.queryForObject("select habit_id from routine_habits where id = ?", Long.class, step);
        assertEquals(200, send("PUT", "/routines/" + mine + "/habits/" + habit, me,
                Map.of("title", "Play video games", "description", "Evening", "durationMinutes", 30)).statusCode());
        assertEquals(200, send("PUT", "/routines/" + mine + "/routine-habits/" + step, me,
                Map.of("startTime", "23:30", "durationMinutes", 30, "repeatDays", List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"))).statusCode());

        JsonNode after = matches(me);
        assertRankedAbove(after, kazakh.id(), russian.id());
        assertFalse(after.toString().equals(before.toString()));
        assertTrue(card(after, kazakh.id()).path("score").asInt() > scoreOrZero(before, kazakh.id()), after.toString());
    }

    @Test
    void theServiceAnswersOnlyTheBackendsToken() throws Exception {
        String url = startService();
        String body = "{\"user\":{\"id\":1,\"interests\":\"gym\"},\"candidates\":[{\"id\":2,\"interests\":\"fitness\"}]}";
        for (String token : new String[]{null, "wrong-" + TOKEN}) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url + "/match"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (token != null) {
                request.header("X-AI-Token", token);
            }
            assertEquals(401, http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(url + "/health")).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode(), "the healthcheck needs no token");
    }

    // ---- the real service ----

    private static synchronized String startService() {
        if (service == null) {
            try {
                int port;
                try (ServerSocket socket = new ServerSocket(0)) {
                    port = socket.getLocalPort();
                }
                serviceLog = Files.createTempFile("lifestyle-ai", ".log");
                ProcessBuilder builder = new ProcessBuilder(System.getenv("AI_PYTHON"), "-X", "utf8", "app.py")
                        .directory(new File("ai"))
                        .redirectErrorStream(true)
                        .redirectOutput(serviceLog.toFile());
                // The container's settings: all interfaces, Railway's PORT, a required token.
                builder.environment().put("PORT", String.valueOf(port));
                builder.environment().put("CML_AI_HOST", "::");
                builder.environment().put("CML_AI_ALLOW_REMOTE", "1");
                builder.environment().put("CML_AI_TOKEN", TOKEN);
                builder.environment().remove("CML_AI_PORT");
                service = builder.start();

                // By name, as the backend reaches copymylife-ai.railway.internal.
                String url = "http://localhost:" + port;
                HttpClient client = HttpClient.newHttpClient();
                for (int waited = 0; waited < 90; waited++) {
                    try {
                        HttpResponse<String> health = client.send(HttpRequest.newBuilder(URI.create(url + "/health"))
                                .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
                        if (health.statusCode() == 200) {
                            System.setProperty("lifestyle.ai.url", url);
                            return url;
                        }
                    } catch (Exception notYet) {
                        // still loading the model
                    }
                    if (!service.isAlive()) {
                        break;
                    }
                    Thread.sleep(1000);
                }
                throw new IllegalStateException("The matching service did not start: " + Files.readString(serviceLog));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
        return System.getProperty("lifestyle.ai.url");
    }

    // ---- helpers ----

    private static void assertRankedAbove(JsonNode reply, long first, long second) {
        List<Long> ids = new ArrayList<>();
        reply.path("matches").forEach(card -> ids.add(card.path("userId").asLong()));
        assertTrue(ids.contains(first), first + " not recommended: " + reply);
        assertTrue(!ids.contains(second) || ids.indexOf(first) < ids.indexOf(second), ids + " " + reply);
    }

    private static JsonNode card(JsonNode reply, long userId) {
        for (JsonNode card : reply.path("matches")) {
            if (card.path("userId").asLong() == userId) {
                return card;
            }
        }
        throw new AssertionError("no card for " + userId + " in " + reply);
    }

    private static int scoreOrZero(JsonNode reply, long userId) {
        for (JsonNode card : reply.path("matches")) {
            if (card.path("userId").asLong() == userId) {
                return card.path("score").asInt();
            }
        }
        return 0;
    }

    private static List<String> texts(JsonNode list) {
        List<String> values = new ArrayList<>();
        list.forEach(item -> values.add(item.asString()));
        return values;
    }

    private JsonNode matches(Account who) throws Exception {
        HttpResponse<String> reply = get("/users/me/lifestyle-matches", who);
        assertEquals(200, reply.statusCode(), reply.body());
        return json.readTree(reply.body());
    }

    private void profile(Account who, String name, String interests, String bio) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("interests", interests);
        body.put("bio", bio);
        HttpResponse<String> reply = send("PUT", "/users/me", who, body);
        assertEquals(200, reply.statusCode(), reply.body());
    }

    private Account register(String name) throws Exception {
        HttpResponse<String> reply = send("POST", "/auth/register", null, Map.of(
                "name", name,
                "email", "match26ai_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test",
                "password", "Audit#12345"));
        assertEquals(200, reply.statusCode(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private long routine(Account owner, String title, boolean isPublic) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", "");
        fields.put("category", "Morning");
        fields.put("durationMinutes", "60");
        fields.put("isPublic", String.valueOf(isPublic));

        StringBuilder body = new StringBuilder();
        fields.forEach((key, value) -> body.append("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n")
                .append(value).append("\r\n"));
        body.append("--").append(BOUNDARY).append("--\r\n");

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/routines"))
                .header("Authorization", "Bearer " + owner.token())
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body()).path("id").asLong();
    }

    private void habit(Account owner, long routine, String title, String time, List<String> days) throws Exception {
        HttpResponse<String> reply = send("POST", "/routines/" + routine + "/habits", owner, Map.of(
                "title", title, "description", "", "durationMinutes", 20, "startTime", time, "repeatDays", days));
        assertEquals(200, reply.statusCode(), reply.body());
    }

    private HttpResponse<String> get(String path, Account who) throws Exception {
        return send("GET", path, who, null);
    }

    private HttpResponse<String> send(String method, String path, Account who, Map<String, ?> payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .method(method, payload == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload), StandardCharsets.UTF_8));
        if (payload != null) {
            builder.header("Content-Type", "application/json");
        }
        if (who != null) {
            builder.header("Authorization", "Bearer " + who.token());
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
