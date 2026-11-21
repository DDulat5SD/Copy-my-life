package com.copymylife.copymylife.feature;

import com.copymylife.copymylife.service.EmailService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * US-22: given reminders are on, a reminder about an activity is shown as its time approaches;
 * given they are off, none is sent. Activities are the timed habits of the user's own routines
 * and the routines on their calendar, in the user's time zone; each occurrence is reminded once,
 * only to its owner, and paused, deleted, cancelled or moved activities follow their new plan.
 * Times are set relative to now, ten minutes ahead, inside the default 15-minute lead.
 * Needs TEST_DB_URL (a local *_test database); the browser part also needs node and Chrome.
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoutineRemindersRegressionTest {

    private static final String BOUNDARY = "ReminderBoundary";
    private static final String ZONE = "Asia/Almaty";
    private static final List<String> EVERY_DAY = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final String TRICKY = "<img src=x onerror=\"window.__xss=1\">";

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

    // Ten minutes from now in the user's zone, to the minute: due under the default 15-minute lead.
    private ZonedDateTime soon;
    private JsonNode browser;

    // ---- AC1: shown as the time approaches, once ----

    @Test
    void anApproachingActivityIsRemindedOnce() throws Exception {
        Account user = register("Reminded");
        long routine = routine(user, "Morning plan", 10);
        long stretch = habit(user, routine, "Stretch", soon);
        habit(user, routine, "Later on", soon.plusHours(3));
        habit(user, routine, "Any time", null);

        JsonNode due = due(user, ZONE);
        assertEquals(1, due.size(), due.toString());
        JsonNode reminder = due.get(0);
        assertEquals("HABIT", reminder.path("kind").asString());
        assertEquals("Stretch", reminder.path("title").asString());
        assertEquals("Morning plan", reminder.path("routineTitle").asString());
        assertEquals(routine, reminder.path("routineId").asLong());
        assertEquals(soon.format(HH_MM), reminder.path("time").asString().substring(0, 5));
        long minutes = reminder.path("minutesUntil").asLong();
        assertTrue(minutes >= 9 && minutes <= 10, reminder.toString());
        assertTrue(reminder.path("key").asString().startsWith("habit:" + stretch + ":"), reminder.toString());

        // Asked again, from this tab or another device: nothing new.
        assertEquals(0, due(user, ZONE).size());
        assertEquals(1, count("select count(*) from reminder_deliveries where user_id = ?", user.id()));
    }

    @Test
    void manyTabsAskingAtOnceGetTheReminderOnce() throws Exception {
        Account user = register("Many tabs");
        long routine = routine(user, "Tab plan", 1);
        habit(user, routine, "Read", soon);

        List<CompletableFuture<HttpResponse<String>>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(http.sendAsync(request("POST", "/reminders/due", user, ZONE).build(), HttpResponse.BodyHandlers.ofString()));
        }
        int shown = 0;
        for (CompletableFuture<HttpResponse<String>> call : calls) {
            HttpResponse<String> response = call.get(30, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode(), response.body());
            shown += json.readTree(response.body()).size();
        }
        assertEquals(1, shown);
    }

    @Test
    void timesAreTheUsersOwnInTheirTimeZone() throws Exception {
        Account user = register("Zones");
        long routine = routine(user, "Zone plan", 1);
        habit(user, routine, "Call home", soon);

        // The same wall-clock time in London (five hours behind Almaty) is hours away.
        assertEquals(0, due(user, "Europe/London").size());
        assertEquals(1, due(user, ZONE).size());
    }

    @Test
    void theChosenLeadTimeIsUsed() throws Exception {
        Account user = register("Short lead");
        long routine = routine(user, "Lead plan", 1);
        habit(user, routine, "Water plants", soon);

        JsonNode saved = settings(user, Map.of("enabled", true, "leadMinutes", 5));
        assertEquals(5, saved.path("leadMinutes").asInt(), saved.toString());
        assertEquals(0, due(user, ZONE).size(), "ten minutes ahead is not within 5 minutes");

        settings(user, Map.of("enabled", true, "leadMinutes", 30));
        assertEquals(1, due(user, ZONE).size());
    }

    // ---- AC2: none while reminders are off ----

    @Test
    void noReminderIsSentWhileRemindersAreOff() throws Exception {
        Account user = register("Quiet");
        long routine = routine(user, "Quiet plan", 1);
        habit(user, routine, "Meditate", soon);
        long calendar = routine(user, "Quiet calendar", 1);
        event(user, calendar, soon, "DAILY");

        JsonNode defaults = json.readTree(send("GET", "/reminders/settings", user, null).body());
        assertTrue(defaults.path("enabled").asBoolean(), defaults.toString());
        assertEquals(15, defaults.path("leadMinutes").asInt(), defaults.toString());

        JsonNode off = settings(user, Map.of("enabled", false));
        assertFalse(off.path("enabled").asBoolean(), off.toString());
        assertEquals(15, off.path("leadMinutes").asInt(), "the lead time is kept");
        assertEquals(0, due(user, ZONE).size());
        assertEquals(0, due(user, ZONE).size());
        assertEquals(0, count("select count(*) from reminder_deliveries where user_id = ?", user.id()), "nothing recorded as sent");

        // Turned on again while both are still ahead: both are reminded.
        settings(user, Map.of("enabled", true));
        assertEquals(2, due(user, ZONE).size());
    }

    @Test
    void settingsAreCheckedAndOnlyForTheSignedInUser() throws Exception {
        Account user = register("Settings");
        assertEquals(400, send("PUT", "/reminders/settings", user, Map.of("enabled", true, "leadMinutes", 7)).status());
        assertEquals(400, send("PUT", "/reminders/settings", user, Map.of("leadMinutes", 10)).status());
        for (String[] call : new String[][]{{"GET", "/reminders/settings"}, {"PUT", "/reminders/settings"},
                {"POST", "/reminders/due"}, {"GET", "/reminders/upcoming"}}) {
            int status = send(call[0], call[1], null, call[0].equals("PUT") ? Map.of("enabled", false) : null).status();
            assertTrue(status == 401 || status == 403, call[1] + " " + status);
        }
    }

    // ---- privacy ----

    @Test
    void remindersGoOnlyToTheirOwner() throws Exception {
        Account owner = register("Owner");
        Account other = register("Other");
        long routine = routine(owner, "Private plan", 1);
        habit(owner, routine, "Secret step " + TRICKY, soon);
        event(owner, routine, soon, "NONE");

        assertEquals(0, due(other, ZONE).size());
        assertFalse(send("GET", "/reminders/upcoming", other, null, ZONE).body().contains("Secret step"));
        assertEquals(2, due(owner, ZONE).size());
    }

    // ---- paused, deleted, cancelled and moved ----

    @Test
    void pausedOrDeletedActivitiesAndDeletedRoutinesAreNotReminded() throws Exception {
        Account user = register("Changes");
        long paused = routine(user, "Paused plan", 1);
        long pausedLink = habit(user, paused, "Paused step", soon);
        long removed = routine(user, "Removed plan", 1);
        long removedLink = habit(user, removed, "Removed step", soon);
        long deleted = routine(user, "Deleted plan", 1);
        habit(user, deleted, "In a deleted routine", soon);
        event(user, deleted, soon, "DAILY");

        assertEquals(200, send("POST", "/routines/" + paused + "/routine-habits/" + pausedLink + "/pause", user, null).status());
        assertEquals(200, send("DELETE", "/routines/" + removed + "/routine-habits/" + removedLink, user, null).status());
        assertEquals(204, send("DELETE", "/routines/" + deleted, user, null).status());

        assertEquals(0, due(user, ZONE).size());
    }

    @Test
    void aMovedActivityIsRemindedAtItsNewTimeOnly() throws Exception {
        Account user = register("Moved");
        long routine = routine(user, "Moving plan", 1);
        long link = habit(user, routine, "Moved step", soon.plusHours(3));
        assertEquals(0, due(user, ZONE).size());

        Map<String, Object> schedule = new HashMap<>();
        schedule.put("startTime", soon.format(HH_MM));
        schedule.put("durationMinutes", 1);
        schedule.put("repeatDays", EVERY_DAY);
        Reply moved = send("PUT", "/routines/" + routine + "/routine-habits/" + link, user, schedule);
        assertEquals(200, moved.status(), moved.body());

        JsonNode due = due(user, ZONE);
        assertEquals(1, due.size(), due.toString());
        assertEquals(soon.format(HH_MM), due.get(0).path("time").asString().substring(0, 5));
    }

    @Test
    void aCalendarRoutineIsRemindedAndACancelledDayIsNot() throws Exception {
        Account user = register("Calendar");
        long routine = routine(user, "Calendar plan", 1);
        event(user, routine, soon, "DAILY");
        JsonNode due = due(user, ZONE);
        assertEquals(1, due.size(), due.toString());
        assertEquals("ROUTINE", due.get(0).path("kind").asString());
        assertEquals("Calendar plan", due.get(0).path("title").asString());

        Account canceller = register("Cancelled");
        long theirs = routine(canceller, "Cancelled plan", 1);
        long cancelled = event(canceller, theirs, soon, "DAILY");
        assertEquals(204, send("DELETE", "/calendar-events/" + cancelled + "?scope=occurrence&date=" + soon.toLocalDate(), canceller, null).status());
        assertEquals(0, due(canceller, ZONE).size());
    }

    @Test
    void aCalendarDayMovedToNowIsRemindedAtItsNewTime() throws Exception {
        Account user = register("Rescheduled");
        long routine = routine(user, "Rescheduled plan", 1);
        ZonedDateTime later = soon.plusHours(3);
        long series = event(user, routine, later, "DAILY");
        assertEquals(0, due(user, ZONE).size());

        // Only the day of "soon" is moved; it becomes its own single event at the new time.
        Map<String, Object> change = new HashMap<>();
        change.put("routineId", routine);
        change.put("day", soon.toLocalDate().toString());
        change.put("time", soon.format(HH_MM));
        Reply moved = send("PUT", "/calendar-events/" + series + "?scope=occurrence&date=" + soon.toLocalDate(), user, change);
        assertEquals(200, moved.status(), moved.body());

        JsonNode due = due(user, ZONE);
        assertEquals(1, due.size(), due.toString());
        assertFalse(due.get(0).path("key").asString().startsWith("event:" + series + ":"), "not the series' old time");
    }

    // ---- browser ----

    @Test
    void browserShowsTheReminderAndTheSetting() {
        JsonNode toast = page("toast");
        assertEquals(1, toast.path("titles").size(), toast.toString());
        assertEquals("Browser step " + TRICKY, toast.path("titles").get(0).asString(), "shown as text: " + toast);
        assertTrue(toast.path("when").asString().startsWith("Starts in "), toast.toString());
        assertFalse(toast.path("xss").asBoolean(), toast.toString());
        assertEquals(0, toast.path("afterDismiss").asInt(), toast.toString());
        assertEquals(0, toast.path("afterReload").asInt(), "a shown reminder does not come back: " + toast);

        JsonNode settings = page("settings");
        assertTrue(settings.path("visible").asBoolean(), settings.toString());
        assertTrue(settings.path("checked").asBoolean(), settings.toString());
        assertEquals("15", settings.path("lead").asString(), settings.toString());
        assertTrue(settings.path("savedOff").asString().contains("off"), settings.toString());
        assertFalse(settings.path("checkedAfter").asBoolean(), settings.toString());

        JsonNode layouts = page("layouts");
        assertEquals(4, layouts.size(), layouts.toString());
        layouts.properties().forEach(entry -> {
            // Negative is the desktop scrollbar; positive would be sideways scrolling.
            assertTrue(entry.getValue().path("overflow").asInt() <= 0, entry.getKey() + " " + entry.getValue());
            assertTrue(entry.getValue().path("inside").asBoolean(), entry.getKey() + " " + entry.getValue());
        });
        assertEquals(0, page("errors").size(), browser.toString());
        assertEquals(0, page("dialogs").size(), browser.toString());
    }

    // ---- data ----

    @BeforeAll
    void createContent() throws Exception {
        assertTrue(jdbc.queryForObject("select current_database()", String.class).endsWith("_test"),
                "Refusing to run outside a *_test database");
        soon = ZonedDateTime.now(ZoneId.of(ZONE)).plusMinutes(10).truncatedTo(ChronoUnit.MINUTES);

        String chrome = findChrome();
        if (chrome == null || !nodeAvailable()) {
            return;
        }

        Account toast = register("Toast");
        long routine = routine(toast, "Browser plan", 1);
        habit(toast, routine, "Browser step " + TRICKY, soon);
        Account settings = register("Setting");

        Map<String, Object> fixtures = new LinkedHashMap<>();
        fixtures.put("base", "http://127.0.0.1:" + port);
        fixtures.put("toastToken", toast.token());
        fixtures.put("settingsToken", settings.token());
        fixtures.put("zone", ZONE);

        Path dir = Files.createTempDirectory("reminders-browser");
        Files.writeString(dir.resolve("fixtures.json"), json.writeValueAsString(fixtures));
        Process process = new ProcessBuilder(
                "node", "src/test/e2e/reminders-check.mjs", chrome,
                dir.resolve("fixtures.json").toString(), dir.resolve("results.json").toString()
        ).redirectErrorStream(true).redirectOutput(dir.resolve("node.log").toFile()).start();

        if (!process.waitFor(5, TimeUnit.MINUTES)) {
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

    // ---- helpers ----

    private JsonNode due(Account account, String zone) throws Exception {
        Reply reply = send("POST", "/reminders/due", account, null, zone);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private JsonNode settings(Account account, Map<String, ?> body) throws Exception {
        Reply reply = send("PUT", "/reminders/settings", account, body);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body());
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private Account register(String name) throws Exception {
        Reply reply = send("POST", "/auth/register", null, Map.of(
                "name", name,
                "email", "reminder_" + UUID.randomUUID().toString().substring(0, 12) + "@audit.test",
                "password", "Audit#12345"
        ));
        assertEquals(200, reply.status(), reply.body());
        JsonNode node = json.readTree(reply.body());
        return new Account(node.path("user").path("id").asLong(), node.path("token").asString());
    }

    private long routine(Account owner, String title, int minutes) throws Exception {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", "Reminder check");
        fields.put("category", "Morning");
        fields.put("durationMinutes", String.valueOf(minutes));
        fields.put("isPublic", "false");

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

    // A one-minute habit at the given time on every day (untimed when null); returns its link id.
    private long habit(Account owner, long routine, String title, ZonedDateTime at) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("description", "Reminder check");
        body.put("durationMinutes", 1);
        if (at != null) {
            body.put("startTime", at.format(HH_MM));
            body.put("repeatDays", EVERY_DAY);
        }
        Reply reply = send("POST", "/routines/" + routine + "/habits", owner, body);
        assertEquals(200, reply.status(), reply.body());
        return jdbc.queryForObject("""
                select rh.id from routine_habits rh join habits h on h.id = rh.habit_id
                where rh.routine_id = ? and h.title = ?
                """, Long.class, routine, title);
    }

    private long event(Account owner, long routine, ZonedDateTime at, String repeat) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("routineId", routine);
        body.put("day", at.toLocalDate().toString());
        body.put("time", at.format(HH_MM));
        body.put("repeat", repeat);
        Reply reply = send("POST", "/calendar-events", owner, body);
        assertEquals(200, reply.status(), reply.body());
        return json.readTree(reply.body()).path("id").asLong();
    }

    private Reply send(String method, String path, Account account, Map<String, ?> payload) throws Exception {
        return send(method, path, account, payload, ZONE);
    }

    private Reply send(String method, String path, Account account, Map<String, ?> payload, String zone) throws Exception {
        HttpRequest.Builder builder = request(method, path, account, zone);
        if (payload != null) {
            builder.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .header("Content-Type", "application/json");
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body());
    }

    private HttpRequest.Builder request(String method, String path, Account account, String zone) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("X-Time-Zone", zone)
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (account != null) {
            builder.header("Authorization", "Bearer " + account.token());
        }
        return builder;
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
