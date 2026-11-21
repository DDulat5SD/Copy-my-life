package com.copymylife.copymylife.feature;

import com.copymylife.copymylife.service.EmailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A banned member's routines are no longer anyone else's to see: not through Saved, Liked or
 * Commented routines, nor by a direct link, its activities, comments or ratings, nor to copy,
 * save or like. Nothing is deleted, so unbanning brings them and the bookmarks back.
 * Needs TEST_DB_URL (a local *_test database).
 */
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
                "MAILJET_SENDER_EMAIL=test@invalid.test",
                "app.upload-dir=target/test-uploads"
        }
)
class BannedAuthorRoutineVisibilityRegressionTest {

    private static final String BOUNDARY = "BannedAuthorBoundary";

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

    private record Reply(int status, String body) {
    }

    @Test
    void aBannedAuthorsRoutineIsClosedOnEveryReadPath() throws Exception {
        Account author = register("Soon banned");
        Account reader = register("Bookmarker");
        long routine = publicRoutine(author, "Banned morning");

        assertEquals(200, send("POST", "/saved-routines/" + routine, reader, null).status());
        assertEquals(200, send("POST", "/liked-routines/" + routine, reader, null).status());
        assertEquals(200, send("POST", "/routine-comments/" + routine, reader, Map.of("text", "Nice")).status());

        ban(author, true);

        // Signed in, or as a visitor by direct link.
        for (Account viewer : new Account[]{reader, null}) {
            for (String path : List.of("/routines/%d", "/routines/%d/routine-habits")) {
                assertEquals(404, send("GET", path.formatted(routine), viewer, null).status(), path);
            }
        }
        assertEquals(404, send("GET", "/routine-comments/routine/" + routine, reader, null).status());
        assertEquals(404, send("GET", "/routine-ratings/" + routine, reader, null).status());
        assertFalse(ids("/saved-routines", reader).contains(routine), "Saved routines");
        assertFalse(ids("/liked-routines", reader).contains(routine), "Liked routines");
        assertFalse(ids("/routine-comments/commented-routines", reader).contains(routine), "Commented routines");

        // Nor can it be taken further.
        for (String path : List.of("/routines/%d/copy", "/saved-routines/%d", "/liked-routines/%d")) {
            assertEquals(404, send("POST", path.formatted(routine), reader, null).status(), path);
        }
        assertEquals(404, send("POST", "/routine-comments/" + routine, reader, Map.of("text", "Again")).status());

        // Nothing was deleted.
        assertEquals(1, count("select count(*) from saved_routines where routine_id = ?", routine));
        assertEquals(1, count("select count(*) from routines where id = ? and deleted = false", routine));
    }

    @Test
    void unbanningBringsTheRoutineAndItsBookmarkBack() throws Exception {
        Account author = register("Unbanned later");
        Account reader = register("Patient");
        long routine = publicRoutine(author, "Comes back");
        assertEquals(200, send("POST", "/saved-routines/" + routine, reader, null).status());

        ban(author, true);
        assertFalse(ids("/saved-routines", reader).contains(routine));

        ban(author, false);
        assertTrue(ids("/saved-routines", reader).contains(routine));
        assertEquals(200, send("GET", "/routines/" + routine, reader, null).status());
        assertEquals(200, send("GET", "/routines/" + routine + "/routine-habits", null, null).status());
        assertEquals(200, send("POST", "/routines/" + routine + "/copy", reader, null).status());
    }

    @Test
    void theAuthorKeepsTheirRoutineOnceUnbannedAndCannotSignInWhileBanned() throws Exception {
        Account author = register("Own routine");
        long routine = publicRoutine(author, "Mine");

        ban(author, true);
        int status = send("GET", "/routines/" + routine, author, null).status();
        assertTrue(status == 401 || status == 403 || status == 404, "a banned member's token is refused: " + status);

        ban(author, false);
        Reply own = send("GET", "/routines/" + routine, author, null);
        assertEquals(200, own.status(), own.body());
        assertTrue(json.readTree(own.body()).path("ownedByMe").asBoolean());
    }

    @Test
    void otherRoutinesAndOwnCopiesAreUnaffected() throws Exception {
        Account banned = register("Banned author");
        Account other = register("Fine author");
        Account reader = register("Copier");
        long bannedRoutine = publicRoutine(banned, "Copied before the ban");
        long fine = publicRoutine(other, "Still public");
        Reply copy = send("POST", "/routines/" + bannedRoutine + "/copy", reader, null);
        assertEquals(200, copy.status(), copy.body());
        long copyId = json.readTree(copy.body()).path("id").asLong();
        assertEquals(200, send("POST", "/saved-routines/" + fine, reader, null).status());

        ban(banned, true);

        assertEquals(200, send("GET", "/routines/" + fine, reader, null).status());
        assertTrue(ids("/saved-routines", reader).contains(fine));
        assertEquals(200, send("GET", "/routines/" + copyId, reader, null).status(), "a copy is the copier's own");
        assertFalse(ids("/routines/public", null).contains(bannedRoutine), "Explore");
        assertTrue(ids("/routines/public", null).contains(fine), "Explore");

        long builtIn = count("select id from routines where built_in = true and deleted = false and hidden_at is null "
                + "and is_public = true order by id limit 1");
        assertEquals(200, send("GET", "/routines/" + builtIn, reader, null).status(), "built-in routines");
    }

    // ---- helpers ----

    private void ban(Account account, boolean banned) {
        jdbc.update("update users set banned = ? where id = ?", banned, account.id());
    }

    private List<Long> ids(String path, Account account) throws Exception {
        Reply reply = send("GET", path, account, null);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).valueStream().map(node -> node.path("id").asLong()).toList();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private Account register(String name) throws Exception {
        Reply reply = send("POST", "/auth/register", null, Map.of(
                "name", name,
                "email", "bannedroutine_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test",
                "password", "Audit#12345"));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private long publicRoutine(Account owner, String title) throws Exception {
        StringBuilder body = new StringBuilder();
        Map.of("title", title, "description", "Before work", "category", "Morning",
                "durationMinutes", "60", "isPublic", "true").forEach((key, value) ->
                body.append("--").append(BOUNDARY).append("\r\n")
                        .append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n")
                        .append(value).append("\r\n"));
        body.append("--").append(BOUNDARY).append("--\r\n");
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri("/routines"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("Authorization", "Bearer " + owner.token())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        long routine = json.readTree(response.body()).path("id").asLong();

        Reply habit = send("POST", "/routines/" + routine + "/habits", owner,
                Map.of("title", "Stretch", "description", "Stretch notes", "durationMinutes", 20));
        assertEquals(200, habit.status(), habit.body());
        return routine;
    }

    private Reply send(String method, String path, Account account, Map<String, ?> payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path));
        if (account != null) {
            builder.header("Authorization", "Bearer " + account.token());
        }
        if (payload != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(method, payload == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
