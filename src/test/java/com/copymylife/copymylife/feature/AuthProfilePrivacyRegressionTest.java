package com.copymylife.copymylife.feature;

import com.copymylife.copymylife.service.EmailService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.SecretKey;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyString;

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
                "jwt.secret=" + AuthProfilePrivacyRegressionTest.SECRET,
                "spring.mail.username=test",
                "spring.mail.password=test",
                "MAILJET_API_KEY=test",
                "MAILJET_SECRET_KEY=test",
                "MAILJET_SENDER_EMAIL=test@invalid.test"
        }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthProfilePrivacyRegressionTest {

    static final String SECRET = "dGVzdC1vbmx5LWtleS10ZXN0LW9ubHkta2V5LXRlc3Qtb25seS1rZXktdGVzdC1vbmx5";
    private static final String PASSWORD = "Audit#12345";
    private static final String BOUNDARY = "AuthProfileBoundary";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private EmailService emailService;

    private JsonNode browser;

    private record Reply(int status, String body) {
    }

    private record Account(long id, String email, String token) {
    }

    // ---- US-02: sign in ----

    @Test
    void signingInWorksWithAnyCaseOfTheEmail() throws Exception {
        Account account = register();

        Reply reply = login(account.email().toUpperCase(), PASSWORD);

        assertEquals(200, reply.status(), reply.body());
        String token = json.readTree(reply.body()).path("token").asString();
        assertEquals(200, get("/users/me", token).status());
        assertFalse(reply.body().contains("password"), "no password data is sent back");
    }

    @Test
    void aFailedSignInSaysNothingAboutWhichPartWasWrong() throws Exception {
        Account account = register();

        Reply wrongPassword = login(account.email(), "Wrong#12345");
        Reply unknownEmail = login("nobody_" + UUID.randomUUID() + "@audit.test", PASSWORD);

        assertEquals(401, wrongPassword.status());
        assertEquals(401, unknownEmail.status());
        assertEquals(wrongPassword.body(), unknownEmail.body());
        assertEquals("Invalid email or password. Please try again.", message(wrongPassword));
    }

    @Test
    void protectedDataNeedsAValidSession() throws Exception {
        for (String path : List.of("/users/me", "/routines", "/habit-library", "/calendar-events?from=2030-01-07&to=2030-01-13")) {
            assertEquals(403, get(path, null).status(), path);
            assertEquals(403, get(path, "not.a.token").status(), path);
        }
    }

    // ---- US-02: sign out and revocation ----

    @Test
    void signingOutMakesThatTokenUselessEverywhere() throws Exception {
        Account account = register();
        String otherDevice = json.readTree(login(account.email(), PASSWORD).body()).path("token").asString();

        Reply out = post("/auth/logout", account.token(), null);

        assertEquals(204, out.status(), out.body());
        assertEquals(403, get("/users/me", account.token()).status(), "the signed-out token is refused");
        assertEquals(403, put("/users/me", account.token(), profile("Changed", null)).status());
        assertEquals(200, get("/users/me", otherDevice).status(), "another session stays signed in");
        assertEquals(1, jdbc.queryForObject("select count(*) from revoked_tokens where user_id = ?", Integer.class, account.id()));
        assertEquals(0, jdbc.queryForObject("select count(*) from revoked_tokens where token_hash = ?", Integer.class, account.token()),
                "only a hash of the token is stored");
    }

    @Test
    void signingOutTwiceOrWithoutATokenIsHarmless() throws Exception {
        Account account = register();

        assertEquals(204, post("/auth/logout", account.token(), null).status());
        assertEquals(204, post("/auth/logout", account.token(), null).status());
        assertEquals(204, post("/auth/logout", null, null).status());
        assertEquals(204, post("/auth/logout", "not.a.token", null).status());

        assertEquals(1, jdbc.queryForObject("select count(*) from revoked_tokens where user_id = ?", Integer.class, account.id()));
    }

    @Test
    void simultaneousSignOutsStoreOneRevocation() throws Exception {
        Account account = register();

        List<Integer> statuses = together(10, index -> post("/auth/logout", account.token(), null).status());

        assertTrue(statuses.stream().allMatch(status -> status == 204), statuses.toString());
        assertEquals(1, jdbc.queryForObject("select count(*) from revoked_tokens where user_id = ?", Integer.class, account.id()));
        assertEquals(403, get("/users/me", account.token()).status());
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        Account account = register();
        String expired = Jwts.builder()
                .subject(String.valueOf(account.id()))
                .issuedAt(new Date(System.currentTimeMillis() - 7_200_000))
                .expiration(new Date(System.currentTimeMillis() - 3_600_000))
                .signWith(key())
                .compact();

        assertEquals(403, get("/users/me", expired).status());
    }

    @Test
    void aForgedOrAlteredTokenIsRefused() throws Exception {
        Account victim = register();
        Account attacker = register();
        String[] parts = attacker.token().split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"sub\":\"" + attacker.id() + "\"", "\"sub\":\"" + victim.id() + "\"");
        String altered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        String otherKey = Jwts.builder().subject(String.valueOf(victim.id()))
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(Keys.hmacShaKeyFor(new byte[48])).compact();
        String unsigned = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes())
                + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"sub\":\"" + victim.id() + "\"}").getBytes()) + ".";

        for (String token : List.of(altered, otherKey, unsigned)) {
            assertEquals(403, get("/users/me", token).status(), token);
        }
    }

    @Test
    void tokensIssuedBeforeThisReleaseKeepWorking() throws Exception {
        Account account = register();
        String legacy = Jwts.builder()
                .subject(String.valueOf(account.id()))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(key())
                .compact();

        assertEquals(200, get("/users/me", legacy).status());
        assertEquals(204, post("/auth/logout", legacy, null).status());
        assertEquals(403, get("/users/me", legacy).status(), "an old token can be signed out too");
    }

    @Test
    void anAccountSavedByAnEarlierVersionCanStillSignIn() throws Exception {
        String email = "legacy_" + UUID.randomUUID().toString().substring(0, 10) + "@audit.test";
        jdbc.update("insert into users (name, email, password, role, banned) values ('Legacy', ?, ?, 'USER', false)",
                email, passwordEncoder.encode(PASSWORD));

        Reply reply = login(email, PASSWORD);

        assertEquals(200, reply.status(), reply.body());
        assertEquals(200, get("/users/me", json.readTree(reply.body()).path("token").asString()).status());
    }

    @Test
    void aBannedUserIsSignedOut() throws Exception {
        Account account = register();
        jdbc.update("update users set banned = true where id = ?", account.id());

        assertEquals(403, get("/users/me", account.token()).status());
        assertEquals(401, login(account.email(), PASSWORD).status());
    }

    // ---- US-02: password reset ----

    @Test
    void resettingThePasswordSignsOutEveryOldSession() throws Exception {
        Account account = register();
        String resetToken = requestReset(account.email());

        Reply weak = post("/auth/reset-password", null, Map.of("token", resetToken, "newPassword", "short"));
        assertEquals(400, weak.status(), weak.body());
        assertEquals(200, get("/users/me", account.token()).status(), "a refused reset changes nothing");

        Reply reset = post("/auth/reset-password", null, Map.of("token", resetToken, "newPassword", "Better#2026"));

        assertEquals(200, reset.status(), reset.body());
        assertEquals(403, get("/users/me", account.token()).status(), "sessions from before the reset are closed");
        assertEquals(401, login(account.email(), PASSWORD).status());
        Reply fresh = login(account.email(), "Better#2026");
        assertEquals(200, fresh.status());
        assertEquals(200, get("/users/me", json.readTree(fresh.body()).path("token").asString()).status());
        assertEquals(400, post("/auth/reset-password", null, Map.of("token", resetToken, "newPassword", "Another#2026")).status(),
                "a reset link works once");
    }

    @Test
    void askingForAResetRevealsNothingAboutWhoHasAnAccount() throws Exception {
        Account account = register();
        clearInvocations(emailService);

        Reply known = post("/auth/forgot-password", null, Map.of("email", account.email()));
        Reply unknown = post("/auth/forgot-password", null, Map.of("email", "nobody_" + UUID.randomUUID() + "@audit.test"));

        assertEquals(200, known.status());
        assertEquals(200, unknown.status(), unknown.body());
        assertEquals(known.body(), unknown.body());
        verify(emailService).sendPasswordResetEmail(eq(account.email()), anyString());
    }

    // ---- US-03: personal profile ----

    @Test
    void theWholeProfileIsSavedAndSurvivesARefresh() throws Exception {
        Account account = register();
        String username = "u" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = profile("  Ada Lovelace  ", "@" + username);
        body.put("bio", "Writes programs");
        body.put("interests", "Mathematics, poetry");
        body.put("goals", "Run 5 km");
        body.put("profession", "Analyst");

        Reply saved = put("/users/me", account.token(), body);

        assertEquals(200, saved.status(), saved.body());
        JsonNode me = json.readTree(get("/users/me", account.token()).body());
        assertEquals("Ada Lovelace", me.path("name").asString());
        assertEquals(username, me.path("username").asString(), "a leading @ is not part of the username");
        assertEquals("Mathematics, poetry", me.path("interests").asString());
        assertEquals("Run 5 km", me.path("goals").asString());
        assertEquals("Writes programs", me.path("bio").asString());
        assertEquals(account.email(), me.path("email").asString());
    }

    @Test
    void anEmptyUsernameStaysEmpty() throws Exception {
        Account account = register();

        assertEquals(200, put("/users/me", account.token(), profile("Plain Name", "   ")).status());

        assertNull(jdbc.queryForObject("select username from users where id = ?", String.class, account.id()));
    }

    @Test
    void invalidProfilesAreRefusedWithAReasonAndChangeNothing() throws Exception {
        Account account = register();
        String before = userRow(account.id());

        List<Map<String, Object>> bad = new ArrayList<>(List.of(
                profile("", null),
                profile("   ", null),
                profile("A", null),
                profile("x".repeat(61), null),
                profile("Fine Name", "a"),
                profile("Fine Name", "bad name!"),
                profile("Fine Name", "x".repeat(31))
        ));
        for (String field : List.of("bio", "interests", "goals")) {
            Map<String, Object> body = profile("Fine Name", null);
            body.put(field, "y".repeat(501));
            bad.add(body);
        }
        Map<String, Object> unsafeAvatar = profile("Fine Name", null);
        unsafeAvatar.put("avatarUrl", "javascript:alert(1)");
        bad.add(unsafeAvatar);

        for (Map<String, Object> body : bad) {
            Reply reply = put("/users/me", account.token(), body);
            assertEquals(400, reply.status(), body + " " + reply.body());
            assertFalse(message(reply).isBlank(), reply.body());
        }
        assertEquals(before, userRow(account.id()));
    }

    @Test
    void aUsernameBelongsToOnePersonWhateverItsCase() throws Exception {
        Account first = register();
        Account second = register();
        String name = "Taken" + UUID.randomUUID().toString().substring(0, 6);
        assertEquals(200, put("/users/me", first.token(), profile("First", name)).status());

        Reply clash = put("/users/me", second.token(), profile("Second", name.toLowerCase()));

        assertEquals(409, clash.status(), clash.body());
        assertEquals("That username is already taken", message(clash));
        assertNull(jdbc.queryForObject("select username from users where id = ?", String.class, second.id()));
        assertEquals(200, put("/users/me", first.token(), profile("First renamed", name)).status(), "keeping your own name is fine");
    }

    @Test
    void usernamesSharedBeforeThisReleaseAreLeftAlone() throws Exception {
        Account first = register();
        Account second = register();
        String shared = "shared" + UUID.randomUUID().toString().substring(0, 6);
        jdbc.update("update users set username = ? where id in (?, ?)", shared, first.id(), second.id());

        Reply keep = put("/users/me", first.token(), profile("Keeps It", shared));
        Reply takeOther = put("/users/me", register().token(), profile("Newcomer", shared));

        assertEquals(200, keep.status(), keep.body());
        assertEquals(409, takeOther.status());
        assertEquals(shared, jdbc.queryForObject("select username from users where id = ?", String.class, second.id()),
                "no existing account is renamed");
    }

    @Test
    void simultaneousClaimsOfOneUsernameLeaveOneOwner() throws Exception {
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            accounts.add(register());
        }
        String name = "race" + UUID.randomUUID().toString().substring(0, 8);

        List<Integer> statuses = together(accounts.size(), index -> put("/users/me", accounts.get(index).token(), profile("Racer", name)).status());

        assertEquals(1, statuses.stream().filter(status -> status == 200).count(), statuses.toString());
        assertEquals(accounts.size() - 1, statuses.stream().filter(status -> status == 409).count(), statuses.toString());
        assertEquals(1, jdbc.queryForObject("select count(*) from users where lower(username) = lower(?)", Integer.class, name));
    }

    @Test
    void emailsStayUniqueWhateverTheirCase() throws Exception {
        Account first = register();
        Account second = register();

        Reply taken = put("/users/" + second.id(), second.token(), Map.of("name", "Second", "email", first.email().toUpperCase()));
        Reply duplicate = post("/auth/register", null, Map.of("name", "Copycat", "email", first.email().toUpperCase(), "password", PASSWORD));

        assertEquals(409, taken.status(), taken.body());
        assertEquals(409, duplicate.status());
        assertEquals(second.email(), jdbc.queryForObject("select email from users where id = ?", String.class, second.id()));
    }

    @Test
    void nobodyCanChangeSomeoneElsesAccount() throws Exception {
        Account victim = register();
        Account attacker = register();
        String before = userRow(victim.id());

        assertEquals(403, put("/users/" + victim.id(), attacker.token(), Map.of("name", "Owned", "email", attacker.email())).status());
        assertEquals(403, send("DELETE", "/users/" + victim.id(), attacker.token(), null).status());
        assertEquals(403, get("/users/" + victim.id(), attacker.token()).status());
        assertEquals(200, put("/users/me", attacker.token(), profile("Only Me", null)).status());

        assertEquals(before, userRow(victim.id()));
    }

    @Test
    void aFailedProfileSaveRollsBack() throws Exception {
        Account account = register();
        String before = userRow(account.id());
        jdbc.execute("""
                create or replace function profile_test_fail() returns trigger language plpgsql as $$
                begin raise exception 'profile test failure'; end $$
                """);
        jdbc.execute("create trigger profile_test_fail before update on users for each row when (new.id = "
                + account.id() + ") execute function profile_test_fail()");
        try {
            Map<String, Object> body = profile("Will Fail", "fail" + UUID.randomUUID().toString().substring(0, 6));
            body.put("goals", "never saved");
            Reply reply = put("/users/me", account.token(), body);
            assertTrue(reply.status() >= 500, reply.status() + " " + reply.body());
        } finally {
            jdbc.execute("drop trigger if exists profile_test_fail on users");
            jdbc.execute("drop function if exists profile_test_fail()");
        }

        assertEquals(before, userRow(account.id()));
    }

    // ---- US-03: profile photo ----

    @Test
    void aProfilePhotoCanBeUploaded() throws Exception {
        Account account = register();

        Reply reply = upload(account.token(), "me.png", "image/png", png());

        assertEquals(200, reply.status(), reply.body());
        String url = json.readTree(reply.body()).path("avatarUrl").asString();
        assertTrue(url.startsWith("/uploads/avatars/") && url.endsWith(".png"), url);
        assertEquals(url, json.readTree(get("/users/me", account.token()).body()).path("avatarUrl").asString());
        HttpResponse<byte[]> served = http.send(HttpRequest.newBuilder(uri(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, served.statusCode());
    }

    @Test
    void anUnsafeProfilePhotoIsRefused() throws Exception {
        Account account = register();
        String before = userRow(account.id());

        assertEquals(415, upload(account.token(), "evil.png", "image/png", "<script>alert(1)</script>".getBytes()).status());
        assertEquals(415, upload(account.token(), "vector.svg", "image/svg+xml",
                "<svg xmlns='http://www.w3.org/2000/svg' onload='alert(1)'/>".getBytes()).status());
        assertEquals(415, upload(account.token(), "page.html", "text/html", png()).status());
        assertEquals(403, upload(null, "me.png", "image/png", png()).status());

        assertEquals(before, userRow(account.id()));
    }

    // ---- US-03 / US-23: what other people see ----

    @Test
    void otherPeopleSeeOnlyThePublicPartOfAProfile() throws Exception {
        Account owner = register();
        Map<String, Object> body = profile("Public Person", "pub" + UUID.randomUUID().toString().substring(0, 6));
        body.put("interests", "Chess");
        body.put("goals", "Learn Kazakh");
        put("/users/me", owner.token(), body);
        Account viewer = register();

        Reply profile = get("/users/" + owner.id() + "/profile", viewer.token());
        Reply search = get("/users/search?query=Public%20Person", viewer.token());

        assertEquals(200, profile.status());
        JsonNode node = json.readTree(profile.body());
        assertEquals("Chess", node.path("interests").asString());
        assertEquals("Learn Kazakh", node.path("goals").asString());
        for (Reply reply : List.of(profile, search)) {
            assertNoSecrets(reply.body(), owner);
        }
    }

    // ---- US-23: private routines ----

    @Test
    void followersAndFriendsGetNoWayIntoAPrivateRoutine() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Secret plan", false);
        post("/routines/" + routine + "/habits", owner, Map.of("title", "Hidden habit", "durationMinutes", 1));
        Account follower = register();
        Account friend = register();
        post("/users/" + owner.id() + "/follow", follower.token(), null);
        long request = json.readTree(post("/friends/request/" + owner.id(), friend.token(), null).body()).path("requestId").asLong();
        assertEquals(200, send("PUT", "/friends/requests/" + request + "/accept", owner.token(), null).status());

        for (Account outsider : List.of(follower, friend)) {
            assertEquals(404, get("/routines/" + routine, outsider.token()).status());
            assertEquals(404, get("/routines/" + routine + "/routine-habits", outsider.token()).status());
            assertEquals(404, post("/routines/" + routine + "/copy", outsider.token(), null).status());
            assertEquals(404, post("/liked-routines/" + routine, outsider.token(), null).status());
            assertEquals(404, post("/saved-routines/" + routine, outsider.token(), null).status());
            assertEquals(404, post("/routine-ratings/" + routine, outsider.token(), Map.of("value", 5)).status());
            assertEquals(404, post("/routine-comments/" + routine, outsider.token(), Map.of("text", "hi")).status());
            assertEquals(404, get("/routine-comments/routine/" + routine, outsider.token()).status());
            assertEquals(400, post("/routine-runs/" + routine + "/start", outsider.token(), null).status());
            assertFalse(get("/users/" + owner.id() + "/routines/public", outsider.token()).body().contains("Secret plan"));
        }
        assertFalse(get("/routines/public", null).body().contains("Secret plan"));
        assertFalse(get("/top-routines", null).body().contains("Secret plan"));
        assertEquals(200, get("/routines/" + routine, owner.token()).status(), "the owner always sees it");
    }

    @Test
    void switchingBetweenPublicAndPrivateTakesEffectAtOnce() throws Exception {
        Account owner = register();
        long routine = routine(owner, "Switching plan", true);
        Account viewer = register();
        assertEquals(200, get("/routines/" + routine, viewer.token()).status());

        assertEquals(200, saveRoutine("PUT", "/routines/" + routine, owner, "Switching plan", false).status());
        assertEquals(404, get("/routines/" + routine, viewer.token()).status());
        assertFalse(json.readTree(get("/routines/" + routine, owner.token()).body()).path("public").asBoolean());
        assertTrue(get("/routines", owner.token()).body().contains("Switching plan"), "the owner still lists it");

        assertEquals(200, saveRoutine("PUT", "/routines/" + routine, owner, "Switching plan", true).status());
        assertEquals(200, get("/routines/" + routine, viewer.token()).status());
        assertTrue(json.readTree(get("/routines/" + routine, owner.token()).body()).path("public").asBoolean());
    }

    @Test
    void schemaChangesOnlyAdd() {
        Map<String, String> columns = new HashMap<>();
        jdbc.queryForList("""
                select column_name, is_nullable from information_schema.columns
                where table_name = 'users' and column_name in ('interests', 'goals', 'token_version')
                """).forEach(row -> columns.put((String) row.get("column_name"), (String) row.get("is_nullable")));
        assertEquals(Map.of("interests", "YES", "goals", "YES", "token_version", "YES"), columns);

        List<String> constraints = jdbc.queryForList(
                "select conname from pg_constraint where conrelid = 'revoked_tokens'::regclass", String.class);
        assertTrue(constraints.contains("uk_revoked_tokens_hash"), constraints.toString());
    }

    // ---- real browser ----

    @BeforeAll
    void signInAndEditTheProfileInABrowser() throws Exception {
        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Account main = register();
        Account rival = register();
        String rivalName = "rival" + UUID.randomUUID().toString().substring(0, 6);
        put("/users/me", rival.token(), profile("Rival", rivalName));
        long privateRoutine = routine(main, "Quiet plan", false);
        long publicRoutine = routine(main, "Shared plan", true);

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("email", main.email());
        fixtures.put("password", PASSWORD);
        fixtures.put("rivalName", rivalName);
        fixtures.put("newUsername", "ada" + UUID.randomUUID().toString().substring(0, 6));
        fixtures.put("privateRoutine", privateRoutine);
        fixtures.put("publicRoutine", publicRoutine);

        Path dir = Files.createTempDirectory("auth-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/auth-profile-check.mjs", chrome,
                dir.resolve("fixtures.json").toString(), dir.resolve("results.json").toString()
        ).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();

        if (!process.waitFor(4, TimeUnit.MINUTES)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new AssertionError("Browser check timed out: " + readQuietly(dir.resolve("node.log")));
        }
        assertEquals(0, process.exitValue(), () -> readQuietly(dir.resolve("node.log")));
        browser = json.readTree(Files.readString(dir.resolve("results.json")));
    }

    private JsonNode page(String name) {
        Assumptions.assumeTrue(browser != null, "Chrome or node not found");
        return browser.path(name);
    }

    @Test
    void browserSignsInAndExplainsAWrongPassword() {
        assertTrue(page("wrongPassword").path("error").asString().contains("Invalid email or password"), browser.toString());
        assertTrue(page("signedIn").path("url").asString().endsWith("/main.html"), browser.toString());
        assertTrue(page("signedIn").path("hasToken").asBoolean());
    }

    @Test
    void browserSignOutEndsTheSessionOnTheServerToo() {
        JsonNode out = page("signedOut");

        assertTrue(out.path("url").asString().contains("login.html"), browser.toString());
        assertFalse(out.path("hasToken").asBoolean());
        assertFalse(out.path("hasUser").asBoolean());
        assertEquals(403, out.path("oldTokenStatus").asInt(), "the old token no longer works");
        assertTrue(out.path("notice").asString().contains("signed out"), out.toString());
    }

    @Test
    void browserSendsAnExpiredSessionBackToSignIn() {
        JsonNode expired = page("expired");

        assertTrue(expired.path("url").asString().contains("login.html"), browser.toString());
        assertFalse(expired.path("hasToken").asBoolean());
        assertTrue(expired.path("notice").asString().contains("session has ended"), expired.toString());
    }

    @Test
    void browserEditsTheProfileWithSaveCancelAndRefresh() {
        JsonNode opened = page("editOpened");
        assertEquals("", opened.path("usernameField").asString(), "an empty username is not filled with placeholder text");

        JsonNode cancelled = page("afterCancel");
        assertFalse(cancelled.path("name").asString().contains("Cancelled"), browser.toString());

        JsonNode saved = page("afterSave");
        assertEquals("Ada Browser", saved.path("name").asString(), browser.toString());
        assertTrue(saved.path("interests").asString().contains("Robots"), saved.toString());
        assertTrue(saved.path("goals").asString().contains("Marathon"), saved.toString());

        JsonNode reloaded = page("afterReload");
        assertEquals("Ada Browser", reloaded.path("name").asString());
        assertTrue(reloaded.path("username").asString().startsWith("@ada"), reloaded.toString());
    }

    @Test
    void browserExplainsProfileMistakesWithoutClosingTheForm() {
        JsonNode blank = page("blankName");
        assertTrue(blank.path("error").asString().toLowerCase().contains("name"), browser.toString());
        assertTrue(blank.path("modalOpen").asBoolean());

        JsonNode taken = page("takenUsername");
        assertEquals("That username is already taken", taken.path("error").asString(), taken.toString());
        assertTrue(taken.path("modalOpen").asBoolean());
        assertEquals("[]", browser.path("dialogs").toString(), "messages appear on the page, not in alert boxes");
    }

    @Test
    void browserShowsWhetherARoutineIsPublicOrPrivate() {
        assertEquals("Private", page("privateRoutine").path("badge").asString(), browser.toString());
        assertEquals("Public", page("publicRoutine").path("badge").asString(), browser.toString());
        assertTrue(page("routineCards").path("badges").toString().contains("Private"), browser.toString());
        assertTrue(page("routineCards").path("badges").toString().contains("Public"), browser.toString());
    }

    @Test
    void browserWorksInEveryDesignAndOnA390PixelPhone() {
        for (String layout : List.of("loginPhone", "profilePhone", "profileAutumnPhone", "profileAutumnDesktop")) {
            JsonNode view = page(layout);
            assertTrue(view.path("rendered").asBoolean(), layout + " " + view);
            assertTrue(view.path("overflow").asInt() <= 0, layout + " " + view);
            assertTrue(view.path("rightmost").asInt() <= view.path("width").asInt(), layout + " " + view);
            assertTrue(view.path("minContrast").asDouble() >= 4.5, layout + " " + view);
        }
        assertEquals(390, page("profilePhone").path("width").asInt());
        assertEquals("autumn", page("profileAutumnPhone").path("theme").asString());
    }

    // ---- helpers ----

    private interface Attempt {
        int run(int index) throws Exception;
    }

    private List<Integer> together(int count, Attempt attempt) throws Exception {
        List<Callable<Integer>> attempts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = i;
            attempts.add(() -> attempt.run(index));
        }
        ExecutorService pool = Executors.newFixedThreadPool(count);
        List<Integer> statuses = new ArrayList<>();
        try {
            for (Future<Integer> future : pool.invokeAll(attempts)) {
                statuses.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }
        return statuses;
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
    }

    private String requestReset(String email) throws Exception {
        clearInvocations(emailService);
        assertEquals(200, post("/auth/forgot-password", null, Map.of("email", email)).status());
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendPasswordResetEmail(eq(email), token.capture());
        return token.getValue();
    }

    private Map<String, Object> profile(String name, String username) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("username", username);
        return body;
    }

    private String userRow(long id) {
        return jdbc.queryForList("select name, username, email, bio, profession, avatar_url, interests, goals, password from users where id = ?", id).toString();
    }

    private void assertNoSecrets(String body, Account owner) {
        assertFalse(body.contains(owner.email()), body);
        assertFalse(body.contains("\"email\""), body);
        assertFalse(body.toLowerCase().contains("password"), body);
        assertFalse(body.contains("$2a$"), body);
        assertFalse(body.contains(owner.token()), body);
    }

    private byte[] png() throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private Reply upload(String token, String filename, String type, byte[] content) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"" + filename
                + "\"\r\nContent-Type: " + type + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri("/users/me/avatar"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private String message(Reply reply) throws Exception {
        return json.readTree(reply.body()).path("message").asString();
    }

    private Reply login(String email, String password) throws Exception {
        return post("/auth/login", null, Map.of("email", email, "password", password));
    }

    private Account register() throws Exception {
        String email = "auth_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test";
        Reply reply = post("/auth/register", null, Map.of("name", "Auth QA", "email", email, "password", PASSWORD));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), email, node.path("token").asString());
    }

    private long routine(Account owner, String title, boolean isPublic) throws Exception {
        Reply reply = saveRoutine("POST", "/routines", owner, title, isPublic);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private Reply saveRoutine(String method, String path, Account owner, String title, boolean isPublic) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", "Privacy check");
        fields.put("category", "Morning");
        fields.put("durationMinutes", "30");
        fields.put("isPublic", String.valueOf(isPublic));

        StringBuilder body = new StringBuilder();
        fields.forEach((key, value) -> body.append("--").append(BOUNDARY).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(key).append("\"\r\n\r\n")
                .append(value).append("\r\n"));
        body.append("--").append(BOUNDARY).append("--\r\n");

        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + owner.token())
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private Reply get(String path, String token) throws Exception {
        return send("GET", path, token, null);
    }

    private Reply post(String path, Object auth, Map<String, ?> payload) throws Exception {
        return send("POST", path, tokenOf(auth), payload);
    }

    private Reply put(String path, Object auth, Map<String, ?> payload) throws Exception {
        return send("PUT", path, tokenOf(auth), payload);
    }

    private String tokenOf(Object auth) {
        return auth instanceof Account account ? account.token() : (String) auth;
    }

    private Reply send(String method, String path, String token, Map<String, ?> payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
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

    private static String readQuietly(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            return "(no output)";
        }
    }

    private static boolean nodeAvailable() {
        try {
            return new ProcessBuilder("node", "--version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception exception) {
            return false;
        }
    }

    private static String findChrome() {
        List<String> candidates = new ArrayList<>();
        if (System.getenv("CHROME_BIN") != null) {
            candidates.add(System.getenv("CHROME_BIN"));
        }
        candidates.add("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe");
        candidates.add("C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe");
        candidates.add("/usr/bin/google-chrome");
        candidates.add("/usr/bin/chromium");
        candidates.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        return candidates.stream().filter(path -> new File(path).isFile()).findFirst().orElse(null);
    }
}
