package com.copymylife.copymylife.security;

import com.copymylife.copymylife.service.EmailService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Runs only when TEST_DB_URL points at a local database whose name ends in _test,
 * so a production or development database can never be reached from here.
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
                "MAILJET_SENDER_EMAIL=test@invalid.test"
        }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountIdentityRegressionTest {

    private static final String PASSWORD = "Audit#12345";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private EmailService emailService;

    private record Account(long id, String email, String token) {
    }

    private record Reply(int status, String body) {
    }

    @BeforeAll
    void requireTestDatabaseAndApplyConstraint() throws Exception {
        assertTrue(
                jdbc.queryForObject("select current_database()", String.class).endsWith("_test"),
                "Refusing to run outside a *_test database"
        );

        try (Connection connection = jdbc.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("db/manual/users_email_unique.sql"));
        }
    }

    // ---- registration ----

    @Test
    void newUserRegistersWithServerAssignedIdAndDefaults() throws Exception {
        String email = email();

        Reply reply = register(body("name", "New User", "email", email, "password", PASSWORD));

        assertEquals(200, reply.status());
        long id = json.readTree(reply.body()).path("user").path("id").asLong();
        Map<String, Object> row = row(id);
        assertEquals(email, row.get("email"));
        assertEquals("USER", row.get("role"));
        assertEquals(false, row.get("banned"));
        assertTrue(row.get("password").toString().startsWith("$2"), "Password must be stored hashed");
        assertFalse(reply.body().contains("$2"), "Response must not expose the hash");
    }

    @Test
    void registeringWithAnExistingIdLeavesThatAccountUntouched() throws Exception {
        Account victim = newAccount();
        Map<String, Object> before = row(victim.id());
        String attackerEmail = email();

        Reply reply = register(body("id", victim.id(), "name", "Attacker", "email", attackerEmail, "password", PASSWORD));

        assertEquals(200, reply.status());
        assertNotEquals(victim.id(), json.readTree(reply.body()).path("user").path("id").asLong());
        assertEquals(before, row(victim.id()));
        assertEquals(200, login(victim.email(), PASSWORD).status());
    }

    @Test
    void registeringWithAnExistingIdCannotReadThatAccountsPrivateData() throws Exception {
        Account victim = newAccount();

        Reply reply = register(body("id", victim.id(), "name", "Attacker", "email", email(), "password", PASSWORD));
        String attackerToken = json.readTree(reply.body()).path("token").asString();
        Reply me = get("/users/me", attackerToken);

        assertEquals(200, me.status());
        assertNotEquals(victim.id(), json.readTree(me.body()).path("id").asLong());
        assertNotEquals(victim.email(), json.readTree(me.body()).path("email").asString());
    }

    @Test
    void adminAccountCannotBeOverwrittenThroughRegistration() throws Exception {
        Account admin = newAccount();
        jdbc.update("update users set role = 'ADMIN' where id = ?", admin.id());
        Map<String, Object> before = row(admin.id());

        Reply reply = register(body("id", admin.id(), "name", "Attacker", "email", email(), "password", PASSWORD, "role", "ADMIN"));

        assertEquals(before, row(admin.id()));
        assertEquals(403, get("/users", json.readTree(reply.body()).path("token").asString()).status());
        assertEquals(200, get("/users", tokenFrom(login(admin.email(), PASSWORD))).status());
    }

    @Test
    void clientCannotChooseRoleOrBannedAtRegistration() throws Exception {
        Reply reply = register(body("name", "Attacker", "email", email(), "password", PASSWORD, "role", "ADMIN", "banned", true));

        assertEquals(200, reply.status());
        Map<String, Object> row = row(json.readTree(reply.body()).path("user").path("id").asLong());
        assertEquals("USER", row.get("role"));
        assertEquals(false, row.get("banned"));
        assertEquals(403, get("/users", json.readTree(reply.body()).path("token").asString()).status());
    }

    @Test
    void registeringWithAnUnusedIdIgnoresThatId() throws Exception {
        long unusedId = 900_000_000L + (System.nanoTime() % 1_000_000L);

        Reply reply = register(body("id", unusedId, "name", "New User", "email", email(), "password", PASSWORD));

        assertEquals(200, reply.status());
        assertNotEquals(unusedId, json.readTree(reply.body()).path("user").path("id").asLong());
    }

    @Test
    void duplicateEmailIsRejectedAndOriginalIsUnchanged() throws Exception {
        Account original = newAccount();
        Map<String, Object> before = row(original.id());

        Reply reply = register(body("name", "Copy", "email", original.email(), "password", "Other#12345"));

        assertEquals(409, reply.status());
        assertEquals(1, accountsWithEmail(original.email()));
        assertEquals(before, row(original.id()));
        assertEquals(401, login(original.email(), "Other#12345").status());
    }

    @Test
    void duplicateEmailDifferingOnlyByCaseIsRejected() throws Exception {
        Account original = newAccount();

        Reply reply = register(body("name", "Copy", "email", original.email().toUpperCase(), "password", PASSWORD));

        assertEquals(409, reply.status());
        assertEquals(1, accountsWithEmail(original.email()));
    }

    @Test
    void concurrentRegistrationsCreateExactlyOneAccount() throws Exception {
        String email = email();
        String payload = body("name", "Race", "email", email, "password", PASSWORD);
        List<Callable<Integer>> attempts = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            attempts.add(() -> register(payload).status());
        }

        ExecutorService pool = Executors.newFixedThreadPool(12);
        List<Integer> statuses = new ArrayList<>();
        try {
            for (Future<Integer> attempt : pool.invokeAll(attempts)) {
                statuses.add(attempt.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, statuses.stream().filter(status -> status == 200).count(), statuses.toString());
        assertEquals(11, statuses.stream().filter(status -> status == 409).count(), statuses.toString());
        assertEquals(1, accountsWithEmail(email));
        assertEquals(200, login(email, PASSWORD).status());
    }

    @Test
    void invalidRegistrationDataGetsBadRequest() throws Exception {
        assertEquals(400, register(body("name", "A", "email", email(), "password", PASSWORD)).status());
        assertEquals(400, register(body("name", "Valid", "email", "not-an-email", "password", PASSWORD)).status());
        assertEquals(400, register(body("name", "Valid", "email", email(), "password", "short1#")).status());
        assertEquals(400, register(body("name", "Valid", "email", email(), "password", "NoDigitsNoSpecial")).status());
        assertEquals(400, register(body("email", email(), "password", PASSWORD)).status());
        assertEquals(400, register(body("name", "Valid", "password", PASSWORD)).status());
        assertEquals(400, register(body("name", "Valid", "email", "x".repeat(250) + "@a.test", "password", PASSWORD)).status());
        assertEquals(400, register(body("name", "n".repeat(300), "email", email(), "password", PASSWORD)).status());
    }

    // ---- login ----

    @Test
    void emailIsStoredLowercaseAndLoginIgnoresCase() throws Exception {
        String email = email();
        String mixed = "MiXeD." + email;

        Reply reply = register(body("name", "Mixed", "email", mixed, "password", PASSWORD));

        assertEquals(200, reply.status());
        assertEquals(mixed.toLowerCase(), json.readTree(reply.body()).path("user").path("email").asString());
        assertEquals(200, login(mixed, PASSWORD).status());
        assertEquals(200, login(mixed.toLowerCase(), PASSWORD).status());
        assertEquals(200, login(mixed.toUpperCase(), PASSWORD).status());
    }

    @Test
    void accountStoredWithMixedCaseKeepsWorkingAndIsNotRewritten() throws Exception {
        Account account = newAccount();
        String legacyEmail = "Legacy." + account.email().toUpperCase();
        jdbc.update("update users set email = ? where id = ?", legacyEmail, account.id());
        Map<String, Object> before = row(account.id());

        Reply exact = login(legacyEmail, PASSWORD);

        assertEquals(200, exact.status());
        assertEquals(200, login(legacyEmail.toLowerCase(), PASSWORD).status());
        assertEquals(200, get("/users/me", tokenFrom(exact)).status());
        assertEquals(account.id(), json.readTree(exact.body()).path("user").path("id").asLong());
        assertEquals(409, register(body("name", "Copy", "email", legacyEmail.toLowerCase(), "password", PASSWORD)).status());
        assertEquals(before, row(account.id()));
    }

    @Test
    void loginStillRejectsBadCredentialsAndProtectsApi() throws Exception {
        Account account = newAccount();

        assertEquals(401, login(account.email(), "Wrong#12345").status());
        assertEquals(401, login(email(), PASSWORD).status());
        assertEquals(403, get("/users/me", null).status());
        assertEquals(403, get("/users/me", "not.a.token").status());
    }

    // ---- taking another user's e-mail through the profile ----

    @Test
    void cannotTakeAnotherUsersEmailThroughProfileUpdate() throws Exception {
        Account victim = newAccount();
        Account attacker = newAccount();
        Map<String, Object> attackerBefore = row(attacker.id());
        Map<String, Object> victimBefore = row(victim.id());

        Reply exact = put("/users/" + attacker.id(), attacker.token(), body("name", "Attacker", "email", victim.email()));
        Reply otherCase = put("/users/" + attacker.id(), attacker.token(), body("name", "Attacker", "email", victim.email().toUpperCase()));

        assertEquals(409, exact.status());
        assertEquals(409, otherCase.status());
        assertEquals(attackerBefore, row(attacker.id()));
        assertEquals(victimBefore, row(victim.id()));
        assertEquals(200, login(victim.email(), PASSWORD).status());
        assertEquals(200, get("/users/me", victim.token()).status());
    }

    @Test
    void cannotUpdateAnotherUsersAccount() throws Exception {
        Account owner = newAccount();
        Account other = newAccount();
        Map<String, Object> before = row(owner.id());

        Reply reply = put("/users/" + owner.id(), other.token(), body("name", "Hacked", "email", email()));

        assertEquals(403, reply.status());
        assertEquals(before, row(owner.id()));
    }

    @Test
    void userCanStillChangeOwnEmailAndKeepsSession() throws Exception {
        Account account = newAccount();
        String newEmail = email();

        Reply reply = put("/users/" + account.id(), account.token(), body("name", "Renamed", "email", newEmail.toUpperCase()));

        assertEquals(200, reply.status());
        assertEquals(newEmail, json.readTree(reply.body()).path("email").asString());
        assertEquals(200, get("/users/me", account.token()).status());
        assertEquals(200, login(newEmail, PASSWORD).status());
        assertEquals(401, login(account.email(), PASSWORD).status());
    }

    @Test
    void savingOwnUnchangedEmailIsNotAConflict() throws Exception {
        Account account = newAccount();

        assertEquals(200, put("/users/" + account.id(), account.token(), body("name", "Same", "email", account.email())).status());
        assertEquals(200, put("/users/" + account.id(), account.token(), body("name", "Same", "email", account.email().toUpperCase())).status());
    }

    @Test
    void accountUpdateRejectsInvalidDataAndIgnoresPrivilegedFields() throws Exception {
        Account account = newAccount();
        Map<String, Object> before = row(account.id());

        assertEquals(400, put("/users/" + account.id(), account.token(), body("name", "Valid", "email", "not-an-email")).status());
        assertEquals(400, put("/users/" + account.id(), account.token(), body("name", "", "email", email())).status());
        assertEquals(400, put("/users/" + account.id(), account.token(), body("name", "Valid")).status());
        assertEquals(before, row(account.id()));

        Reply reply = put("/users/" + account.id(), account.token(), body(
                "id", account.id() + 1, "name", "Valid", "email", account.email(), "role", "ADMIN", "banned", true, "password", "Changed#123"
        ));

        assertEquals(200, reply.status());
        Map<String, Object> after = row(account.id());
        assertEquals(before.get("role"), after.get("role"));
        assertEquals(before.get("banned"), after.get("banned"));
        assertEquals(before.get("password"), after.get("password"));
    }

    // ---- existing behaviour that must keep working ----

    @Test
    void profileCanStillBeViewedAndEditedWithoutTouchingEmail() throws Exception {
        Account account = newAccount();
        String username = "u" + UUID.randomUUID().toString().substring(0, 8);

        Reply updated = put("/users/me", account.token(), body(
                "name", "Profile Name", "username", username, "profession", "QA", "bio", "bio text", "email", email()
        ));
        JsonNode profile = json.readTree(get("/users/me", account.token()).body());

        assertEquals(200, updated.status());
        assertEquals("Profile Name", profile.path("name").asString());
        assertEquals(username, profile.path("username").asString());
        assertEquals("bio text", profile.path("bio").asString());
        assertEquals(account.email(), profile.path("email").asString());
    }

    @Test
    void passwordResetFindsAccountRegardlessOfTypedCase() throws Exception {
        Account account = newAccount();

        Reply reply = post("/auth/forgot-password", body("email", account.email().toUpperCase()));

        assertEquals(200, reply.status());
        verify(emailService).sendPasswordResetEmail(eq(account.email()), anyString());
    }

    // ---- helpers ----

    private Account newAccount() throws Exception {
        String email = email();
        Reply reply = register(body("name", "Test User", "email", email, "password", PASSWORD));
        assertEquals(200, reply.status());
        JsonNode node = json.readTree(reply.body());
        String token = node.path("token").asString();
        assertNotNull(token);
        return new Account(node.path("user").path("id").asLong(), email, token);
    }

    private String email() {
        return "regression_" + UUID.randomUUID().toString().replace("-", "") + "@audit.test";
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("select * from users where id = ?", id);
    }

    private int accountsWithEmail(String email) {
        return jdbc.queryForObject("select count(*) from users where lower(email) = lower(?)", Integer.class, email);
    }

    private String tokenFrom(Reply reply) throws Exception {
        return json.readTree(reply.body()).path("token").asString();
    }

    private String body(Object... pairs) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(pairs[i].toString(), pairs[i + 1]);
        }
        return json.writeValueAsString(values);
    }

    private Reply register(String payload) throws Exception {
        return post("/auth/register", payload);
    }

    private Reply login(String email, String password) throws Exception {
        return post("/auth/login", body("email", email, "password", password));
    }

    private Reply get(String path, String token) throws Exception {
        return send(request(path, token).GET());
    }

    private Reply post(String path, String payload) throws Exception {
        return send(request(path, null)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)));
    }

    private Reply put(String path, String token, String payload) throws Exception {
        return send(request(path, token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(payload)));
    }

    private HttpRequest.Builder request(String path, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private Reply send(HttpRequest.Builder builder) throws Exception {
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }
}
