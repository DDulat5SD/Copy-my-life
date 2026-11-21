package com.copymylife.copymylife.security;

import com.copymylife.copymylife.repository.RevokedTokenRepository;
import com.copymylife.copymylife.service.EmailService;
import com.copymylife.copymylife.service.JwtService;
import com.copymylife.copymylife.service.TokenRevocationService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.SecretKey;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

// A JWT must have exactly one spelling. With an HS256 or HS512 key the last character of
// the signature carries bits that Base64URL decoding throws away, so several strings decode
// to the same signature. Every such look-alike must be refused, and signing out must end
// the token however it is spelled.
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
                "jwt.secret=" + JwtTokenIntegrityRegressionTest.SECRET,
                "spring.mail.username=test",
                "spring.mail.password=test",
                "MAILJET_API_KEY=test",
                "MAILJET_SECRET_KEY=test",
                "MAILJET_SENDER_EMAIL=test@invalid.test"
        }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JwtTokenIntegrityRegressionTest {

    // 32 bytes, so tokens are signed with HS256 and the signature has unused bits.
    static final String SECRET = "and0LWludGVncml0eS10ZXN0LWtleS0zMi1ieXRlcyE=";
    private static final String PASSWORD = "Audit#12345";
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private TokenRevocationService tokenRevocationService;

    @Autowired
    private RevokedTokenRepository revokedTokenRepository;

    @MockitoBean
    private EmailService emailService;

    private record Reply(int status, String body) {
    }

    private record Account(long id, String email, String token) {
    }

    // ---- accepting and refusing tokens ----

    @Test
    void aValidTokenIsAccepted() throws Exception {
        Account account = register();

        assertEquals(200, get("/users/me", account.token()).status());
        assertEquals("HS256", header(account.token()).path("alg").asString(), "the test key gives an HS256 signature");
    }

    @Test
    void aTokenWhoseSignatureEndsInAnotherCharacterIsRefused() throws Exception {
        Account account = register();
        List<String> lookAlikes = signatureLookAlikes(account.token());

        assertFalse(lookAlikes.isEmpty(), "an HS256 signature always has unused bits");
        for (String lookAlike : lookAlikes) {
            assertEquals(403, get("/users/me", lookAlike).status(), lookAlike);
        }
        assertEquals(200, get("/users/me", account.token()).status(), "the real token still works");
    }

    @Test
    void everyNonCanonicalSpellingIsRefused() throws Exception {
        Account account = register();

        for (String spelling : nonCanonicalSpellings(account.token())) {
            assertEquals(403, get("/users/me", spelling).status(), spelling);
        }
        assertEquals(200, get("/users/me", account.token()).status());
    }

    @Test
    void aTokenWithCharactersAddedIsRefused() throws Exception {
        Account account = register();

        for (String suffix : List.of("A", "=", "==", ".", ".A", "%3D")) {
            assertEquals(403, get("/users/me", account.token() + suffix).status(), "suffix " + suffix);
        }
        for (String prefix : List.of("=", ".", "A")) {
            assertEquals(403, get("/users/me", prefix + account.token()).status(), "prefix " + prefix);
        }
    }

    @Test
    void expiredAndForgedTokensAreRefusedHoweverTheyAreSpelled() throws Exception {
        Account victim = register();
        Account attacker = register();
        String expired = Jwts.builder()
                .subject(String.valueOf(victim.id()))
                .issuedAt(new Date(System.currentTimeMillis() - 7_200_000))
                .expiration(new Date(System.currentTimeMillis() - 3_600_000))
                .signWith(key())
                .compact();
        String otherKey = Jwts.builder().subject(String.valueOf(victim.id()))
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(Keys.hmacShaKeyFor(new byte[32])).compact();
        String[] parts = attacker.token().split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("\"sub\":\"" + attacker.id() + "\"", "\"sub\":\"" + victim.id() + "\"");
        String altered = parts[0] + "." + encode(payload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        String unsigned = encode("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                + "." + encode(("{\"sub\":\"" + victim.id() + "\"}").getBytes(StandardCharsets.UTF_8)) + ".";

        List<String> refused = new ArrayList<>(List.of(expired, otherKey, altered, unsigned));
        refused.addAll(signatureLookAlikes(expired));
        refused.addAll(signatureLookAlikes(otherKey));
        for (String token : refused) {
            assertEquals(403, get("/users/me", token).status(), token);
        }
    }

    // ---- signing out ----

    @Test
    void signingOutEndsTheTokenInEverySpelling() throws Exception {
        Account account = register();
        String otherDevice = login(account.email());

        assertEquals(204, post("/auth/logout", account.token(), null).status());

        assertEquals(403, get("/users/me", account.token()).status());
        for (String spelling : allSpellings(account.token())) {
            assertEquals(403, get("/users/me", spelling).status(), spelling);
        }
        assertEquals(200, get("/users/me", otherDevice).status(), "the session on another device stays open");
        assertEquals(1, revocations(account.id()));
    }

    @Test
    void signingOutWithALookAlikeEndsTheRealToken() throws Exception {
        Account account = register();
        String otherDevice = login(account.email());
        String lookAlike = signatureLookAlikes(account.token()).get(0);

        assertEquals(204, post("/auth/logout", lookAlike, null).status());

        assertEquals(403, get("/users/me", account.token()).status(), "the token behind the look-alike is signed out");
        assertEquals(403, get("/users/me", lookAlike).status());
        assertEquals(200, get("/users/me", otherDevice).status());
        assertEquals(1, revocations(account.id()));
    }

    @Test
    void signingOutInSeveralSpellingsKeepsOneRevocation() throws Exception {
        Account account = register();

        assertEquals(204, post("/auth/logout", account.token(), null).status());
        for (String lookAlike : signatureLookAlikes(account.token())) {
            assertEquals(204, post("/auth/logout", lookAlike, null).status());
        }

        assertEquals(1, revocations(account.id()));
    }

    @Test
    void theRevocationCheckSeesThroughEveryLookAlike() throws Exception {
        Account account = register();

        tokenRevocationService.revoke(account.token());

        assertTrue(tokenRevocationService.isRevoked(account.token()));
        for (String lookAlike : signatureLookAlikes(account.token())) {
            assertTrue(tokenRevocationService.isRevoked(lookAlike), lookAlike);
        }
    }

    @Test
    void aRevocationOutlivesARestart() throws Exception {
        Account account = register();
        assertEquals(204, post("/auth/logout", account.token(), null).status());

        // A fresh service holds nothing in memory: only what is in PostgreSQL counts.
        TokenRevocationService afterRestart = new TokenRevocationService(revokedTokenRepository, jwtService);

        assertTrue(afterRestart.isRevoked(account.token()));
        for (String lookAlike : signatureLookAlikes(account.token())) {
            assertTrue(afterRestart.isRevoked(lookAlike), lookAlike);
        }
    }

    @Test
    void revocationsStoredBeforeThisFixStillApply() throws Exception {
        Account account = register();
        jdbc.update("insert into revoked_tokens (token_hash, user_id, expires_at, revoked_at) values (?, ?, now() + interval '1 hour', now())",
                sha256(account.token()), account.id());

        assertEquals(403, get("/users/me", account.token()).status());
        for (String spelling : allSpellings(account.token())) {
            assertEquals(403, get("/users/me", spelling).status(), spelling);
        }
    }

    @Test
    void onlyAHashOfTheTokenIsStored() throws Exception {
        Account account = register();

        assertEquals(204, post("/auth/logout", account.token(), null).status());

        assertEquals(sha256(account.token()), jdbc.queryForObject(
                "select token_hash from revoked_tokens where user_id = ?", String.class, account.id()));
    }

    // ---- older tokens and password reset ----

    @Test
    void aTokenWithoutIdOrVersionStillWorksAndCanBeSignedOut() throws Exception {
        Account account = register();
        String legacy = Jwts.builder()
                .subject(String.valueOf(account.id()))
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(key())
                .compact();

        assertEquals(200, get("/users/me", legacy).status());
        for (String lookAlike : signatureLookAlikes(legacy)) {
            assertEquals(403, get("/users/me", lookAlike).status(), lookAlike);
        }

        assertEquals(204, post("/auth/logout", legacy, null).status());

        for (String spelling : allSpellings(legacy)) {
            assertEquals(403, get("/users/me", spelling).status(), spelling);
        }
        assertEquals(403, get("/users/me", legacy).status());
        assertEquals(200, get("/users/me", account.token()).status(), "signing out one token leaves the others alone");
    }

    @Test
    void resettingThePasswordStillClosesOldSessionsInEverySpelling() throws Exception {
        Account account = register();
        String resetToken = requestReset(account.email());

        assertEquals(200, post("/auth/reset-password", null, Map.of("token", resetToken, "newPassword", "Better#2026")).status());

        assertEquals(403, get("/users/me", account.token()).status());
        for (String spelling : allSpellings(account.token())) {
            assertEquals(403, get("/users/me", spelling).status(), spelling);
        }
        String fresh = loginWith(account.email(), "Better#2026");
        assertEquals(200, get("/users/me", fresh).status());
        for (String lookAlike : signatureLookAlikes(fresh)) {
            assertEquals(403, get("/users/me", lookAlike).status(), lookAlike);
        }
    }

    // ---- every key size ----

    @Test
    void lookAlikesAreRefusedForEveryHmacKeySize() {
        for (int keyBytes : List.of(32, 48, 64)) {
            byte[] raw = new byte[keyBytes];
            for (int i = 0; i < raw.length; i++) {
                raw[i] = (byte) (i * 7 + keyBytes);
            }
            JwtService service = new JwtService();
            ReflectionTestUtils.setField(service, "secret", Base64.getEncoder().encodeToString(raw));
            ReflectionTestUtils.setField(service, "expiration", 3_600_000L);
            String token = Jwts.builder()
                    .id(UUID.randomUUID().toString())
                    .subject("42")
                    .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                    .signWith(Keys.hmacShaKeyFor(raw))
                    .compact();

            assertTrue(service.readClaims(token).isPresent(), keyBytes + "-byte key");
            for (String spelling : allSpellings(token)) {
                assertTrue(service.readClaims(spelling).isEmpty(), keyBytes + "-byte key: " + spelling);
            }
        }
    }

    // ---- spellings of one token ----

    // Same signature bytes, different last character.
    private static List<String> signatureLookAlikes(String token) {
        String[] parts = token.split("\\.");
        List<String> result = new ArrayList<>();
        for (String signature : lookAlikes(parts[2])) {
            result.add(parts[0] + "." + parts[1] + "." + signature);
        }
        return result;
    }

    // Look-alikes of any part, padded parts and the standard Base64 alphabet.
    private static List<String> nonCanonicalSpellings(String token) {
        String[] parts = token.split("\\.");
        List<String> result = new ArrayList<>();
        for (int index = 0; index < parts.length; index++) {
            List<String> variants = new ArrayList<>(lookAlikes(parts[index]));
            int padding = (4 - parts[index].length() % 4) % 4;
            if (padding > 0) {
                variants.add(parts[index] + "=".repeat(padding));
            }
            String standard = parts[index].replace('-', '+').replace('_', '/');
            if (!standard.equals(parts[index])) {
                variants.add(standard);
            }
            for (String variant : variants) {
                String[] copy = parts.clone();
                copy[index] = variant;
                result.add(String.join(".", copy));
            }
        }
        return result;
    }

    private static List<String> allSpellings(String token) {
        List<String> result = new ArrayList<>(nonCanonicalSpellings(token));
        result.add(token + "=");
        result.add(token + ".");
        return result;
    }

    private static List<String> lookAlikes(String part) {
        int unusedBits = switch (part.length() % 4) {
            case 2 -> 4;
            case 3 -> 2;
            default -> 0;
        };
        List<String> result = new ArrayList<>();
        if (unusedBits == 0) {
            return result;
        }
        int value = ALPHABET.indexOf(part.charAt(part.length() - 1));
        int base = value & ~((1 << unusedBits) - 1);
        for (int candidate = base; candidate < base + (1 << unusedBits); candidate++) {
            if (candidate != value) {
                result.add(part.substring(0, part.length() - 1) + ALPHABET.charAt(candidate));
            }
        }
        return result;
    }

    // ---- helpers ----

    private JsonNode header(String token) {
        return json.readTree(new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8));
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
    }

    private int revocations(long userId) {
        return jdbc.queryForObject("select count(*) from revoked_tokens where user_id = ?", Integer.class, userId);
    }

    private String requestReset(String email) throws Exception {
        clearInvocations(emailService);
        assertEquals(200, post("/auth/forgot-password", null, Map.of("email", email)).status());
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendPasswordResetEmail(eq(email), token.capture());
        return token.getValue();
    }

    private Account register() throws Exception {
        String email = "jwt_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test";
        Reply reply = post("/auth/register", null, Map.of("name", "JWT QA", "email", email, "password", PASSWORD));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), email, node.path("token").asString());
    }

    private String login(String email) throws Exception {
        return loginWith(email, PASSWORD);
    }

    private String loginWith(String email, String password) throws Exception {
        Reply reply = post("/auth/login", null, Map.of("email", email, "password", password));
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("token").asString();
    }

    private Reply get(String path, String token) throws Exception {
        return send("GET", path, token, null);
    }

    private Reply post(String path, String token, Map<String, ?> payload) throws Exception {
        return send("POST", path, token, payload);
    }

    private Reply send(String method, String path, String token, Map<String, ?> payload) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
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
}
